package com.superbrain.glasses

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * SNTP client that queries ntp.aliyun.com and sets system time via DevicePolicyManager.
 * Requires the app to be Device Owner.
 */
object NtpSync {

    private const val TAG = "NtpSync"
    private const val NTP_HOST = "ntp.aliyun.com"
    private const val NTP_PORT = 123
    private const val TIMEOUT_MS = 5000
    // NTP epoch starts 1900-01-01; Unix epoch starts 1970-01-01
    private const val NTP_DELTA = 2208988800L

    /**
     * Queries NTP and sets system time if Device Owner.
     * @return true if time was set successfully
     */
    fun syncTime(context: Context): Boolean {
        return try {
            val ntpTime = queryNtp() ?: run {
                Log.e(TAG, "NTP query failed")
                return false
            }
            Log.i(TAG, "NTP time: $ntpTime ms")
            setSystemTime(context, ntpTime)
        } catch (e: Exception) {
            Log.e(TAG, "syncTime error: ${e.message}")
            false
        }
    }

    /**
     * Sets system time using a timestamp received from VPS (fallback path).
     */
    fun setTimeFromServer(context: Context, epochMs: Long): Boolean {
        return setSystemTime(context, epochMs)
    }

    private fun queryNtp(): Long? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS

            val buf = ByteArray(48)
            buf[0] = 0x1B.toByte()  // LI=0, VN=3, Mode=3 (client)

            val address = InetAddress.getByName(NTP_HOST)
            val request = DatagramPacket(buf, buf.size, address, NTP_PORT)

            val t1 = System.currentTimeMillis()
            socket.send(request)

            val response = DatagramPacket(ByteArray(48), 48)
            socket.receive(response)
            val t4 = System.currentTimeMillis()

            socket.close()

            val data = response.data
            // Transmit Timestamp (bytes 40-47) — server's send time
            val seconds = ((data[40].toLong() and 0xFF) shl 24) or
                          ((data[41].toLong() and 0xFF) shl 16) or
                          ((data[42].toLong() and 0xFF) shl 8) or
                          (data[43].toLong() and 0xFF)
            val fraction = ((data[44].toLong() and 0xFF) shl 24) or
                           ((data[45].toLong() and 0xFF) shl 16) or
                           ((data[46].toLong() and 0xFF) shl 8) or
                           (data[47].toLong() and 0xFF)

            val ntpMs = (seconds - NTP_DELTA) * 1000L + (fraction * 1000L / 0x100000000L)

            // Apply round-trip correction
            val rtt = t4 - t1
            ntpMs + rtt / 2
        } catch (e: Exception) {
            Log.e(TAG, "NTP query error: ${e.message}")
            null
        }
    }

    private fun setSystemTime(context: Context, epochMs: Long): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceAdmin::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.w(TAG, "Not Device Owner — cannot set time")
                return false
            }
            dpm.setTime(admin, epochMs)
            Log.i(TAG, "System time set to $epochMs ms")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setSystemTime error: ${e.message}")
            false
        }
    }
}
