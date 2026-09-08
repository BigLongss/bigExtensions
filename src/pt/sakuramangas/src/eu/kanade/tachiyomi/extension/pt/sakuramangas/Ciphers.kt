package eu.kanade.tachiyomi.extension.pt.sakuramangas

import android.webkit.JavascriptInterface
import app.cash.quickjs.QuickJs
import keiyoushi.utils.toJsonString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.Locale

internal object Ciphers {
    fun decrypt(cipher: String, payload: String, secret: String, mode: Int?): ByteArray = QuickJs.create().use { engine ->
        val bytes = payload.decodeBase64()?.toByteArray() ?: throw IOException("Chave de capítulo inválida.")
        engine.set(
            "sakuraDigest",
            Digest::class.java,
            object : Digest {
                @JavascriptInterface
                override fun sha256(value: String): String = value.encodeUtf8().sha256().hex()

                @JavascriptInterface
                override fun sha512(value: String): String = value.encodeUtf8().sha512().hex()
            },
        )
        engine.evaluate(implementation)
        val result = engine.evaluate(
            "decipher(" + cipher.uppercase(Locale.ROOT).toJsonString() + "," +
                bytes.map { it.toInt() and 255 }.toJsonString() + "," + secret.toJsonString() + "," + mode +
                ").map(value => value.toString(16).padStart(2, '0')).join('')",
        ) as? String ?: throw IOException("Não foi possível decifrar a chave do capítulo.")
        result.decodeHex().toByteArray()
    }

    interface Digest {
        @JavascriptInterface
        fun sha256(value: String): String

        @JavascriptInterface
        fun sha512(value: String): String
    }

