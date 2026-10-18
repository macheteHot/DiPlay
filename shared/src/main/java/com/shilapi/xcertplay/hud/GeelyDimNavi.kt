package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.shilapi.xcertplay.network.GeelyHeadUnit
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pushes CarPlay's turn-by-turn guidance into the cluster's own navigation area through the XUI DIM
 * interaction API, so the instrument panel keeps its factory layout instead of showing DiPlay's
 * mirrored map.
 *
 * <p>Measured on the car (issue #3): `DimInteraction.create(Context)` returns an instance,
 * `getNaviInteraction()` exposes the update surface, and a `Proxy` for
 * `INaviInteraction$INavigationInfo` carrying `PROBE / 450 m / turn id` was rendered by the cluster.
 * Unlike the `DimNaviService` binder, which accepts the same values and renders nothing, this path
 * works. The turn ids are the car's own 100..148 vocabulary ([GeelyTurnId]); the probe's 1..8 are
 * not drawn.
 *
 * <p>Which calls the cluster needs: `notifyNavigationStatus(2)` and `notifyTurnByTurnStarted` once
 * per route, then `updateNavigationInfo` plus the individual fields about once a second. Ending the
 * route sends `notifyTurnByTurnStopped` and `notifyNavigationStatus(3)` once and stops the tick, so
 * the panel does not keep the last guidance (issue #5).
 *
 * <p>Every entry point is gated by [supported], and a JVM unit test never takes this path: the
 * classes are absent there, so [start] returns false.
 */
object GeelyDimNavi {

    private const val TAG = "lynkPlay-DimNavi"
    private const val CLS_DIM = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    private const val CLS_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo"
    private const val CLS_LANE = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$ILaneInfo"
    private const val PERIOD_MILLIS = 1_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "geely-dim-navi").apply { isDaemon = true }
    }
    private val lock = Any()
    private var navi: Any? = null
    private var info: Any? = null
    private var laneClass: Class<*>? = null
    private var lastLaneLogMillis = 0L
    private var tick: ScheduledFuture<*>? = null

    @Volatile private var latest: ClusterTurnGuidance? = null
    @Volatile private var lanes: List<com.shilapi.xcertplay.iap2.message.LaneGuidanceLane> = emptyList()
    @Volatile private var started = false

    /** True on the one head unit whose DIM interaction API was measured (issue #3). */
    fun supported(): Boolean = GeelyHeadUnit.detected

    /** Starts the DIM session; false when the API is unavailable or the car is not this one. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (started) return true
        if (!supported()) return false
        val created = runCatching { bind(context.applicationContext) }
            .onFailure { Log.w(TAG, "DIM interaction unavailable", it) }
            .getOrNull()
            ?: return false
        navi = created
        started = true
        if (latest != null) beginRoute()
        Log.i(TAG, "cluster navigation area in use")
        return true
    }

    /**
     * The guidance to show. A null guidance ends the route: the cluster is told to stop and the
     * one-second push stops with it, so nothing is left on the panel. A new guidance starts it
     * again on the same DIM session.
     */
    fun update(guidance: ClusterTurnGuidance?) {
        latest = guidance
        if (!started) return
        if (guidance == null) endRoute() else beginRoute()
    }

    /** The lane guidance to draw under the turn, from iAP2 0x5204; empty clears the lane area. */
    fun updateLanes(lanes: List<com.shilapi.xcertplay.iap2.message.LaneGuidanceLane>) {
        this.lanes = lanes
    }

    /** Ends the route and releases the DIM session. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        endRoute()
        navi = null
        info = null
        laneClass = null
        lanes = emptyList()
        Log.i(TAG, "cluster navigation area released")
    }

    private fun beginRoute() {
        synchronized(lock) {
            if (tick != null) return
            val navi = navi ?: return
            // Once per route: the cluster treats a turn-by-turn session as started here.
            executor.execute {
                runCatching { call(navi, "notifyNavigationStatus", 2) }
                runCatching { call(navi, "notifyTurnByTurnStarted") }
            }
            tick = executor.scheduleWithFixedDelay(
                { runCatching { push() }.onFailure { Log.w(TAG, "cluster navigation push failed", it) } },
                0,
                PERIOD_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun endRoute() {
        val ended: Any?
        synchronized(lock) {
            tick?.cancel(false)
            tick = null
            ended = navi
        }
        if (ended == null) return
        executor.execute {
            runCatching { call(ended, "notifyTurnByTurnStopped") }
            runCatching { call(ended, "notifyNavigationStatus", 3) }
            Log.i(TAG, "cluster navigation area stopped")
        }
    }

    private fun bind(context: Context): Any {
        val infoClass = Class.forName(CLS_INFO, false, GeelyDimNavi::class.java.classLoader)
        val dimClass = Class.forName(CLS_DIM, false, GeelyDimNavi::class.java.classLoader)
        val dim = dimClass.getMethod("create", Context::class.java).invoke(null, context) ?: error("create returned null")
        val navi = dim.javaClass.getMethod("getNaviInteraction").invoke(dim) ?: error("no navi interaction")
        info = Proxy.newProxyInstance(infoClass.classLoader, arrayOf(infoClass), InfoHandler())
        laneClass = Class.forName(CLS_LANE, false, GeelyDimNavi::class.java.classLoader)
        return navi
    }

    private fun push() {
        if (!started) return
        val navi = navi ?: return
        val guidance = latest ?: return
        call(navi, "updateNavigationInfo", info)
        call(navi, "updateDistanceToNextGuidancePoint", guidance.distanceMeters)
        // The cluster's own getters are ints; the route state keeps these as Long.
        guidance.remainingMeters?.let { call(navi, "updateDistanceToDestination", it.toInt()) }
        guidance.remainingSeconds?.let { call(navi, "updateETA", it.toInt()) }
        if (guidance.road.isNotEmpty()) call(navi, "updateNextGuidancePointName", guidance.road)
        call(navi, "updateTurnByTurnArrow", GeelyTurnId.forManeuver(guidance.icon, guidance.roundaboutExit))
        pushLanes(navi)
        arrivalLabel(guidance)?.let { arrival ->
            val extension = Bundle().apply {
                putString("KEY_NAVI_INFO_ESTIMATED_ARRIVAL_TIME", arrival)
            }
            call(navi, "updateExtensionInfo", extension)
        }
    }

    /**
     * The arrival as `HH:mm`, which is what the DIM side reads from
     * `KEY_NAVI_INFO_ESTIMATED_ARRIVAL_TIME`; `updateETA` only carries the remaining seconds and the
     * cluster shows 00:00 in its arrival field without this.
     */
    private fun arrivalLabel(guidance: ClusterTurnGuidance): String? {
        val epochSeconds = guidance.arrivalEpochSeconds
            ?: guidance.remainingSeconds?.let { System.currentTimeMillis() / 1000 + it }
            ?: return null
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }
        return String.format(
            java.util.Locale.CHINA,
            "%02d:%02d",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
        )
    }

    /** The lane strip as the car's own `ILaneInfo[]`; nothing is sent when there are no lanes. */
    private fun pushLanes(navi: Any) {
        val current = lanes
        if (current.isEmpty()) return
        val type = laneClass ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLaneLogMillis >= 2_000) {
            lastLaneLogMillis = now
            Log.i(TAG, "lanes " + current.joinToString(" | ") {
                "i=${it.index} st=${it.status} a=${it.angle} h=${it.highlightAngle} id=${GeelyLaneIcon.forLane(it)}"
            })
        }
        val array = java.lang.reflect.Array.newInstance(type, current.size)
        current.forEachIndexed { position, lane ->
            val icon = GeelyLaneIcon.forLane(lane)
            val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "getLaneNumber" -> lane.index + 1
                    "getLaneBackIconId" -> icon
                    "getLaneBackIcon" -> null
                    "toString" -> "Lane(${lane.index},$icon)"
                    "hashCode" -> lane.index
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            }
            java.lang.reflect.Array.set(array, position, proxy)
        }
        call(navi, "updateLaneInfo", array)
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private fun call(target: Any?, name: String, vararg arguments: Any?): Any? {
        if (target == null) return null
        // Several overloads share a name (updateTurnByTurnArrow takes int, Bitmap or String), so the
        // argument types have to pick the method, not just the argument count.
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterCount == arguments.size &&
                candidate.parameterTypes.indices.all { index ->
                    accepts(candidate.parameterTypes[index], arguments[index])
                }
        } ?: return null
        return method.invoke(target, *arguments)
    }

    private fun accepts(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        return when (type) {
            Int::class.javaPrimitiveType -> value is Int
            Long::class.javaPrimitiveType -> value is Long
            Boolean::class.javaPrimitiveType -> value is Boolean
            Short::class.javaPrimitiveType -> value is Short
            Byte::class.javaPrimitiveType -> value is Byte
            Float::class.javaPrimitiveType -> value is Float
            Double::class.javaPrimitiveType -> value is Double
            else -> type.isInstance(value)
        }
    }

    /** Answers the 13 `INavigationInfo` getters from the latest guidance. */
    private class InfoHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val guidance = latest
            return when (method.name) {
                "getNavigationTurnId" -> guidance?.let {
                    GeelyTurnId.forManeuver(it.icon, it.roundaboutExit)
                } ?: GeelyTurnId.UNKNOWN
                "getDistanceToNextGuidancePoint" -> guidance?.distanceMeters ?: 0
                "getDistanceToDestination" -> guidance?.remainingMeters?.toInt() ?: 0
                "getETA" -> guidance?.remainingSeconds?.toInt() ?: 0
                "getNextGuidancePointName" -> guidance?.road.orEmpty()
                "getNavigationStatus" -> if (guidance == null) 3 else 2
                "getDayNightMode" -> 0
                "getExtensionInfo" -> Bundle()
                "toString" -> "GeelyDimNavi"
                "hashCode" -> GeelyTurnId.UNKNOWN
                "equals" -> proxy === args?.firstOrNull()
                else -> GeelyDimNavi.defaultValue(method.returnType)
            }
        }
    }
}
