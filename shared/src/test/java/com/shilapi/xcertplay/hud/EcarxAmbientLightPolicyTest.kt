package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.media.AmbientAlbumPalette
import com.shilapi.xcertplay.media.AmbientColorSpeed
import com.shilapi.xcertplay.media.AmbientEffect
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

    @Test fun anEasedTransitionStartsAndEndsAtZeroSlope() {
        assertEquals(0.0, EcarxAmbientLightPolicy.ease(0), 0.0001)
        assertEquals(1.0, EcarxAmbientLightPolicy.ease(EcarxAmbientLightPolicy.RAMP_STEPS), 0.0001)
        val steps = (1..EcarxAmbientLightPolicy.RAMP_STEPS).map { EcarxAmbientLightPolicy.ease(it) }
        assertEquals("monotonic", steps, steps.sorted())
        assertEquals(0.5, EcarxAmbientLightPolicy.ease(EcarxAmbientLightPolicy.RAMP_STEPS / 2), 0.0001)
        // The first step is small, which is what stops the six-level scale snapping.
        assertTrue("first step must be gentle: ${steps.first()}", steps.first() < 0.2)
        assertEquals(0.0, EcarxAmbientLightPolicy.ease(-3), 0.0001)
        assertEquals(1.0, EcarxAmbientLightPolicy.ease(99), 0.0001)
    }

    @Test fun aSmallCorrectionRampsFasterThanAFullSweep() {
        val small = EcarxAmbientLightPolicy.rampMillis(150, 160)
        val full = EcarxAmbientLightPolicy.rampMillis(0, EcarxAmbientLightPolicy.MAX_INTENSITY)
        assertTrue("$small must be shorter than $full", small < full)
        assertTrue(small >= EcarxAmbientLightPolicy.MIN_RAMP_MILLIS)
        assertTrue(full <= EcarxAmbientLightPolicy.MIN_RAMP_MILLIS + EcarxAmbientLightPolicy.EXTRA_RAMP_MILLIS)
        assertTrue("even an unchanged target takes some time", EcarxAmbientLightPolicy.rampMillis(20, 20) >= 90L)
    }

    @Test fun anIntermediateValueStaysBetweenItsEnds() {
        assertEquals(0, EcarxAmbientLightPolicy.mix(0, 255, 0.0))
        assertEquals(255, EcarxAmbientLightPolicy.mix(0, 255, 1.0))
        assertEquals(128, EcarxAmbientLightPolicy.mix(0, 255, 0.5))
        assertEquals(200, EcarxAmbientLightPolicy.mix(200, 100, 0.0))
        assertEquals(100, EcarxAmbientLightPolicy.mix(200, 100, 1.0))
        assertEquals(150, EcarxAmbientLightPolicy.mix(200, 100, 0.5))
        // Clamped, so a caller cannot overshoot the property range.
        assertEquals(0, EcarxAmbientLightPolicy.mix(0, 255, -1.0))
        assertEquals(255, EcarxAmbientLightPolicy.mix(0, 255, 2.0))
    }

    @Test fun aBreathRisesAndFallsWithZeroSlopeAtBothEnds() {
        assertEquals(0.0, EcarxAmbientLightPolicy.breathFraction(0.0), 0.0001)
        assertEquals(1.0, EcarxAmbientLightPolicy.breathFraction(0.5), 0.0001)
        assertEquals(0.0, EcarxAmbientLightPolicy.breathFraction(1.0), 0.0001)
        // Symmetric about the top of the breath.
        assertEquals(
            EcarxAmbientLightPolicy.breathFraction(0.25),
            1 - EcarxAmbientLightPolicy.breathFraction(0.75),
            0.0001,
        )
    }

    @Test fun aBreathNeverGoesDarkAndNeverExceedsTheTarget() {
        val target = 200
        val floor = EcarxAmbientLightPolicy.floorIntensity(target)
        assertEquals(50, floor)
        val values = (0..10).map { EcarxAmbientLightPolicy.breathIntensity(target, it / 10.0) }
        assertTrue("stays between the floor and the target: $values", values.all { it in floor..target })
        assertEquals(floor, values.first())
        assertEquals(target, values[5])
        assertEquals(floor, values.last())
        assertTrue("even a tiny target stays visible", EcarxAmbientLightPolicy.floorIntensity(1) >= 1)
    }

    @Test fun theMeteorHeadTravelsWithATailAndWraps() {
        val start = EcarxAmbientLightPolicy.meteorLevels(0.0)
        assertEquals(EcarxAmbientLightPolicy.ZONE_COUNT, start.size)
        assertEquals(1.0, start[0], 0.0001)
        assertTrue("the tail follows the head: $start", start[1] < start[0] && start[1] > 0.0)
        // The head sits on the first zone, so the last zone holds the other half of the tail.
        assertEquals(start[1], start[4], 0.0001)
        val middle = EcarxAmbientLightPolicy.meteorLevels(0.5)
        assertTrue("the head moved: $middle", middle[2] > middle[0])
        assertTrue((start + middle).all { it in 0.0..1.0 })
        // A full cycle is the same frame again, so the sweep loops without a jump.
        assertEquals(start, EcarxAmbientLightPolicy.meteorLevels(1.0))
    }

    @Test fun aMeteorZoneStaysBetweenTheFloorAndTheTarget() {
        val target = 255
        val floor = EcarxAmbientLightPolicy.floorIntensity(target)
        assertEquals(floor, EcarxAmbientLightPolicy.meteorIntensity(target, 0.0))
        assertEquals(target, EcarxAmbientLightPolicy.meteorIntensity(target, 1.0))
        assertEquals(target, EcarxAmbientLightPolicy.meteorIntensity(target, 4.0))
        assertEquals(floor, EcarxAmbientLightPolicy.meteorIntensity(target, -2.0))
    }

    @Test fun theEffectPeriodFollowsTheSpeedAndMusicHasNone() {
        assertEquals(0L, EcarxAmbientLightPolicy.effectPeriodMillis(AmbientEffect.MUSIC, AmbientColorSpeed.STANDARD))
        listOf(AmbientEffect.BREATHING, AmbientEffect.METEOR).forEach { effect ->
            val slow = EcarxAmbientLightPolicy.effectPeriodMillis(effect, AmbientColorSpeed.SLOW)
            val standard = EcarxAmbientLightPolicy.effectPeriodMillis(effect, AmbientColorSpeed.STANDARD)
            val fast = EcarxAmbientLightPolicy.effectPeriodMillis(effect, AmbientColorSpeed.FAST)
            assertTrue("$effect must speed up: $slow/$standard/$fast", slow > standard && standard > fast)
            assertTrue("a frame every ${fast}ms still leaves room: $fast", fast >= 500L)
        }
        assertTrue(
            "a breath is slower than a sweep",
            EcarxAmbientLightPolicy.effectPeriodMillis(AmbientEffect.BREATHING, AmbientColorSpeed.STANDARD) >
                EcarxAmbientLightPolicy.effectPeriodMillis(AmbientEffect.METEOR, AmbientColorSpeed.STANDARD),
        )
    }

    @Test fun aDataValueIsReadFromTheVendorToString() {
        assertEquals(
            16742655,
            EcarxAmbientLightPolicy.parseDataValue("PATypes{availability = Active, dataValue = 16742655, statusValue = 0, formatValue = null}"),
        )
        assertEquals(20, EcarxAmbientLightPolicy.parseDataValue("PATypes{dataValue = 20}"))
        assertEquals(0, EcarxAmbientLightPolicy.parseDataValue("PATypes{dataValue = 0,}"))
    }

    @Test fun aStatusIsOnOrOffRatherThanANumber() {
        // Measured on the head unit: PA_Zone1StatusSettings{dataValue = Off}, PA_ZoneAllStatusSettings{... = On}.
        val on = "PA_ZoneAllStatusSettings{availability = Active, dataValue = On, statusValue = 0, formatValue = 0}"
        val off = "PA_Zone1StatusSettings{availability = Active, dataValue = Off, statusValue = 0, formatValue = 0}"
        assertEquals(1, EcarxAmbientLightPolicy.parseStatus(on))
        assertEquals(0, EcarxAmbientLightPolicy.parseStatus(off))
        // The unwrapped field value reads the same way.
        assertEquals(1, EcarxAmbientLightPolicy.parseStatus("On"))
        assertEquals(0, EcarxAmbientLightPolicy.parseStatus("Off"))
        // A vendor that reports the number instead is accepted too.
        assertEquals(1, EcarxAmbientLightPolicy.parseStatus("PATypes{dataValue = 1}"))
        assertEquals(0, EcarxAmbientLightPolicy.parseStatus("PATypes{dataValue = 0}"))
    }

    @Test fun anUnreadableStatusIsNotOff() {
        // Writing 0 back for an unread status would turn a lit zone off.
        assertNull(EcarxAmbientLightPolicy.parseStatus("PATypes{availability = Active}"))
        assertNull(EcarxAmbientLightPolicy.parseStatus("PATypes{dataValue = NoReq}"))
        assertNull(EcarxAmbientLightPolicy.parseStatus("PATypes{dataValue = 2}"))
        assertNull(EcarxAmbientLightPolicy.parseStatus(""))
    }

    @Test fun aSnapshotIsValidWithTheValuesTheHeadUnitActuallyReports() {
        // Live read 2026-10-15: Zones 1..2 lit, 3..5 off, ZoneAll on at intensity 20.
        val lit = EcarxAmbientLightPolicy.Zone(color = 16736077, intensity = 20, status = 1)
        val off = EcarxAmbientLightPolicy.Zone(color = 0, intensity = 0, status = 0)
        val snapshot = EcarxAmbientLightPolicy.Snapshot(
            zones = listOf(lit, EcarxAmbientLightPolicy.Zone(9140479, 20, 1), off, off, off),
            all = EcarxAmbientLightPolicy.Zone(16751663, 20, 1),
        )
        assertTrue("the real head-unit state must be accepted as a restore target", snapshot.valid)
    }

    @Test fun aToStringWithoutAUsableValueIsNotZero() {
        assertNull(EcarxAmbientLightPolicy.parseDataValue("PATypes{availability = Active}"))
        assertNull(EcarxAmbientLightPolicy.parseDataValue("PATypes{dataValue = unknown}"))
        assertNull(EcarxAmbientLightPolicy.parseDataValue(""))
    }
}
