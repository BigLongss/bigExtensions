package eu.kanade.tachiyomi.extension.pt.sakuramangas

import android.webkit.JavascriptInterface
import app.cash.quickjs.QuickJs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

internal object Crypto {
    private const val CATALOG_KEY = "S4kur4_Fl0w3r_K3y_S3cr3t_2026"
    private const val META_KEY = "SakuraKey"
    private const val CHAPTER_KEY = "SakuraCSS"
    private val random = SecureRandom()

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
        var seed = key.toString().takeLast(9).toInt()
        val table = IntArray(256) { i ->
            seed = seed * 1664525 + 1013904223
            (seed xor i) and 255
        }
        val state = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        )
        var index = 0
        var carry = 0
        for (char in parts[0] + userAgent + key + parts[2]) {
            index = (index + char.code + carry) and 15
            val factor = table[(state[index] xor char.code) and 255]
            val mixed = state[index] xor (state[(index + 1) and 15] * factor)
            state[index] = Integer.rotateLeft(mixed, mixed and 31)
            carry += state[index]
        }
        return (0 until 16 step 4).joinToString("") { i ->
            val mixed = ((state[i] xor state[i + 1]) + state[i + 2]) xor state[i + 3]
            Integer.toHexString(mixed).padStart(8, '0')
        }
    }

    fun decipherKey(cipher: String, payload: String, subtoken: String): ByteArray {
        if (!cipher.equals("Gungnir", ignoreCase = true)) {
            throw IOException("Cifra $cipher não suportada. Atualize a extensão.")
        }
        val key = (subtoken + "gungnir_v5_spear").encodeUtf8().sha256().toByteArray()
        var state = 75
        return decodeBase64(payload).mapIndexed { i, byte ->
            val k = key[i % key.size].toInt() and 255
            val mode = (i + k) and 3
            state = (state + k + 31) and 255
            val bit = (k + i + state) and 7
            val swappedKey = ((k and 15) shl 4) or ((k and 240) shr 4)
            val value = (byte.toInt() and 255) xor (1 shl bit) xor swappedKey
            val mixed = when (mode) {
                0 -> value - state
                1 -> value + state
                2 -> value xor state
                else -> value.inv() xor state
            } and 255
            when (mode) {
                0 -> ((mixed and 85) shl 1) or ((mixed and 170) shr 1)
                1 -> ((mixed and 51) shl 2) or ((mixed and 204) shr 2)
                2 -> ((mixed and 15) shl 4) or ((mixed and 240) shr 4)
                else -> mixed.inv()
            }.toByte()
        }.toByteArray()
    }

    suspend fun decipherKey(cipher: String, payload: String, subtoken: String, script: suspend () -> String): ByteArray {
        if (cipher.equals("Gungnir", ignoreCase = true)) return decipherKey(cipher, payload, subtoken)

        val implementation = script()
        if (!Access.isAndroid) return decipherKeyOnJvm(cipher, payload, subtoken, implementation)

        val result = runWebView<String>(timeout = 10.seconds) {
            blockImages = true
            jsBridge("sakuraKey") { value ->
                if (value.startsWith("ok:")) {
                    resolve(value.removePrefix("ok:"))
                } else {
                    reject(IOException("Não foi possível decifrar a chave $cipher do capítulo."))
                }
            }
            onPageFinished {
                evaluateJs(
                    """
                    globalThis.CryptoUtils = {
                        sha256: async value => Array.from(new Uint8Array(await crypto.subtle.digest(
                            "SHA-256", new TextEncoder().encode(value)
                        )), byte => byte.toString(16).padStart(2, "0")).join(""),
                        hexToBytes: value => value.match(/../g).map(byte => parseInt(byte, 16))
                    };
                    $implementation
                    (async () => {
                        try {
                            const bytes = Array.from(atob(${payload.toJsonString()}), char => char.charCodeAt(0));
                            const key = await window.YggdrasilCipherImplementations[${cipher.uppercase(Locale.ROOT).toJsonString()}](bytes, ${subtoken.toJsonString()});
                            window.sakuraKey.post("ok:" + btoa(key));
                        } catch (_) {
                            window.sakuraKey.post("error");
                        }
                    })();
                    """.trimIndent(),
                )
            }
            loadData(
                "https://sakuramangas.org/",
                """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'">""",
            )
        }
        return decodeBase64(result)
    }

    private fun decipherKeyOnJvm(cipher: String, payload: String, subtoken: String, implementation: String): ByteArray = QuickJs.create().use { engine ->
        engine.set(
            "sakuraDigest",
            Digest::class.java,
            object : Digest {
                @JavascriptInterface
                override fun sha256(value: String): String = value.encodeUtf8().sha256().hex()
            },
        )
        engine.evaluate(
            """
            globalThis.window = globalThis;
            globalThis.CryptoUtils = {
                sha256: async value => sakuraDigest.sha256(value),
                hexToBytes: value => value.match(/../g).map(byte => parseInt(byte, 16))
            };
            $implementation
            globalThis.sakuraResult = null;
            window.YggdrasilCipherImplementations[${cipher.uppercase(Locale.ROOT).toJsonString()}](
                ${decodeBase64(payload).map { it.toInt() and 255 }.toJsonString()},
                ${subtoken.toJsonString()}
            ).then(key => {
                globalThis.sakuraResult = Array.from(key, char => char.charCodeAt(0).toString(16).padStart(2, "0")).join("");
            });
            """.trimIndent(),
        )
        val result = engine.evaluate("globalThis.sakuraResult") as? String
            ?: throw IOException("Não foi possível decifrar a chave $cipher do capítulo.")
        result.decodeHex().toByteArray()
    }

    interface Digest {
        @JavascriptInterface
        fun sha256(value: String): String
    }

    fun decrypt(payload: String, secret: ByteArray, version: Int): ByteArray {
        val packet = decodeBase64(payload)
        require(packet.size >= 32 && packet.copyOfRange(0, 4).contentEquals(packetHeader(version))) {
            "Dados do leitor inválidos. Atualize a extensão."
        }
        val cipher = cipher(Cipher.DECRYPT_MODE, secret, version, packet.copyOfRange(4, 16))
        return cipher.doFinal(packet, 16, packet.size - 16)
    }

    fun encrypt(value: String, secret: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, secret.toByteArray(), 0, iv)
        return (packetHeader(0) + iv + cipher.doFinal(value.toByteArray())).toByteString().base64()
    }

    private fun cipher(mode: Int, secret: ByteArray, version: Int, iv: ByteArray): Cipher {
        val key = ("Kaguya13:key\u0000".toByteArray() + version.toByte() + secret)
            .toByteString().sha256().toByteArray()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(packetHeader(version))
        }
    }

    private fun packetHeader(version: Int) = byteArrayOf(75, 49, 51, version.toByte())

    private fun decodeBase64(value: String): ByteArray = value.decodeBase64()?.toByteArray()
        ?: throw IOException("Resposta codificada inválida.")
}
