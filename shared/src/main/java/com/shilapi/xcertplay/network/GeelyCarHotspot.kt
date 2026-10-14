package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build

/**
 * Reads the head unit's own Wi-Fi hotspot state from the ECARX vendor API.
 *
 * The FX11 hotspot is a vendor `p2p0` local hotspot, so `WifiManager`'s SoftAP state
 * (`getWifiApState` / `isWifiApEnabled`) reports it as off even while it is on.
 * `ecarx.fw.api.wifi.TcamWifi` (implementation `com.autolink.adaptersrv.wifi.TcamWifiImpl`, public
 * `(Context)` constructor, on the boot classpath) reports the true state.
 *
 * Gated to the reverse-engineered unit and returns `null` on any other device or when the vendor
 * class is unavailable, so [CarHotspotStatus.isEnabled] falls back to the standard path.
 */
internal object GeelyCarHotspot {
    private const val SUPPORTED_DEVICE = "msmnile_gvmq"
    private const val TCAM_WIFI_IMPL = "com.autolink.adaptersrv.wifi.TcamWifiImpl"

    /** `true`/`false` when the vendor AP state is known, or `null` to keep the standard path. */
    fun read(context: Context): Boolean? {
        if (Build.DEVICE != SUPPORTED_DEVICE) return null
        return runCatching {
            val cls = Class.forName(TCAM_WIFI_IMPL, false, context.classLoader)
            val constructor = cls.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            val wifi = constructor.newInstance(context)
            val state = runCatching { cls.getMethod("getWifiApState").invoke(wifi) as? Int }.getOrNull()
            CarHotspotStatus.stateEnabled(state) ?: runCatching {
                cls.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean
            }.getOrNull()
        }.getOrNull()
    }
}
