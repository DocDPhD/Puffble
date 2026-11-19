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
    private var startDurationS = 0
    private var boostedS = 0

    /** Feed every poll. Returns seconds left in the active phase, or null if unknown. */
    fun update(state: Int, durationS: Int, nowMs: Long): Int? {
        val prev = lastState
        lastState = state
        if (state != OperatingState.ACTIVE) {
            activeSinceMs = null
            boostedS = 0
            return null
        }
        // Only time a phase we saw begin; joining mid-cycle leaves the countdown unknown.
        if (prev != null && prev != OperatingState.ACTIVE) {
            activeSinceMs = nowMs
            startDurationS = durationS
        }
        val since = activeSinceMs ?: return null
        // Boost adds time. If the Proxy also raises its own session length, count it once.
        val extra = (boostedS - (durationS - startDurationS)).coerceAtLeast(0)
        return (durationS + extra - ((nowMs - since) / 1000).toInt()).coerceAtLeast(0)
    }

    /** A boost was accepted during the active phase. */
    fun boost(seconds: Int) {
        if (lastState == OperatingState.ACTIVE) boostedS += seconds
    }

    fun reset() {
        lastState = null
        activeSinceMs = null
        boostedS = 0
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
 * Spots when the Proxy has shut itself down to cool off, so the watch can say "wait".
 *
 * The Proxy has no readable overheat flag. What it does when too hot (seen on the real
 * device): it acknowledges a start but never begins preheating, then turns itself off, which
 * drops the Bluetooth link; it comes back in sleep. So a cool-down starts when:
 *  - a start was sent but preheat or active doesn't appear within [REFUSE_MS], or
 *  - the link drops while starting or heating, or
 *  - it goes from heating straight to off/sleep without a stop from Puffble.
 * Pure logic, unit tested.
 */
class CooldownDetector(private val durationMs: Long = 120_000L) {
    var untilMs: Long? = null
        private set
    private var startSentAt: Long? = null
    private var lastState: Int? = null

    fun onStartSent(now: Long) {
        startSentAt = now
    }

    /** Feed every poll. Returns true if the cool-down started or ended. */
    fun onPoll(state: Int, now: Long, abortedRecently: Boolean): Boolean {
        val prev = lastState
        lastState = state
        if (state == OperatingState.PREHEAT || state == OperatingState.ACTIVE) {
            startSentAt = null
            return clear()  // heating again (the user skipped the wait): the cool-down is over
        }
        val sent = startSentAt
        if (sent != null && now - sent > REFUSE_MS) {
            startSentAt = null
            return trigger(now)
        }
        val wasHeating = prev == OperatingState.PREHEAT || prev == OperatingState.ACTIVE
        if (wasHeating && (state == OFF || state == SLEEP) && !abortedRecently) return trigger(now)
        return expire(now)
    }

    /** The Bluetooth link dropped. Returns true if that started a cool-down. */
    fun onLinkLost(now: Long): Boolean {
        val heating = lastState == OperatingState.PREHEAT || lastState == OperatingState.ACTIVE
        val starting = startSentAt?.let { now - it < LINK_LOSS_AFTER_START_MS } == true
        lastState = null
        startSentAt = null
        return if (heating || starting) trigger(now) else false
    }

    fun expire(now: Long): Boolean {
        val until = untilMs ?: return false
        if (now < until) return false
        untilMs = null
        return true
    }

    fun clear(): Boolean {
        val had = untilMs != null
        untilMs = null
        return had
    }

    fun remainingS(now: Long): Int? = untilMs?.let { ((it - now + 999) / 1000).toInt().coerceAtLeast(0) }

    private fun trigger(now: Long): Boolean {
        if (untilMs != null) return false
        untilMs = now + durationMs
        return true
    }

    companion object {
        const val REFUSE_MS = 4_000L
        const val LINK_LOSS_AFTER_START_MS = 30_000L
        const val OFF = 3
        const val SLEEP = 4
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
        /** Seconds left of a suggested cool-down after the Proxy shut itself off; null if none. */
        val cooldownRemainS: Int? = null,
        val cooldownReason: String = "",
        /** Last heater reading, kept through a disconnect so the cool-down screen can show it. */
        val lastHeaterDeciC: Long? = null,
        /** The running (or just-started) session is a one-time custom one, not a stored profile. */
        val custom: Boolean = false,
    ) {
        val currentProfile: ProfileInfo? get() = live?.let { profiles.getOrNull(it.profile) }
    }

    private const val TAG = "ProxySession"

    // Shown on the watch while searching; written for someone looking at their wrist.
    const val NEEDS_PERMISSION = "Open the Puffble companion app and allow Nearby Devices Permissions"
    const val NEEDS_DEVICE = "Open the Puffble companion app and choose your Proxy"
    const val TOO_HOT = "Your Proxy shut off to cool down"
    const val LOW_BATTERY = "Your Proxy's battery may be too low. Charge it"
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

    const val COOLDOWN_S = 120
    const val BOOST_S = 15

    /** Custom-session limits, matching the watch's editor. */
    val CUSTOM_F_RANGE = 400..620
    val CUSTOM_S_RANGE = 15..180

    /** Profile to restore after a custom session, or null when none is running. */
    @Volatile private var customFor: Int? = null
    @Volatile private var lastStartAt = Long.MIN_VALUE / 2
    private const val CUSTOM_SETTLE_MS = 6_000L  // time for the Proxy to leave idle after a start
    private val cooldown = CooldownDetector(COOLDOWN_S * 1000L)
    private var cooldownReason = ""
    private var lastHeater: Long? = null
    private var lastBatterySoc: Long? = null
    private var cooldownTicker: Job? = null

    /** Publishes a snapshot with the cool-down state attached. */
    private var published: Snapshot
        get() = _snapshot.value
        set(v) {
            _snapshot.value = v.copy(
                custom = customFor != null,
                cooldownRemainS = cooldown.remainingS(now()),
                cooldownReason = cooldownReason,
                lastHeaterDeciC = v.live?.heaterDeciC ?: lastHeater,
            )
        }

    private fun onCooldownChanged() {
        cooldownReason = if ((lastBatterySoc ?: 10_000) < 1_000) LOW_BATTERY else TOO_HOT
        Log.i(TAG, "cool-down ${if (cooldown.untilMs != null) "started" else "ended"}")
        published = _snapshot.value
        cooldownTicker?.cancel()
        val left = cooldown.remainingS(now()) ?: return
        // Republish when it runs out, even if the Proxy is still unreachable.
        cooldownTicker = scope.launch {
            delay(left * 1000L + 200)
            if (cooldown.expire(now())) published = _snapshot.value
        }
    }

    /** The watch's "Hold SELECT to skip". */
    fun skipCooldown() {
        scope.launch { if (cooldown.clear()) onCooldownChanged() }
    }

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
        published = Snapshot()
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
        lastStartAt = now()
        cooldown.onStartSent(now())
        kick.trySend(Unit)
    }

    /** Starts a one-time custom session. Callers must already have the user's explicit confirmation. */
    suspend fun startCustomHeatCycle(tempF: Int, seconds: Int) {
        require(tempF in CUSTOM_F_RANGE && seconds in CUSTOM_S_RANGE) { "custom session out of range" }
        val c = ready()
        if (deviceState != OperatingState.IDLE) throw IOException("Proxy is not idle")
        customFor = _snapshot.value.live?.profile ?: 0
        try {
            c.startCustomHeatCycle(
                dev.proxywatch.companion.lorax.Units.fahrenheitToDeciC(tempF),
                dev.proxywatch.companion.lorax.Units.secondsToTicks(seconds),
            )
        } catch (e: Exception) {
            restoreProfileAfterCustom(c)
            throw e
        }
        lastStartAt = now()
        cooldown.onStartSent(now())
        kick.trySend(Unit)
    }

    /** Standard Puffco boost during a session: hotter, and [BOOST_S] seconds longer. */
    suspend fun boostHeatCycle() {
        val c = ready()
        if (deviceState != OperatingState.ACTIVE) throw IOException("Boost works during a session")
        c.boostHeatCycle()
        timer.boost(BOOST_S)
        kick.trySend(Unit)
    }

    /** Re-selects the profile that was current before a custom session, reloading its values. */
    private suspend fun restoreProfileAfterCustom(c: ProxyClient) {
        val index = customFor ?: return
        customFor = null
        runCatching { c.selectProfile(index) }.onFailure { Log.w(TAG, "restore after custom failed: ${it.message}") }
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
                published = Snapshot(Link.Connecting)
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
                    lastHeater = device.heaterDeciC
                    // A custom session is over once the Proxy is idle again (finished, stopped or refused).
                    if (customFor != null && device.operatingState == OperatingState.IDLE &&
                        now() - lastStartAt > CUSTOM_SETTLE_MS
                    ) restoreProfileAfterCustom(c)
                    lastBatterySoc = battery
                    if (cooldown.onPoll(device.operatingState, now(), aborted)) onCooldownChanged()
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
                    published = Snapshot(
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
                if (cooldown.onLinkLost(now())) onCooldownChanged()
                client?.close()
                client = null
                published = Snapshot(Link.Error(e.message ?: "connection failed"))
            }
            // Retry quickly a few times (a refused reconnect usually succeeds a moment later), then back off.
            delay(if (failures <= 3) 1_000 else 3_000)
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
}
