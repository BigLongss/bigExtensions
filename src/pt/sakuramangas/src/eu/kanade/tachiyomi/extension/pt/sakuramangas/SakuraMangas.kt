package eu.kanade.tachiyomi.extension.pt.sakuramangas

import android.webkit.WebSettings
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.Instant

@Source
abstract class SakuraMangas : KeiSource() {

    private val access = Access()

    private val webViewUserAgent by lazy {
        WebSettings.getDefaultUserAgent(applicationContext)
            .replace(WEBVIEW_PLATFORM, "; Android 10; K)")
            .replace(WEBVIEW_VERSION, "Chrome/")
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder {
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        set("Accept-Language", "pt-BR,pt;q=0.9")
        if (!Access.isAndroid) return this

        val majorVersion = webViewUserAgent.substringAfter("Chrome/").substringBefore('.')
        return set("User-Agent", webViewUserAgent)
            .set("Sec-CH-UA", "\"Google Chrome\";v=\"$majorVersion\", \"Chromium\";v=\"$majorVersion\"")
            .set("Sec-CH-UA-Mobile", "?1")
            .set("Sec-CH-UA-Platform", "\"Android\"")
            .set("Sec-Fetch-Site", "none")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-User", "?1")
            .set("Sec-Fetch-Dest", "document")
    }

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", getFilterList(null))

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = access.getPage(client, "$baseUrl/", headers, baseUrl).asJsoup()
        val token = document.selectFirst("meta[csrf-token]")?.attr("csrf-token")
            ?: document.requiredAttr("meta[name=csrf-token]", "content")
        val body = FormBody.Builder().add("csrf_home", token).build()
        val ajaxHeaders = headers.newBuilder().set("X-Requested-With", "XMLHttpRequest").build()
        val updates = client.post("$baseUrl/dist/sakura/models/home/__.home_ultimo.php", ajaxHeaders, body).parseAs<List<LatestDto>>()
        return MangasPage(updates.map { it.toSManga(baseUrl) }, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val document = access.getPage(client, "$baseUrl/obras/", headers, baseUrl).asJsoup()
        val ajaxHeaders = headers.newBuilder()
            .set("X-CSRF-TOKEN", document.requiredAttr("meta[name=csrf-token]", "content"))
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val body = FormBody.Builder()
            .add("search", query)
            .add("offset", ((page - 1) * PAGE_SIZE).toString())
            .add("limit", PAGE_SIZE.toString())
            .apply {
                val activeFilters = filters.ifEmpty { getFilterList(null) }
                activeFilters.filterIsInstance<BodyFilter>().forEach { it.addToBody(this) }
            }
            .build()
        return client.post("$baseUrl/dist/sakura/models/obras/obras__buscar.php", ajaxHeaders, body)
            .parseAs<CatalogEnvelope>().decode().toMangasPage(baseUrl)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "obras") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return details(mangaPage("/obras/$slug"))
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        if (!fetchDetails && !fetchChapters) return@coroutineScope SMangaUpdate(manga, chapters)
        val page = mangaPage(manga.url)
        val newDetails = async { if (fetchDetails) details(page) else manga }
        val newChapters = async { if (fetchChapters) chapters(page) else chapters }
        SMangaUpdate(newDetails.await(), newChapters.await())
    }

    private suspend fun mangaPage(url: String): AccessPage = accessPage("$baseUrl${url.trimEnd('/')}/", "manga-id", 8006199014741981L)

    private suspend fun details(page: AccessPage): SManga = client.post(
        "$baseUrl/dist/sakura/models/manga/..__obf__manga_info.php",
        page.headers,
        page.body().add("manga_id", page.id).build(),
    ).parseAs<DetailsDto>().toSManga(baseUrl)

