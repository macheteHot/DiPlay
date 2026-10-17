package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane

/**
 * Maps an iAP2 lane to the cluster's own lane icon id, from
 * `carDocs/吉利导航车道图标ID-laneIconId表.md`.
 *
 * <p>Angles are signed degrees with 0 straight, positive right and negative left (measured on the
 * car: a -90 lane drew the left arrow, and a 0 lane with a +90 highlight drew straight+right).
 * Ids 0..14 are the plain set and 15..29 the same arrows highlighted; the car marks the lane it
 * recommends with status 2, and the highlighted id is the plain one + 15.
 */
object GeelyLaneIcon {

    /** The icon id for one lane, highlighted when the car recommends it. */
    fun forLane(lane: LaneGuidanceLane): Int {
        val base = baseFor(lane.angle, lane.highlightAngle)
        return if (lane.recommended) base + HIGHLIGHT_OFFSET else base
    }

    /**
     * The plain icon for a lane's own angle, or the \"this lane + the next turn\" form when the lane
     * carries a highlight angle while pointing straight ahead.
     */
    fun baseFor(angle: Int, highlightAngle: Int?): Int {
        if (highlightAngle != null && angle in -22..22) {
            return when {
                highlightAngle > 22 -> RIGHT_AHEAD
                highlightAngle < -22 -> LEFT_AHEAD
                else -> STRAIGHT
            }
        }
        return when {
            angle in -22..22 -> STRAIGHT
            angle in 23..67 -> RIGHT_AHEAD
            angle in 68..112 -> RIGHT
            angle in -67..-23 -> LEFT_AHEAD
            angle in -112..-68 -> LEFT
            else -> U_TURN
        }
    }

    const val HIGHLIGHT_OFFSET = 15

    // Plain ids; the highlighted versions are these + HIGHLIGHT_OFFSET.
    const val STRAIGHT = 0
    const val LEFT = 1
    const val LEFT_AHEAD = 2
    const val RIGHT = 3
    const val RIGHT_AHEAD = 4
    const val U_TURN = 5
}
