package com.voicexiaoc.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 周期上报所在位置，让任意 CC 都能回答"统帅现在在哪"。
 *
 * 用系统的 [LocationManager] 而不是 Google 的 FusedLocationProvider：
 * 这台是国行 vivo，Google Play 服务多半没有，依赖它会直接拿不到位置。
 * LocationManager 是 AOSP 自带的，任何机型都在。
 *
 * 上报的是 **WGS-84**（Android 原生坐标系）。国内地图要的 GCJ-02 由网关换算，
 * 手机端不掺和——同一个换算只在一个地方做，免得两端各算一套算出不一致。
 *
 * 省电取舍：网络定位（基站/WiFi，几十米精度、几乎不耗电）常开；GPS 也挂着，
 * 但把最小间隔和最小位移放宽，静止时系统自然不会频繁给点。对"他在哪"这个
 * 需求，几十米精度完全够，不值得为几米精度一直点亮 GPS 芯片。
 */
class LocationReporter(
    private val context: Context,
    private val onLocation: (Location) -> Unit,
) {
    companion object {
        private const val TAG = "LocationReporter"
        private const val MIN_INTERVAL_MS = 60_000L   // 最快 1 分钟一报
        private const val MIN_DISTANCE_M = 100f       // 或移动超过 100 米
        /** 比这个还旧的缓存位置不拿来当"当前位置"用。 */
        private const val STALE_FIX_MS = 10 * 60 * 1000L
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var started = false

    /** 远程日志出口，由 Service 接到网关（本地 logcat 在路上看不到）。 */
    var onLog: ((level: String, msg: String) -> Unit)? = null
    private fun rlog(level: String, msg: String) { Log.i(TAG, msg); onLog?.invoke(level, msg) }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)
        // 老设备上不实现这几个回调会崩（API < 30 的 LocationListener 是抽象类）
        override fun onProviderEnabled(provider: String) {
            rlog("info", "定位源 $provider 已开启")
        }
        override fun onProviderDisabled(provider: String) {
            rlog("warn", "定位源 $provider 被关闭")
        }
        @Deprecated("API 29 起不再回调")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun start() {
        if (started) return
        if (!hasPermission) {
            rlog("warn", "没有定位权限，位置上报未启动")
            return
        }
        var any = false
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(provider)) {
                    rlog("warn", "定位源 $provider 未启用（系统设置里关着？）")
                    continue
                }
                lm.requestLocationUpdates(provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener)
                any = true
                rlog("info", "已订阅定位源 $provider")
            } catch (e: SecurityException) {
                rlog("error", "订阅 $provider 被拒: ${e.message}")
            } catch (e: Exception) {
                rlog("error", "订阅 $provider 失败: ${e.message}")
            }
        }
        if (!any) {
            rlog("error", "没有任何可用定位源，位置功能不可用")
            return
        }
        started = true
        pushLastKnown()
    }

    /**
     * 立刻推一个系统缓存的最后已知位置，避免刚启动那几分钟 CC 查不到任何位置。
     * 太旧的不用——宁可报"暂无位置"，也别把半小时前的点当成"他现在在哪"。
     */
    private fun pushLastKnown() {
        if (!hasPermission) return
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } }
            .filter { System.currentTimeMillis() - it.time < STALE_FIX_MS }
            .maxByOrNull { it.time }
        if (best != null) {
            rlog("info", "先用系统缓存的位置垫一下（${(System.currentTimeMillis() - best.time) / 1000}秒前）")
            onLocation(best)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try { lm.removeUpdates(listener) } catch (e: Exception) {
            rlog("warn", "取消定位订阅失败: ${e.message}")
        }
    }
}
