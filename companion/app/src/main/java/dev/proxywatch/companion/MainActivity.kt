package dev.proxywatch.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.proxywatch.companion.ble.ProxyDevices
import dev.proxywatch.companion.ble.ProxySession
import dev.proxywatch.companion.lorax.OperatingState
import dev.proxywatch.companion.lorax.Units
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Puffble setup screen: grant Bluetooth, choose the Proxy, see live state, and drive it
 * by hand. The watch uses the same [ProxySession] through WatchListenerService.
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var controls: List<View>
    private var note: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Puffble"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "A Pebble Puffco Companion"
            textSize = 14f
            setPadding(0, 0, 0, pad)
        })
        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, pad)
        }
        root.addView(status)
        root.addView(button("Connect") { connect() })

        val profiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val profileButtons = (0..3).map { i ->
            button("P${i + 1}") { act("select profile") { ProxySession.selectProfile(i) } }.also {
                profiles.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        root.addView(profiles)
        val start = button("Start heat cycle…") { confirmStart() }
        val abort = button("Abort") { act("abort") { ProxySession.abortHeatCycle() } }
        root.addView(start)
        root.addView(abort)
        controls = profileButtons + start + abort
        setContentView(root)

        scope.launch { ProxySession.snapshot.collect { render(it) } }
    }

    override fun onStart() {
        super.onStart()
        // Hold the Proxy link only while this screen is visible (the watch holds its own).
        if (ProxyDevices.resolve(this) != null && hasBluetoothPermission()) ProxySession.acquire(this, HOLDER)
    }

    override fun onStop() {
        ProxySession.release(HOLDER)
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) connect()
        else say("Bluetooth permission is required")
    }

    private fun hasBluetoothPermission() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun connect() {
        if (!hasBluetoothPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        val bonded = ProxyDevices.bonded(this)
        val known = ProxyDevices.resolve(this)
        when {
            known != null -> connectTo(known)
            bonded.isEmpty() ->
                say("No paired devices. Turn Bluetooth on, put the Proxy in pairing mode and pair it in Android settings.")
            else -> AlertDialog.Builder(this)
                .setTitle("Choose your Proxy")
                .setItems(bonded.map { "${it.name ?: "?"}  ${it.address}" }.toTypedArray()) { _, i ->
                    connectTo(bonded[i])
                }
                .show()
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        ProxyDevices.save(this, device)
        note = null
        ProxySession.acquire(this, HOLDER)
    }

    private fun confirmStart() {
        val snap = ProxySession.snapshot.value
        val live = snap.live ?: return
        if (live.operatingState != OperatingState.IDLE) {
            say("Not idle — cannot start.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Start heating?")
            .setMessage(
                "The Proxy will heat to ${Units.deciCToFahrenheit(live.targetDeciC)} °F " +
                    "for ${Units.ticksToSeconds(live.durationTicks)} s (${snap.currentProfile?.name ?: "current profile"})."
            )
            .setPositiveButton("Heat") { _, _ -> act("start") { ProxySession.startHeatCycle() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun act(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                note = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Could not $what: ${e.message}")
            }
        }
    }

    private fun say(message: String) {
        note = message
        render(ProxySession.snapshot.value)
    }

    private fun render(snap: ProxySession.Snapshot) {
        val live = snap.live
        val body = when (val link = snap.link) {
            is ProxySession.Link.Idle -> "Not connected"
            is ProxySession.Link.Connecting -> "Connecting…"
            is ProxySession.Link.Error -> "Error: ${link.message}\nRetrying…"
            is ProxySession.Link.Ready -> if (live == null) "Connected" else
                "${snap.currentProfile?.name ?: "?"} (profile ${live.profile + 1})\n" +
                    "State: ${OperatingState.label(live.operatingState)}\n" +
                    "Target: ${Units.deciCToFahrenheit(live.targetDeciC)} °F for ${Units.ticksToSeconds(live.durationTicks)} s\n" +
                    "Heater: ${Units.deciCToFahrenheit(live.heaterDeciC)} °F\n" +
                    "Battery: ${snap.batterySoc?.let { "${Units.socToPercent(it)} %" } ?: "?"}" +
                    (snap.remainS?.let { "\nRemaining: $it s" } ?: "")
        }
        status.text = listOfNotNull(body, note).joinToString("\n\n")
        val ready = snap.link is ProxySession.Link.Ready
        controls.forEach { it.isEnabled = ready }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private companion object {
        const val HOLDER = "activity"
    }
}
