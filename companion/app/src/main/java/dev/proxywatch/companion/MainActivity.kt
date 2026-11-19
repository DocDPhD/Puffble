package dev.proxywatch.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import dev.proxywatch.companion.ble.ProxyDevices
import dev.proxywatch.companion.ble.ProxySession
import dev.proxywatch.companion.lorax.Units
import dev.proxywatch.companion.watch.PuffbleSettings
import dev.proxywatch.companion.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Puffble's only phone screen: a three-step setup checklist and a live link status.
 * Everything else (profiles, heating) happens on the watch.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val scope = MainScope()

    private class Step(root: View) {
        val badge: TextView = root.findViewById(R.id.badge)
        val title: TextView = root.findViewById(R.id.title)
        val body: TextView = root.findViewById(R.id.body)
        val action: Button = root.findViewById(R.id.action)
    }

    private lateinit var permissionStep: Step
    private lateinit var proxyStep: Step
    private lateinit var watchStep: Step
    private lateinit var batterySwitch: Switch
    private lateinit var status: TextView
    private lateinit var statusDot: View

    /** Set after a denial with "don't ask again": the button then opens app settings. */
    private var permissionBlocked = false
    private var holding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge (enforced from Android 15): keep content clear of the system bars.
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        permissionStep = Step(findViewById(R.id.step_permission))
        proxyStep = Step(findViewById(R.id.step_proxy))
        watchStep = Step(findViewById(R.id.step_watch))
        batterySwitch = findViewById(R.id.battery_switch)
        batterySwitch.isChecked = prefs().getBoolean(PREF_BATTERY_GUIDE, false)
        batterySwitch.setOnCheckedChangeListener { _, on ->
            prefs().edit().putBoolean(PREF_BATTERY_GUIDE, on).apply()
            refresh()
        }
        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.status_dot)

        permissionStep.title.setText(R.string.step_permission_title)
        proxyStep.title.setText(R.string.step_proxy_title)
        watchStep.title.setText(R.string.step_watch_title)

        setUpUpdates()

        findViewById<View>(R.id.link_help).setOnClickListener { open(REPO_URL) }
        findViewById<View>(R.id.link_privacy).setOnClickListener { open("$REPO_URL/blob/main/PRIVACY.md") }
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        findViewById<TextView>(R.id.footer).text = getString(R.string.footer, version)

        scope.launch { ProxySession.snapshot.collect { renderStatus(it) } }
    }

    override fun onResume() {
        super.onResume()
        refresh()  // picks up changes made in Bluetooth or app settings
    }

    override fun onStop() {
        releaseLink()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        val granted = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
        permissionBlocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
        refresh()
    }

    // ---- checklist -----------------------------------------------------------

    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothOn() = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    private fun pebbleApp(): Intent? = PEBBLE_PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }

    private fun refresh() {
        val permission = hasPermission()
        val proxy = if (permission) ProxyDevices.resolve(this) else null
        val watchSeen = PuffbleSettings.watchSeen(this)

        // 1. Permission
        setStep(permissionStep, 1, done = permission, enabled = true,
            body = getString(when {
                permission -> R.string.step_permission_done
                permissionBlocked -> R.string.step_permission_settings
                else -> R.string.step_permission_todo
            }),
            action = if (permission) null else getString(if (permissionBlocked) R.string.action_settings else R.string.action_allow),
        ) {
            if (permissionBlocked) openAppSettings()
            else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }

        // 2. Proxy
        val bonded = if (permission) ProxyDevices.bonded(this) else emptyList()
        setStep(proxyStep, 2, done = proxy != null, enabled = permission,
            body = when {
                !permission -> getString(R.string.step_proxy_locked)
                !bluetoothOn() -> getString(R.string.step_proxy_bt_off)
                proxy != null -> proxy.name ?: proxy.address
                bonded.isEmpty() -> getString(R.string.step_proxy_unpaired)
                else -> getString(R.string.step_proxy_pick)
            },
            action = when {
                !permission -> null
                !bluetoothOn() || bonded.isEmpty() -> getString(R.string.action_pair)
                proxy != null -> getString(R.string.action_change)
                else -> getString(R.string.action_choose)
            },
        ) {
            if (!bluetoothOn() || ProxyDevices.bonded(this).isEmpty()) openBluetoothSettings() else chooseProxy()
        }

        // 3. Watch
        val pebble = pebbleApp()
        setStep(watchStep, 3, done = watchSeen, enabled = true,
            body = getString(when {
                watchSeen -> R.string.step_watch_done
                pebble == null -> R.string.step_watch_no_pebble
                else -> R.string.step_watch_todo
            }),
            action = when {
                watchSeen -> null
                pebble == null -> getString(R.string.action_get_pebble)
                else -> getString(R.string.action_open_pebble)
            },
        ) {
            val launch = pebbleApp()
            if (launch != null) startActivity(launch) else open(PEBBLE_APP_URL)
        }

        renderBatteryCard()

        // Hold the Proxy link while this screen is open, so the status shows it working.
        if (permission && proxy != null) holdLink() else releaseLink()
        renderStatus(ProxySession.snapshot.value)
    }

    private fun setStep(
        step: Step, number: Int, done: Boolean, enabled: Boolean, body: String, action: String?, onAction: () -> Unit,
    ) {
        step.badge.text = if (done) "✓" else number.toString()
        step.badge.setBackgroundResource(if (done) R.drawable.badge_done else R.drawable.badge_todo)
        step.badge.setTextColor(getColor(if (done) R.color.bg else R.color.text))
        step.body.text = body
        step.title.alpha = if (enabled) 1f else 0.5f
        step.body.alpha = if (enabled) 1f else 0.5f
        step.action.visibility = if (action == null) View.GONE else View.VISIBLE
        step.action.text = action
        step.action.setOnClickListener { onAction() }
    }

    private fun renderStatus(snap: ProxySession.Snapshot) {
        if (!::status.isInitialized) return
        val ready = hasPermission() && ProxyDevices.resolve(this) != null
        val (text, color) = if (!ready) getString(R.string.status_setup) to R.color.muted else when (val link = snap.link) {
            is ProxySession.Link.Ready -> {
                val name = ProxyDevices.resolve(this)?.name ?: "Proxy"
                val battery = snap.batterySoc?.let { " · ${Units.socToPercent(it)}% battery" }.orEmpty()
                "Connected to $name$battery" to R.color.ok
            }
            is ProxySession.Link.Connecting -> getString(R.string.status_connecting) to R.color.warn
            is ProxySession.Link.Error -> link.message to R.color.warn
            is ProxySession.Link.Idle -> getString(R.string.status_waiting) to R.color.muted
        }
        status.text = text
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    // ---- actions -------------------------------------------------------------

    private fun chooseProxy() {
        val bonded = ProxyDevices.bonded(this)
        val likely = ProxyDevices.likely(this).map { it.address }.toSet()
        // Likely Proxies first, then everything else that's paired.
        val ordered = bonded.sortedBy { if (it.address in likely) 0 else 1 }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_title)
            .setItems(ordered.map { it.name ?: it.address }.toTypedArray()) { _, i -> choose(ordered[i]) }
            .show()
    }

    private fun choose(device: BluetoothDevice) {
        ProxyDevices.save(this, device)
        ProxySession.restart(this)  // drop any link to the old choice and connect to this one
        refresh()
    }

    private fun holdLink() {
        if (holding) return
        holding = true
        ProxySession.acquire(this, HOLDER)
    }

    private fun releaseLink() {
        if (!holding) return
        holding = false
        ProxySession.release(HOLDER)
    }

    // ---- updates ----------------------------------------------------------------

    private fun installedVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

    private fun setUpUpdates() {
        val link = findViewById<TextView>(R.id.link_update)
        link.setText(R.string.link_update)
        link.setOnClickListener { checkForUpdates(link) }
    }

    /** Asks GitHub for the latest release; only runs when the user taps the link. */
    private fun checkForUpdates(link: TextView) {
        link.isEnabled = false
        link.setText(R.string.link_update_checking)
        scope.launch {
            val latest = runCatching { withContext(Dispatchers.IO) { Updates.fetchLatest() } }.getOrNull()
            link.isEnabled = true
            when {
                latest == null -> link.setText(R.string.link_update_failed)
                Updates.isNewer(latest.version, installedVersion()) -> {
                    // The link becomes the download: tap again to get the new APK.
                    link.text = getString(R.string.link_update_get, latest.version)
                    link.setTextColor(getColor(R.color.flame))
                    link.setOnClickListener { open(latest.apkUrl ?: latest.pageUrl) }
                    return@launch
                }
                else -> link.setText(R.string.link_update_latest)
            }
            link.setOnClickListener { checkForUpdates(link) }  // tap again to re-check
        }
    }

    // ---- optional battery card -----------------------------------------------------

    /** Off: a short explanation. On: the steps expand. Allowed: they collapse and a check shows. */
    private fun renderBatteryCard() {
        val on = batterySwitch.isChecked
        val exempt = ignoringBatteryOptimizations()
        val body = findViewById<TextView>(R.id.battery_body)
        val badge = findViewById<View>(R.id.battery_badge)
        val action = findViewById<Button>(R.id.battery_action)
        badge.visibility = if (on && exempt) View.VISIBLE else View.GONE
        body.setText(
            when {
                !on -> R.string.battery_switch_body
                exempt -> R.string.battery_on_done
                else -> R.string.battery_on_todo
            }
        )
        action.visibility = if (on && !exempt) View.VISIBLE else View.GONE
        action.setOnClickListener { requestBatteryExemption() }
    }

    private fun prefs() = getSharedPreferences("setup", MODE_PRIVATE)

    private fun ignoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    /** Android's own "let this app run in the background?" prompt. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(ask) }.onFailure { openBatterySettings() }
    }

    private fun openBatterySettings() {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(list) }.onFailure { openAppSettings() }
    }

    private fun openBluetoothSettings() = startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private companion object {
        const val HOLDER = "activity"
        const val PREF_BATTERY_GUIDE = "battery_guide"
        const val REPO_URL = "https://github.com/DocDPhD/Puffble"
        const val PEBBLE_APP_URL = "https://repebble.com"
        val PEBBLE_PACKAGES = listOf("coredevices.coreapp")
    }
}
