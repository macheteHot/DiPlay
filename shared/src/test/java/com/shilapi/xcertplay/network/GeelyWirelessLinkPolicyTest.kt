package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyWirelessLinkPolicyTest {

    /** The Geely/Lynk unit can only run Wi-Fi Direct: its hotspot link was measured dead. */
    @Test
    fun theGeelyHeadUnitStartsOnWifiDirectAndHidesTheLinksItCannotRun() {
        assertEquals(WirelessHotspotMode.WIFI_P2P, GeelyWirelessLinkPolicy.defaultMode(detected = true))
        assertEquals(setOf(WirelessHotspotMode.MANUAL), GeelyWirelessLinkPolicy.disabledModes(detected = true))
    }

    /**
     * Wired CarPlay stays offered on the Geely unit: the port can be flipped to host from the
     * engineering mode USB2.0 page and the iPhone then enumerates (measured 2026-10-21).
     */
    @Test
    fun wiredCarPlayStaysOfferedOnTheGeelyHeadUnit() {
        assertTrue(GeelyWirelessLinkPolicy.wiredCarPlayAvailable(detected = true))
    }

    /** Every other head unit keeps the stock default and every option enabled. */
    @Test
    fun otherHeadUnitsKeepEveryLink() {
        assertEquals(WirelessHotspotMode.MANUAL, GeelyWirelessLinkPolicy.defaultMode(detected = false))
        assertTrue(GeelyWirelessLinkPolicy.disabledModes(detected = false).isEmpty())
        assertTrue(GeelyWirelessLinkPolicy.wiredCarPlayAvailable(detected = false))
    }

    @Test
    fun aSavedBuiltInHotspotSelectionMigratesToWifiDirectOnTheGeelyHeadUnit() {
        assertEquals(
            WirelessHotspotMode.WIFI_P2P,
            GeelyWirelessLinkPolicy.resolve("MANUAL", detected = true),
        )
        assertEquals(
            WirelessHotspotMode.MANUAL,
            GeelyWirelessLinkPolicy.resolve("MANUAL", detected = false),
        )
    }

    @Test
    fun aFreshInstallUsesTheHeadUnitDefault() {
        assertEquals(
            WirelessHotspotMode.WIFI_P2P,
            GeelyWirelessLinkPolicy.resolve(null, detected = true),
        )
        assertEquals(
            WirelessHotspotMode.MANUAL,
            GeelyWirelessLinkPolicy.resolve(null, detected = false),
        )
    }

    @Test
    fun linksThatStillWorkAreKept() {
        assertEquals(
            WirelessHotspotMode.WIFI_P2P,
            GeelyWirelessLinkPolicy.resolve("WIFI_P2P", detected = true),
        )
        assertEquals(
            WirelessHotspotMode.EXISTING_WIFI,
            GeelyWirelessLinkPolicy.resolve("EXISTING_WIFI", detected = true),
        )
        assertEquals(
            WirelessHotspotMode.WIFI_P2P,
            GeelyWirelessLinkPolicy.resolve("LOCAL_ONLY_HOTSPOT", detected = true),
        )
        assertEquals(
            WirelessHotspotMode.MANUAL,
            GeelyWirelessLinkPolicy.resolve("LOCAL_ONLY_HOTSPOT", detected = false),
        )
    }
}
