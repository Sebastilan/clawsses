package com.superbrain.glasses

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.text.SimpleDateFormat
import java.util.*

class VideoRecorder(private val context: Context) : LifecycleOwner {

    companion object {
        private const val TAG = "VideoRecorder"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null

    var isRecording = false
        private set
    var lastFilePath: String? = null
        private set

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start(onResult: (success: Boolean, message: String) -> Unit) {
        if (isRecording) {
            onResult(false, "Already recording")
            return
        }

        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()

                val videoCapture = VideoCapture.withOutput(recorder)

                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)

                // Build output options using MediaStore
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val filename = "VID_$timestamp.mp4"
                lastFilePath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/SuperBrain/$filename"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/SuperBrain")
                }

                val mediaStoreOutput = MediaStoreOutputOptions.Builder(
                    context.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(contentValues).build()

                // Start recording
                val pendingRecording = videoCapture.output
                    .prepareRecording(context, mediaStoreOutput)

                if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PermissionChecker.PERMISSION_GRANTED) {
                    pendingRecording.withAudioEnabled()
                }

                recording = pendingRecording.start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            Log.i(TAG, "Recording started: $filename")
                            onResult(true, "Recording started")
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            if (event.hasError()) {
                                Log.e(TAG, "Recording error: ${event.error} - ${event.cause?.message}")
                                onResult(false, "Recording error: ${event.cause?.message}")
                            } else {
                                Log.i(TAG, "Recording saved: ${event.outputResults.outputUri}")
                            }
                            stopCamera()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}", e)
                onResult(false, "Failed: ${e.message}")
            }
        }, mainExecutor)
    }

    fun stop(onResult: (success: Boolean, filePath: String?) -> Unit) {
        if (!isRecording || recording == null) {
            onResult(false, null)
            return
        }
        Log.i(TAG, "Stopping recording...")
        recording?.stop()
        recording = null
        onResult(true, lastFilePath)
    }

    private fun stopCamera() {
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "stopCamera error", e)
        }
    }

    fun cleanup() {
        if (isRecording) {
            recording?.stop()
            recording = null
            isRecording = false
        }
        stopCamera()
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (_: Exception) {}
    }
}
