package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterProjectionWatchdogTest {
    private val watchdog = ClusterProjectionWatchdog()

    @Test fun aProjectionThatStaysOnIsNeverTouched() {
        // The stock map owns the switch while it shows on the cluster; DiPlay must not fight it.
        repeat(10) { assertFalse(watchdog.onStatus(true)) }
    }

    @Test fun oneOffReadingIsNotEnoughBecauseTheStockMapSwitchesThroughIt() {
        assertFalse(watchdog.onStatus(false))
        assertFalse(watchdog.onStatus(true))
        assertFalse(watchdog.onStatus(false))
    }

    @Test fun twoOffReadingsInARowAskForTheProjectionAgain() {
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
        // The count restarts, so a re-assert that did not take is not retried on every reading.
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
    }

    @Test fun anUnreadableStatusIsNotEvidenceAndNeverActs() {
        repeat(5) { assertFalse(watchdog.onStatus(null)) }
        assertFalse(watchdog.onStatus(false))
        assertFalse(watchdog.onStatus(null))
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
    }

    @Test fun anOnReadingClearsPartialEvidence() {
        assertFalse(watchdog.onStatus(false))
        assertFalse(watchdog.onStatus(true))
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
    }

    @Test fun resetDropsPartialEvidence() {
        assertFalse(watchdog.onStatus(false))
        watchdog.reset()
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
    }
}
