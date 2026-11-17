package dev.proxywatch.companion.lorax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Blobs read from the real Proxy on 2026-11-17 (docs/PROTOCOL.md). */
class RingColorTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val pinkSpin = hex(
        "a200a2000001ab0018ff01190400020703010401058c000000000000000000000000064fff00d0ff00d0ff00d0ff00d0ff00d0" +
            "070508190100091908000a19010001aa0050019ece3f7872ba53c11860a4925265940113026970696e6b207370696e031a6a49e2" +
            "3404030650019eb28fecec5a4bfd43b47700b64442071a6a30b44c0850019ece3f7872ba53c11860a4925265940943ff00d00d194000"
    )
    private val green = hex(
        "a200a2000001a80018ff01190400020103000401058c000000000000000000000000064353ff1207182001a301020650019eb197" +
            "27062b58c5d6f6377ed47434071a6a31fa74"
    )

    @Test fun readsShownColor() = assertEquals(0xFF00D0, RingColor.fromColr(pinkSpin))

    @Test fun fallsBackToAnimationColor() = assertEquals(0x53FF12, RingColor.fromColr(green))

    @Test fun decodesNestedMap() {
        val root = Cbor.decode(green) as Map<*, *>
        assertEquals(2, root.size)
    }

    @Test fun garbageGivesNull() {
        assertNull(RingColor.fromColr(hex("ff")))
        assertNull(RingColor.fromColr(ByteArray(0)))
    }
}
