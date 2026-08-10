package com.voicexiaoc.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal status screen: shows gateway connection state + current version +
 * OTA self-check status. All heavy lifting lives in VoiceXiaocService.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val serviceState = MutableStateFlow<VoiceXiaocService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceState.value = (binder as VoiceXiaocService.LocalBinder).service
            Log.i(TAG, "bound to service")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()

        VoiceXiaocService.start(this)
        bindService(Intent(this, VoiceXiaocService::class.java), connection, BIND_AUTO_CREATE)

        val versionName = appVersionName()
        val versionCode = appVersionCode()

        setContent {
            MaterialTheme {
                val svc by serviceState.collectAsState()
                StatusScreen(svc, versionName, versionCode)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        // 定位：让任意 CC 都能回答"统帅现在在哪"。不申请就永远拿不到位置。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun appVersionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        }
    } catch (e: Exception) { -1 }

    override fun onDestroy() {
        super.onDestroy()
        try { unbindService(connection) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = permissions.zip(grantResults.toTypedArray())
            .joinToString { (p, r) -> "$p=${if (r == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}" }
        Log.i(TAG, "permission result: $granted")
        VoiceXiaocService.instance?.ws?.sendLog("info", "MainActivity", "permission result: $granted")
        // 刚授予定位就立刻开始上报，不用重启 APP
        val locOk = permissions.zip(grantResults.toTypedArray()).any { (p, r) ->
            p == Manifest.permission.ACCESS_FINE_LOCATION && r == PackageManager.PERMISSION_GRANTED
        }
        if (locOk) VoiceXiaocService.instance?.onLocationPermissionGranted()
    }
}

@Composable
private fun StatusScreen(svc: VoiceXiaocService?, versionName: String, versionCode: Int) {
    val connected by (svc?.ws?.connected ?: MutableStateFlow(false)).collectAsState()
    val status by (svc?.ws?.status ?: MutableStateFlow("Starting…")).collectAsState()
    val otaState by (svc?.versionChecker?.state ?: MutableStateFlow(VersionChecker.State.Idle))
        .collectAsState(initial = VersionChecker.State.Idle)
    val lastReply by (svc?.lastReply ?: MutableStateFlow("")).collectAsState()
    val voice by (svc?.voiceState ?: MutableStateFlow<VoiceState>(VoiceState.Idle)).collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0B0F)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("语音小C", color = Color(0xFF7CFF9B), fontSize = 30.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(14.dp).clip(CircleShape)
                    .background(if (connected) Color(0xFF37E06B) else Color(0xFFE04B4B)))
                Text(if (connected) "已连接 voice-xiaoc-gateway" else "未连接",
                    color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            }

            Field("状态", status)
            Field("网关", svc?.let { "${it.config.host}:${it.config.port}" } ?: "—")
            Field("版本", "v$versionName (code $versionCode)")
            OtaField(otaState) { svc?.installPendingUpdate() }

            // ── Push-to-talk (P2a simulated wake) ─────────────────────
            VoiceStatusCard(voice)

            val active = voice is VoiceState.Listening || voice is VoiceState.Recognizing
            TalkButton(active = active, enabled = svc != null) { svc?.toggleListening() }

            Spacer(Modifier.weight(1f))
            Text("说\"${VoiceXiaocService.WAKE_WORD}\"唤醒 · 本地识别，唤醒前不联网", color = Color(0xFF555560),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun VoiceStatusCard(voice: VoiceState) {
    val (accent, detail) = when (voice) {
        is VoiceState.Idle -> Color(0xFF888892) to "点按下方按钮，说一句话"
        is VoiceState.WakeListening -> Color(0xFF555560) to "常听中，说\"${VoiceXiaocService.WAKE_WORD}\"唤醒…"
        is VoiceState.Listening -> Color(0xFF37E06B) to "麦克风已开，请说话…"
        is VoiceState.Recognizing -> Color(0xFF7CC4FF) to (voice.text.ifBlank { "识别中…" })
        is VoiceState.Sent -> Color(0xFF7CFF9B) to "已发送: ${voice.text}"
        is VoiceState.Reply -> Color(0xFFFFD37C) to "小C: ${voice.text}"
        is VoiceState.Error -> Color(0xFFE04B4B) to voice.message
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF15151C)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("语音", color = Color(0xFF888892), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(voice.label(), color = accent, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(detail, color = Color(0xFFE6E6EA), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TalkButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = when {
        !enabled -> Color(0xFF2A2A31)
        active -> Color(0xFFE04B4B)
        else -> Color(0xFF1E7A3E)
    }
    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp)
            .clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (active) "■  立即发送" else "🎤  点按手动唤醒",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
        )
    }
}

/**
 * OTA 状态行。发现新版本时整行可点 —— 这是安装新版本的唯一入口。
 *
 * v0.6.0 把"开机自动装"关掉了(2026-08-09 曾在统帅夜间开车时把安装确认框弹到
 * 屏幕上)，却忘了在界面上给出手动确认的入口，结果那一版的 OTA 直接成了死路：
 * 能看到"发现新版本"，但没有任何地方可以点。只能浏览器下 APK 手动装。
 */
@Composable
private fun OtaField(state: VersionChecker.State, onInstall: () -> Unit) {
    val can = otaClickable(state)
    Column(modifier = if (can) Modifier.clickable { onInstall() } else Modifier) {
        Text("OTA", color = Color(0xFF888892), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(otaStateText(state),
            color = if (can) Color(0xFF7CFF9B) else Color(0xFFE6E6EA),
            fontSize = 16.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, color = Color(0xFF888892), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = Color(0xFFE6E6EA), fontSize = 16.sp, fontFamily = FontFamily.Monospace)
    }
}

/** 发现新版本时才可点：点一下才真正下载安装。见 VoiceXiaocService.installPendingUpdate。 */
private fun otaClickable(s: VersionChecker.State) = s is VersionChecker.State.UpdateAvailable

private fun otaStateText(s: VersionChecker.State): String = when (s) {
    is VersionChecker.State.Idle -> "空闲"
    is VersionChecker.State.Checking -> "检查更新中…"
    is VersionChecker.State.UpToDate -> "已是最新"
    is VersionChecker.State.UpdateAvailable -> "发现新版本 v${s.manifest.versionName} —— 点这里安装"
    is VersionChecker.State.Updating -> "更新中：${s.progress}"
    is VersionChecker.State.Failed -> "检查失败：${s.reason}"
}