    private suspend fun chapters(page: AccessPage): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var offset = 0
        do {
            val body = page.body()
                .add("manga_id", page.id)
                .add("offset", offset.toString())
                .add("limit", CHAPTER_PAGE_SIZE.toString())
                .add("order", "desc")
                .build()
            val batch = client.post("$baseUrl/dist/sakura/models/manga/..__obf__manga_capitulos.php", page.headers, body)
                .parseAs<ChaptersEnvelope>().decode()
            chapters += batch.toChapters()
            offset += batch.size
        } while (batch.hasMore && batch.size > 0)
        return chapters.distinctBy { it.url }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url.trimEnd('/')}/"
        val page = accessPage(chapterUrl, "chapter-id", 9006099254140970L)
        val token = page.document.requiredAttr("meta[token]", "token")
        val subtoken = page.document.requiredAttr("meta[subtoken]", "subtoken")
        val imageAuth = Crypto.decodeMeta(page.document.requiredAttr("meta[name=poly-auth]", "content"))
        val signal = SignalDto(page.id.toLong(), Instant.now().epochSecond, false, "normal", 0)
        val signalKey = "kaguya13-signal-v1:$subtoken:${page.id}:$token"
        val body = page.body()
            .add("action", "read")
            .add("chapter_id", page.id)
            .add("token", token)
            .add("reader_state", "")
            .add("client_signal_payload", Crypto.encrypt(signal.toJsonString(), signalKey))
            .build()
        return client.post("$baseUrl/dist/sakura/models/capitulo/__sectron__capitulos__read.php", page.headers, body)
            .parseAs<ReaderDto>().toPages(subtoken, chapterUrl.toHttpUrl(), imageAuth) {
                val scriptUrl = page.document.requiredAttr("script[src*=/__ciphers/]", "abs:src").toHttpUrl()
                require(scriptUrl.host == baseUrl.toHttpUrl().host && scriptUrl.encodedPath.startsWith("/dist/sakura/__ciphers/")) {
                    "Módulo de cifra inválido. Atualize a extensão."
                }
                client.get(scriptUrl, headers).use { it.body.string() }
            }
    }

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl!!.toHttpUrl()
        val auth = url.fragment?.split('|', limit = 2)
            ?: throw IOException("Reabra o capítulo para atualizar o acesso às imagens.")
        require(auth.size == 2) { "Autorização de imagem inválida." }
        val imageHeaders = headers.newBuilder()
            .set("Referer", page.url)
            .set("Accept", "image/webp,image/svg+xml,image/*,*/*;q=0.8")
            .set("Content-Type", "application/octet-stream")
            .set("X-Request", "45931497111523530")
            .set("X-Sigma", "v5-fetch")
            .set("X-Harry", "patronum")
            .set("X-Sakura", "sectron")
            .set(auth[0], auth[1])
            .build()
        return GET(url.newBuilder().fragment(null).build(), imageHeaders)
    }

    private suspend fun accessPage(url: String, idAttribute: String, key: Long): AccessPage = access.getPage(client, url, headers, baseUrl).use { response ->
        val userAgent = response.request.header("User-Agent") ?: headers["User-Agent"]
            ?: throw IOException("User-Agent não disponível.")
        val document = response.asJsoup()
        val challenge = Crypto.decodeMeta(document.requiredAttr("meta[name=header-challenge]", "content"))
        val authHeaders = headers.newBuilder()
            .set("User-Agent", userAgent)
            .set("Referer", url)
            .set("X-CSRF-TOKEN", document.requiredAttr("meta[name=csrf-token]", "content"))
            .set("X-Requested-With", "XMLHttpRequest")
            .set("X-Client-Signature", "FTY9K-SY6WY-96LKPK")
            .set("X-Verification-Key-1", "a1b2c3d4-g0h2-f3j4k5l6m7n7-7890-e5f5")
            .set("X-Verification-Key-2", "z9y8x7w6-v2u3-3210t9s9-r7q6p5o4n3n2")
            .build()
        AccessPage(document, document.requiredAttr("meta[$idAttribute]", idAttribute), authHeaders, challenge, Crypto.proof(challenge, key, userAgent))
    }

    private class AccessPage(
        val document: Document,
        val id: String,
        val headers: Headers,
        private val challenge: String,
        private val proof: String,
    ) {
        fun body() = FormBody.Builder().add("challenge", challenge).add("proof", proof)
    }

    private fun Document.requiredAttr(selector: String, attribute: String): String = selectFirst(selector)?.attr(attribute)?.takeIf(String::isNotBlank)
        ?: throw IOException("Não foi possível ler os dados do site. Atualize a extensão.")

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = access.getPage(client, "$baseUrl/obras/", headers, baseUrl).asJsoup()
        fun tags(id: String) = document.select("#$id .genre-chip[data-value]").map { it.attr("data-value") }
        return FilterDataDto(tags("generos-badges"), tags("temas-badges")).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        buildList {
            add(SortFilter())
            add(SelectFilter("Demografia", "demography", arrayOf("Shounen", "Shoujo", "Seinen", "Josei")))
            add(SelectFilter("Classificação", "classification", arrayOf("Livre", "Sugestivo", "Erótico", "Pornográfico")))
            add(SelectFilter("Estado", "status", arrayOf("Em andamento", "Concluído", "Finalizado")))
            add(AuthorFilter())
            data?.parseAs<FilterDataDto>()?.let {
                add(Filter.Separator())
                add(TagsFilter("Gêneros", it.genres))
                add(TagsFilter("Temas", it.themes))
            }
        },
    )

    companion object {
        private val WEBVIEW_PLATFORM = Regex("; Android .*?\\)")
        private val WEBVIEW_VERSION = Regex("Version/.* Chrome/")
        private const val PAGE_SIZE = 30
        private const val CHAPTER_PAGE_SIZE = 100
    }
}
