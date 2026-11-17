package dev.proxywatch.companion.watch

import android.content.Context
import android.util.Log
import dev.proxywatch.companion.ble.ProxySession
import dev.proxywatch.companion.lorax.Units
import dev.proxywatch.companion.watch.WatchProtocol.Command
import dev.proxywatch.companion.watch.WatchProtocol.Key
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.TimelineLayout
import io.rebble.pebblekit2.common.model.TimelineLayoutType
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val TAG = "WatchLink"
private const val HOLDER = "watch"

/** Watch-side settings the phone needs to know (the watch is the source of truth). */
object PuffbleSettings {
    private const val PREFS = "puffble"
    private const val KEY = "watch_settings"

    fun save(context: Context, bits: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, bits).apply()
    }

    private const val KEY_WATCH_SEEN = "watch_seen"

    /** True once the watch app has talked to this phone; ticks the last setup step. */
    fun watchSeen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WATCH_SEEN, false)

    fun markWatchSeen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WATCH_SEEN, false)) prefs.edit().putBoolean(KEY_WATCH_SEEN, true).apply()
    }

    fun pinsEnabled(context: Context): Boolean =
        WatchProtocol.pinsEnabled(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0))
}

/** Pushes every change in Proxy state to the watchapp while it is open. */
object WatchBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var pinJob: Job? = null

    /**
     * Turns each finished session into a timeline pin (unless switched off in the watch
     * settings). Runs for the life of the process, so a session that ends after the watch
     * app was closed, while the Proxy link is still lingering, still gets its pin.
     */
    private fun ensurePinWatcher(app: Context) {
        if (pinJob?.isActive == true) return
        pinJob = scope.launch {
            ProxySession.sessions.collect { done ->
                if (!PuffbleSettings.pinsEnabled(app)) return@collect
                val durationS = Units.ticksToSeconds(done.durationTicks)
                val text = WatchProtocol.sessionPin(done.profileName, Units.deciCToFahrenheit(done.targetDeciC), durationS)
                val pin = TimelinePin(
                    id = "puffble-${done.endedAtMs}",
                    startTime = Instant.fromEpochMilliseconds(done.endedAtMs - durationS * 1000L),
                    duration = durationS.seconds,
                    layout = TimelineLayout(
                        type = TimelineLayoutType.GENERIC_PIN,
                        title = text.title,
                        subtitle = text.subtitle,
                        body = text.body,
                        tinyIcon = "system://images/GENERIC_CONFIRMATION",
                    ),
                )
                val sender = DefaultPebbleSender(app)
                try {
                    Log.d(TAG, "timeline pin -> ${sender.insertTimelinePin(WatchProtocol.APP_UUID, pin)}")
                } finally {
                    sender.close()
                }
            }
        }
    }

    /** (Re)starts the push loop; the current state and the profile table are always sent first. */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            job?.cancel()
            ensurePinWatcher(app)
            ProxySession.acquire(app, HOLDER)
            job = scope.launch {
                val sender = DefaultPebbleSender(app)
                var sentTable: List<WatchProtocol.ProfileEntry> = emptyList()
                try {
                    ProxySession.snapshot
                        .map { WatchProtocol.fromSnapshot(it) to WatchProtocol.profileTable(it) }
                        .distinctUntilChanged()
                        .collect { (message, table) ->
                            // The table only changes when profiles are edited, so it rides along only then.
                            val withTable = table.isNotEmpty() && table != sentTable
                            val dict = toDictionary(message) + if (withTable) tableDictionary(table) else emptyMap()
                            val result = sender.sendDataToPebble(WatchProtocol.APP_UUID, dict)
                            val ok = !result.isNullOrEmpty() && result.values.all { it is TransmissionResult.Success }
                            if (ok && withTable) sentTable = table
                            Log.d(TAG, "sent $message table=$withTable -> $result")
                        }
                } finally {
                    sender.close()
                }
            }
        }
    }

    fun stop() {
        scope.launch {
            job?.cancel()
            job = null
            ProxySession.release(HOLDER)
        }
    }

    private fun toDictionary(m: WatchProtocol.WatchMessage): PebbleDictionary {
        val d = mutableMapOf<UInt, PebbleDictionaryItem>(
            Key.STATE to PebbleDictionaryItem.UInt8(m.state),
            Key.STATUS_TEXT to PebbleDictionaryItem.Text(m.statusText),
        )
        if (m.state != WatchProtocol.STATE_SEARCHING) {
            d += mapOf(
                Key.PROFILE_IDX to PebbleDictionaryItem.UInt8(m.profileIdx),
                Key.PROFILE_NAME to PebbleDictionaryItem.Text(m.profileName),
                Key.TARGET_F to PebbleDictionaryItem.UInt16(m.targetF),
                Key.DURATION_S to PebbleDictionaryItem.UInt16(m.durationS),
                Key.HEATER_F to PebbleDictionaryItem.UInt16(m.heaterF),
                Key.REMAIN_S to PebbleDictionaryItem.Int16(m.remainS.toShort()),
                Key.BATT_PCT to PebbleDictionaryItem.UInt8(m.battPct),
                Key.RING_RGB to PebbleDictionaryItem.UInt32(m.ringRgb.toLong()),
            )
        }
        return d
    }

    private fun tableDictionary(table: List<WatchProtocol.ProfileEntry>): PebbleDictionary =
        table.withIndex().flatMap { (i, p) ->
            val n = i.toUInt()
            listOf(
                Key.P_NAME + n to PebbleDictionaryItem.Text(p.name),
                Key.P_TARGET_F + n to PebbleDictionaryItem.UInt16(p.targetF),
                Key.P_DURATION_S + n to PebbleDictionaryItem.UInt16(p.durationS),
                Key.P_RGB + n to PebbleDictionaryItem.UInt32(p.rgb.toLong()),
            )
        }.toMap()
}

