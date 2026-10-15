package com.shilapi.xcertplay.hud

import android.os.Build
import com.shilapi.xcertplay.media.AmbientAlbumPalette
import com.shilapi.xcertplay.media.AmbientColorSpeed
import com.shilapi.xcertplay.media.AmbientEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Pure rules for driving the Geely / Lynk & Co (ECARX) interior lamps from DiPlay's ambient settings.
 *
 * BYD's lamp interface takes an OEM color number (1..31), a raw brightness level (1..6) and an
 * area (front/rear/all). The ECARX one (`ecarx.car.hardware.vehicle.ECarXCarAmbliManager`, reached
 * through `ECarXCar`) takes arbitrary 24-bit RGB, an intensity (0..255) and named zones (Zones 1..5
 * plus ZoneAll), so the product's model has to be mapped. Measured on the gated head unit: a zone
 * write is accepted and the strip changes color (see `carDocs/headunit/15-氛围灯与HUD控制实测.md`).
 *
 * Writes change the car's visible state, so the gate is deliberately the same device string the
 * probe used: an unexpected head unit must never be written, and unknown states are rejected before
 * DiPlay takes the lamps over. Everything here is deterministic and testable; the reflection lives
 * in [EcarxAmbientLightClient].
 */
internal object EcarxAmbientLightPolicy {

    /** The one head unit whose ECARX lamp properties were measured. */
    const val DEVICE = "msmnile_gvmq"

    /** Zones the manager exposes; the product drives ZoneAll only (see [applyZone]). */
    const val ZONE_COUNT = 5

    const val MAX_INTENSITY = 255

    /**
     * BYD level 1..6 to ECARX intensity. The two scales are unrelated (BYD level 1 can switch a Han's
     * lamps off), so the mapping is an explicit table rather than a ratio: level 1 stays visible but
     * dim, level 6 is the full range. Tune here after seeing it on the car.
     */
    private val intensities = listOf(25, 60, 100, 150, 200, MAX_INTENSITY)

    fun intensityFor(level: Int): Int {
        require(level in 1..6) { "brightness level out of range: $level" }
        return intensities[level - 1]
    }

    /**
     * The RGB to write for an OEM color number. The stock BYD preview is DiPlay's only reference for
     * what the UI swatch shows, and the ECARX interface accepts that RGB directly. The alpha byte is
     * dropped: the lamp property is 24-bit.
     */
    fun rgbFor(colorNumber: Int): Int = AmbientAlbumPalette.previewRgb(colorNumber) and 0xFFFFFF

    /**
     * Only the measured device may be driven. The default is read defensively: a JVM unit test has no
     * usable `Build`, and reading it must not throw there.
     */
    fun supported(device: String? = runCatching { Build.DEVICE }.getOrNull()): Boolean = device == DEVICE

    /** One lamp zone as the manager reports it. */
    data class Zone(val color: Int, val intensity: Int, val status: Int) {
        val valid: Boolean get() =
            color in 0..0xFFFFFF && intensity in 0..MAX_INTENSITY && status in 0..1
    }

    /** Every zone DiPlay can write back, captured once before it takes the lamps over. */
    data class Snapshot(val zones: List<Zone>, val all: Zone) {
        val valid: Boolean get() = zones.size == ZONE_COUNT && zones.all { it.valid } && all.valid
    }

    /** The ZoneAll write for one music frame. */
    data class Values(val color: Int, val intensity: Int)

    /**
     * The product's `(area, color, brightness)` frame as an ECARX ZoneAll write.
     *
     * `area` is ignored on purpose: the BYD front/rear split has no known zone mapping on this head
     * unit, and guessing one could light the wrong part of the interior. ZoneAll is the whole strip.
     */
    fun valuesFor(colorNumber: Int, brightness: Int): Values =
        Values(rgbFor(colorNumber), intensityFor(brightness))

    /**
     * Restore order, matching the measured probe: the whole-strip value first, then each zone. Status
     * before color and intensity, so a zone that was off is not briefly lit by the write.
     */
    fun restoreSteps(snapshot: Snapshot): List<Triple<Int, Zone, Boolean>> {
        val steps = mutableListOf(Triple(0, snapshot.all, true))
        snapshot.zones.forEachIndexed { index, zone -> steps += Triple(index + 1, zone, false) }
        return steps
    }

    /**
     * Steps per eased transition. The zone lamps are driven over the vehicle bus, so a transition is
     * a handful of writes rather than a stream: the product hands over a level every ~200 ms, and
     * easing between those levels is what removes the visible steps.
     */
    const val RAMP_STEPS = 4

    /** Shortest and longest transition. Small corrections stay quick so the lamps still follow the music. */
    const val MIN_RAMP_MILLIS = 90L
    const val EXTRA_RAMP_MILLIS = 130L

