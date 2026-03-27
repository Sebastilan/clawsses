package com.superbrain.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CameraCapture(private val context: Context) : LifecycleOwner {

    companion object {
        private const val TAG = "CameraCapture"
        private const val MAX_WIDTH = 1280
        private const val MAX_HEIGHT = 960
        private const val JPEG_QUALITY = 75
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    var isCapturing = false
        private set

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun capture(onResult: (base64: String?) -> Unit) {
        if (isCapturing) return
        isCapturing = true

        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val imageCaptureUseCase = ImageCapture.Builder()
                    .setTargetResolution(android.util.Size(MAX_WIDTH, MAX_HEIGHT))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageCaptureUseCase)

                imageCaptureUseCase.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val rotation = image.imageInfo.rotationDegrees
                            image.close()
                            val base64 = processCapture(bytes, rotation)
                            onResult(base64)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing capture", e)
                            onResult(null)
                        } finally {
                            mainExecutor.execute { stopCamera() }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Capture failed: ${exception.message}", exception)
                        onResult(null)
                        mainExecutor.execute { stopCamera() }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup failed", e)
                isCapturing = false
                onResult(null)
            }
        }, mainExecutor)
    }

    private fun stopCamera() {
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "stopCamera error", e)
        }
        isCapturing = false
    }

    private fun processCapture(jpegBytes: ByteArray, rotationDegrees: Int): String? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)

            val sampleSize = maxOf(options.outWidth / MAX_WIDTH, options.outHeight / MAX_HEIGHT).coerceAtLeast(1)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions) ?: return null

            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }

            val scale = minOf(MAX_WIDTH.toFloat() / bitmap.width, MAX_HEIGHT.toFloat() / bitmap.height).coerceAtMost(1f)
            val finalBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                    .also { if (it !== bitmap) bitmap.recycle() }
            } else bitmap

            val os = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)
            if (finalBitmap !== bitmap) finalBitmap.recycle()

            Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing capture", e)
            null
        }
    }

    fun cleanup() {
        stopCamera()
        executor.shutdown()
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (_: Exception) {}
    }
}
