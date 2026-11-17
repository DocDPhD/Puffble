package dev.proxywatch.companion.lorax

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Lorax wire protocol: framing, auth and value codecs. Pure JVM, no Android types,
 * so it is covered by plain unit tests.
 *
 * Auth and framing ported from PuffcoBLE (Fr0st3h, MIT); protocol notes also from melt-linux
 * (jen88uk). Encodings confirmed against a Proxy on firmware API 7 — see docs/PROTOCOL.md.
 */
object Lorax {
    val SERVICE: UUID = UUID.fromString("e276967f-ea8a-478a-a92e-d78f5dd15dd5")
    val COMMAND: UUID = UUID.fromString("60133d5c-5727-4f2c-9697-d842c5292a3c")
    val REPLY: UUID = UUID.fromString("8dc5ec05-8f7d-45ad-99db-3fbde65dbd9c")
    val EVENT: UUID = UUID.fromString("43312cd1-7d34-46ce-a7d3-0a98fd9b4cb8")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    object Op {
        const val GET_ACCESS_SEED = 0x00
        const val UNLOCK_ACCESS = 0x01
        const val GET_LIMITS = 0x02
        const val READ_SHORT = 0x10
        const val WRITE_SHORT = 0x11
    }

    /** Largest value chunk the device returns per READ_SHORT (GET_LIMITS maxPayload). */
    const val MAX_CHUNK = 125

    /** Flags byte the phone app sends on WRITE_SHORT. The Proxy also accepts 0. */
    const val WRITE_FLAGS = 4

    /** Lorax handshake key as published in both source libraries. */
    private val HANDSHAKE_KEY: ByteArray = Base64.getDecoder().decode("ZMZFYlbyb1scoSc3pd1x+w==")

    /** Request frame: [seq u16 LE][opcode u8][body]. */
    fun frame(seq: Int, op: Int, body: ByteArray = ByteArray(0)): ByteArray =
        le(3 + body.size).putShort(seq.toShort()).put(op.toByte()).put(body).array()

    /** READ_SHORT body: [offset u16][size u16][path]. */
    fun readBody(path: String, offset: Int = 0, size: Int = MAX_CHUNK): ByteArray {
        val p = path.toByteArray(Charsets.US_ASCII)
        return le(4 + p.size).putShort(offset.toShort()).putShort(size.toShort()).put(p).array()
    }

    /** WRITE_SHORT body: [offset u16][flags u8][path][0x00][data]. */
    fun writeBody(path: String, data: ByteArray, offset: Int = 0, flags: Int = WRITE_FLAGS): ByteArray {
        val p = path.toByteArray(Charsets.US_ASCII)
        return le(3 + p.size + 1 + data.size)
            .putShort(offset.toShort()).put(flags.toByte()).put(p).put(0).put(data).array()
    }

    /** UNLOCK_ACCESS body: first 16 bytes of SHA-256(handshake key ‖ seed). */
    fun unlockKey(seed: ByteArray): ByteArray {
        require(seed.size == 16) { "access seed must be 16 bytes, got ${seed.size}" }
        return MessageDigest.getInstance("SHA-256").digest(HANDSHAKE_KEY + seed).copyOf(16)
    }

    /** Reply frame: [seq u16 LE][status u8][payload]; status 0 = success. */
    class Reply(val seq: Int, val status: Int, val payload: ByteArray)

    fun parseReply(frame: ByteArray): Reply? {
        if (frame.size < 3) return null
        val seq = (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)
        return Reply(seq, frame[2].toInt() and 0xFF, frame.copyOfRange(3, frame.size))
    }

    fun u8(payload: ByteArray): Int {
        require(payload.isNotEmpty()) { "empty value" }
        return payload[0].toInt() and 0xFF
    }

    fun u32(payload: ByteArray): Long {
        require(payload.size >= 4) { "expected 4 bytes, got ${payload.size}" }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    fun text(payload: ByteArray): String =
        payload.toString(Charsets.UTF_8).trimEnd('\u0000')

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

/** Value paths used by v1. */
object Paths {
    const val MODE_COMMAND = "/p/app/mc"          // write u8, see [ModeCommand]
    const val OPERATING_STATE = "/p/app/stat/id"  // u8, see [OperatingState]
    const val ELAPSED = "/p/app/stat/elap"        // u32, unit unverified during a cycle
    const val SELECTED_PROFILE = "/p/app/hcs"     // u8, 0..3
    const val TARGET_TEMP = "/p/app/thc/temp"     // u32, tenths of a degree C
    const val TARGET_TIME = "/p/app/thc/time"     // u32, 1/200 s
    const val HEATER_TEMP = "/p/app/htr/temp"     // u32, tenths of a degree C
    const val BATTERY_SOC = "/p/bat/soc"          // u32, hundredths of a percent
    fun profileName(index: Int) = "/u/app/hc/$index/name"
    fun profileTemp(index: Int) = "/u/app/hc/$index/temp"     // u32, tenths of a degree C
    fun profileTime(index: Int) = "/u/app/hc/$index/time"     // u32, 1/200 s
    fun profileColor(index: Int) = "/u/app/hc/$index/colr"    // CBOR, see RingColor
}

object ModeCommand {
    const val HEAT_CYCLE_START = 7
    const val HEAT_CYCLE_ABORT = 8
}

object OperatingState {
    const val IDLE = 5
    const val TEMP_SELECT = 6
    const val PREHEAT = 7
    const val ACTIVE = 8
    const val FADE = 9

    fun label(id: Int): String = when (id) {
        3 -> "off"
        4 -> "sleep"
        IDLE -> "idle"
        TEMP_SELECT -> "temp select"
        PREHEAT -> "preheating"
        ACTIVE -> "active"
        FADE -> "cooling"
        else -> "state $id"
    }
}

object Units {
    /** Device temperatures are tenths of a degree Celsius. */
    fun deciCToFahrenheit(deciC: Long): Int = Math.round(deciC / 10.0 * 9 / 5 + 32).toInt()

    /** Device durations are in 1/200 s ticks. */
    fun ticksToSeconds(ticks: Long): Int = (ticks / 200).toInt()

    fun socToPercent(soc: Long): Int = Math.round(soc / 100.0).toInt()
}
