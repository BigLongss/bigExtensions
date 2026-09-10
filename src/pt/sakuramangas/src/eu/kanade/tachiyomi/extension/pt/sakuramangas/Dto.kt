package eu.kanade.tachiyomi.extension.pt.sakuramangas

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okio.ByteString.Companion.decodeBase64
import java.io.IOException

@Serializable
internal class CatalogEnvelope(private val payload: String) {
    fun decode(): CatalogDto = Crypto.decodeCatalog(payload).parseAs()
}

@Serializable
internal class CatalogDto(
    private val success: Boolean,
    private val data: List<MangaDto>,
    private val hasMore: Boolean,
) {
    fun toMangasPage(baseUrl: String): MangasPage {
        check(success) { "Não foi possível carregar o catálogo." }
        return MangasPage(data.map { it.toSManga(baseUrl) }, hasMore)
    }
}

@Serializable
internal class MangaDto(
    @SerialName("titulo") private val title: String,
    @SerialName("url_slug") private val slug: String,
) {
    fun toSManga(baseUrl: String) = manga(title, "/obras/$slug", baseUrl)
}

@Serializable
internal class LatestDto(
    @SerialName("titulo") private val title: String,
    @SerialName("url_manga") private val url: String,
) {
    fun toSManga(baseUrl: String) = manga(title, url, baseUrl)
}

