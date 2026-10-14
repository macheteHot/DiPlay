package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * The wireless links the Geely/Lynk head unit (`geely_fx11`, `CS11_A4`, Android 11) can run.
 *
 * Two links were measured unusable on the car itself:
 *
 * 1. **Built-in car hotspot.** The vendor hotspot is a separate module: the iPhone joins it and
 *    gets an address on that module's own network (measured `192.168.15.4`), while the head unit
 *    only holds `eth0`/`eth1` (`192.168.8.2`) and has no route to that network. CarPlay cannot
 *    reach the head unit over it, so DiPlay stops at `Hotspot network is not ready`.
 * 2. **Wired USB.** The port runs as a gadget (`sys.usb.config=carplay_carlife,adb`,
 *    `port can_change_data_role = false`), so Android never enumerates the iPhone and
 *    `UsbManager.getDeviceList()` stays empty.
 *
 * Wi-Fi Direct is the link that works (measured on 5 GHz channel 149). Everything here is gated on
 * [LynkHeadUnit.detected] so other head units keep every option.
 */
object GeelyWirelessLinkPolicy {
    /** The wireless link a fresh install starts on. */
    fun defaultMode(detected: Boolean = LynkHeadUnit.detected): WirelessHotspotMode =
        if (detected) WirelessHotspotMode.WIFI_P2P else WirelessHotspotMode.MANUAL

    /** Links this head unit offers. Callers keep the others visible but disabled. */
    fun disabledModes(detected: Boolean = LynkHeadUnit.detected): Set<WirelessHotspotMode> =
        if (detected) setOf(WirelessHotspotMode.MANUAL) else emptySet()

    /** Wired CarPlay is offered only where the port can enumerate an iPhone. */
    fun wiredCarPlayAvailable(detected: Boolean = LynkHeadUnit.detected): Boolean = !detected

    /** The link a stored selection resolves to, so an unusable old choice migrates by itself. */
    fun resolve(stored: String?, detected: Boolean = LynkHeadUnit.detected): WirelessHotspotMode {
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored } ?: defaultMode(detected)
        // LOCAL_ONLY_HOTSPOT is no longer offered; it stands for the built-in hotspot where that works.
        val preferred = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) WirelessHotspotMode.MANUAL else mode
        return if (preferred in disabledModes(detected)) defaultMode(detected) else preferred
    }
}
