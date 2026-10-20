package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The five-band split that drives the lamp brightness from the decoded CarPlay audio. */
class AmbientMusicSpectrumTest {

    @Test
    fun aToneLandsInItsOwnBandAndDrivesTheActivity() {
        val bands = AmbientMusicSpectrum.pcmBands(sine(450.0, amplitude = 0.5), 0, sine(450.0, 0.5).size)
        assertEquals(2, AmbientMusicSpectrum.dominant(bands))
        assertTrue("450 Hz should be well above the noise floor: ${bands.toList()}", bands[2] > 0.5)
        assertTrue("activity follows the tone: ${bands.toList()}", AmbientMusicSpectrum.activity(bands) > 0.15)
    }

    @Test
    fun aBassToneLandsInTheFirstBand() {
        val data = sine(70.0, amplitude = 0.5)
        val bands = AmbientMusicSpectrum.pcmBands(data, 0, data.size)
        assertEquals(0, AmbientMusicSpectrum.dominant(bands))
    }

    @Test
    fun silenceIsZeroAndNeverNegative() {
        val bands = AmbientMusicSpectrum.pcmBands(ByteArray(4096), 0, 4096)
        assertEquals(0.0, AmbientMusicSpectrum.activity(bands), 0.0001)
        assertTrue(bands.all { it in 0.0..1.0 })
    }

    @Test
    fun aShortHitInsideAWindowKeepsItsLevel() {
        val quiet = DoubleArray(AmbientMusicSpectrum.BAND_COUNT)
        val hit = quiet.copyOf().also { it[1] = 0.8 }
        val merged = AmbientMusicSpectrum.strong(quiet, hit)
        assertEquals(0.8, merged[1], 0.0001)
    }

    @Test
    fun outOfRangeWindowsAreEmptyNotCrashing() {
        val bands = AmbientMusicSpectrum.pcmBands(ByteArray(8), 4, 100)
        assertEquals(AmbientMusicSpectrum.BAND_COUNT, bands.size)
        assertEquals(0.0, AmbientMusicSpectrum.activity(bands), 0.0001)
    }

    private fun sine(hz: Double, amplitude: Double): ByteArray {
        val samples = 4096
        val bytes = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value = (sin(2 * PI * hz * index / 48_000.0) * amplitude * 32767).toInt()
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return bytes
    }
}