@Serializable
internal class DetailsDto(
    @SerialName("titulo") private val title: String,
    @SerialName("url") private val slug: String,
    @SerialName("autor") private val author: String? = null,
    @SerialName("sinopse") private val synopsis: String? = null,
    private val tags: List<String> = emptyList(),
    @SerialName("demografia") private val demographic: String? = null,
    @SerialName("classificacao") private val classification: String? = null,
    private val status: String? = null,
) {
    fun toSManga(baseUrl: String): SManga = manga(title, "/obras/$slug", baseUrl).apply {
        author = this@DetailsDto.author
        description = synopsis
        genre = (tags + listOfNotNull(demographic, classification)).filter(String::isNotBlank).distinct().joinToString()
        status = when (this@DetailsDto.status?.lowercase()) {
            "em andamento", "lançamento" -> SManga.ONGOING
            "concluído", "finalizado" -> SManga.COMPLETED
            "hiato", "em hiato" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

private fun manga(title: String, url: String, baseUrl: String): SManga {
    require(title.isNotBlank() && url.removePrefix("/obras/").isNotBlank()) { "Obra sem título ou endereço." }
    return SManga.create().apply {
        this.title = title
        this.url = url.trimEnd('/')
        thumbnail_url = "$baseUrl${this.url}/thumb_256.jpg"
    }
}

@Serializable
internal class ChaptersEnvelope(@SerialName("_enc") private val encoded: String) {
    fun decode(): ChaptersDto = Crypto.decodeChapters(encoded).parseAs()
}

@Serializable
internal class ChaptersDto(
    private val success: Boolean,
    @SerialName("has_more") val hasMore: Boolean,
    private val data: List<ChapterGroupDto>,
) {
    val size get() = data.size

    fun toChapters(): List<SChapter> {
        check(success) { "Não foi possível carregar os capítulos." }
        return data.flatMap { it.toChapters() }
    }
}

@Serializable
internal class ChapterGroupDto(
    @SerialName("numero") private val number: Float,
    @SerialName("versoes") private val versions: List<ChapterDto>,
) {
    fun toChapters() = versions.map { it.toSChapter(number) }
}

@Serializable
internal class ChapterDto(
    private val url: String,
    @SerialName("titulo") private val title: String? = null,
    private val timestamp: Long = 0,
    private val scans: List<ScanDto> = emptyList(),
) {
    fun toSChapter(number: Float) = SChapter.create().apply {
        require(this@ChapterDto.url.isNotBlank()) { "Capítulo sem endereço." }
        url = this@ChapterDto.url
        name = "Capítulo ${number.toString().removeSuffix(".0")}" + title?.takeIf(String::isNotBlank)?.let { " - $it" }.orEmpty()
        chapter_number = number
        date_upload = timestamp * 1000
        scanlator = scans.joinToString { it.name }
    }
}

@Serializable
internal class ScanDto(@SerialName("nome") val name: String)

@Serializable
internal class SignalDto(
    private val cid: Long,
    private val ts: Long,
    private val forceCaptcha: Boolean,
    private val reason: String,
    private val count: Int,
    private val touchPrimary: Boolean,
)

@Serializable
internal class ReaderDto(
    val status: String,
    private val data: JsonElement? = null,
) {
    fun verification(subtoken: String): ReaderVerificationDto = data?.parseAs<VerificationEnvelope>()?.decode(subtoken)
        ?: throw IOException("Resposta de verificação inválida.")

    fun toPages(subtoken: String, chapterUrl: HttpUrl, imageAuth: String): List<Page> {
        if (status != "success") {
            throw IOException("Abra este capítulo na WebView e conclua a verificação do site. Depois tente novamente.")
        }
        val reader = data?.parseAs<ReaderDataDto>() ?: throw IOException("Resposta do leitor sem páginas.")
        return reader.toPages(subtoken, chapterUrl, imageAuth)
    }
}

@Serializable
internal class ReaderDataDto(
    private val encryptedEphemeralKey: EphemeralKeyDto,
    private val encryptedImageKey: String,
    private val encryptedUrls: String,
) {
    fun toPages(subtoken: String, chapterUrl: HttpUrl, imageAuth: String): List<Page> {
        val ephemeralKey = encryptedEphemeralKey.decrypt(subtoken)
        val imageKey = Crypto.decrypt(encryptedImageKey, ephemeralKey, 1)
        val decoded = Crypto.decrypt(encryptedUrls, imageKey, 0).toString(Charsets.UTF_8).parseAs<JsonElement>()
        val urls = if (decoded is JsonArray) decoded.parseAs<List<String>>() else decoded.parseAs<PageRangeDto>().urls()
        return urls.mapIndexed { index, url ->
            val imageUrl = requireNotNull(chapterUrl.resolve(url)) { "Endereço de imagem inválido." }
                .newBuilder().fragment(imageAuth).build().toString()
            Page(index, url = chapterUrl.toString(), imageUrl = imageUrl)
        }
    }
}

@Serializable
internal class EphemeralKeyDto(
    private val cipher: String,
    private val payload: String,
    private val mode: Int? = null,
) {
    fun decrypt(subtoken: String) = Ciphers.decrypt(cipher, payload, subtoken, mode)
}

@Serializable
internal class PageRangeDto(
    private val type: String,
    private val base: String,
    private val pages: Int,
    private val pad: Int = 0,
    private val ext: String = ".jpg",
) {
    fun urls(): List<String> {
        require(type == "range" && pages >= 0) { "Lista de páginas inválida." }
        return (1..pages).map { "$base${it.toString().padStart(pad, '0')}$ext" }
    }
}

@Serializable
internal class FilterDataDto(val genres: List<String>, val themes: List<String>)

@Serializable
internal class VerificationEnvelope(
    private val encryptedEphemeralKey: EphemeralKeyDto? = null,
    private val encryptedPayloadKey: String,
    private val encryptedPayload: String,
) {
    fun decode(subtoken: String): ReaderVerificationDto {
        val ephemeralKey = encryptedEphemeralKey?.decrypt(subtoken) ?: subtoken.toByteArray(Charsets.ISO_8859_1)
        val key = Crypto.decrypt(encryptedPayloadKey, ephemeralKey, 1)
        return Crypto.decrypt(encryptedPayload, key, 0).toString(Charsets.UTF_8).parseAs()
    }
}

@Serializable
internal class ReaderVerificationDto(
    @SerialName("g") private val characters: String,
    @SerialName("m") private val positions: List<Int>,
    @SerialName("t") val token: String,
    @SerialName("k") private val key: String,
    @SerialName("w") private val wait: Long,
) {
    val waitMillis get() = wait.coerceIn(600L, 5000L)

    fun payload(startedAt: Long): String {
        require(positions.size in 3..6 && positions.distinct().size == positions.size && positions.all { it in characters.indices }) {
            "Verificação do leitor inválida. Atualize a extensão."
        }
        val answer = positions.joinToString("") { characters[it].toString() }
        val secret = key.decodeBase64()?.toByteArray()?.toString(Charsets.ISO_8859_1)
            ?: throw IOException("Chave de verificação inválida.")
        return Crypto.encrypt(VerificationAnswerDto(answer, startedAt, System.currentTimeMillis()).toJsonString(), secret)
    }
}

@Serializable
internal class VerificationAnswerDto(private val answer: String, private val renderedAt: Long, private val submittedAt: Long)