/** Bound by the Pebble app when the watchapp opens; receives its button commands. */
class WatchListenerService : BasePebbleListenerService() {
    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != WatchProtocol.APP_UUID) return
        PuffbleSettings.markWatchSeen(this)
        WatchBridge.start(this)
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == WatchProtocol.APP_UUID) WatchBridge.stop()
    }

    /**
     * The reply (Ack/Nack) reaches the watch as outbox sent/failed. Commands are only
     * acked once the Proxy has accepted the write, so the watch can show errors.
     */
    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != WatchProtocol.APP_UUID) return ReceiveResult.Nack
        PuffbleSettings.markWatchSeen(this)
        val settings = data[Key.SETTINGS].asInt()
        if (settings != null) PuffbleSettings.save(this, settings)
        val command = WatchProtocol.parseCommand(data[Key.CMD].asInt(), data[Key.SET_PROFILE].asInt())
        Log.d(TAG, "command $command settings $settings from $data")
        if (command == null && settings != null) return ReceiveResult.Ack
        try {
            when (command) {
                // The watch asks for this on launch; it also covers a missed onAppOpened.
                Command.Refresh -> WatchBridge.start(this)
                // The watch only sends this after its own hold-to-confirm screen.
                Command.StartHeat -> ProxySession.startHeatCycle()
                Command.AbortHeat -> ProxySession.abortHeatCycle()
                is Command.SelectProfile -> ProxySession.selectProfile(command.index)
                null -> return ReceiveResult.Nack
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "command $command failed: ${e.message}")
            return ReceiveResult.Nack
        }
        return ReceiveResult.Ack
    }

    private fun PebbleDictionaryItem?.asInt(): Int? = when (val v = this?.value) {
        is UByte -> v.toInt()
        is UShort -> v.toInt()
        is UInt -> v.toInt()
        is Number -> v.toInt()
        else -> null
    }
}
