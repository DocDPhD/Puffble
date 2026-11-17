package dev.proxywatch.companion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import dev.proxywatch.companion.lorax.OperatingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Finds the Proxy among the phone's bonded devices and remembers the user's choice. */
@SuppressLint("MissingPermission")
object ProxyDevices {
    private const val PREFS = "proxy"
    private const val KEY_ADDRESS = "address"

    fun bonded(context: Context): List<BluetoothDevice> =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }?.bondedDevices?.toList().orEmpty()

    fun likely(context: Context): List<BluetoothDevice> = bonded(context).filter { d ->
        val n = d.name.orEmpty().lowercase()
        "proxy" in n || "puffco" in n
    }

    fun save(context: Context, device: BluetoothDevice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, device.address).apply()
    }

    /** The saved device if still bonded, else the only bonded device that looks like a Proxy. */
    fun resolve(context: Context): BluetoothDevice? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDRESS, null)
        return bonded(context).firstOrNull { it.address == saved } ?: likely(context).singleOrNull()
    }
}

/**
 * Times the active phase of a heat cycle from the phone's clock, since the unit of the
 * Proxy's own elapsed counter is not established. Pure logic, unit tested.
 */
class CycleTimer {
    private var lastState: Int? = null
    private var activeSinceMs: Long? = null

    /** Feed every poll. Returns seconds left in the active phase, or null if unknown. */
    fun update(state: Int, durationS: Int, nowMs: Long): Int? {
        val prev = lastState
        lastState = state
        if (state != OperatingState.ACTIVE) {
            activeSinceMs = null
            return null
        }
        // Only time a phase we saw begin; joining mid-cycle leaves the countdown unknown.
        if (prev != null && prev != OperatingState.ACTIVE) activeSinceMs = nowMs
        val since = activeSinceMs ?: return null
        return (durationS - ((nowMs - since) / 1000).toInt()).coerceAtLeast(0)
    }

    fun reset() {
        lastState = null
        activeSinceMs = null
    }
}

/**
 * Decides when a heat session finished on its own (as opposed to being stopped).
 *
 * The Proxy tested goes straight from active (8) to idle (5) at the end of a session; it never
 * reports fade (9). So a session counts as finished when it leaves active either into fade, or
 * with the countdown within [GRACE_S] of zero, or, when the countdown is unknown (the phone
 * joined mid-session), when nobody stopped it from Puffble. Pure logic, unit tested.
 */
class SessionEndDetector {
    private var prev: Int? = null
    private var lastRemain: Int? = null

    fun update(state: Int, remainS: Int?, abortedRecently: Boolean): Boolean {
        val wasActive = prev == OperatingState.ACTIVE
        val known = lastRemain
        prev = state
        lastRemain = if (state == OperatingState.ACTIVE) remainS else null
        if (!wasActive || state == OperatingState.ACTIVE) return false
        return when {
            state == OperatingState.FADE -> true
            known != null -> known <= GRACE_S
            else -> !abortedRecently
        }
    }

    private companion object {
        const val GRACE_S = 8  // poll interval plus clock drift between phone and Proxy
    }
}

/**
 * The single shared connection to the Proxy. The activity and the watch link both
 * "hold" it; it connects when the first holder arrives and disconnects when the last leaves.
 */
object ProxySession {
    sealed interface Link {
        data object Idle : Link
        data object Connecting : Link
        data object Ready : Link
        data class Error(val message: String) : Link
    }

    data class Snapshot(
        val link: Link = Link.Idle,
        val live: LiveState? = null,
        val profiles: List<ProfileInfo> = emptyList(),
        val batterySoc: Long? = null,
        /** Seconds left in the active phase, or null if unknown / not active. */
        val remainS: Int? = null,
    ) {
        val currentProfile: ProfileInfo? get() = live?.let { profiles.getOrNull(it.profile) }
    }

    private const val TAG = "ProxySession"

    // Shown on the watch while searching; written for someone looking at their wrist.
    const val NEEDS_PERMISSION = "Open the Puffble companion app and allow Nearby Devices Permissions"
    const val NEEDS_DEVICE = "Open the Puffble companion app and choose your Proxy"
    private const val LINGER_MS = 120_000L
    private val HEATING_STATES = setOf(OperatingState.PREHEAT, OperatingState.ACTIVE, OperatingState.FADE)
    private const val DONE_SHOW_MS = 6_000L
    private const val ABORT_WINDOW_MS = 5_000L
    private const val POLL_MS = 1_000L

    @Volatile private var deviceState: Int? = null
    @Volatile private var lastAbortAt = Long.MIN_VALUE / 2
    private const val BATTERY_EVERY_MS = 30_000L
    private const val PROFILES_EVERY_MS = 60_000L

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    /** A heat cycle the phone watched run to completion (active → fade). */
    data class SessionDone(val profileName: String, val targetDeciC: Long, val durationTicks: Long, val endedAtMs: Long)

    private val _sessions = MutableSharedFlow<SessionDone>(extraBufferCapacity = 4)
    val sessions: SharedFlow<SessionDone> = _sessions

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val holders = mutableSetOf<String>()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val timer = CycleTimer()
    private var client: ProxyClient? = null
    private var job: Job? = null

