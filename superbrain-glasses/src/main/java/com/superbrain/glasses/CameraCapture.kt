package com.superbrain.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** Callback delivers saved JPEG file (null on error). base64 由调用方按需生成以避免 OOM. */
    fun capture(onResult: (file: File?) -> Unit) {
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
                    onResult(null, null)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error")
                    camera.close()
                    cleanup()
                    onResult(null, null)
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed", e)
            cleanup()
            onResult(null, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            cleanup()
            onResult(null, null)
        }
    }

    private fun takePhoto(camera: CameraDevice, onResult: (file: File?, base64: String?) -> Unit) {
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
                val (photoFile, base64) = processCapture(bytes) ?: run {
                    onResult(null, null)
                    return@setOnImageAvailableListener
                }
                onResult(photoFile, base64)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading image", e)
                onResult(null, null)
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
                                    onResult(null, null)
                                    cleanup()
                                }
                            }, AE_CONVERGE_MS)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Preview request failed", e)
                            onResult(null, null)
                            cleanup()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configure failed")
                        onResult(null, null)
                        cleanup()
                    }
                },
                handler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession failed", e)
            onResult(null, null)
            cleanup()
        }
    }

    private fun rotationToExifOrientation(degrees: Int): Int = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun processCapture(jpegBytes: ByteArray): Pair<File, String>? {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "photo_$ts.webp")
        return try {
            // 降采样 decode：12MP→3MP bitmap，~12MB RAM（Rokid 安全区）
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                ?: return null

            // 旋转像素以使图像正向（而不是只写 EXIF tag，后端/小C Read 不解析 EXIF）
            if (SENSOR_ROTATION != 0) {
                val matrix = Matrix().apply { postRotate(SENSOR_ROTATION.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }

            // WebP q85 压缩：~150-300KB，视觉无感差，VLM 识别充足
            val os = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Bitmap.CompressFormat.WEBP_LOSSY
            else
                Bitmap.CompressFormat.WEBP
            bitmap.compress(fmt, 85, os)
            bitmap.recycle()

            val compressed = os.toByteArray()
            outFile.writeBytes(compressed)
            Log.i(TAG, "Photo compressed: ${compressed.size / 1024}KB WebP q85 (from ${jpegBytes.size / 1024}KB JPEG)")

            Pair(outFile, Base64.encodeToString(compressed, Base64.NO_WRAP))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM on processCapture", e)
            outFile.delete()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error processing capture", e)
            outFile.delete()
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
