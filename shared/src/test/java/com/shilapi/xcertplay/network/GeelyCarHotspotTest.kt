package com.shilapi.xcertplay.network

import android.os.Build
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GeelyCarHotspotTest {

    /**
     * The vendor reader is gated to the reverse-engineered unit. Every other device (and every
     * unit test host) must keep the standard [CarHotspotStatus] path, so the gate must return null
     * before any vendor call is attempted.
     */
    @Test
    fun nonTargetDeviceKeepsTheStandardHotspotPath() {
        assertNotEquals("msmnile_gvmq", Build.DEVICE)
        assertNull(GeelyCarHotspot.read(RuntimeEnvironment.getApplication()))
    }
}
