package com.shilapi.xcertplay.hud

import android.content.Context
import android.graphics.Bitmap
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
 * <p>Measured on the car (issue #3, question C): `DimInteraction.create(Context)` returns an
 * instance, `getNaviInteraction()` exposes the update surface, and a `Proxy` for
 * `INaviInteraction$INavigationInfo` carrying `PROBE / 450 m / 9999` was rendered by the cluster.
 * Unlike the `DimNaviService` binder, which accepts the same values and renders nothing, this path
 * works.
 *
 * <p>The data comes from [ClusterTurnGuidance], which is fed by iAP2 route guidance
 * (0x5201/0x5202). Pushes repeat once a second because the cluster clears a stale navigation; a
 * stopped route sends `notifyTurnByTurnStopped` so the panel goes back to its own state.
 *
 * <p>Every entry point is gated by [supported], and a JVM unit test never takes this path: the
 * classes are absent there, so [start] returns false.
 */
object GeelyDimNavi {

    private const val TAG = "lynkPlay-DimNavi"
    private const val CLS_DIM = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    private const val CLS_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo"
    private const val PERIOD_MILLIS = 1_000L

    /**
     * Turn id the cluster rendered in the probe. The car's own ids are not mapped yet; this one is
     * known to draw, and the distance/road/ETA carry the actual guidance meanwhile.
     */
    const val TURN_ID = 9999

    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "geely-dim-navi").apply { isDaemon = true }
    }
    private var navi: Any? = null
    private var info: Any? = null
    private var tick: ScheduledFuture<*>? = null

    @Volatile private var latest: ClusterTurnGuidance? = null
    @Volatile private var arrow: Bitmap? = null
    @Volatile private var started = false

    /** True on the one head unit whose DIM interaction API was measured (issue #3). */
    fun supported(): Boolean = GeelyHeadUnit.detected

    /** Starts the one-second push; false when the API is unavailable or the car is not this one. */
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
        tick = executor.scheduleWithFixedDelay(
            { runCatching { push() }.onFailure { Log.w(TAG, "cluster navigation push failed", it) } },
            0,
            PERIOD_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        Log.i(TAG, "cluster navigation area in use")
        return true
    }

    /**
     * The guidance to show, or null when there is no route: the next push clears the area.
     * [arrow] is the turn glyph as a bitmap; without one the cluster's own id is sent.
     */
    fun update(guidance: ClusterTurnGuidance?, arrow: Bitmap? = null) {
        latest = guidance
        this.arrow = arrow
    }

    /** Stops the push and tells the cluster the route ended. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        tick?.cancel(false)
        tick = null
        latest = null
        val ended = navi
        navi = null
        info = null
        executor.execute {
            runCatching { call(ended, "notifyTurnByTurnStopped") }
            runCatching { call(ended, "notifyNavigationStatus", 3) }
            Log.i(TAG, "cluster navigation area released")
        }
    }

    private fun bind(context: Context): Any {
        val infoClass = Class.forName(CLS_INFO, false, GeelyDimNavi::class.java.classLoader)
        val dimClass = Class.forName(CLS_DIM, false, GeelyDimNavi::class.java.classLoader)
        val dim = dimClass.getMethod("create", Context::class.java).invoke(null, context) ?: error("create returned null")
        val navi = dim.javaClass.getMethod("getNaviInteraction").invoke(dim) ?: error("no navi interaction")
        info = Proxy.newProxyInstance(infoClass.classLoader, arrayOf(infoClass), InfoHandler())
        return navi
    }

    private fun push() {
        if (!started) return
        val navi = navi ?: return
        val guidance = latest ?: return
        // The route is live as far as the cluster is concerned once a turn is pushed.
        call(navi, "notifyNavigationStatus", 2)
        call(navi, "updateNavigationInfo", info)
        call(navi, "updateDistanceToNextGuidancePoint", guidance.distanceMeters)
        // The cluster's own getters are ints; the route state keeps these as Long.
        guidance.remainingMeters?.let { call(navi, "updateDistanceToDestination", it.toInt()) }
        guidance.remainingSeconds?.let { call(navi, "updateETA", it.toInt()) }
        if (guidance.road.isNotEmpty()) call(navi, "updateNextGuidancePointName", guidance.road)
        val picture = arrow
        if (picture != null) call(navi, "updateTurnByTurnArrow", picture)
        else call(navi, "updateTurnByTurnArrow", TURN_ID)
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
                "getNavigationTurnId" -> TURN_ID
                "getDistanceToNextGuidancePoint" -> guidance?.distanceMeters ?: 0
                "getDistanceToDestination" -> guidance?.remainingMeters ?: 0
                "getETA" -> guidance?.remainingSeconds ?: 0
                "getNextGuidancePointName" -> guidance?.road.orEmpty()
                "getNavigationStatus" -> if (guidance == null) 3 else 2
                "getDayNightMode" -> 0
                "getExtensionInfo" -> Bundle()
                "toString" -> "GeelyDimNavi"
                "hashCode" -> TURN_ID
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
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
    }
}
