package dev.proxywatch.companion.lorax

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Vectors come from the working Python client (PuffcoBLE) and from bytes
 * read off the real Proxy on 2026-11-17 (docs/PROTOCOL.md).
 */
class LoraxTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun readShortFrameMatchesPythonClient() {
        val frame = Lorax.frame(5, Lorax.Op.READ_SHORT, Lorax.readBody(Paths.TARGET_TEMP, size = 4))
        assertArrayEquals(hex("050010000004002f702f6170702f7468632f74656d70"), frame)
    }

    @Test fun writeShortFrameMatchesPhoneCapture() {
        val body = Lorax.writeBody(Paths.MODE_COMMAND, byteArrayOf(ModeCommand.HEAT_CYCLE_START.toByte()))
        assertArrayEquals(hex("7601110000042f702f6170702f6d630007"), Lorax.frame(0x176, Lorax.Op.WRITE_SHORT, body))
    }

    @Test fun sequenceWrapsToSixteenBits() {
        assertArrayEquals(hex("ffff00"), Lorax.frame(0xFFFF, Lorax.Op.GET_ACCESS_SEED))
    }

    @Test fun unlockKeyMatchesPythonClient() {
        val seed = ByteArray(16) { it.toByte() }
        assertArrayEquals(hex("cc4af4246a24eb8bb512bc7dc5157b9c"), Lorax.unlockKey(seed))
    }

    @Test fun parsesLimitsReplyFromDevice() {
        val r = Lorax.parseReply(hex("0000007d0000000800"))!!
        assertEquals(0, r.seq)
        assertEquals(0, r.status)
        assertEquals(6, r.payload.size)
    }

    @Test fun parsesSeedReplyFromDevice() {
        val r = Lorax.parseReply(hex("010000e8d33f544e3f80dfd6e5a3903420c58b"))!!
        assertEquals(1, r.seq)
        assertEquals(16, r.payload.size)
    }

    @Test fun shortFrameIsRejected() {
        assertNull(Lorax.parseReply(hex("0100")))
    }

    @Test fun decodesValuesReadFromDevice() {
        assertEquals(2461L, Lorax.u32(hex("9d090000")))
        assertEquals(475, Units.deciCToFahrenheit(2461))
        assertEquals(570, Units.deciCToFahrenheit(2989))
        assertEquals(120, Units.ticksToSeconds(Lorax.u32(hex("c05d0000"))))
        assertEquals(100, Units.socToPercent(Lorax.u32(hex("10270000"))))
        assertEquals(5, Lorax.u8(hex("05")))
        assertEquals("Ultra Flavor", Lorax.text(hex("556c74726120466c61766f72")))
    }

    @Test fun u32IsUnsigned() {
        assertEquals(2147483648L, Lorax.u32(hex("00000080")))
    }
}
