package dev.proxywatch.companion.lorax

/**
 * Minimal CBOR reader for the Proxy's color blobs (/u/app/hc/{n}/colr, /p/app/thc/colr).
 * Supports the types those blobs use: unsigned/negative ints, byte and text strings,
 * arrays and maps (definite length only). Anything else throws.
 */
object Cbor {
    fun decode(data: ByteArray): Any? = Reader(data).read()

    private class Reader(private val b: ByteArray) {
        private var i = 0

        fun read(): Any? {
            val head = u8()
            val major = head shr 5
            val info = head and 0x1F
            return when (major) {
                0 -> arg(info)
                1 -> -1 - arg(info)
                2 -> bytes(arg(info).toInt())
                3 -> String(bytes(arg(info).toInt()), Charsets.UTF_8)
                4 -> List(arg(info).toInt()) { read() }
                5 -> LinkedHashMap<Any?, Any?>().also { m -> repeat(arg(info).toInt()) { m[read()] = read() } }
                7 -> when (info) {
                    20 -> false
                    21 -> true
                    22, 23 -> null
                    else -> throw IllegalArgumentException("unsupported CBOR simple/float $info")
                }
                else -> throw IllegalArgumentException("unsupported CBOR major type $major")
            }
        }

        private fun arg(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> u8().toLong()
            info == 25 -> (u8().toLong() shl 8) or u8().toLong()
            info == 26 -> (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or u8().toLong() }
            info == 27 -> (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or u8().toLong() }
            else -> throw IllegalArgumentException("indefinite-length CBOR is not supported")
        }

        private fun u8(): Int {
            if (i >= b.size) throw IllegalArgumentException("truncated CBOR")
            return b[i++].toInt() and 0xFF
        }

        private fun bytes(n: Int): ByteArray {
            if (n < 0 || i + n > b.size) throw IllegalArgumentException("truncated CBOR")
            return b.copyOfRange(i, i + n).also { i += n }
        }
    }
}

/** Ring color of a profile, from its colr blob. */
object RingColor {
    /**
     * Returns 0x00RRGGBB, or null if the blob has no color.
     * Layout seen on firmware API 7 (docs/PROTOCOL.md): map[0][1][6] is the animation's RGB list;
     * map[1][9], when present, is the short list the app shows. The first entry is used.
     */
    fun fromColr(blob: ByteArray): Int? {
        val root = runCatching { Cbor.decode(blob) }.getOrNull() as? Map<*, *> ?: return null
        val shown = (root.key(1) as? Map<*, *>)?.key(9) as? ByteArray
        val anim = ((root.key(0) as? Map<*, *>)?.key(1) as? Map<*, *>)?.key(6) as? ByteArray
        val rgb = shown?.takeIf { it.size >= 3 } ?: anim?.takeIf { it.size >= 3 } ?: return null
        return ((rgb[0].toInt() and 0xFF) shl 16) or ((rgb[1].toInt() and 0xFF) shl 8) or (rgb[2].toInt() and 0xFF)
    }

    private fun Map<*, *>.key(k: Int): Any? = this[k.toLong()]
}
