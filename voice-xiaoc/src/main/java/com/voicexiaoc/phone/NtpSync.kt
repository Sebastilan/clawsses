package com.voicexiaoc.phone

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Best-effort SNTP client (optional). Queries ntp.aliyun.com and reports drift.
 *
 * Ported from superbrain-glasses/NtpSync.kt but trimmed: a normal phone keeps
 * accurate RTC time from the carrier/NITZ and cannot set the system clock
 * without privileged permissions, so the glasses' `service call alarm 2` hack
 * is dropped. This only measures drift (useful for time-sensitive licences).
 */
object NtpSync {

    private const val TAG = "NtpSync"
    private const val NTP_HOST = "ntp.aliyun.com"
    private const val NTP_PORT = 123
    private const val TIMEOUT_MS = 5000
    private const val NTP_DELTA = 2208988800L

    /** @return drift in ms between device clock and NTP, or null on failure. */
    fun measureDriftMs(): Long? {
        val ntp = queryNtp() ?: return null
        val drift = Math.abs(ntp - System.currentTimeMillis())
        Log.i(TAG, "NTP time=$ntp drift=${drift}ms")
        return drift
    }

    private fun queryNtp(): Long? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS
            val buf = ByteArray(48)
            buf[0] = 0x1B.toByte()
            val address = InetAddress.getByName(NTP_HOST)
            val t1 = System.currentTimeMillis()
            socket.send(DatagramPacket(buf, buf.size, address, NTP_PORT))
            val response = DatagramPacket(ByteArray(48), 48)
            socket.receive(response)
            val t4 = System.currentTimeMillis()
            socket.close()
            val data = response.data
            val seconds = ((data[40].toLong() and 0xFF) shl 24) or
                    ((data[41].toLong() and 0xFF) shl 16) or
                    ((data[42].toLong() and 0xFF) shl 8) or
                    (data[43].toLong() and 0xFF)
            val fraction = ((data[44].toLong() and 0xFF) shl 24) or
                    ((data[45].toLong() and 0xFF) shl 16) or
                    ((data[46].toLong() and 0xFF) shl 8) or
                    (data[47].toLong() and 0xFF)
            val ntpMs = (seconds - NTP_DELTA) * 1000L + (fraction * 1000L / 0x100000000L)
            ntpMs + (t4 - t1) / 2
        } catch (e: Exception) {
            Log.e(TAG, "NTP query error: ${e.message}")
            null
        }
    }
}
