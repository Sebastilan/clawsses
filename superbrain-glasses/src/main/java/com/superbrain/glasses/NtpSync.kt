package com.superbrain.glasses

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * SNTP client that queries ntp.aliyun.com and sets system time.
 * Uses "service call alarm 2" which works from app context on Rokid glasses.
 */
object NtpSync {

    private const val TAG = "NtpSync"
    private const val NTP_HOST = "ntp.aliyun.com"
    private const val NTP_PORT = 123
    private const val TIMEOUT_MS = 5000
    private const val NTP_DELTA = 2208988800L

    /**
     * Queries NTP and sets system time.
     * @return true if time was set successfully
     */
    fun syncTime(context: Context): Boolean {
        return try {
            val ntpTime = queryNtp() ?: run {
                Log.e(TAG, "NTP query failed")
                return false
            }

            val drift = Math.abs(ntpTime - System.currentTimeMillis())
            Log.i(TAG, "NTP time: $ntpTime ms, drift: ${drift}ms")

            if (drift < 30_000) {
                Log.i(TAG, "Time drift < 30s, no need to sync")
                return true
            }

            setSystemTime(ntpTime)
        } catch (e: Exception) {
            Log.e(TAG, "syncTime error: ${e.message}")
            false
        }
    }

    /**
     * Sets system time using a timestamp received from VPS (fallback path).
     */
    fun setTimeFromServer(context: Context, epochMs: Long): Boolean {
        val drift = Math.abs(epochMs - System.currentTimeMillis())
        Log.i(TAG, "Server time: $epochMs ms, drift: ${drift}ms")
        if (drift < 30_000) {
            Log.i(TAG, "Time drift < 30s, no need to sync")
            return true
        }
        return setSystemTime(epochMs)
    }

    private fun queryNtp(): Long? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS

            val buf = ByteArray(48)
            buf[0] = 0x1B.toByte()

            val address = InetAddress.getByName(NTP_HOST)
            val request = DatagramPacket(buf, buf.size, address, NTP_PORT)

            val t1 = System.currentTimeMillis()
            socket.send(request)

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
            val rtt = t4 - t1
            ntpMs + rtt / 2
        } catch (e: Exception) {
            Log.e(TAG, "NTP query error: ${e.message}")
            null
        }
    }

    /**
     * Set system time via "service call alarm 2 i64 <epochMs>".
     * This works on Rokid glasses without root or Device Owner.
     */
    private fun setSystemTime(epochMs: Long): Boolean {
        return try {
            val cmd = "service call alarm 2 i64 $epochMs"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText().trim()
            Log.i(TAG, "setSystemTime: cmd='$cmd' exit=$exitCode output='$output'")
            if (exitCode == 0 && output.contains("00000001")) {
                Log.i(TAG, "System time set to $epochMs ms successfully")
                true
            } else {
                Log.w(TAG, "setSystemTime may have failed: exit=$exitCode output='$output'")
                // Check if time actually changed
                val newDrift = Math.abs(epochMs - System.currentTimeMillis())
                newDrift < 5_000
            }
        } catch (e: Exception) {
            Log.e(TAG, "setSystemTime error: ${e.message}")
            false
        }
    }
}
