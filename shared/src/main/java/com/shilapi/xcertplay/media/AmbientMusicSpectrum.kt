package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * Five-band spectrum of the decoded CarPlay audio, so the lamps follow the music's structure
 * (kick, snare, hats) instead of a single loudness value.
 *
 * The bands are log-spaced across the range that a car's lamp strip can usefully show. The level
 * comes from a Goertzel filter, which costs one multiply-add per sample per band and is accurate
 * enough for a 5-band display; a full FFT would not tell the eye anything more.
 */
object AmbientMusicSpectrum {

    /** Band centres in Hz. Low is the kick, high is the hats. */
    val BAND_HZ = doubleArrayOf(70.0, 180.0, 450.0, 1100.0, 2800.0)

    val BAND_COUNT: Int get() = BAND_HZ.size

    /** How much each band contributes to the overall "activity" that drives brightness. */
    private val WEIGHTS = doubleArrayOf(0.34, 0.26, 0.18, 0.13, 0.09)

    private const val SAMPLE_RATE = 48_000.0

    /**
     * Normalised 0..1 level per band for one PCM window. Values are mapped from dBFS with a floor
     * around the noise level of decoded music, so quiet passages settle near zero instead of
     * jittering.
     */
    fun pcmBands(data: ByteArray, offset: Int, length: Int): DoubleArray {
        val bands = DoubleArray(BAND_COUNT)
        if (offset < 0 || length < 2 || offset > data.size - length) return bands
        val samples = length / 2
        val starts = DoubleArray(BAND_COUNT) { band ->
            2 * cos(2 * PI * BAND_HZ[band] / SAMPLE_RATE)
        }
        val s1 = DoubleArray(BAND_COUNT)
        val s2 = DoubleArray(BAND_COUNT)
        var taken = 0
        var index = 0
        while (index < samples) {
            val position = offset + index * 2
            val sample = ((data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)).toShort() / 32768.0
            for (band in 0 until BAND_COUNT) {
                val s0 = sample + starts[band] * s1[band] - s2[band]
                s2[band] = s1[band]
                s1[band] = s0
            }
            taken++
            index++
        }
        if (taken == 0) return bands
        for (band in 0 until BAND_COUNT) {
            val coefficient = starts[band]
            val power = s1[band] * s1[band] + s2[band] * s2[band] - coefficient * s1[band] * s2[band]
            val db = 10 * log10(maxOf(1e-12, power / taken / taken))
            // -60 dBFS reads as silence, -10 dBFS as full scale.
            bands[band] = ((db + 60.0) / 50.0).coerceIn(0.0, 1.0)
        }
        return bands
    }

    /** Weighted 0..1 activity for the whole window; this is what brightness follows. */
    fun activity(bands: DoubleArray): Double {
        if (bands.size < BAND_COUNT) return 0.0
        var sum = 0.0
        for (band in 0 until BAND_COUNT) sum += WEIGHTS[band] * bands[band].coerceIn(0.0, 1.0)
        return sum.coerceIn(0.0, 1.0)
    }

    /** Index of the loudest band, used to pick a colour from the palette. */
    fun dominant(bands: DoubleArray): Int {
        var best = 0
        var bestValue = -1.0
        for (band in 0 until minOf(bands.size, BAND_COUNT)) {
            if (bands[band] > bestValue) {
                bestValue = bands[band]
                best = band
            }
        }
        return best
    }

    /** Element-wise maximum, so a short hit inside a window is not averaged away. */
    fun strong(previous: DoubleArray?, next: DoubleArray): DoubleArray {
        if (previous == null || previous.size != next.size) return next.copyOf()
        return DoubleArray(next.size) { maxOf(previous[it], next[it]) }
    }
}
