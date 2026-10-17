package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mapping restored from the car's stock packages: AMap `NEW_ICON` (0..28, 65..66) to the
 * cluster's `turnId` 100..148. Values come from `carDocs/吉利导航转向ID-turnId表.md`.
 */
class GeelyTurnIdTest {

    @Test
    fun plainManeuversUseTheHundredRange() {
        assertEquals(GeelyTurnId.LEFT, GeelyTurnId.forManeuver(2))
        assertEquals(GeelyTurnId.RIGHT, GeelyTurnId.forManeuver(3))
        assertEquals(GeelyTurnId.SLIGHT_LEFT, GeelyTurnId.forManeuver(4))
        assertEquals(GeelyTurnId.SLIGHT_RIGHT, GeelyTurnId.forManeuver(5))
        assertEquals(GeelyTurnId.HARD_LEFT, GeelyTurnId.forManeuver(6))
        assertEquals(GeelyTurnId.HARD_RIGHT, GeelyTurnId.forManeuver(7))
        assertEquals(GeelyTurnId.U_TURN, GeelyTurnId.forManeuver(8))
        assertEquals(GeelyTurnId.CONTINUE, GeelyTurnId.forManeuver(9))
        assertEquals(GeelyTurnId.DESTINATION, GeelyTurnId.forManeuver(15))
        assertEquals(GeelyTurnId.KEEP_LEFT, GeelyTurnId.forManeuver(65))
        assertEquals(GeelyTurnId.KEEP_RIGHT, GeelyTurnId.forManeuver(66))
    }

    @Test
    fun roundaboutExitsOffsetByTheExitNumber() {
        assertEquals(121, GeelyTurnId.forManeuver(12, roundaboutExit = 1))
        assertEquals(130, GeelyTurnId.forManeuver(12, roundaboutExit = 10))
        assertEquals(131, GeelyTurnId.forManeuver(18, roundaboutExit = 1))
        assertEquals(140, GeelyTurnId.forManeuver(18, roundaboutExit = 10))
    }

    @Test
    fun anInvalidExitFallsBackToThePlainLeaveIcon() {
        assertEquals(GeelyTurnId.LEAVE_ROUNDABOUT, GeelyTurnId.forManeuver(12, roundaboutExit = 0))
        assertEquals(GeelyTurnId.LEAVE_ROUNDABOUT, GeelyTurnId.forManeuver(12, roundaboutExit = 11))
        assertEquals(GeelyTurnId.LEAVE_LEFT_ROUNDABOUT, GeelyTurnId.forManeuver(18, roundaboutExit = -1))
    }

    @Test
    fun bubbleAndSecondFormsKeepTheirOwnIds() {
        assertEquals(GeelyTurnId.U_TURN_BUBBLE, GeelyTurnId.forManeuver(19))
        assertEquals(GeelyTurnId.CONTINUE_BUBBLE, GeelyTurnId.forManeuver(20))
        assertEquals(GeelyTurnId.ROUNDABOUT_LEFT_BUBBLE, GeelyTurnId.forManeuver(21))
        assertEquals(GeelyTurnId.ROUNDABOUT_RIGHT_BUBBLE, GeelyTurnId.forManeuver(22))
        assertEquals(GeelyTurnId.ROUNDABOUT_UTURN_BUBBLE, GeelyTurnId.forManeuver(24))
        assertEquals(GeelyTurnId.ROUNDABOUT_LEFT_2, GeelyTurnId.forManeuver(25))
        assertEquals(GeelyTurnId.ROUNDABOUT_RIGHT_2, GeelyTurnId.forManeuver(26))
        assertEquals(GeelyTurnId.ROUNDABOUT_UTURN_2, GeelyTurnId.forManeuver(28))
    }

    @Test
    fun mergedRoundaboutFormsShareTheLeaveIds() {
        assertEquals(GeelyTurnId.LEAVE_ROUNDABOUT, GeelyTurnId.forManeuver(23))
        assertEquals(GeelyTurnId.LEAVE_LEFT_ROUNDABOUT, GeelyTurnId.forManeuver(27))
    }

    @Test
    fun unknownManeuversFallBackToTheNoTurnIcon() {
        assertEquals(GeelyTurnId.UNKNOWN, GeelyTurnId.forManeuver(0))
        assertEquals(GeelyTurnId.UNKNOWN, GeelyTurnId.forManeuver(29))
        assertEquals(GeelyTurnId.UNKNOWN, GeelyTurnId.forManeuver(99))
        assertEquals(GeelyTurnId.UNKNOWN, GeelyTurnId.forManeuver(-1))
    }
}
