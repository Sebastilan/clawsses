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

---

## P2a — 唤醒词 + 腾讯流式 ASR

在 P1c 的骨架上补齐语音输入链路。**唤醒词这一版走最简方案**：不引入本地
KWS 引擎（Picovoice 留到后续），改用 `MainActivity` 上一个「🎤 点按开始说话」
按钮模拟唤醒——够跑通整条 ASR 链路。免手唤醒后续单独排期。

### 新增 / 改动

| 文件 | 改动 |
|---|---|
| `TencentAsrClient.kt` | **新增**。腾讯云实时流式 ASR（WebSocket v2，`wss://asr.cloud.tencent.com/asr/v2/<APPID>`，HMAC-SHA1 签名 URL）。移植自 super-brain `asr_websocket.py`。麦克风 PCM 逐帧流式上送，回调 `onPartial`（中间结果 slice_type 0/1）/ `onFinal`（一句结束 slice_type 2）/ `onCompleted`（`final=1`）。`buildSignedUrl()` 抽为可单测的纯函数 |
| `VoiceState.kt` | **新增**。语音管线 UI 状态：Idle → Listening → Recognizing(partial) → Sent → Reply → Error |
| `AudioCapture.kt` | 新增 `startPcm()` 原始 PCM 回调路径（ASR 消费裸字节，不做 base64） |
| `ConfigStore.kt` | 新增 `asrSecretId/asrSecretKey/asrAppId`，默认取 `BuildConfig`（构建时从 `local.properties` 注入），可运行时覆盖；`asrConfigured` 校验 |
| `VoiceXiaocService.kt` | `startListening()/stopListening()/toggleListening()`：点按 → 上报 `wake` + 停 TTS → `AudioCapture.startPcm` → `TencentAsrClient` → `onFinal` 累积 → 停止时 `finish()` → 识别文本经 `WsClient.sendAsrText` 发网关 → 网关 `text_reply`/`echo` 回来 → 系统 TTS 念出。带 3s finish 兜底 |
| `MainActivity.kt` | 加大号点按按钮 + 实时语音状态卡片（录音/识别中/已发送/收到回复/错误 + partial 文本） |
| `build.gradle.kts` | `versionCode 2 / 0.2.0`；从 `local.properties` 注入 `TENCENT_*` 到 BuildConfig；`testOptions` 打开 `returnDefaultValues` + 单测输出流 |

### 凭据（不硬编码）

腾讯 ASR 凭据放 `local.properties`（gitignore），构建时注入 BuildConfig：

```properties
tencent.secretId=...
tencent.secretKey=...
tencent.appId=1317727798
```

来源见 super-brain `asr-pipeline` / home-hub `lgp-tv-soul` 记忆模块（一句话/流式共用同一份）。

### 端到端链路

```
点按按钮(模拟唤醒) → AudioCapture(PCM16k) → TencentAsrClient(腾讯流式ASR)
   → 识别文本 → WsClient {"type":"asr_text"} → voice-xiaoc-gateway
   → {"type":"echo"/"text_reply"} → 手机系统 TTS(TextToSpeech) 念出
```

### 构建产物（P2a，versionCode=2 / 0.2.0）

- **APK 下载 URL**：https://lgp-docs.oss-cn-hangzhou.aliyuncs.com/tmp/voice-xiaoc/voice-xiaoc-0.2.0-debug.apk1
- APK 大小：23,789,120 字节（约 22.7 MB）
- 构建环境：dev2（`xinxiang-win`，JDK17 `E:/lgp/jdk-17` + Android SDK `E:/lgp/android-sdk` compileSdk 34）
- 命令：`gradlew.bat :voice-xiaoc:assembleDebug`

> 说明：未自动 bump 线上 `version.json`（避免向已装机的手机意外推 OTA）。要发布给手机自动更新时，把 `version.json` 的 `versionCode` 改为 2、`apkUrl` 指向上面的 `.apk1`。

## P2a 验证记录（2026-08-09）

均在 dev2 实测，非纸面。

### 1. 腾讯流式 ASR 单元测试（真实云端，非 mock）

`src/test/java/.../TencentAsrIntegrationTest.kt` 用**真实 `TencentAsrClient`** 连
腾讯云线上 ASR 端点，喂入 `src/test/resources/test_zh.mp3`（edge-TTS 合成的中文
句子「帮我看看今天的日程安排」，16KB），断言识别文本非空且含关键词。完整走通
HMAC-SHA1 URL 签名 → WS 握手 → 音频流式上送 → `{"type":"end"}` 收尾 → 结果帧
JSON 解析（**与生产同一代码路径**，仅 `voice_format` 从 1=PCM 换成 8=mp3 以复用
测试音频）。

命令：
```
gradlew.bat :voice-xiaoc:testDebugUnitTest --tests "*TencentAsrIntegrationTest*"
```

结果:`BUILD SUCCESSFUL`, 2 tests, 0 failures。测试 stdout（腾讯真实返回，含流式中间结果）：
```
[partial] 帮我看
[partial] 帮我看看
[partial] 帮我看看今天的日程
[partial] 帮我看看今天的日程安排
[final]   帮我看看今天的日程安排。
==== ASR RESULT: "帮我看看今天的日程安排。" ====
```

另一用例 `signedUrl_isDeterministic_andWellFormed` 校验签名 URL 参数字典序 + 结构，通过。

### 2. APK 构建

`gradlew.bat :voice-xiaoc:assembleDebug` → `BUILD SUCCESSFUL in 12s`，
产物 `voice-xiaoc-debug.apk` 23,789,120 字节。`aapt dump badging` 确认：
`package: name='com.voicexiaoc.phone' versionCode='2' versionName='0.2.0'`。

### 3. 手机 → 网关 WS 契约（连线上 gateway.py）

本地起 `voice-xiaoc-gateway/gateway.py`（python3.8 + websockets 13.1，P2b CC-bridge），
用与 `WsClient.kt` 完全一致的帧实测：

```
1) connect ->  {"type":"connected","sessionId":"s-...","serverVersion":"0.2.0","id":"c-1"}
2) wake    ->  (网关日志 "wake event",按协议无回包)
3) ping    ->  {"type":"pong"}
```

`asr_text` 一帧未向线上发送——当前 gateway 已升级到 P2b，会把 `asr_text` 真实转发给
满血 CC actor；为不无谓触发 CC，未发该帧。其路由逻辑经代码核对（`gateway.py:248`
`asr_text → ask_cc → text_reply`），且 `WsClient` 已同时兼容 `echo`(P1a) 与
`text_reply`(P2b) 两种回包。

### 4. 未覆盖 / 局限

- **无真机**：设备端麦克风采集 → 扬声器 TTS 播报未在物理机跑（无可用真机/带音频的模拟器）。
  系统 TTS 走 `android.speech.tts.TextToSpeech`（P1c 起沿用未改）。ASR 前的 `AudioRecord`
  采集在生产用 `voice_format=1`(PCM)，与测试(mp3)仅差格式码，签名/流式/解析同路径已覆盖。
- 唤醒仍是点按（模拟），非免手 KWS。
