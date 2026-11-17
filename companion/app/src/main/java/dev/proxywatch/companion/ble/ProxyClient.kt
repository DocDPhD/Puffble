package dev.proxywatch.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.proxywatch.companion.lorax.Lorax
import dev.proxywatch.companion.lorax.ModeCommand
import dev.proxywatch.companion.lorax.Paths
import dev.proxywatch.companion.lorax.RingColor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Fast-changing values, read every poll. Raw device units; convert with Units. */
data class LiveState(
    val operatingState: Int,
    val profile: Int,
    val targetDeciC: Long,
    val durationTicks: Long,
    val heaterDeciC: Long,
)

/** One stored heat profile (/u/app/hc/{n}). [rgb] is 0x00RRGGBB, or null if unknown. */
data class ProfileInfo(
    val name: String,
    val targetDeciC: Long,
    val durationTicks: Long,
    val rgb: Int?,
)

class LoraxException(message: String) : IOException(message)

/**
 * Lorax client over Android GATT: bond, connect, unlock, then serialized
 * request/reply commands. All calls require BLUETOOTH_CONNECT to be granted.
 */
@SuppressLint("MissingPermission")
class ProxyClient(private val context: Context) {
    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var seq = 0

    private val commandLock = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Lorax.Reply>>()

    @Volatile private var linkUp = CompletableDeferred<Unit>()
    @Volatile private var servicesReady = CompletableDeferred<Unit>()
    @Volatile private var mtuReady = CompletableDeferred<Unit>()
    @Volatile private var notifyReady = CompletableDeferred<Unit>()

    /** Called on a binder thread when the link drops. */
    var onDisconnected: (() -> Unit)? = null

    val isConnected: Boolean get() = gatt != null && commandChar != null

    suspend fun connect(device: BluetoothDevice) {
        close()
        ensureBonded(device)

        linkUp = CompletableDeferred()
        servicesReady = CompletableDeferred()
        mtuReady = CompletableDeferred()
        notifyReady = CompletableDeferred()
        seq = 0

        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        gatt = g
        try {
            await(linkUp, 20_000, "connecting")
            if (!g.discoverServices()) throw IOException("discoverServices refused")
            await(servicesReady, 15_000, "service discovery")
            // Replies carry up to 125 value bytes + 3 header bytes; default MTU 23 is too small.
            if (g.requestMtu(247)) withTimeoutOrNull(5_000) { mtuReady.await() }

            val service = g.getService(Lorax.SERVICE)
                ?: throw IOException("Lorax service not found on this device")
            val reply = service.getCharacteristic(Lorax.REPLY)
                ?: throw IOException("Lorax reply characteristic not found")
            val command = service.getCharacteristic(Lorax.COMMAND)
                ?: throw IOException("Lorax command characteristic not found")

            g.setCharacteristicNotification(reply, true)
            val cccd = reply.getDescriptor(Lorax.CCCD)
                ?: throw IOException("reply characteristic has no CCCD")
            val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (rc != BluetoothStatusCodes.SUCCESS) throw IOException("enable notifications failed, rc=$rc")
            await(notifyReady, 5_000, "enabling notifications")

            commandChar = command
            unlock()
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    fun close() {
        commandChar = null
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        failPending(IOException("connection closed"))
    }

    // ---- high level ---------------------------------------------------------

    suspend fun readLive(): LiveState {
        val profile = Lorax.u8(read(Paths.SELECTED_PROFILE, 1))
        return LiveState(
            operatingState = Lorax.u8(read(Paths.OPERATING_STATE, 1)),
            profile = profile,
            targetDeciC = Lorax.u32(read(Paths.TARGET_TEMP, 4)),
            durationTicks = Lorax.u32(read(Paths.TARGET_TIME, 4)),
            heaterDeciC = Lorax.u32(read(Paths.HEATER_TEMP, 4)),
        )
    }

    suspend fun readBatterySoc(): Long = Lorax.u32(read(Paths.BATTERY_SOC, 4))

    suspend fun readProfile(index: Int): ProfileInfo = ProfileInfo(
        name = Lorax.text(read(Paths.profileName(index))),
        targetDeciC = Lorax.u32(read(Paths.profileTemp(index), 4)),
        durationTicks = Lorax.u32(read(Paths.profileTime(index), 4)),
        rgb = RingColor.fromColr(readAll(Paths.profileColor(index))),
    )

    /** Chunked read of a value longer than one reply (READ_SHORT at increasing offsets). */
    suspend fun readAll(path: String, limit: Int = 4096): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < limit) {
            val chunk = command(Lorax.Op.READ_SHORT, Lorax.readBody(path, out.size(), Lorax.MAX_CHUNK), "read $path")
            out.write(chunk)
            if (chunk.size < Lorax.MAX_CHUNK) break
        }
        return out.toByteArray()
    }

