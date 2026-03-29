package com.superbrain.glasses

import android.content.Context
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
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

class CameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapture"
        private const val JPEG_QUALITY = 95
        private const val AE_CONVERGE_MS = 2000L
        // Rokid sensor is mounted rotated 270°
        private const val SENSOR_ROTATION = 270
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
        // Full 12MP sensor resolution — no bitmap decode needed, EXIF handles rotation
        val reader = ImageReader.newInstance(4032, 3024, ImageFormat.JPEG, 1)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            if (!readyToCapture) {
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
                            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            session.setRepeatingRequest(previewRequest.build(), null, handler)
                            Log.i(TAG, "Preview started, waiting ${AE_CONVERGE_MS}ms for AE/AF...")

                            handler?.postDelayed({
                                try {
                                    readyToCapture = true
                                    session.stopRepeating()
                                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                                        set(CaptureRequest.JPEG_ORIENTATION, 0) // We set EXIF orientation manually
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

    private fun rotationToExifOrientation(degrees: Int): Int = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun processCapture(jpegBytes: ByteArray): String? {
        val tmpFile = File(context.cacheDir, "capture_tmp.jpg")
        return try {
            // Write raw JPEG bytes to temp file (no bitmap decode = zero OOM risk)
            tmpFile.writeBytes(jpegBytes)
            Log.i(TAG, "Wrote ${jpegBytes.size} bytes to temp file")

            // Set EXIF orientation tag so viewers rotate correctly
            val exif = ExifInterface(tmpFile.absolutePath)
            val orientation = rotationToExifOrientation(SENSOR_ROTATION)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            Log.i(TAG, "Set EXIF orientation: $orientation (${SENSOR_ROTATION}°)")

            // Read back the EXIF-tagged JPEG
            val result = tmpFile.readBytes()
            Log.i(TAG, "Final JPEG: ${result.size} bytes (${result.size / 1024}KB)")

            Base64.encodeToString(result, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing capture", e)
            null
        } finally {
            tmpFile.delete()
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
