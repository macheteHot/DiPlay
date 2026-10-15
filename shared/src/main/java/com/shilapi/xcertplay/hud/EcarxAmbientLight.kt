package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Drives the Geely / Lynk & Co interior lamps through ECARX's car API.
 *
 * Unlike BYD's lamps, this needs no ADB authorization and no helper process: `ecarx.car.jar` is on the
 * boot classpath, so a normal third-party uid can call it in-process. The wired route is
 * `ECarXCar.createCar(context, connection).connect()` -> `getCarManager("car_publicattribute")` ->
 * `getECarXCarAmbliManager()`, and a zone write then takes a 24-bit RGB (measured on the gated head
 * unit, see [EcarxAmbientLightPolicy]).
 *
 * Every call runs on this client's own executor, so [AmbientLampSession] keeps its serialized
 * lifecycle. The service bind is asynchronous, so [ensureConnected] creates the car object on the
 * main thread (where the vendored class expects to register its connection) and then waits for
 * `isConnected()` on the executor before the first property call.
 */
internal class EcarxAmbientLightClient(context: Context) : AutoCloseable {

    enum class Phase { IDLE, ACTIVE, FAILED, CLOSED }

    private val app = context.applicationContext
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "diplay-ecarx-ambient").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var phase = Phase.IDLE
    @Volatile private var closed = false
    private var car: Any? = null
    private var ambli: Any? = null
    private var restore: EcarxAmbientLightPolicy.Snapshot? = null
    private var readsForDiagnostics = 0

    /** The vendored class binds itself; the connection must stay referenced for the bind to survive. */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "car service connected: ${name?.flattenToShortString()}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "car service disconnected: ${name?.flattenToShortString()}")
            car = null
            ambli = null
        }
    }

    fun phase(): Phase = phase

    /** True when the session must stop trusting this client and start over. */
    fun unhealthy(): Boolean = phase == Phase.FAILED || phase == Phase.CLOSED

    /** Read-only support check: connect and read the zone state without writing anything. */
    fun read(): CompletableFuture<EcarxAmbientLightPolicy.Snapshot> = submit {
        ensureConnected()
        readSnapshot().also { check(it.valid) { "lamp-state-unreadable" } }
    }

    /**
     * Takes the restore snapshot. A seed is the snapshot of an earlier session whose stop was never
     * confirmed: it is kept, because re-reading now would capture DiPlay's own colors as "the car's".
     */
    fun begin(seed: EcarxAmbientLightPolicy.Snapshot? = null): CompletableFuture<EcarxAmbientLightPolicy.Snapshot> = submit {
        check(phase != Phase.ACTIVE) { "session-already-active" }
        ensureConnected()
        val snapshot = seed ?: readSnapshot()
        check(snapshot.valid) { "lamp-state-unreadable" }
        if (seed != null) check(seed.valid) { "invalid-restore-seed" }
        restore = snapshot
        phase = Phase.ACTIVE
        readsForDiagnostics = 0
        snapshot
    }

    /** One music frame. The area is deliberately ignored; see [EcarxAmbientLightPolicy.valuesFor]. */
    fun apply(area: Int, color: Int, brightness: Int): CompletableFuture<Unit> = submit {
        check(phase == Phase.ACTIVE) { "ambient-session-not-active" }
        val manager = manager()
        val values = EcarxAmbientLightPolicy.valuesFor(color, brightness)
        invokeInt(manager, "CB_ZoneAllStatusSettings", ON)
        invokeInt(manager, "CB_ZoneAllColorSettings", values.color)
        invokeInt(manager, "CB_ZoneAllIntensitySettings", values.intensity)
        logReadBack(manager)
    }

    /** Writes the snapshot back. Restoration is never coalesced, matching the BYD session contract. */
    fun stop(): CompletableFuture<Unit> = submit {
        if (phase != Phase.ACTIVE) {
            phase = Phase.IDLE
            return@submit
        }
        val snapshot = restore
        try {
            if (snapshot != null && snapshot.valid) {
                val manager = manager()
                EcarxAmbientLightPolicy.restoreSteps(snapshot).forEach { (index, zone, whole) ->
                    if (whole) writeZone(manager, "ZoneAll", zone, withStatus = true) else {
                        writeZone(manager, "Zone$index", zone, withStatus = true)
                    }
                }
                Log.i(TAG, "lamps restored")
            }
            phase = Phase.IDLE
            restore = null
        } catch (error: Throwable) {
            phase = Phase.FAILED
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        phase = Phase.CLOSED
        runCatching { car?.let { invokeNoArg(it, "disconnect") } }
        runCatching { car?.let { invokeNoArg(it, "disConnect") } }
        car = null
        ambli = null
        executor.shutdown()
    }

    // ---------------------------------------------------------------- connection

    private fun ensureConnected() {
        if (ambli != null) return
        check(!closed) { "client-closed" }
        val created = CompletableFuture<Any?>()
        mainHandler.post {
            // The vendored createCar captures the calling thread's looper for its callbacks.
            created.complete(runCatching { createCar() }.getOrNull())
        }
        val instance = created.get(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            ?: error("ecarx-car-unavailable")
        car = instance
        runCatching { invokeNoArg(instance, "connect") }
        val deadline = SystemClock.uptimeMillis() + CONNECT_TIMEOUT_MILLIS
        while (invokeNoArg(instance, "isConnected") != true) {
            if (SystemClock.uptimeMillis() > deadline) error("ecarx-car-connect-timeout")
            Thread.sleep(CONNECT_POLL_MILLIS)
        }
        val setManager = invoke(instance, "getCarManager", "car_publicattribute")
            ?: error("ecarx-car-manager-unavailable")
        ambli = invokeNoArg(setManager, "getECarXCarAmbliManager")
            ?: error("ecarx-ambient-manager-unavailable")
        Log.i(TAG, "car API ready; ambient manager $ambli")
    }

    private fun createCar(): Any? {
        val cls = load(CLS_ECARX_CAR) ?: return null
        // The static factory takes (Context, ServiceConnection) and binds the connection itself.
        val method = cls.methods.firstOrNull { it.name == "createCar" && it.parameterTypes.size == 2 } ?: return null
        return runCatching { method.invoke(null, app, connection) }.getOrElse {
            Log.w(TAG, "ECarXCar.createCar failed", it)
            null
        }
    }

    private fun manager(): Any = ambli ?: run { ensureConnected(); ambli ?: error("ecarx-ambient-manager-unavailable") }

    // ---------------------------------------------------------------- reads

    private fun readSnapshot(): EcarxAmbientLightPolicy.Snapshot {
        val manager = manager()
        val zones = (1..EcarxAmbientLightPolicy.ZONE_COUNT).map { zone ->
            EcarxAmbientLightPolicy.Zone(
                color = readValue(manager, "getPA_Zone${zone}ColorSettings"),
                intensity = readValue(manager, "getPA_Zone${zone}IntensitySettings"),
                status = readValue(manager, "getPA_Zone${zone}StatusSettings"),
            )
        }
        val all = EcarxAmbientLightPolicy.Zone(
            color = readValue(manager, "getPA_ZoneAllColorSettings"),
            intensity = readValue(manager, "getPA_ZoneAllIntensitySettings"),
            status = readValue(manager, "getPA_ZoneAllStatusSettings"),
        )
        Log.i(TAG, "lamp snapshot all=$all zones=$zones")
        return EcarxAmbientLightPolicy.Snapshot(zones, all)
    }

    /**
     * `getPA_*` answers with the vendor's `PATypes`. Its value is a `dataValue` field; the probe read
     * it out of `toString()`, so both forms are accepted. [UNREADABLE] marks a failed read so an
     * unknown state can never be mistaken for a real zero and written back as one.
     */
    private fun readValue(manager: Any, name: String): Int {
        val result = invokeNoArg(manager, name) ?: return UNREADABLE
        runCatching {
            result.javaClass.getField("dataValue").get(result) as? Int
        }.getOrNull()?.let { return it }
        runCatching {
            result.javaClass.getMethod("getDataValue").invoke(result) as? Int
        }.getOrNull()?.let { return it }
        return EcarxAmbientLightPolicy.parseDataValue(result.toString()) ?: UNREADABLE
    }

    /** A rare read-back so a silently ignored write is visible in a report, without failing the apply. */
    private fun logReadBack(manager: Any) {
        readsForDiagnostics++
        if (readsForDiagnostics % READ_BACK_EVERY != 0) return
        Log.i(TAG, "lamp readback all(color=${readValue(manager, "getPA_ZoneAllColorSettings")} " +
            "intensity=${readValue(manager, "getPA_ZoneAllIntensitySettings")} " +
            "mode=${readValue(manager, "getPA_AmbLiModSetting")})")
    }

    // ---------------------------------------------------------------- writes

    private fun writeZone(manager: Any, zoneName: String, zone: EcarxAmbientLightPolicy.Zone, withStatus: Boolean) {
        if (withStatus) invokeInt(manager, "CB_${zoneName}StatusSettings", zone.status)
        invokeInt(manager, "CB_${zoneName}ColorSettings", zone.color)
        invokeInt(manager, "CB_${zoneName}IntensitySettings", zone.intensity)
    }

    // ---------------------------------------------------------------- reflection

    private fun load(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrElse {
        Log.w(TAG, "class not found: $name", it)
        null
    }

    /**
     * Matches by name, argument count and an assignable parameter type: the vendored managers expose
     * overloads, and picking the wrong one would silently call a different property.
     */
    private fun findMethod(cls: Class<*>, name: String, parameter: Class<*>?): Method? {
        val candidates = cls.methods.filter { it.name == name }
        if (parameter == null) return candidates.firstOrNull { it.parameterTypes.isEmpty() }
        return candidates.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(parameter) }
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = findMethod(target.javaClass, name, null) ?: return null
        return runCatching { method.invoke(target) }.getOrElse {
            Log.w(TAG, "${target.javaClass.simpleName}.$name() failed", it)
            null
        }
    }

    private fun invoke(target: Any, name: String, argument: Any): Any? {
        val method = findMethod(target.javaClass, name, argument.javaClass) ?: return null
        return runCatching { method.invoke(target, argument) }.getOrElse {
            Log.w(TAG, "${target.javaClass.simpleName}.$name($argument) failed", it)
            null
        }
    }

    private fun invokeInt(target: Any, name: String, value: Int): Any? {
        // An int property is declared as the primitive; a boxed parameter also accepts it.
        val method = findMethod(target.javaClass, name, Int::class.javaPrimitiveType)
            ?: findMethod(target.javaClass, name, Integer::class.java)
            ?: run {
                Log.w(TAG, "${target.javaClass.simpleName}.$name(int) not found")
                return null
            }
        return runCatching { method.invoke(target, value) }.getOrElse {
            Log.w(TAG, "${target.javaClass.simpleName}.$name($value) failed", it)
            null
        }
    }

    /** Runs on the client's own executor; the session only ever has one call in flight. */
    private fun <T> submit(block: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (closed) {
            future.completeExceptionally(IllegalStateException("client-closed"))
            return future
        }
        try {
            executor.execute {
                try {
                    future.complete(block())
                } catch (error: Throwable) {
                    if (phase == Phase.ACTIVE) phase = Phase.FAILED
                    Log.w(TAG, "lamp call failed", error)
                    future.completeExceptionally(error)
                }
            }
        } catch (error: Throwable) {
            // Closed between the check above and the submit; the session treats this as a failed call.
            future.completeExceptionally(error)
        }
        return future
    }

    private companion object {
        const val TAG = "DiPlay-Ambient"
        const val CLS_ECARX_CAR = "ecarx.car.ECarXCar"
        const val ON = 1
        const val CONNECT_TIMEOUT_MILLIS = 6_000L
        const val CONNECT_POLL_MILLIS = 100L
        const val READ_BACK_EVERY = 20
        const val UNREADABLE = Int.MIN_VALUE
    }
}
