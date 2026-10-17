package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lane angle/status -> the cluster's own lane icon id, from
 * `carDocs/吉利导航车道图标ID-laneIconId表.md`, using the sample captured on the car:
 * three lanes, the third at 0 degrees with a +90 highlight and status 2 (straight+right, right lit).
 */
class GeelyLaneIconTest {

    @Test
    fun straightLeftAndRightUseThePlainIds() {
        assertEquals(0, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 0, null)))
        assertEquals(1, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, -90, null)))
        assertEquals(3, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 90, null)))
        assertEquals(5, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 180, null)))
    }

    @Test
    fun slightTurnsUseTheAheadIds() {
        assertEquals(2, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, -45, null)))
        assertEquals(4, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 45, null)))
    }

    @Test
    fun aStraightLaneWithARightHighlightIsStraightPlusRight() {
        // The sample lane 2: angle 0, highlight +90, status 2 -> id 4 highlighted -> 19.
        assertEquals(19, GeelyLaneIcon.forLane(LaneGuidanceLane(2, 2, 0, 90)))
        assertEquals(4, GeelyLaneIcon.forLane(LaneGuidanceLane(2, 0, 0, 90)))
    }

    @Test
    fun aStraightLaneWithALeftHighlightIsStraightPlusLeft() {
        assertEquals(2, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 0, -90)))
        assertEquals(17, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 0, -90)))
    }

    @Test
    fun recommendedLanesUseTheHighlightedSet() {
        assertEquals(16, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, -90, null)))
        assertEquals(18, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 90, null)))
        assertEquals(15, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 0, null)))
    }
}
