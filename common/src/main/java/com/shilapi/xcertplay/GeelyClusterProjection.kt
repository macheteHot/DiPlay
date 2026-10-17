package com.shilapi.xcertplay

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Turns the instrument panel's projection into DiPlay's display.
 *
 * <p>On `geely_fx11` the cluster keeps its own layer on top of Android's display 2 until the
 * head unit is told to project to it. The stock map does this through the DIM service
 * (`com.autolink.diminteraction`); a normal app can call the same binder directly: the
 * `DimNaviService` binder, `notifyDimProjectScreenSwitch` (transaction 9, an int). Without it a
 * `Presentation` on the cluster display stays invisible behind the factory dials.
 *
 * <p>Only the Geely device takes this route; everywhere else the cluster projection is driven by
 * the car's own cluster support. The switch is head-unit-wide and shared with the stock map, so the
 * state is read back as well ([status]) and re-asserted when the map hands it back.
 */
internal object GeelyClusterProjection {

    private const val TAG = "lynkPlay-Cluster"
    private const val SERVICE = "DimNaviService"
    private const val DESCRIPTOR = "ecarx.dimprotocol.custom.service.IDimNaviService"
    private const val NOTIFY_PROJECT_SCREEN_SWITCH = 9
    private const val GET_PROJECT_SCREEN_STATUS = 10

    /** Whether this process asked for the projection and has not handed it back. */
    @Volatile private var heldInProcess = false

    /**
     * Reads the head unit's projection state, or null when the answer cannot be obtained (service not
     * registered, transaction refused). The state is head-unit-wide: the stock map drives the same
     * switch, so an off reading while DiPlay's presentation is up means the map took it away and the
     * cluster is dark (see [ClusterProjectionWatchdog]).
     */
    fun status(): Boolean? {
        val binder = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(GET_PROJECT_SCREEN_STATUS, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (error: Throwable) {
            // Polled, so a failure is logged but never treated as "off": the watchdog acts on evidence.
            Log.w(TAG, "dim projection: getDimProjectScreenStatus() failed", error)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Asks the head unit to show (or stop showing) the projected cluster. Returns whether the
     * call reached the service; a missing binder is not an error worth failing over.
     *
     * When [context] is given, the claim is also written to disk while the switch is on: the process
     * can be killed without running `onStop`, and the next run hands the switch back then
     * ([releaseStale]).
     */
    fun set(on: Boolean, context: Context? = null): Boolean {
        val binder = service() ?: run {
            Log.i(TAG, "dim projection: DimNaviService is not registered")
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (on) 1 else 0)
            binder.transact(NOTIFY_PROJECT_SCREEN_SWITCH, data, reply, 0)
            reply.readException()
            Log.i(TAG, "dim projection: notifyDimProjectScreenSwitch($on) ok")
            if (context != null) AirPlayPersistence.saveClusterProjectionHeld(context, on)
            heldInProcess = on
            true
        } catch (error: Throwable) {
            Log.w(TAG, "dim projection: notifyDimProjectScreenSwitch($on) failed", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Hands back a projection a previous process left on, so a kill that skipped `onStop` does not
     * leave the cluster showing DiPlay's last frame. Only acts when this process has not asked for
     * the projection itself, and clears the claim without touching the switch when it reads off.
     */
    fun releaseStale(context: Context) {
        if (heldInProcess) return
        if (!AirPlayPersistence.loadClusterProjectionHeld(context)) return
        if (!runCatching { GeelyClusterDisplay.supported() }.getOrDefault(false)) {
            // Another head unit restored settings that carry this claim: the switch is not ours there.
            AirPlayPersistence.saveClusterProjectionHeld(context, false)
            return
        }
        when (status()) {
            true -> {
                Log.i(TAG, "dim projection: handing back a projection left on by a previous run")
                set(false, context)
            }
            // The switch is already off, or the car is not reachable this time; an unreadable
            // answer keeps the claim so the next run tries again.
            false -> AirPlayPersistence.saveClusterProjectionHeld(context, false)
            null -> Unit
        }
    }

    /** {@code android.os.ServiceManager.getService(String)} is a hidden API. */
    private fun service(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        serviceManager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
    } catch (error: Throwable) {
        Log.w(TAG, "dim projection: ServiceManager.getService($SERVICE) failed", error)
        null
    }
}
