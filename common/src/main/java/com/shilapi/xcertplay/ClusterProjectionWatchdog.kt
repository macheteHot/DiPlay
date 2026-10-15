package com.shilapi.xcertplay

/**
 * Decides when DiPlay has to ask for the Geely cluster projection again.
 *
 * The DIM projection switch is one head-unit-wide state that DiPlay and the stock map share. Waking the
 * map with the steering-wheel key moves the projection to the map; when that session ends the head unit
 * turns the switch off, and DiPlay's `Presentation` never stops, so `onStart` never asserts it again and
 * the cluster stays dark (measured: `DimNaviService: notifyDimProjectScreenSwitchFun turnId(false)`, with
 * no callback reaching DiPlay - see `carDocs/吉利仪表与HUD接入调研.md`).
 *
 * The watchdog never acts while the projection reads on: that is the stock map legitimately owning the
 * cluster, and re-asserting then would fight it. A single off reading is not enough either, because the
 * map passes through off while it takes the switch over, so [FALSE_READINGS] consecutive off readings
 * are required before DiPlay claims the cluster back.
 */
internal class ClusterProjectionWatchdog(
    private val falseReadings: Int = FALSE_READINGS,
) {
    private var consecutiveOff = 0

    /** Feeds one reading of the projection state; true when DiPlay must ask for the projection again. */
    fun onStatus(projecting: Boolean?): Boolean {
        // An unreadable state is not evidence of anything, and it clears partial evidence.
        if (projecting != false) {
            consecutiveOff = 0
            return false
        }
        consecutiveOff++
        if (consecutiveOff < falseReadings) return false
        // Start over, so a re-assert that did not take effect is not retried on every reading.
        consecutiveOff = 0
        return true
    }

    fun reset() {
        consecutiveOff = 0
    }

    companion object {
        /** Two readings, so a map switching through off does not hand the cluster back mid-switch. */
        const val FALSE_READINGS = 2
    }
}
