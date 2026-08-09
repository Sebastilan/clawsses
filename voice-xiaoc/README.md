# voice-xiaoc — 语音小C 手机 APK

面向**手机**的语音壳（app id `com.voicexiaoc.phone`）。连接 [voice-xiaoc-gateway](../../voice-xiaoc-gateway)
的 WebSocket 网关，是 super-brain 眼镜外脑在手机场景的精简重写。

本模块从 `superbrain-glasses/` 移植改造，**不含**蓝牙 CXR / 眼镜配对 / 唤醒词 / 手势路由
（那些留给后续步骤）。当前范围（P1c）：网关长连 + TTS + 麦克风采集骨架 + 常驻前台服务 +
OSS 首装/自动更新。

## 组成

| 文件 | 来源 | 职责 |
|---|---|---|
| `WsClient.kt` | 移植改造 | 连网关 WebSocket。**改用扁平 `type` 协议**（`connect`/`asr_text`/`ping` ↔ `connected`/`echo`/`text_reply`/`tts_audio`/`push`/`pong`），非眼镜端的 OpenClaw req/res/event RPC。自动重连 + 30s 心跳 |
| `TtsPlayer.kt` | 移植 | Android TTS 念 `text_reply`；`playBase64` 解码播放 `tts_audio` 的 base64 音频 |
| `AudioCapture.kt` | 移植 | 麦克风 PCM 16k 采集（手机改用 `VOICE_RECOGNITION` 源，去掉 Rokid 的 CAMCORDER hack）。已接线未启用，留给后续语音管线 |
| `ConfigStore.kt` | 移植 | 网关地址/端口/token 可配置，默认 `120.26.28.49:8021` |
| `NtpSync.kt` | 移植（精简） | 可选。仅测漂移，去掉眼镜端 `service call alarm` 特权改时 |
| `OtaUpdater.kt` | **原样照搬机制** | OSS 下载 APK → PackageInstaller 静默安装 |
| `VersionChecker.kt` | 新增 | 启动请求远端 `version.json`，本地 versionCode < 远端则自动触发 OtaUpdater |
| `VoiceXiaocService.kt` | 新增 | 常驻前台服务，持有 WsClient/TtsPlayer，WakeLock 保活（锁屏不断连），接 PackageInstaller 安装回调 |
| `MainActivity.kt` | 新增 | 极简状态屏：连接状态 + 版本号 + OTA 状态 |

## 自动更新流程（P1c）

1. APP 启动 → `VoiceXiaocService` 拉起 → `VersionChecker.check(versionUrl)`。
2. 拉取远端 `version.json`（`versionCode`/`versionName`/`apkUrl`/`changelog`）。
3. 本地 `versionCode` < 远端 → 自动调 `OtaUpdater` 下载 APK 并安装（无需用户点）。
4. Android 12+ 在 `session.commit()` 时**由系统弹出安装确认框**（OS 限制，不可绕过），
   UI 会先给出「更新中」提示。

> 说明：OSS 公网拦截 `.apk` 直链下载，故 APK 以 `.apk1` 后缀存放；PackageInstaller 不看扩展名，正常安装。

## 构建产物（P1c 首版，versionCode=1 / 0.1.0）

- **APK 下载 URL**：https://lgp-docs.oss-cn-hangzhou.aliyuncs.com/tmp/voice-xiaoc/voice-xiaoc-debug.apk1
- **version.json URL**：https://lgp-docs.oss-cn-hangzhou.aliyuncs.com/tmp/voice-xiaoc/version.json
- APK 大小：23,756,348 字节（约 22.7 MB）
- 构建：`gradlew :voice-xiaoc:assembleDebug`（JDK 17 + Android SDK compileSdk 34）

## 编译

```bash
# 在仓库根
./gradlew :voice-xiaoc:assembleDebug
# 产物：voice-xiaoc/build/outputs/apk/debug/voice-xiaoc-debug.apk
```

本模块自包含，不依赖 `:shared` / Rokid SDK，构建只拉 compose/okhttp/gson 等标准依赖。
