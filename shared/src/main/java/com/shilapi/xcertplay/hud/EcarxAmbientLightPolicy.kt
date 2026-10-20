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
     * The product's `(area, color, intensity)` frame as an ECARX ZoneAll write. On this path the third
     * value is already an intensity: the six levels are expanded before they reach here (see
     * [scaledIntensity]), so nothing is quantised twice.
     */
    fun valuesFor(colorNumber: Int, intensity: Int): Values =
        Values(rgbFor(colorNumber), intensity.coerceIn(1, MAX_INTENSITY))

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
    fun ease(step: Int): Double = easeFraction(step.toDouble() / RAMP_STEPS)

    /** The same raised cosine as a function of any 0..1 progress, for time-based motion. */
    fun easeFraction(progress: Double): Double = (1 - cos(PI * progress.coerceIn(0.0, 1.0))) / 2

    /** A bigger jump takes a little longer, so a full-range change does not snap either. */
    fun rampMillis(fromIntensity: Int, toIntensity: Int): Long {
        val distance = abs(toIntensity - fromIntensity).toDouble() / MAX_INTENSITY
        return (MIN_RAMP_MILLIS + distance * EXTRA_RAMP_MILLIS).toLong()
    }

    /** Intermediate value of an eased transition, rounded to what the property accepts. */
    fun mix(from: Int, to: Int, fraction: Double): Int =
        (from + (to - from) * fraction.coerceIn(0.0, 1.0)).roundToInt()

    /**
     * Animation frames per second budget: the zone lamps sit on the vehicle bus, not a display.
     * Measured with the carprobe timing probe on the gated unit (2026-10-21): one
     * {@code color+intensity} frame costs p50 3.7 ms / p95 11.7 ms and a tight loop sustains
     * 237 writes per second, so 25 ms (40 fps) keeps the write well under a third of the budget
     * while staying far below what the lamp service can absorb. The previous 40 ms value left the
     * motion visibly stepped at ~22 fps once the write time was added in.
     */
    const val ANIMATION_FRAME_MILLIS = 25L

    /** How long a colour takes to glide to a new one, so a beat does not snap the strip. */
    const val COLOR_GLIDE_MILLIS = 220L

    /** A breath never reaches zero, so the strip keeps its colour while it moves. */
    const val BREATH_FLOOR_PERCENT = 25

    /** One full cycle of an effect. The dialog's existing speed control sets the pace. */
    fun effectPeriodMillis(effect: AmbientEffect, speed: AmbientColorSpeed): Long = when (effect) {
        AmbientEffect.MUSIC -> 0L
        AmbientEffect.BREATHING -> when (speed) {
            AmbientColorSpeed.SLOW -> 6_000L
            AmbientColorSpeed.STANDARD -> 3_500L
            AmbientColorSpeed.FAST -> 1_800L
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
     * The music response on a continuous scale: the lowest level at no energy, the chosen ceiling at
     * full energy, and every value in between. This is what the product's six-level scale cannot
     * offer, and what makes the response glide instead of stepping.
     */
    fun scaledIntensity(fraction: Double, ceilingLevel: Int): Int =
        mix(intensityFor(1), intensityFor(ceilingLevel.coerceIn(1, 6)), fraction)

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
