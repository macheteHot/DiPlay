package com.shilapi.xcertplay.hud

/**
 * The cluster's own turn icon ids (`turnId`), restored from the car's shipped packages — see
 * `carDocs/吉利导航转向ID-turnId表.md`.
 *
 * <p>The stock chain is `GeelyMap newIcon -> OneOS-NaviService TbtIconMapping -> turnId 100..148`,
 * and the DIM side passes that number through unchanged. The probe's `1..8` are not part of this
 * vocabulary; the cluster only draws the ids below.
 *
 * <p>[forManeuver] takes the same AMap `NEW_ICON` vocabulary as [ClusterTurnGuidance.icon], so the
 * guidance the turn card already draws is all it needs.
 */
object GeelyTurnId {
    const val UNKNOWN = 100
    const val SELF = 101
    const val LEFT = 102
    const val RIGHT = 103
    const val SLIGHT_LEFT = 104
    const val SLIGHT_RIGHT = 105
    const val HARD_LEFT = 106
    const val HARD_RIGHT = 107
    const val U_TURN = 108
    const val CONTINUE = 109
    const val WAYPOINT = 110
    const val ENTER_ROUNDABOUT = 111
    const val LEAVE_ROUNDABOUT = 112
    const val SERVICE_AREA = 113
    const val TOLL_GATE = 114
    const val DESTINATION = 115
    const val TUNNEL = 116
    const val ENTER_LEFT_ROUNDABOUT = 117
    const val LEAVE_LEFT_ROUNDABOUT = 118
    const val U_TURN_BUBBLE = 119
    const val CONTINUE_BUBBLE = 120
    const val ROUNDABOUT_LEFT_BUBBLE = 141
    const val ROUNDABOUT_RIGHT_BUBBLE = 142
    const val ROUNDABOUT_UTURN_BUBBLE = 143
    const val ROUNDABOUT_LEFT_2 = 144
    const val ROUNDABOUT_RIGHT_2 = 145
    const val ROUNDABOUT_UTURN_2 = 146
    const val KEEP_LEFT = 147
    const val KEEP_RIGHT = 148

    /** The `exit`-th exit of a roundabout (1..10), or the plain leave icon when it is not valid. */
    fun roundaboutExit(exit: Int): Int = if (exit in 1..10) 120 + exit else LEAVE_ROUNDABOUT

    /** The `exit`-th exit of a left roundabout (1..10), or the plain leave icon. */
    fun leftRoundaboutExit(exit: Int): Int =
        if (exit in 1..10) 130 + exit else LEAVE_LEFT_ROUNDABOUT

    /**
     * The AMap `NEW_ICON` (0..28, 65..66) the guidance carries, as the car's turn id. Unknown
     * values fall back to [UNKNOWN], which the cluster draws as its own "no turn" icon.
     */
    fun forManeuver(icon: Int, roundaboutExit: Int = 0): Int = when (icon) {
        0 -> UNKNOWN
        1 -> SELF
        2 -> LEFT
        3 -> RIGHT
        4 -> SLIGHT_LEFT
        5 -> SLIGHT_RIGHT
        6 -> HARD_LEFT
        7 -> HARD_RIGHT
        8 -> U_TURN
        9 -> CONTINUE
        10 -> WAYPOINT
        11 -> ENTER_ROUNDABOUT
        12 -> roundaboutExit(roundaboutExit)
        13 -> SERVICE_AREA
        14 -> TOLL_GATE
        15 -> DESTINATION
        16 -> TUNNEL
        17 -> ENTER_LEFT_ROUNDABOUT
        18 -> leftRoundaboutExit(roundaboutExit)
        19 -> U_TURN_BUBBLE
        20 -> CONTINUE_BUBBLE
        21 -> ROUNDABOUT_LEFT_BUBBLE
        22 -> ROUNDABOUT_RIGHT_BUBBLE
        23 -> LEAVE_ROUNDABOUT
        24 -> ROUNDABOUT_UTURN_BUBBLE
        25 -> ROUNDABOUT_LEFT_2
        26 -> ROUNDABOUT_RIGHT_2
        27 -> LEAVE_LEFT_ROUNDABOUT
        28 -> ROUNDABOUT_UTURN_2
        65 -> KEEP_LEFT
        66 -> KEEP_RIGHT
        else -> UNKNOWN
    }
}
