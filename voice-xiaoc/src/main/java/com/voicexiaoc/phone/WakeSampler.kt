package com.voicexiaoc.phone

/**
 * 唤醒样本采集 —— 把每次唤醒命中前后的音频留住，供事后校准阈值。
 *
 * ## 为什么需要
 *
 * 统帅反馈"唤醒有时候生效有时候不生效"。KWS 本质是相似度过阈值
 * （[KwsDetector] 的 `keywordsThreshold`），所以这是个召回率问题：阈值卡太严就漏。
 *
 * 麻烦在于 **sherpa 的 JNI 不返回置信度**：
 *
 *     data class KeywordSpotterResult(val keyword: String, val tokens: ..., val timestamps: ...)
 *
 * 没命中就是空字符串，连"差多少"都不知道。所以没法直接统计"差一点点的那些"。
 * 退而求其次：**把阈值降下来提召回，然后把每次命中的音频留证**，事后转写核对
 * 到底哪些是他真喊了、哪些是误唤醒，再据此定阈值。
 *
 * ## 环形缓冲
 *
 * 命中那一刻再开始录就晚了 —— 唤醒词已经说完了。所以一直往环里写，
 * 命中时把**之前**那两秒倒出来。环是定长的，不会涨。
 *
 * ## 隐私
 *
 * 这会把"没唤醒成功的那两秒"也传上云（统帅 2026-08-14 明确同意）。
 * 边界必须守住：**只有 KWS 命中那一刻的窗口**，不是常开录音。没命中就什么都不传，
 * 环里的数据被后来的覆盖掉，从不离开手机。
 */
class WakeSampler(
    /** 留多长的前情。唤醒词本身约 1 秒，留 2 秒能把起头也裹进去。 */
    private val preRollMs: Int = 2000,
    private val sampleRate: Int = 16000,
) {
    /** 16-bit 单声道 → 每毫秒 32 字节。 */
    private val capacity = preRollMs * sampleRate * 2 / 1000
    private val ring = ByteArray(capacity)
    private var writePos = 0
    private var filled = 0

    /** 每来一块 PCM 就写进环。**必须无条件调用**，命中时才有前情可倒。 */
    @Synchronized
    fun feed(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        // 单块超过环容量时只留最后 capacity 字节（正常块 100ms，不会发生）
        val src = if (pcm.size > capacity) pcm.copyOfRange(pcm.size - capacity, pcm.size) else pcm
        val firstLen = minOf(src.size, capacity - writePos)
        System.arraycopy(src, 0, ring, writePos, firstLen)
        if (firstLen < src.size) {
            System.arraycopy(src, firstLen, ring, 0, src.size - firstLen)
        }
        writePos = (writePos + src.size) % capacity
        filled = minOf(capacity, filled + src.size)
    }

    /** 倒出环里现有的音频（时间正序）。环没满时只给已有的那部分。 */
    @Synchronized
    fun snapshot(): ByteArray {
        if (filled == 0) return ByteArray(0)
        val out = ByteArray(filled)
        if (filled < capacity) {
            System.arraycopy(ring, 0, out, 0, filled)
        } else {
            val tailLen = capacity - writePos          // writePos 之后是较早的数据
            System.arraycopy(ring, writePos, out, 0, tailLen)
            System.arraycopy(ring, 0, out, tailLen, writePos)
        }
        return out
    }

    @Synchronized
    fun clear() {
        writePos = 0
        filled = 0
    }

    /** 环里现有多少毫秒的音频（供日志判断样本够不够长）。 */
    @Synchronized
    fun bufferedMs(): Int = filled * 1000 / (sampleRate * 2)
}
