package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane

/**
 * Maps an iAP2 lane to the cluster's own lane icon id, from
 * `carDocs/吉利导航车道图标ID-laneIconId表.md`.
 *
 * <p>Angles are signed degrees with 0 straight, positive right and negative left (measured on the
 * car: a -90 lane drew the left arrow). A lane can carry more than one angle: Apple Maps sent
 * `[-90, 0]` for a lane that merges left and also continues straight. The cluster has one icon per
 * lane, and its left-ahead / right-ahead arrows are the shapes that combine the two directions.
 *
 * <p>Fields 2 and 3 have two shapes in the captures:
 * - `angles` non-empty: those are the lane's arrows; `highlightAngle` is the highlighted path of
 *   the recommended lane and must not add an arrow (a straight lane with highlight 90 stays
 *   straight).
 * - `angles` empty: `highlightAngle` is the lane's single arrow. Apple Maps sent recommended left
 *   lanes as `Angles=[] highlight=-90`, and those are plain left arrows, not left-ahead arrows.
 *
 * <p>The status field marks the recommended lane with 2; its icon is the plain one + 15.
 */
object GeelyLaneIcon {

    /** The icon id for one lane, highlighted when the car recommends it. */
    fun forLane(lane: LaneGuidanceLane): Int {
        val base = if (lane.angles.isNotEmpty()) {
            baseFor(lane.angles)
        } else {
            baseForAngle(lane.highlightAngle ?: 0)
        }
        return if (lane.recommended) base + HIGHLIGHT_OFFSET else base
    }

    /**
     * The plain icon for a lane's arrows.
     *
     * <p>An empty list is straight. One angle keeps the single-arrow mapping. Several angles pick
     * the compound arrow for the set, for example `[-90, 0]` -> left-ahead and `[0, 90]` ->
     * right-ahead.
     */
    fun baseFor(angles: List<Int>): Int {
        val distinct = angles.distinct()
        if (distinct.isEmpty()) return STRAIGHT
        if (distinct.size == 1) return baseForAngle(distinct.first())

        val hasStraight = distinct.any { it in -22..22 }
        val hasLeft = distinct.any { it < -22 && it > -135 }
        val hasRight = distinct.any { it > 22 && it < 135 }
        val hasLeftUTurn = distinct.any { it <= -135 }
        val hasRightUTurn = distinct.any { it >= 135 }

        return when {
            hasLeft && hasRight && hasStraight -> AHEAD_LEFT_RIGHT
            hasLeft && hasRight -> LEFT_RIGHT
            hasStraight && hasLeftUTurn -> AHEAD_LEFT_UTURN
            hasStraight && hasRightUTurn -> AHEAD_RIGHT_UTURN
            hasStraight && hasLeft -> LEFT_AHEAD
            hasStraight && hasRight -> RIGHT_AHEAD
            hasLeft && (hasLeftUTurn || hasRightUTurn) -> LEFT_LEFT_UTURN
            hasRight && (hasLeftUTurn || hasRightUTurn) -> RIGHT_RIGHT_UTURN
            hasLeft -> LEFT
            hasRight -> RIGHT
            else -> U_TURN
        }
    }

    private fun baseForAngle(angle: Int): Int = when {
        angle in -22..22 -> STRAIGHT
        angle in 23..67 -> RIGHT_AHEAD
        angle in 68..112 -> RIGHT
        angle in -67..-23 -> LEFT_AHEAD
        angle in -112..-68 -> LEFT
        else -> U_TURN
    }

    const val HIGHLIGHT_OFFSET = 15

    // Plain ids; the highlighted versions are these + HIGHLIGHT_OFFSET.
    const val STRAIGHT = 0
    const val LEFT = 1
    const val LEFT_AHEAD = 2
    const val RIGHT = 3
    const val RIGHT_AHEAD = 4
    const val U_TURN = 5
    const val LEFT_RIGHT = 6
    const val AHEAD_LEFT_RIGHT = 7
    const val AHEAD_LEFT_UTURN = 9
    const val AHEAD_RIGHT_UTURN = 10
    const val LEFT_LEFT_UTURN = 11
    const val RIGHT_RIGHT_UTURN = 12
}
