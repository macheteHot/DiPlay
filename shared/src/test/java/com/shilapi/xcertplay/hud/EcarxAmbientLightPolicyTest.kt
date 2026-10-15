package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.media.AmbientAlbumPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxAmbientLightPolicyTest {

    @Test fun theHeadUnitGateOnlyAdmitsTheMeasuredDevice() {
        assertTrue(EcarxAmbientLightPolicy.supported(EcarxAmbientLightPolicy.DEVICE))
        assertFalse(EcarxAmbientLightPolicy.supported("robolectric"))
        assertFalse(EcarxAmbientLightPolicy.supported(""))
    }

    @Test fun everyBrightnessLevelMapsInsideTheIntensityRangeInOrder() {
        val intensities = (1..6).map { EcarxAmbientLightPolicy.intensityFor(it) }
        assertEquals(listOf(25, 60, 100, 150, 200, 255), intensities)
        assertTrue(intensities.all { it in 0..EcarxAmbientLightPolicy.MAX_INTENSITY })
        assertEquals(intensities, intensities.sorted())
        assertEquals(EcarxAmbientLightPolicy.MAX_INTENSITY, intensities.last())
        assertTrue("level 1 must stay visible", intensities.first() > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun brightnessOutsideTheSliderRangeIsRejected() {
        EcarxAmbientLightPolicy.intensityFor(0)
    }

    @Test fun theColorNumberBecomesTheSwatchRgbWithoutAlpha() {
        for (number in 1..31) {
            val rgb = EcarxAmbientLightPolicy.rgbFor(number)
            assertEquals(AmbientAlbumPalette.previewRgb(number) and 0xFFFFFF, rgb)
            assertTrue("alpha must be dropped: $rgb", rgb in 0..0xFFFFFF)
        }
        // The swatch table is the only reference for what the UI shows; spot-check two ends of it.
        assertEquals(0xa84cc1, EcarxAmbientLightPolicy.rgbFor(1))
        assertEquals(0x88b3b8, EcarxAmbientLightPolicy.rgbFor(31))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aColorNumberOutsideTheSwatchTableIsRejected() {
        EcarxAmbientLightPolicy.rgbFor(32)
    }

    @Test fun oneFrameBecomesTheWholeStripWrite() {
        val values = EcarxAmbientLightPolicy.valuesFor(colorNumber = 1, brightness = 6)
        assertEquals(0xa84cc1, values.color)
        assertEquals(255, values.intensity)
    }

    @Test fun aSnapshotNeedsEveryZoneAndAcceptableValues() {
        val zone = EcarxAmbientLightPolicy.Zone(color = 0x102030, intensity = 20, status = 1)
        val snapshot = EcarxAmbientLightPolicy.Snapshot(List(5) { zone }, zone)
        assertTrue(snapshot.valid)
        assertFalse("a short zone list is not a full snapshot", snapshot.copy(zones = List(4) { zone }).valid)
        assertFalse("an over-range intensity is rejected", snapshot.copy(all = zone.copy(intensity = 256)).valid)
        assertFalse("an over-range color is rejected", snapshot.copy(all = zone.copy(color = 0x1000000)).valid)
        assertFalse("a status other than on/off is rejected", snapshot.copy(all = zone.copy(status = 2)).valid)
        assertFalse(
            "an unreadable zone must fail the snapshot",
            snapshot.copy(zones = List(5) { if (it == 2) zone.copy(color = Int.MIN_VALUE) else zone }).valid,
        )
    }

    @Test fun anUnreadableStateIsNotAValidWriteBack() {
        val readable = EcarxAmbientLightPolicy.Zone(color = 0, intensity = 0, status = 0)
        assertTrue(readable.valid)
        assertFalse(readable.copy(intensity = -1).valid)
    }

    @Test fun restoreWritesTheWholeStripBeforeEachZone() {
        val zone = EcarxAmbientLightPolicy.Zone(color = 0x0a0b0c, intensity = 30, status = 1)
        val snapshot = EcarxAmbientLightPolicy.Snapshot(List(5) { zone }, zone)
        val steps = EcarxAmbientLightPolicy.restoreSteps(snapshot)
        assertEquals(6, steps.size)
        assertTrue("ZoneAll is written first", steps.first().third)
        assertEquals(0, steps.first().first)
        assertEquals(listOf(1, 2, 3, 4, 5), steps.drop(1).map { it.first })
        assertTrue("per-zone steps are not the whole-strip step", steps.drop(1).none { it.third })
        assertEquals(List(6) { snapshot.all }, steps.map { it.second })
    }

    @Test fun aDataValueIsReadFromTheVendorToString() {
        assertEquals(
            16742655,
            EcarxAmbientLightPolicy.parseDataValue("PATypes{availability = Active, dataValue = 16742655, statusValue = 0, formatValue = null}"),
        )
        assertEquals(20, EcarxAmbientLightPolicy.parseDataValue("PATypes{dataValue = 20}"))
        assertEquals(0, EcarxAmbientLightPolicy.parseDataValue("PATypes{dataValue = 0,}"))
    }

    @Test fun aToStringWithoutAUsableValueIsNotZero() {
        assertNull(EcarxAmbientLightPolicy.parseDataValue("PATypes{availability = Active}"))
        assertNull(EcarxAmbientLightPolicy.parseDataValue("PATypes{dataValue = unknown}"))
        assertNull(EcarxAmbientLightPolicy.parseDataValue(""))
    }
}