    /**
     * Eased fraction of a transition at [step] of [RAMP_STEPS]: a raised cosine, so the change starts
     * and ends at zero slope. That is the "sine" shape - a linear ramp still reads as a snap at both
     * ends, and the stock six-level scale only ever offered those ends.
     */
    fun ease(step: Int): Double {
        val t = (step.toDouble() / RAMP_STEPS).coerceIn(0.0, 1.0)
        return (1 - cos(PI * t)) / 2
    }

    /** A bigger jump takes a little longer, so a full-range change does not snap either. */
    fun rampMillis(fromIntensity: Int, toIntensity: Int): Long {
        val distance = abs(toIntensity - fromIntensity).toDouble() / MAX_INTENSITY
        return (MIN_RAMP_MILLIS + distance * EXTRA_RAMP_MILLIS).toLong()
    }

    /** Intermediate value of an eased transition, rounded to what the property accepts. */
    fun mix(from: Int, to: Int, fraction: Double): Int =
        (from + (to - from) * fraction.coerceIn(0.0, 1.0)).roundToInt()

    /** Animation frames per second budget: the zone lamps sit on the vehicle bus, not a display. */
    const val ANIMATION_FRAME_MILLIS = 100L

    /** A breath never reaches zero, so the strip keeps its colour while it moves. */
    const val BREATH_FLOOR_PERCENT = 25

    /** How many zones the meteor's tail trails behind its head. */
    const val METEOR_TAIL_ZONES = 2.0

    /** One full cycle of an effect. The dialog's existing speed control sets the pace. */
    fun effectPeriodMillis(effect: AmbientEffect, speed: AmbientColorSpeed): Long = when (effect) {
        AmbientEffect.MUSIC -> 0L
        AmbientEffect.BREATHING -> when (speed) {
            AmbientColorSpeed.SLOW -> 6_000L
            AmbientColorSpeed.STANDARD -> 3_500L
            AmbientColorSpeed.FAST -> 1_800L
        }
        AmbientEffect.METEOR -> when (speed) {
            AmbientColorSpeed.SLOW -> 3_000L
            AmbientColorSpeed.STANDARD -> 1_600L
            AmbientColorSpeed.FAST -> 900L
        }
    }

    /** Breathing wave: a raised cosine, so the lamp eases at both ends of the breath. */
    fun breathFraction(phase: Double): Double = (1 - cos(2 * PI * phase)) / 2

    /** Lowest intensity a breath drops to: visible, but clearly below the target. */
    fun floorIntensity(target: Int): Int = maxOf(1, target * BREATH_FLOOR_PERCENT / 100)

    /** A breath between the floor and the music-driven target. */
    fun breathIntensity(target: Int, phase: Double): Int =
        mix(floorIntensity(target), target, breathFraction(phase))

    /**
     * Meteor levels at [phase] of a full sweep, one 0..1 value per zone. A head travels around the
     * zones with a tail behind it and wraps at the end, so the sweep never stops.
     */
    fun meteorLevels(
        phase: Double,
        zones: Int = ZONE_COUNT,
        tail: Double = METEOR_TAIL_ZONES,
    ): List<Double> {
        require(zones > 0) { "zones must be positive: $zones" }
        require(tail > 0.0) { "tail must be positive: $tail" }
        val position = (phase.mod(1.0)) * zones
        return (0 until zones).map { zone ->
            val direct = abs(position - zone)
            val distance = minOf(direct, zones - direct)
            (1.0 - distance / tail).coerceIn(0.0, 1.0)
        }
    }

    /** A meteor zone's intensity: the head reaches the target, the tail fades to the floor. */
    fun meteorIntensity(target: Int, level: Double): Int =
        mix(floorIntensity(target), target, level.coerceIn(0.0, 1.0))

    /**
     * `getPA_*` returns the vendor's `PATypes`. Color and intensity answer with a number, but a
     * **status property answers with `On` / `Off`** (measured: `PA_Zone1StatusSettings{dataValue = Off}`),
     * so the two are parsed separately. Returns null when the value cannot be read at all, which must
     * never be treated as zero: a status misread as `Off` would be written back that way.
     */
    fun parseDataValue(text: String): Int? = dataValueToken(text)?.toIntOrNull()

    /** A zone status: `On` -> 1, `Off` -> 0, or the number the vendor may report instead. */
    fun parseStatus(text: String): Int? = when (dataValueToken(text)) {
        "On", "1" -> 1
        "Off", "0" -> 0
        else -> null
    }

    /** The `dataValue` from a `PATypes.toString()`, or the whole text when it is already the value. */
    private fun dataValueToken(text: String): String? {
        val at = text.indexOf("dataValue = ")
        val value = if (at >= 0) text.substring(at + "dataValue = ".length) else text
        return value.split(',', '}').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
