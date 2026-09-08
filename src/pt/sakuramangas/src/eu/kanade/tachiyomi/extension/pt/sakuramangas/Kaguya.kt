package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

internal object Kaguya {
    fun encrypt(value: String, secret: String): String = transform(
        value.toByteArray(Charsets.ISO_8859_1),
        secret.toByteArray(Charsets.ISO_8859_1),
        0,
        false,
    ).toByteString().base64()

    fun decrypt(payload: String, secret: ByteArray, version: Int): ByteArray = transform(
        requireNotNull(payload.decodeBase64()) { "Dados do leitor inválidos." }.toByteArray(),
        secret,
        version,
        true,
    )

    private fun transform(input: ByteArray, secret: ByteArray, version: Int, decrypt: Boolean): ByteArray {
        val key = if (secret.isEmpty()) byteArrayOf(0) else secret
        val initial = seed(key, version)
        val random = initial.copyOf()
        val table = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = ((next(random).toLong() and 0xffffffffL) % (i + 1)).toInt()
            val value = table[i]
            table[i] = table[j]
            table[j] = value
        }
        val inverse = IntArray(256)
        table.forEachIndexed { i, value -> inverse[value] = i }
        var a = initial[0] and 255
        var b = initial[1] and 255
        var c = initial[2] and 255
        var d = initial[3] and 255
        val selector = (initial[0] xor (initial[1] ushr 8) xor (initial[2] ushr 16) xor (initial[3] ushr 24) xor version) and 3
        return ByteArray(input.size) { i ->
            val value = input[i].toInt() and 255
            val k = key[i % key.size].toInt() and 255
            val mode = (selector + ((i ushr 6) and 3)) and 3
            val mixed = (a + b + d + k + i + selector * 13) and 255
            val shift = ((c + k + mode + i) and 7) + 1
            val result = if (decrypt) {
                when (mode) {
                    0 -> rotateRight((inverse[((value - (b xor c)) and 255) xor a] - d) and 255, shift) xor mixed
                    1 -> rotateLeft(inverse[((value - a) and 255) xor b] xor d, shift) - mixed
                    2 -> (rotateRight((inverse[((value xor a) - d) and 255] - b) and 255, shift) - mixed) xor c
                    else -> rotateLeft(inverse[((value xor d) - a) and 255] xor b, shift) + mixed
                }
            } else {
                when (mode) {
                    0 -> (table[(rotateLeft(value xor mixed, shift) + d) and 255] xor a) + (b xor c)
                    1 -> (table[rotateRight((value + mixed) and 255, shift) xor d] xor b) + a
                    2 -> (table[(rotateLeft(((value xor c) + mixed) and 255, shift) + b) and 255] + d) xor a
                    else -> (table[rotateRight((value - mixed) and 255, shift) xor b] + a) xor d
                }
            } and 255
            val plain = if (decrypt) result else value
            val encrypted = if (decrypt) value else result
            val feedback = (plain + encrypted + k + i + mode * 17) and 255
            val nextA = table[a xor feedback xor d]
            val nextB = (b + plain + mode * 29 + (encrypted xor k)) and 255
            val nextC = (c xor nextA xor encrypted xor k xor (mode * 41)) and 255
            d = table[(d + nextB + i + (plain xor encrypted)) and 255]
            a = nextA
            b = nextB
            c = nextC
            result.toByte()
        }
    }

    private fun seed(key: ByteArray, version: Int): IntArray {
        var a = 2738958700L.toInt() xor (version * 2654435769L.toInt())
        var b = 3355524772L.toInt() xor (version * 2135587861)
        var c = 2911926141L.toInt() xor (version * 2496678331L.toInt())
        var d = 2123724318 xor (version * 625341585)
        key.forEachIndexed { i, byte ->
            val mixed = (byte.toInt() and 255) + (i + 1) * 1831565813 + version * 2246822519L.toInt()
            a = Integer.rotateLeft(a + (mixed xor d), 5)
            b = Integer.rotateLeft(b xor mixed xor a, 9) + c
            c = Integer.rotateLeft(c + b + (mixed xor a), 13)
            d = Integer.rotateLeft(d xor c xor mixed, 18) + a
        }
        return intArrayOf(a, b, c, d)
    }

    private fun next(state: IntArray): Int {
        val result = Integer.rotateLeft(state[1] * 5, 7) * 9
        val mixed = state[1] shl 9
        state[2] = state[2] xor state[0]
        state[3] = state[3] xor state[1]
        state[1] = state[1] xor state[2]
        state[0] = state[0] xor state[3]
        state[2] = state[2] xor mixed
        state[3] = Integer.rotateLeft(state[3], 11)
        return result + 1831565813
    }

    private fun rotateLeft(value: Int, shift: Int): Int = ((value shl (shift and 7)) or (value ushr (8 - (shift and 7)))) and 255

    private fun rotateRight(value: Int, shift: Int): Int = ((value ushr (shift and 7)) or (value shl (8 - (shift and 7)))) and 255
}