    suspend fun selectProfile(index: Int) {
        require(index in 0..3) { "profile index must be 0..3" }
        write(Paths.SELECTED_PROFILE, byteArrayOf(index.toByte()))
    }

    /** Starts the heater. Callers must get explicit user confirmation first. */
    suspend fun startHeatCycle() =
        write(Paths.MODE_COMMAND, byteArrayOf(ModeCommand.HEAT_CYCLE_START.toByte()))

    suspend fun abortHeatCycle() =
        write(Paths.MODE_COMMAND, byteArrayOf(ModeCommand.HEAT_CYCLE_ABORT.toByte()))

    // ---- Lorax commands -----------------------------------------------------

    suspend fun read(path: String, size: Int = Lorax.MAX_CHUNK): ByteArray =
        command(Lorax.Op.READ_SHORT, Lorax.readBody(path, 0, size), "read $path")

    suspend fun write(path: String, data: ByteArray) {
        command(Lorax.Op.WRITE_SHORT, Lorax.writeBody(path, data), "write $path")
    }

    private suspend fun unlock() {
        val seed = command(Lorax.Op.GET_ACCESS_SEED, what = "get access seed")
        if (seed.size != 16) throw LoraxException("access seed was ${seed.size} bytes, expected 16")
        command(Lorax.Op.UNLOCK_ACCESS, Lorax.unlockKey(seed), "unlock")
    }

    private suspend fun command(op: Int, body: ByteArray = ByteArray(0), what: String): ByteArray =
        commandLock.withLock {
            val g = gatt ?: throw IOException("not connected")
            val ch = commandChar ?: throw IOException("not connected")
            val s = seq
            seq = (seq + 1) and 0xFFFF
            val reply = CompletableDeferred<Lorax.Reply>()
            pending[s] = reply
            try {
                sendFrame(g, ch, Lorax.frame(s, op, body))
                // An unbonded link drops commands silently, so a timeout usually means "not bonded".
                val r = withTimeoutOrNull(3_000) { reply.await() }
                    ?: throw IOException("no reply to $what (is the Proxy bonded to this phone?)")
                if (r.status != 0) throw LoraxException("$what failed, status ${r.status}")
                r.payload
            } finally {
                pending.remove(s)
            }
        }

    private suspend fun sendFrame(g: BluetoothGatt, ch: BluetoothGattCharacteristic, frame: ByteArray) {
        repeat(10) {
            val rc = g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (rc == BluetoothStatusCodes.SUCCESS) return
            if (rc != BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) throw IOException("GATT write failed, rc=$rc")
            delay(20) // previous write's completion callback has not landed yet
        }
        throw IOException("GATT write stayed busy")
    }

    // ---- bonding ------------------------------------------------------------

    private suspend fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val bonded = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(Unit)
                    BluetoothDevice.BOND_NONE -> bonded.completeExceptionally(
                        IOException("pairing failed: put the Proxy in pairing mode and try again")
                    )
                }
            }
        }
        context.registerReceiver(
            receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED
        )
        try {
            if (!device.createBond()) throw IOException("could not start pairing")
            await(bonded, 60_000, "pairing")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    // ---- GATT callback ------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                linkUp.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = IOException("disconnected (status $status)")
                linkUp.completeExceptionally(e)
                servicesReady.completeExceptionally(e)
                notifyReady.completeExceptionally(e)
                failPending(e)
                commandChar = null
                onDisconnected?.invoke()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesReady.complete(Unit)
            else servicesReady.completeExceptionally(IOException("service discovery failed, status $status"))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuReady.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) notifyReady.complete(Unit)
            else notifyReady.completeExceptionally(IOException("enable notifications failed, status $status"))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != Lorax.REPLY) return
            val reply = Lorax.parseReply(value) ?: return
            pending[reply.seq]?.complete(reply)
        }
    }

    private fun failPending(e: IOException) {
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
    }

    private suspend fun await(d: CompletableDeferred<Unit>, timeoutMs: Long, what: String) {
        withTimeoutOrNull(timeoutMs) { d.await() } ?: throw IOException("timed out $what")
    }
}