    private var lingerJob: Job? = null

    fun acquire(context: Context, holder: String) {
        val app = context.applicationContext
        scope.launch {
            lingerJob?.cancel()
            lingerJob = null
            holders += holder
            if (job?.isActive != true) job = scope.launch { run(app) }
        }
    }

    /**
     * Dropping the last holder does not disconnect at once: the link stays up through any
     * heat cycle and for [LINGER_MS] after, so reopening the watch app mid-cycle is instant
     * and the countdown stays known. A cold reconnect right after a disconnect is what
     * Android's Bluetooth stack most often refuses.
     */
    fun release(holder: String) {
        scope.launch {
            holders -= holder
            if (holders.isNotEmpty() || job == null) return@launch
            lingerJob?.cancel()
            lingerJob = scope.launch {
                while (_snapshot.value.live?.operatingState in HEATING_STATES) delay(5_000)
                delay(LINGER_MS)
                if (holders.isEmpty()) disconnectNow()
            }
        }
    }

    /** Drops the current link (e.g. after the user picks a different Proxy) and reconnects if anyone holds it. */
    fun restart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lingerJob?.cancel()
            lingerJob = null
            disconnectNow()
            if (holders.isNotEmpty()) job = scope.launch { run(app) }
        }
    }

    private fun disconnectNow() {
        job?.cancel()
        job = null
        client?.close()
        client = null
        timer.reset()
        _snapshot.value = Snapshot()
    }

    suspend fun selectProfile(index: Int) {
        ready().selectProfile(index)
        kick.trySend(Unit)
    }

    /** Starts the heater. Callers must already have the user's explicit confirmation. */
    suspend fun startHeatCycle() {
        val c = ready()
        // The real device state: the snapshot may be showing a brief synthetic "done".
        if (deviceState != OperatingState.IDLE) throw IOException("Proxy is not idle")
        c.startHeatCycle()
        kick.trySend(Unit)
    }

    suspend fun abortHeatCycle() {
        ready().abortHeatCycle()
        lastAbortAt = now()
        kick.trySend(Unit)
    }

    private fun ready(): ProxyClient =
        client?.takeIf { _snapshot.value.link == Link.Ready } ?: throw IOException("Proxy is not connected")

    private suspend fun run(app: Context) {
        var failures = 0
        while (scope.isActive) {
            try {
                if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    throw IOException(NEEDS_PERMISSION)
                }
                val device = ProxyDevices.resolve(app)
                    ?: throw IOException(NEEDS_DEVICE)
                _snapshot.value = Snapshot(Link.Connecting)
                timer.reset()
                val c = ProxyClient(app)
                client = c
                c.connect(device)

                Log.i(TAG, "connected to ${device.address}")
                var profiles = (0..3).map { c.readProfile(it) }
                failures = 0
                var battery = c.readBatterySoc()
                var batteryAt = now()
                var profilesAt = now()
                val ends = SessionEndDetector()
                var doneUntil = 0L
                while (true) {
                    val device = c.readLive()
                    deviceState = device.operatingState
                    val durationS = dev.proxywatch.companion.lorax.Units.ticksToSeconds(device.durationTicks)
                    val remain = timer.update(device.operatingState, durationS, now())
                    val aborted = now() - lastAbortAt < ABORT_WINDOW_MS
                    if (ends.update(device.operatingState, remain, aborted)) {
                        _sessions.tryEmit(
                            SessionDone(
                                profileName = profiles.getOrNull(device.profile)?.name.orEmpty(),
                                targetDeciC = device.targetDeciC,
                                durationTicks = device.durationTicks,
                                endedAtMs = System.currentTimeMillis(),
                            )
                        )
                        doneUntil = now() + DONE_SHOW_MS
                    }
                    // This Proxy goes straight from active to idle, so after a natural finish the
                    // watch is shown "done" (state 9) for a few seconds: "All done" plus the end alert.
                    val showDone = device.operatingState == OperatingState.IDLE && now() < doneUntil
                    val live = if (showDone) device.copy(operatingState = OperatingState.FADE) else device
                    if (now() - batteryAt > BATTERY_EVERY_MS) {
                        battery = c.readBatterySoc()
                        batteryAt = now()
                    }
                    // Profiles can be edited from the Puffco app; refresh them while idle.
                    if (live.operatingState == OperatingState.IDLE && now() - profilesAt > PROFILES_EVERY_MS) {
                        profiles = (0..3).map { c.readProfile(it) }
                        profilesAt = now()
                    }
                    _snapshot.value = Snapshot(
                        link = Link.Ready,
                        live = live,
                        profiles = profiles,
                        batterySoc = battery,
                        remainS = remain,
                    )
                    // Poll once a second, or straight away after a command.
                    withTimeoutOrNull(POLL_MS) { kick.receive() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "link attempt $failures failed: ${e.message}")
                client?.close()
                client = null
                _snapshot.value = Snapshot(Link.Error(e.message ?: "connection failed"))
            }
            // Retry quickly a few times (a refused reconnect usually succeeds a moment later), then back off.
            delay(if (failures <= 3) 1_000 else 3_000)
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
}