    private val implementation = """
        function decipherSleipnir(input, key) {
            const values = input.slice();
            for (let index = 0; index <= values.length - 8; index += 8) {
                const shift = (key[Math.floor(index / 8) % 32] % 7) + 1;
                const block = values.slice(index, index + 8);
                for (let i = 0; i < 8; i++) values[index + i] = block[(i + 8 - shift) % 8];
            }
            for (let i = 1; i < values.length; i++) values[i] = (values[i] ^ values[i - 1]) & 255;
            for (let i = 0; i < values.length; i++) values[i] = ((values[i] - key[i % 32]) * (key[i % 32] & 1 ? 171 : 205)) & 255;
            for (let index = 0; index < values.length - 3; index += 4) {
                const block = values.slice(index, index + 4).map((value, i) => value ^ key[(index + i) % 32]);
                const order = [
                    [0, 2, 1, 3],
                    [3, 1, 2, 0],
                    [2, 0, 3, 1],
                    [1, 3, 0, 2],
                ][key[index % 32] & 3];
                for (let i = 0; i < 4; i++) values[index + i] = block[order[i]];
            }
            return values;
        }
        function rotateLeft(value, shift) {
            shift &= 7;
            return ((value << shift) | (value >>> (8 - shift))) & 255;
        }
        function rotateRight(value, shift) {
            shift &= 7;
            return ((value >>> shift) | (value << (8 - shift))) & 255;
        }
        function reverseBits(value) {
            let result = 0;
            for (let bit = 0; bit < 8; bit++) result |= ((value >>> bit) & 1) << (7 - bit);
            return result;
        }
        function gray(value) {
            return (value ^ (value >>> 1)) & 255;
        }
        function inverseGray(value) {
            value ^= value >>> 1;
            value ^= value >>> 2;
            value ^= value >>> 4;
            return value & 255;
        }
        function swapNibbles(value) {
            return ((value & 15) << 4) | ((value & 240) >>> 4);
        }
        function permuteBits(value, order) {
            return order.reduce((result, bit, index) => result | (((value >>> bit) & 1) << index), 0);
        }
        function masterTransform(input, secret, mode) {
            if (mode == null) return input;
            if (!Number.isInteger(mode) || mode < 1 || mode > 7) throw Error('Invalid master mode');
            const key = secret ? Array.from(secret, (c) => c.charCodeAt(0) & 255) : [0];
            let previous = mode === 5 ? 165 : 64;
            return input.map((encrypted, index) => {
                const k = key[index % key.length];
                let value;
                switch (mode) {
                    case 1:
                        value = rotateRight((encrypted - k - 19) & 255, (k & 7) + 1) ^ k ^ (index & 255);
                        break;
                    case 2:
                        value = (swapNibbles(encrypted ^ rotateLeft(k, 2)) - index * 3 - k) & 255;
                        break;
                    case 3: {
                        const mixed = rotateRight(encrypted ^ ((k * 3 + 43) & 255), (index & 7) + 1);
                        const high = (mixed >>> 4) & 15;
                        const low = (mixed & 15) ^ ((high + k + index) & 15);
                        value = (low << 4) | high;
                        break;
                    }
                    case 4:
                        value = (((rotateLeft((encrypted - 23) & 255, (k & 7) + 1) ^ ((k << 1) & 255)) - k - index) * 171) & 255;
                        break;
                    case 5:
                        value = ((rotateRight(encrypted, (previous & 7) + 1) - index - k) & 255) ^ previous ^ k;
                        previous = encrypted;
                        break;
                    case 6:
                        value = reverseBits(((encrypted ^ ((k + 91) & 255)) - index * 5 - k) & 255) ^ k;
                        break;
                    case 7:
                        value = (rotateLeft(encrypted ^ k, (index & 7) + 1) ^ rotateLeft(previous, 3)) - previous - k;
                        previous = (previous + encrypted + k + 45) & 255;
                        break;
                }
                return value & 255;
            });
        }
        const cipherSettings = {
            FENRIR: [256, 'fenrir_v6_wolf', 77],
            AEGIR: [512, 'aegir_v6_tide', 146],
            HEIMDALL: [256, 'heimdall_v6_watch', 209],
            LOKI: [512, 'loki_v6_trick', 38],
            ODIN: [256, 'odin_v6_allfather', 184],
            FREYA: [512, 'freya_v6_seeress', 115],
            THOR: [256, 'thor_v6_storm', 228],
            NJORD: [512, 'njord_v6_sea', 58, 118],
            TYR: [256, 'tyr_v6_oath', 199],
            HEL: [512, 'hel_v6_underworld', 88],
            BALDUR: [256, 'baldur_v6_light', 171, 60],
            SKOLL: [512, 'skoll_v6_chaser', 133],
            HATI: [256, 'hati_v6_chaser', 31],
            VIDAR: [512, 'vidar_v6_silent', 216],
            VALI: [256, 'vali_v6_revenge', 100],
            BRAGI: [512, 'bragi_v6_poet', 190, 25],
            IDUNN: [256, 'idunn_v6_keeper', 64],
            BIFROST: [256, 'bifrost_v5_bridge'],
            DRAUPNIR: [256, 'draupnir_v5_ring'],
            FAFNIR: [512, 'fafnir_v5_dragon', 109],
            GJALLARHORN: [512, 'gjallarhorn_v5_horn', 17, 34, 51],
            GUNGNIR: [256, 'gungnir_v5_spear', 75],
            HUGINN: [512, 'huginn_v5_raven'],
            MJOLNIR: [512, 'mjolnir_v5_poly'],
            MUNINN: [512, 'muninn_v5_memory', 115, 145, 181],
            RAGNAROK: [512, 'ragnarok_v5_twilight', 170],
            RATATOSKR: [256, 'ratatoskr_v5_squirrel'],
            SKADI: [512, 'skadi_v5_winter', 222],
            SLEIPNIR: [256, 'sleipnir_v5_steed'],
            VALKYRIE: [256, 'valkyrie_v5_gate'],
            YMIR: [256, 'ymir_v5_primordial', 153],
        };
        function decipher(cipher, input, secret, masterMode) {
            const settings = cipherSettings[cipher];
            if (!settings) throw Error('Unsupported cipher: ' + cipher);
            input = masterTransform(input, secret, masterMode);
            const hash = settings[0] === 256 ? sakuraDigest.sha256(secret + settings[1]) : sakuraDigest.sha512(secret + settings[1]);
            const key = hash.match(/../g).map((value) => parseInt(value, 16));
            if (cipher === 'SLEIPNIR') return decipherSleipnir(input, key);
            let state = settings[2] || 0,
                previous = settings[3] || 0,
                older = settings[4] || 0,
                register = 44257;
            const output = [];
            for (let index = 0; index < input.length; index++) {
                const encrypted = input[index],
                    k = key[index % key.length];
                let mode, value, mixed, left, right, second, nextKey;
                switch (cipher) {
                    case 'FENRIR':
                        mode = (k ^ index ^ state) & 3;
                        value = (encrypted - index * 7 - 49) & 255;
                        value ^= rotateLeft(k, mode + 1);
                        value = (value - rotateLeft(state, (index & 7) + 1)) & 255;
                        value =
                            mode === 0
                                ? rotateRight(value, (k & 7) + 1)
                                : mode === 1
                                  ? (value - k - index) & 255
                                  : mode === 2
                                    ? rotateLeft(value, (state & 7) + 1)
                                    : ~value & 255;
                        value ^= k ^ state;
                        state = (state + encrypted + k + 17) & 255;
                        break;
                    case 'AEGIR':
                        value = rotateRight((encrypted - index - state - 21) & 255, ((index + k) & 7) + 1) ^ ((k + state) & 255);
                        left = value >>> 4;
                        right = value & 15;
                        for (let round = 3; round >= 0; round--) {
                            const next = (right ^ ((left + (((k >>> (round * 2)) + state + index + round * 3) & 15)) & 15)) & 15;
                            right = left;
                            left = next;
                        }
                        value = (left << 4) | right;
                        state = (state ^ encrypted ^ ((k << 1) & 255)) & 255;
                        break;
                    case 'HEIMDALL':
                        mode = (k + index) & 3;
                        value = gray(rotateRight(encrypted ^ ((k * 9 + state) & 255), mode + 1));
                        value = ((value * [171, 205, 183, 57][mode] - k - index) & 255) ^ state;
                        state = (state + encrypted + (k ^ index)) & 255;
                        break;
                    case 'LOKI':
                        mode = (k ^ index ^ state) & 3;
                        value = rotateRight(encrypted ^ ((k + index * 5 + 47) & 255), (state & 7) + 1);
                        left = value >>> 4;
                        right = value & 15;
                        if (mode & 1) [left, right] = [right, left];
                        right = ((right - (state & 15)) * [11, 13, 7, 9][mode]) & 15;
                        left = (left - ((k >>> 4) & 15) - mode - index) & 15;
                        value = (left << 4) | right;
                        state = (state ^ rotateLeft(encrypted, mode + 1) ^ k) & 255;
                        break;
                    case 'ODIN':
                        mode = ((k >>> 2) ^ index) & 3;
                        value = ((encrypted - mode * k - 8) & 255) ^ rotateRight(state, mode + 1);
                        value = (rotateRight(value, ((index + mode) % 7) + 1) - state * 3 - index * 9 - 8) & 255;
                        value = ((value ^ rotateLeft(k, mode + 2)) - state - k) & 255;
                        state = (encrypted + rotateLeft(state, 3) + k) & 255;
                        break;
                    case 'FREYA':
                        mode = (k + state + index) & 3;
                        value = (rotateRight(encrypted ^ ((state * 5 + 93) & 255), (k & 7) + 1) - index * 17 - k) & 255;
                        value =
                            permuteBits(
                                value,
                                [
                                    [7, 6, 5, 4, 3, 2, 1, 0],
                                    [4, 0, 5, 1, 6, 2, 7, 3],
                                    [1, 4, 0, 5, 3, 7, 2, 6],
                                    [5, 3, 1, 0, 6, 7, 4, 2],
                                ][mode],
                            ) ^
                            k ^
                            state;
                        state = (state + encrypted + (k ^ index)) & 255;
                        break;
                    case 'THOR':
                        state = ((state << 1) | (((state >>> 7) ^ (state >>> 5) ^ (state >>> 4) ^ (state >>> 3)) & 1)) & 255;
                        mode = (state ^ k ^ index) & 3;
                        value = rotateLeft((encrypted - (state ^ k) - 113) & 255, mode + 1);
                        value = (((value - k - index) * [171, 205, 183, 57][mode]) & 255) ^ state;
                        state = (state ^ encrypted ^ ((k << 1) & 255)) & 255;
                        break;
                    case 'NJORD':
                        mode = (k + state + previous + index) & 3;
                        value = rotateRight(encrypted ^ ((k + state) & 255), (previous & 7) + 1);
                        left = value >>> 4;
                        right = value & 15;
                        for (let round = 1; round >= 0; round--) {
                            if (mode === round) [left, right] = [right, left];
                            const next = right ^ ((left + ((previous >>> 4) & 15) + mode + round) & 15);
                            left = (left - ((k >>> (round * 4)) & 15) - (state & 15) - round) & 15;
                            right = next;
                        }
                        value = (left << 4) | right;
                        state = (state + encrypted + k + 90) & 255;
                        previous = (previous ^ ((encrypted + state + index) & 255)) & 255;
                        break;
                    case 'TYR':
                        mode = (k ^ state) & 3;
                        mixed = encrypted ^ rotateRight(k, mode + 1);
                        value = rotateRight((mixed - k - state - mode * 29) & 255, ((k + mode) & 7) + 1);
                        if (mode === 2) value = ~value & 255;
                        if (mode === 3) value = swapNibbles(value);
                        value = gray(value) ^ index;
                        state = (state + (encrypted ^ mixed) + 38) & 255;
                        break;
                    case 'HEL':
                        mode = (k + index + state) & 3;
                        value = rotateRight((encrypted - k - 12) & 255, (index & 7) + 1) ^ rotateLeft(state, mode + 2);
                        value = inverseGray((mode & 1) === 0 ? (value - state) & 255 : (value + state) & 255) ^ k;
                        state = (state ^ encrypted ^ rotateLeft(k, 3)) & 255;
                        break;
                    case 'BALDUR':
                        mode = (k + previous) & 3;
                        value = ((encrypted ^ rotateLeft(previous, mode + 1)) - k - state) & 255;
                        value =
                            mode === 0
                                ? swapNibbles(value)
                                : mode === 1
                                  ? rotateRight(value, 2)
                                  : mode === 2
                                    ? rotateLeft(value, 3)
                                    : ~value & 255;
                        value ^= previous;
                        state = (state + encrypted + k + 112) & 255;
                        previous = encrypted;
                        break;
                    case 'SKOLL':
                        mode = (k ^ index ^ state) & 3;
                        value = rotateRight(encrypted ^ ((state + k + 25) & 255), ((k + mode) & 7) + 1);
                        value = (((value - index * index - state) * [171, 205, 183, 57][mode]) & 255) ^ k;
                        state = (state + encrypted + k * 3 + index) & 255;
                        break;
                    case 'HATI':
                        mode = ((state >>> 2) ^ k ^ index) & 3;
                        value = rotateRight((encrypted - k - 98) & 255, (state & 7) + 1);
                        left = value & 15;
                        right = value >>> 4;
                        for (let round = 1; round >= 0; round--) {
                            if (mode === 3 - round) [left, right] = [right, left];
                            const next = right ^ ((left * (round + 3) + ((k >>> (round * 2)) & 15) + (state & 15) + index) & 15);
                            right = left;
                            left = next;
                        }
                        value = (right << 4) | left;
                        state = (state ^ encrypted ^ k ^ index) & 255;
                        break;
                    case 'VIDAR':
                        mode = (state + k + index) & 3;
                        value = rotateRight(encrypted ^ ((state + k + 62) & 255), mode + 1);
                        value = ((value - state - index * 3) * [163, 197, 241, 27][mode]) & 255;
                        value = rotateLeft(value ^ ((k << mode) & 255), (index % 7) + 1);
                        state = (encrypted + k + index) & 255;
                        break;
                    case 'VALI':
                        if (index + 1 >= input.length) {
                            value = ((rotateRight(encrypted, (k & 7) + 1) - index - 117) & 255) ^ k ^ state;
                            break;
                        }
                        nextKey = key[(index + 1) % key.length];
                        mode = (k + nextKey + index) & 3;
                        left = rotateRight(encrypted, (k & 7) + 1);
                        right = rotateLeft(input[index + 1], (nextKey & 7) + 1);
                        mixed = (right - k - index - 117) & 255;
                        second = left ^ nextKey ^ ((index + mode) * 17);
                        if (mode === 0) {
                            value = 2 * mixed - second;
                            second -= mixed;
                        } else if (mode === 1) {
                            value = -5 * mixed + 3 * second;
                            second = 2 * mixed - second;
                        } else if (mode === 2) {
                            value = mixed - second;
                            second = 3 * second - 2 * mixed;
                        } else {
                            value = -mixed + 2 * second;
                            second = 3 * mixed - 5 * second;
                        }
                        output.push((value ^ k) & 255, (second ^ nextKey) & 255);
                        state = (state + right + left + k + nextKey) & 255;
                        index++;
                        continue;
                    case 'BRAGI':
                        mode = (k + state + index) & 3;
                        value = rotateRight(encrypted ^ ((state * 3 + previous + k) & 255), ((index + mode) % 7) + 1);
                        value = inverseGray((value - previous - k - mode * 19) & 255) ^ rotateLeft(state, 1);
                        state = (state + encrypted + index) & 255;
                        previous = (previous ^ rotateLeft(encrypted, mode + 1)) & 255;
                        break;
                    case 'IDUNN':
                        if (index + 1 >= input.length) {
                            value = ((rotateRight(encrypted ^ k, (state & 7) + 1) - state - 15) & 255) ^ k;
                            break;
                        }
                        nextKey = key[(index + 1) % key.length];
                        mode = (k + nextKey + index + state) & 3;
                        left = rotateRight(encrypted ^ nextKey, (state & 7) + 1);
                        right = rotateLeft(input[index + 1] ^ k, (k & 7) + 1);
                        mixed = (left - k - state - 15) & 255;
                        second = (right - nextKey - index) & 255;
                        {
                            const nibbles = [mixed >>> 4, mixed & 15, second >>> 4, second & 15];
                            const order = [
                                [0, 1, 2, 3],
                                [2, 0, 3, 1],
                                [1, 3, 0, 2],
                                [3, 2, 1, 0],
                            ][mode];
                            const result = order.map((i) => nibbles[i]);
                            output.push((result[0] << 4) | result[1], (result[2] << 4) | result[3]);
                        }
                        state = (state + left + right + k + nextKey) & 255;
                        index++;
                        continue;
                    case 'BIFROST': {
                        second = input[index + 1] || 0;
                        nextKey = key[(index + 1) % key.length];
                        mode = (k ^ nextKey) & 3;
                        left = rotateRight(((encrypted - index - mode) & 255) ^ (~nextKey & 255), (k % 5) + 1);
                        if (index + 1 >= input.length) {
                            value = mode === 0 ? left - k : mode === 1 ? left ^ k : mode === 2 ? (left - k) * 171 : (~left & 255) ^ k;
                            break;
                        }
                        right = rotateLeft(((second + index + mode) & 255) ^ (~k & 255), (nextKey % 5) + 1);
                        let a, b;
                        if (mode === 0) {
                            a = (left - k) & 255;
                            b = (right + nextKey) & 255;
                            right = (b - a) & 255;
                            left = (2 * a - b) & 255;
                        } else if (mode === 1) {
                            right ^= (left + nextKey) & 255;
                            left ^= right ^ k;
                        } else if (mode === 2) {
                            a = (left - k) & 255;
                            b = (right - nextKey) & 255;
                            left = (a - b) & 255;
                            right = (b - 2 * left) & 255;
                        } else {
                            left = (~left & 255) ^ k;
                            right = (~right & 255) ^ nextKey;
                        }
                        output.push(left & 255, right & 255);
                        index++;
                        continue;
                    }
                    case 'DRAUPNIR':
                        mode = (k + index) & 3;
                        value = k & 4 ? swapNibbles(encrypted) : ((encrypted & 51) << 2) | ((encrypted & 204) >>> 2);
                        value ^= key[((index % key.length) + mode + 1) % key.length];
                        key[index % key.length] = (k + value) & 255;
                        mixed = mode % 2 === 0 ? Math.floor((index * (index + 1)) / 2) : index * index + 3;
                        value = (((value - (mixed & 255) - 7) & 255) * [197, 241, 27, 167][mode]) & 255;
                        value ^= k;
                        break;
                    case 'FAFNIR':
                        mode = state & 3;
                        state =
                            (mode === 0
                                ? state * 17 + k
                                : mode === 1
                                  ? state * 31 - k
                                  : mode === 2
                                    ? (state ^ k) * 13
                                    : state + k + 101) & 255;
                        mixed = encrypted ^ (state & (240 >>> mode));
                        value = (mode % 2 === 0 ? k - mixed : mixed - k) & 255;
                        value = rotateRight(value, ((value.toString(2).split('1').length - 1) % 7) + 1) ^ state;
                        break;
                    case 'GJALLARHORN':
                        mode = (k ^ state) & 3;
                        value = (mode === 3 ? rotateRight(encrypted, 3) : encrypted) ^ state ^ k;
                        value = k > (previous % 128) + 64 ? ~value & 255 : value ^ 85;
                        value = (mode % 2 === 0 ? value - k : value + k) & 255;
                        mixed =
                            mode === 0
                                ? state + previous + older
                                : mode === 1
                                  ? state ^ previous ^ older
                                  : mode === 2
                                    ? 2 * state + previous - older
                                    : (~state & 255) + previous;
                        value ^= mixed & 255;
                        older = previous;
                        previous = state;
                        state = encrypted;
                        break;
                    case 'GUNGNIR':
                        mode = (index + k) & 3;
                        state = (state + k + 31) & 255;
                        value = encrypted ^ (1 << ((k + index + state) & 7)) ^ swapNibbles(k);
                        value =
                            (mode === 0 ? value - state : mode === 1 ? value + state : mode === 2 ? value ^ state : ~value ^ state) & 255;
                        value =
                            mode === 0
                                ? ((value & 85) << 1) | ((value & 170) >>> 1)
                                : mode === 1
                                  ? ((value & 51) << 2) | ((value & 204) >>> 2)
                                  : mode === 2
                                    ? swapNibbles(value)
                                    : ~value & 255;
                        break;
                    case 'HUGINN':
                        mode = (k ^ index) & 3;
                        value = mode > 1 ? ~encrypted & 255 : encrypted ^ k;
                        if (index % 2 === 0) value ^= value >>> 1;
                        value ^= value >>> 2;
                        value ^= value >>> 4;
                        value = ((value - index * 17 - k) & 255) ^ k;
                        break;
                    case 'MJOLNIR':
                        mode = (k ^ index) & 3;
                        value = rotateRight((encrypted - index * 13 - (k ^ mode)) & 255, ((k + index) % 7) + 1);
                        value = mode % 2 === 0 ? ~(value ^ (k & 1 ? 170 : 85)) & 255 : value ^ k;
                        value =
                            mode === 0
                                ? (value - k) * 181
                                : mode === 1
                                  ? ((value * 197) & 255) ^ k
                                  : mode === 2
                                    ? value + k
                                    : (value ^ (~k & 255)) * 225;
                        break;
                    case 'MUNINN':
                        mode = (k ^ state) & 3;
                        value = (((encrypted ^ (state & (170 >>> mode))) - k) * [51, 17, 15, 89][mode]) & 255;
                        mixed =
                            [
                                [0, 2, 3, 5],
                                [0, 1, 4, 6],
                                [0, 3, 5, 7],
                                [1, 2, 5, 8],
                            ][mode].reduce((bit, shift) => bit ^ (register >>> shift), 0) & 1;
                        register = ((register >>> 1) | (mixed << 15)) & 65535;
                        value = (value ^ (register & 255)) + older + k;
                        older = previous;
                        previous = state;
                        state = encrypted;
                        break;
                    case 'RAGNAROK':
                        mode = k & 7;
                        mixed = (((encrypted ^ (~k & 255)) - 101) * 27) & 255;
                        value = mixed ^ state;
                        state = (state + mixed + k) & 255;
                        value =
                            mode === 0
                                ? value - k
                                : mode === 1
                                  ? value ^ k ^ 90
                                  : mode === 2
                                    ? -value
                                    : mode === 3
                                      ? swapNibbles(value)
                                      : mode === 4
                                        ? value ^ (~k & 255)
                                        : mode === 5
                                          ? value + k
                                          : mode === 6
                                            ? rotateRight(value, 2)
                                            : value ^ (index & 255);
                        break;
                    case 'RATATOSKR': {
                        mode = (k + index) & 3;
                        value = k & 128 ? rotateRight(encrypted, (index % 5) + 1) : rotateLeft(encrypted, (index % 5) + 1);
                        value = (mode % 2 === 0 ? value ^ k ^ (index & 255) : (value ^ index) - k) & 255;
                        left = (((value >>> 4) - (k & 15)) * [11, 13, 7, 3][mode]) & 15;
                        right = (((value & 15) - (k >>> 4)) * [13, 7, 9, 5][mode]) & 15;
                        mixed = (left << 4) | right;
                        const chunks = [mixed >>> 6, (mixed >>> 4) & 3, (mixed >>> 2) & 3, mixed & 3];
                        const order = [
                            [3, 2, 1, 0],
                            [0, 1, 2, 3],
                            [2, 3, 0, 1],
                            [1, 0, 3, 2],
                        ][mode];
                        value = order.reduce((out, position) => (out << 2) | chunks[position], 0);
                        break;
                    }
                    case 'SKADI':
                        mode = state & 3;
                        value = rotateRight(encrypted ^ k, mode + 1) ^ (((index * 37) ^ (k * 3)) & 255);
                        mixed = (k ^ mode) | 1;
                        for (left = 1; left < 256 && ((mixed * left) & 255) !== 1; left += 2) {}
                        value = (value * left) & 255;
                        value =
                            mode === 0 ? value ^ state : mode === 1 ? value - state : mode === 2 ? value + state : (~value & 255) ^ state;
                        state = encrypted;
                        break;
                    case 'VALKYRIE': {
                        mode = k & 3;
                        value = (rotateRight(encrypted, ((k >>> 2) & 3) + 1) - index * 11 - k) & 255;
                        value ^= k ^ (60 << (mode & 1));
                        const chunks = [(value >>> 6) & 3, (value >>> 4) & 3, (value >>> 2) & 3, value & 3];
                        const order = [
                            [1, 3, 0, 2],
                            [2, 0, 3, 1],
                            [0, 2, 1, 3],
                            [3, 1, 2, 0],
                        ][mode];
                        value = order.reduce((out, position) => (out << 2) | chunks[position], 0);
                        break;
                    }
                    case 'YMIR':
                        mode = (state ^ k) & 3;
                        state =
                            (mode === 0
                                ? state * state + k + 5
                                : mode === 1
                                  ? state * 3 + k * k + 11
                                  : mode === 2
                                    ? (state ^ k) * 17 + 3
                                    : 256 + state - k * 7) & 255;
                        value = ((rotateRight(encrypted, (state % 7) + 1) - k - index * (mode + 1)) & 255) ^ k;
                        value = swapNibbles(value) - state;
                        break;
                }
                output.push(value & 255);
            }
            return output;
        }
    """.trimIndent()
}
