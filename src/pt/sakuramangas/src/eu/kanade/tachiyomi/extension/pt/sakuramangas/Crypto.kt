package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object Crypto {
    private const val CATALOG_KEY = "S4kur4_Fl0w3r_K3y_S3cr3t_2026"
    private const val META_KEY = "SakuraKey"
    private const val CHAPTER_KEY = "SakuraCSS"

    fun decodeCatalog(payload: String): String = decodeBase64(payload).mapIndexed { i, byte ->
        val key = CATALOG_KEY[i % CATALOG_KEY.length].code
        ((byte.toInt() xor key) - key - i).toByte()
    }.toByteArray().toString(Charsets.UTF_8)

    fun decodeChapters(payload: String): String {
        val inner = decodeBase64(payload).mapIndexed { i, byte ->
            (byte.toInt() xor CHAPTER_KEY[i % CHAPTER_KEY.length].code).toByte()
        }.toByteArray().toString(Charsets.US_ASCII)
        return decodeBase64(inner).toString(Charsets.UTF_8)
    }

    fun decodeMeta(payload: String): String {
        val bytes = payload.decodeHex().toByteArray()
        val middle = (bytes.size + 1) / 2
        return ByteArray(bytes.size) { i ->
            val position = if (i % 2 == 0) i / 2 else middle + i / 2
            (bytes[position].toInt() xor META_KEY[i % META_KEY.length].code).toByte()
        }.toString(Charsets.US_ASCII)
    }

    fun proof(challenge: String, key: Long, userAgent: String): String {
        val parts = decodeBase64(challenge).toString(Charsets.US_ASCII).split('/')
        require(parts.size == 3) { "Desafio de acesso inválido." }
        var seed = key.toString().takeLast(9).toInt() xor 0xa5c3d2e1.toInt()
        val table = IntArray(256) { i ->
            seed = seed * 1664525 + 1013904223 + (i + 1) * 0x9e3779b9.toInt()
            val mixed = seed xor (seed ushr 13) xor ((i + 1) * 0x85ebca6b.toInt())
            ((mixed ushr 24) xor (mixed ushr 16) xor mixed xor i) and 255
        }
        val state = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
            0xcbbb9d5d.toInt(), 0x629a292a, 0x9159015a.toInt(), 0x152fecd8,
            0x67332667, 0x8eb44a87.toInt(), 0xdb0c2e0d.toInt(), 0x47b5481d,
        )
        var index = 0
        var carry = 0
        (parts[0] + userAgent + key + parts[2]).forEachIndexed { i, char ->
            index = (index + char.code + (carry and 255) + i * 3) and 15
            val factor = table[(state[index] xor char.code xor ((carry ushr 8) and 255) xor ((i * 157) and 255)) and 255]
            val mixed = state[(index + 1) and 15] xor (factor * 0x01010101) xor carry
            state[index] = Integer.rotateLeft(
                state[index] xor mixed xor (char.code * 257 + factor * 65537),
                ((factor xor i) and 15) + 1,
            )
            val neighbor = (index + 5) and 15
            state[neighbor] = Integer.rotateLeft(
                state[neighbor] + mixed + factor * 257 + (i + 1) * 40503,
                ((state[index] xor factor xor i) and 15) + 1,
            )
            carry = carry xor state[index] xor state[neighbor] xor Integer.rotateLeft(mixed, ((i + index) and 15) + 1)
        }
        for (i in state.indices) {
            val factor = table[(state[i] xor state[(i + 1) and 15] xor i) and 255]
            val mixed = state[i] xor state[(i + 5) and 15] xor (factor * 0x01010101)
            state[i] = Integer.rotateLeft(mixed + state[(i + 9) and 15] + (i + 1) * 40503, ((i + factor) and 15) + 1)
        }
        return (0 until 16 step 4).joinToString("") { i ->
            val mixed = ((state[i] xor Integer.rotateLeft(state[i + 1], 7)) + state[i + 2]) xor Integer.rotateLeft(state[i + 3], 11)
            Integer.toHexString(mixed).padStart(8, '0')
        }
    }

    fun decrypt(payload: String, secret: ByteArray, version: Int): ByteArray {
        val packet = decodeBase64(payload)
        if (packet.size < 3 || packet[0] != 75.toByte() || packet[1] != 49.toByte() || packet[2] != 51.toByte()) {
            return Kaguya.decrypt(payload, secret, version)
        }
        require(packet.size >= 32 && packet[3] == version.toByte()) { "Dados do leitor inválidos. Atualize a extensão." }
        val header = packet.copyOfRange(0, 4)
        val key = ("Kaguya13:key\u0000".toByteArray(Charsets.US_ASCII) + version.toByte() + secret)
            .toByteString().sha256().toByteArray()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet, 4, 12))
            updateAAD(header)
            doFinal(packet, 16, packet.size - 16)
        }
    }

    fun encrypt(value: String, secret: String): String = Kaguya.encrypt(value, secret)

    private fun decodeBase64(value: String): ByteArray = value.decodeBase64()?.toByteArray()
        ?: throw IOException("Resposta codificada inválida.")
}
