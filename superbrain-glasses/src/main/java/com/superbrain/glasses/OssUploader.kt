package com.superbrain.glasses

import android.content.Context
import android.util.Log
import com.alibaba.sdk.android.oss.ClientConfiguration
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.common.auth.OSSPlainTextAKSKCredentialProvider
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OssUploader {

    private const val TAG = "OssUploader"
    // Credentials are injected from local.properties via BuildConfig (never hardcoded in source)
    private const val BUCKET = "lgp-docs"
    private const val ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com"

    @Volatile private var ossClient: OSSClient? = null

    private fun getClient(context: Context): OSSClient {
        return ossClient ?: synchronized(this) {
            ossClient ?: run {
                val cred = OSSPlainTextAKSKCredentialProvider(
                    BuildConfig.OSS_ACCESS_KEY_ID,
                    BuildConfig.OSS_ACCESS_KEY_SECRET
                )
                val config = ClientConfiguration().apply {
                    connectionTimeout = 15_000
                    socketTimeout = 60_000
                    maxConcurrentRequest = 2
                }
                OSSClient(context.applicationContext, ENDPOINT, cred, config).also { ossClient = it }
            }
        }
    }

    /** Upload [file] to OSS. Returns the OSS key on success, null on failure. */
    suspend fun upload(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd/HHmmss_SSS", Locale.US).format(Date())
            val key = "glasses-photos/$ts.jpg"
            val req = PutObjectRequest(BUCKET, key, file.absolutePath)
            req.setProgressCallback { _, currentSize, totalSize ->
                if (totalSize > 0) Log.d(TAG, "Upload ${currentSize * 100 / totalSize}% ($currentSize/$totalSize)")
            }
            val result = getClient(context).putObject(req)
            Log.i(TAG, "Uploaded $key (${file.length() / 1024}KB) requestId=${result.requestId}")
            key
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    fun ossUrl(key: String): String = "https://$BUCKET.oss-cn-hangzhou.aliyuncs.com/$key"
}
