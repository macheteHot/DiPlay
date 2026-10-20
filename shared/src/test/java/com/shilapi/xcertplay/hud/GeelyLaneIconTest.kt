package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lane angle/status -> the cluster's own lane icon id, from
 * `carDocs/吉利导航车道图标ID-laneIconId表.md`.
 *
 * <p>The two angle fields have two shapes. With a non-empty `Angles` list, `AngleHighlight` is the
 * recommended path, not a second lane arrow, so a straight lane with `highlightAngle=90` stays
 * straight. With an empty `Angles` list, `AngleHighlight` is the lane's single arrow: Apple Maps
 * sent recommended left lanes as `Angles=[] highlight=-90`, which are plain left arrows.
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
    fun aStraightLaneWithARightHighlightStaysStraight() {
        assertEquals(15, GeelyLaneIcon.forLane(LaneGuidanceLane(2, 2, 0, 90)))
        assertEquals(0, GeelyLaneIcon.forLane(LaneGuidanceLane(2, 0, 0, 90)))
    }

    @Test
    fun aStraightLaneWithALeftHighlightStaysStraight() {
        assertEquals(0, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 0, -90)))
        assertEquals(15, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 0, -90)))
    }

    @Test
    fun anEmptyAngleListUsesTheHighlightAsItsSingleArrow() {
        // Apple Maps: two recommended left lanes arrive as Angles=[] highlight=-90.
        assertEquals(1, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 0, -90, angles = emptyList())))
        assertEquals(16, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 0, -90, angles = emptyList())))
        assertEquals(3, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 0, 90, angles = emptyList())))
        assertEquals(18, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 0, 90, angles = emptyList())))
        assertEquals(0, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 0, 0, angles = emptyList())))
        assertEquals(15, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 0, 0, angles = emptyList())))
    }

    @Test
    fun compoundMergeLeftAndStraightUsesLeftAhead() {
        // Apple Maps: lane 0 of the six-lane 0x5204, angles [-90, 0].
        assertEquals(
            2,
            GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, -90, null, angles = listOf(-90, 0))),
        )
        assertEquals(
            17,
            GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, -90, null, angles = listOf(-90, 0))),
        )
    }

    @Test
    fun compoundStraightAndRightUsesRightAhead() {
        assertEquals(
            4,
            GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, 0, null, angles = listOf(0, 90))),
        )
        assertEquals(
            19,
            GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 0, null, angles = listOf(0, 90))),
        )
    }

    @Test
    fun compoundLeftAndRightUsesLeftRight() {
        assertEquals(
            6,
            GeelyLaneIcon.forLane(LaneGuidanceLane(0, 0, -90, null, angles = listOf(-90, 90))),
        )
        assertEquals(
            21,
            GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, -90, null, angles = listOf(-90, 90))),
        )
    }

    @Test
    fun recommendedLanesUseTheHighlightedSet() {
        assertEquals(16, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, -90, null)))
        assertEquals(18, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 90, null)))
        assertEquals(15, GeelyLaneIcon.forLane(LaneGuidanceLane(0, 2, 0, null)))
    }
}
