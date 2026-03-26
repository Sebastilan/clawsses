package com.superbrain.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Camera2-based photo capture. Adapted from clawsses glasses-app.
 * Returns base64 JPEG suitable for sending to VPS.
 */
class CameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapture"
        private const val MAX_WIDTH = 1280
        private const val MAX_HEIGHT = 960
        private const val JPEG_QUALITY = 75
    }

    var isCapturing = false
        private set

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    fun capture(onResult: (base64: String?) -> Unit) {
        if (isCapturing) return
        isCapturing = true
        Log.d(TAG, "Starting photo capture")

        val thread = HandlerThread("camera-capture").also { it.start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        cameraHandler = handler

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findBackCamera(cameraManager)
        if (cameraId == null) {
            Log.e(TAG, "No camera found")
            isCapturing = false
            cleanupThread()
            onResult(null)
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
        val captureSize = jpegSizes?.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
            ?.maxByOrNull { it.width * it.height }
            ?: jpegSizes?.minByOrNull { it.width * it.height }

        val width = captureSize?.width ?: MAX_WIDTH
        val height = captureSize?.height ?: MAX_HEIGHT
        Log.d(TAG, "Capture size: ${width}x${height}")

        val imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    val base64 = processCapture(bytes)
                    onResult(base64)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading image", e)
                    onResult(null)
                } finally {
                    isCapturing = false
                    cleanupThread()
                }
            }
        }, handler)

        try @Suppress("MissingPermission") {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureBuilder.addTarget(imageReader.surface)
                        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                                camera.close()
                                            }
                                            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                                                camera.close()
                                                isCapturing = false
                                                cleanupThread()
                                                onResult(null)
                                            }
                                        }, handler)
                                    } catch (e: Exception) {
                                        camera.close()
                                        isCapturing = false
                                        cleanupThread()
                                        onResult(null)
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    camera.close()
                                    isCapturing = false
                                    cleanupThread()
                                    onResult(null)
                                }
                            }, handler
                        )
                    } catch (e: Exception) {
                        camera.close()
                        isCapturing = false
                        cleanupThread()
                        onResult(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    isCapturing = false
                    cleanupThread()
                    onResult(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    isCapturing = false
                    cleanupThread()
                    onResult(null)
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            isCapturing = false
            cleanupThread()
            onResult(null)
        } catch (e: Exception) {
            Log.e(TAG, "Camera error: ${e.message}", e)
            isCapturing = false
            cleanupThread()
            onResult(null)
        }
    }

    private fun processCapture(jpegBytes: ByteArray): String? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)

            val needsResize = options.outWidth > MAX_WIDTH || options.outHeight > MAX_HEIGHT
            val finalBytes: ByteArray

            if (needsResize) {
                val sampleSize = maxOf(options.outWidth / MAX_WIDTH, options.outHeight / MAX_HEIGHT).coerceAtLeast(1)
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions) ?: return null
                val scale = minOf(MAX_WIDTH.toFloat() / bitmap.width, MAX_HEIGHT.toFloat() / bitmap.height).coerceAtMost(1f)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        .also { if (it !== bitmap) bitmap.recycle() }
                } else bitmap
                val os = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)
                finalBytes = os.toByteArray()
                scaled.recycle()
            } else {
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
                val os = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)
                finalBytes = os.toByteArray()
                bitmap.recycle()
            }

            val base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
            Log.d(TAG, "Photo: ${finalBytes.size} bytes, base64=${base64.length} chars")
            base64
        } catch (e: Exception) {
            Log.e(TAG, "Error processing capture", e)
            null
        }
    }

    fun cleanup() {
        cleanupThread()
    }

    private fun cleanupThread() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun findBackCamera(cameraManager: CameraManager): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }
}
