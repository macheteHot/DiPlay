package com.shilapi.xcertplay.media

import kotlin.math.exp
import kotlin.math.sqrt

/** Peak normalization is unchanged; speed controls only attack/release of the light level. */
internal class AmbientMusicBrightness(private val speed: AmbientColorSpeed = AmbientColorSpeed.FAST) {
    private var peak = 0.05
    private var previousTime: Long? = null
    private var smoothed = 1.0

    fun level(rms: Double, nowMillis: Long, maximum: Int): Int {
        val ceiling = maximum.coerceIn(1, 6)
        return kotlin.math.round(advance(rms, nowMillis, ceiling)).toInt().coerceIn(1, ceiling)
    }

    /**
     * The same response as a 0..1 fraction of the ceiling, for a lamp interface with a continuous
     * scale. Rounding to the product's six levels would quantise the music away before it reaches
     * such a lamp, which is exactly what makes the response read as steps.
     */
    fun fraction(rms: Double, nowMillis: Long, maximum: Int): Double {
        val ceiling = maximum.coerceIn(1, 6)
        val level = advance(rms, nowMillis, ceiling)
        if (ceiling <= 1) return 0.0
        return ((level - 1) / (ceiling - 1).toDouble()).coerceIn(0.0, 1.0)
    }

    /** Advances the shared state; callers must use exactly one of [level] and [fraction] per sample. */
    private fun advance(rms: Double, nowMillis: Long, ceiling: Int): Double {
        val energy = if (rms.isFinite()) rms.coerceIn(0.0, 1.0) else 0.0
        val first = previousTime == null
        val elapsed = previousTime?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0
        previousTime = nowMillis
        peak = maxOf(0.05, energy, peak * exp(-elapsed / 3_000.0))
        val normalized = (energy / peak).coerceIn(0.0, 1.0)
        val target = if (energy < 0.008) 1.0 else 1 + sqrt(normalized) * (ceiling - 1)
        val (attack, release) = when (speed) {
            AmbientColorSpeed.SLOW -> 120.0 to 450.0
            AmbientColorSpeed.STANDARD -> 45.0 to 220.0
            AmbientColorSpeed.FAST -> 0.0 to 70.0
        }
        val timeConstant = if (target > smoothed) attack else release
        // The first sample establishes state. Zero elapsed does not advance a fade;
        // FAST's zero attack can still respond immediately to a new upward target.
        if (first || timeConstant == 0.0) smoothed = target
        else if (elapsed > 0) smoothed += (target - smoothed) * (1 - exp(-elapsed / timeConstant))
        smoothed = smoothed.coerceIn(1.0, ceiling.toDouble())
        return smoothed
    }
}

/** Detection is 20Hz; this separate gate allows at most five lamp submissions per second. */
internal class AmbientMusicApplyCadence {
    private var lastSubmit: Long? = null
    fun trySubmit(nowMillis: Long): Boolean {
        if (lastSubmit?.let { nowMillis - it < 200 } == true) return false
        lastSubmit = nowMillis
        return true
    }
    fun reset() { lastSubmit = null }
}
