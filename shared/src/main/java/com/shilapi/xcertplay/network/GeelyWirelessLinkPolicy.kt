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
 * 2. **Wired USB.** The port runs as a gadget in its normal state (`sys.usb.config=carplay_carlife,adb`,
 *    `port can_change_data_role = false`), so Android never enumerates the iPhone and
 *    `UsbManager.getDeviceList()` stays empty. It can be switched to host from the engineering
 *    mode USB2.0 page, and the iPhone then enumerates; the button is therefore no longer hidden
 *    for that unit, see [wiredCarPlayAvailable].
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

    /**
     * Wired CarPlay is offered everywhere. Measured on the Geely unit on 2026-10-21: the port can
     * be flipped to host from engineering mode (USB2.0 certification → `USB1 HOST眼图`), and the
     * iPhone then enumerates as `05ac:12a8` with 12/31 interfaces. Whether DiPlay can drive that
     * session is still being tested, so the button stays enabled and the copy asks for a data port.
     */
    fun wiredCarPlayAvailable(detected: Boolean = LynkHeadUnit.detected): Boolean = true

    /** The link a stored selection resolves to, so an unusable old choice migrates by itself. */
    fun resolve(stored: String?, detected: Boolean = LynkHeadUnit.detected): WirelessHotspotMode {
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored } ?: defaultMode(detected)
        // LOCAL_ONLY_HOTSPOT is no longer offered; it stands for the built-in hotspot where that works.
        val preferred = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) WirelessHotspotMode.MANUAL else mode
        return if (preferred in disabledModes(detected)) defaultMode(detected) else preferred
    }
}
