package com.superbrain.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

class CameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapture"
        private const val JPEG_QUALITY = 95
        private const val AE_CONVERGE_MS = 2000L
    }

    var isCapturing = false
        private set

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    @Volatile private var readyToCapture = false

    fun capture(onResult: (base64: String?) -> Unit) {
        if (isCapturing) return
        isCapturing = true
        readyToCapture = false

        handlerThread = HandlerThread("CameraCapture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    takePhoto(camera, onResult)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cleanup()
                    onResult(null)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error")
                    camera.close()
                    cleanup()
                    onResult(null)
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed", e)
            cleanup()
            onResult(null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            cleanup()
            onResult(null)
        }
    }

    private fun takePhoto(camera: CameraDevice, onResult: (base64: String?) -> Unit) {
        // 4032x3024: full 12MP sensor resolution, no downscaling
        val reader = ImageReader.newInstance(4032, 3024, ImageFormat.JPEG, 1)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            if (!readyToCapture) {
                // Discard preview frames - just drain the reader
                r.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                Log.i(TAG, "Captured JPEG: ${bytes.size} bytes")
                val base64 = processCapture(bytes)
                onResult(base64)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading image", e)
                onResult(null)
            } finally {
                cleanup()
            }
        }, handler)

        try {
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            // Step 1: Run preview for AE/AF convergence
                            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            session.setRepeatingRequest(previewRequest.build(), null, handler)
                            Log.i(TAG, "Preview started, waiting ${AE_CONVERGE_MS}ms for AE/AF...")

                            // Step 2: After AE converges, take the real photo
                            handler?.postDelayed({
                                try {
                                    readyToCapture = true
                                    session.stopRepeating()
                                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                                        set(CaptureRequest.JPEG_ORIENTATION, 0) // Don't rotate JPEG, we rotate bitmap instead
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }
                                    Log.i(TAG, "Taking still capture...")
                                    session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult
                                        ) {
                                            Log.i(TAG, "Capture completed, AE state: ${result.get(CaptureResult.CONTROL_AE_STATE)}")
                                        }
                                    }, handler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Capture failed", e)
                                    onResult(null)
                                    cleanup()
                                }
                            }, AE_CONVERGE_MS)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Preview request failed", e)
                            onResult(null)
                            cleanup()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configure failed")
                        onResult(null)
                        cleanup()
                    }
                },
                handler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession failed", e)
            onResult(null)
            cleanup()
        }
    }

    private fun processCapture(jpegBytes: ByteArray): String? {
        return try {
            // Decode bounds only (no memory allocation)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
            Log.i(TAG, "Raw image: ${opts.outWidth}x${opts.outHeight}")

            // Decode full resolution, no downsampling
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = 1 }
            val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOpts) ?: return null
            Log.i(TAG, "Decoded: ${decoded.width}x${decoded.height}")

            // Rotate 270° (Rokid sensor is mounted rotated, JPEG_ORIENTATION doesn't work)
            val matrix = Matrix().apply { postRotate(270f) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            decoded.recycle()
            Log.i(TAG, "Rotated: ${rotated.width}x${rotated.height}")

            // Compress to JPEG
            val os = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)
            Log.i(TAG, "Photo: ${rotated.width}x${rotated.height}, JPEG ${os.size()} bytes")
            rotated.recycle()
            Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing capture", e)
            null
        }
    }

    fun cleanup() {
        readyToCapture = false
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        cameraDevice = null
        imageReader = null
        handlerThread = null
        handler = null
        isCapturing = false
    }
}
