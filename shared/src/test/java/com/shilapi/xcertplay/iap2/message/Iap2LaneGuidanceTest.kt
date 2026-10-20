package com.shilapi.xcertplay.iap2.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real `0x5204` payloads.
 *
 * <p>[sample] is the three-lane payload captured on the car (Apple Maps): lane 0 turns left, lane 1
 * goes straight, lane 2 is recommended and carries `angle=0, highlight=90`.
 *
 * <p>[compoundSample] was captured on 2026-10-19 from Apple Maps. Lane 0 carries the repeated angle
 * list `[-90, 0]`: it merges left and also continues straight. This is the compound case from #6.
 */
class Iap2LaneGuidanceTest {

    private val sample = hex(
        "00 06 00 00 00 2a" +
            " 00 06 00 01 00 00" +
            " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
            " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
            " 00 1b 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 02 00 00" +
            " 00 06 00 03 00 5a",
    )

    private val compoundSample = hex(
        "00 06 00 00 00 2a" +
            " 00 06 00 01 00 15" +
            " 00 1b 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
            " 00 06 00 02 00 00" +
            " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
            " 00 15 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 03 00 5a",
    )

    @Test
    fun theCapturedPayloadHasThreeLanes() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(3, lanes.size)
        assertEquals(listOf(0, 1, 2), lanes.map { it.index })
    }

    @Test
    fun anglesAreSignedDegrees() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(-90, lanes[0].angle)
        assertEquals(0, lanes[1].angle)
        assertEquals(0, lanes[2].angle)
    }

    @Test
    fun theThirdLaneIsRecommendedAndHighlighted() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(0, lanes[0].status)
        assertEquals(0, lanes[1].status)
        assertEquals(2, lanes[2].status)
        assertEquals(true, lanes[2].recommended)
        assertEquals(90, lanes[2].highlightAngle)
        assertNull(lanes[0].highlightAngle)
    }

    @Test
    fun aLaneKeepsEveryRepeatedAngle() {
        val lanes = Iap2LaneGuidance.parse(compoundSample)
        assertEquals(3, lanes.size)
        assertEquals(listOf(-90, 0), lanes[0].angles)
        assertEquals(-90, lanes[0].angle)
        assertEquals(true, lanes[0].compound)
        assertEquals(listOf(0), lanes[1].angles)
        assertEquals(emptyList<Int>(), lanes[2].angles)
        assertEquals(90, lanes[2].highlightAngle)
        assertEquals(true, lanes[2].recommended)
    }

    @Test
    fun theHighlightSentinelBecomesNull() {
        val lanes = Iap2LaneGuidance.parse(
            hex(
                "00 06 00 00 00 2a" +
                    " 00 06 00 01 00 00" +
                    " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 02" +
                    " 00 06 00 03 03 e8",
            ),
        )
        assertEquals(1, lanes.size)
        assertNull(lanes[0].highlightAngle)
    }

    @Test
    fun aMalformedPayloadYieldsNoLanes() {
        assertEquals(emptyList<LaneGuidanceLane>(), Iap2LaneGuidance.parse(byteArrayOf(0, 1, 2)))
    }

    private fun hex(text: String): ByteArray =
        text.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
