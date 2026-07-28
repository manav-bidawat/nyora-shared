package com.nyora.hasan72341.shared.extension

import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaPage
import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.model.MangaState
import com.nyora.hasan72341.shared.model.MangaTag
import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.net.buildOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import com.nyora.hasan72341.shared.model.MangaSource as NyoraMangaSource

/**
 * Native source for the MangaNato / Mangakakalot platform.
 *
 * Both sites now run the same rewritten stack, and the bundled `MangaboxParser` matches
 * none of it any more:
 *
 * - the catalogue moved to `/manga-list/<hot|latest>-manga`, and the old `/genre` path the
 *   parser builds for an unfiltered browse answers 404 (`/genre/all` is the live one);
 * - the chapter list left the title page entirely. `ul.row-content-chapter` is gone,
 *   replaced by `<div id="chapter-list-container">` filled from a JSON API,
 *   `/api/manga/<slug>/chapters` — which is why details returned a title with zero
 *   chapters and the manga could be opened but never read;
 * - cover and page images are on `*.2xstorage.com` and 403 without a Referer of the site.
 *
 * The JSON API is the good news: it returns names, slugs, numbers and timestamps directly,
 * so chapters no longer depend on scraping at all. It is paginated by `offset`/`limit`
 * though, and its 50-item default silently truncates long series, so it has to be walked.
 *
 * The two domains are the same deployment, so one implementation serves both. Mangakakalot
 * puts Cloudflare in front of its HTML (its API answers fine), so on a headless helper its
 * browse and search fail until a clearance cookie exists — exactly as they do for any other
 * challenge-walled source, and they work once the app's WebView has solved one.
 */
class NatoExtensionService(
    source: NyoraMangaSource,
    networkConfig: HelperNetworkConfig,
) : MangaExtensionService {

    private val sourceName = source.id.removePrefix("parser:").ifBlank { source.name }
    override val domain: String = DOMAINS[sourceName] ?: DEFAULT_DOMAIN
    private val baseUrl = "https://$domain"

    // Both sites hotlink-protect their image CDN, so every image URL carries the Referer
    // the CDN expects. Sent as page/cover headers so the helper's image proxy forwards it.
    private val imageHeaders = mapOf("Referer" to "$baseUrl/")

    private val client: OkHttpClient = buildOkHttpClient(networkConfig.snapshot())
    private val json = Json { ignoreUnknownKeys = true }

    override val supportsLatest: Boolean = true

    override fun getHeaders(): Map<String, String> = imageHeaders

    // -- browse ------------------------------------------------------------

    override suspend fun getPopular(page: Int): MangaSearchPage =
        listPage("$baseUrl/manga-list/hot-manga?page=${page.coerceAtLeast(1)}")

    override suspend fun getLatest(page: Int): MangaSearchPage =
        listPage("$baseUrl/manga-list/latest-manga?page=${page.coerceAtLeast(1)}")

    override suspend fun search(query: String, page: Int, filters: List<SourceFilter>): MangaSearchPage {
        val slug = searchSlug(query)
        if (slug.isEmpty()) return MangaSearchPage(emptyList(), hasNextPage = false)
        return listPage("$baseUrl/search/story/$slug?page=${page.coerceAtLeast(1)}")
    }

    private suspend fun listPage(url: String): MangaSearchPage {
        val entries = fetchDocument(url).select(SELECT_CARD).mapNotNull { it.toManga() }
        return MangaSearchPage(entries = entries, hasNextPage = entries.isNotEmpty())
    }

    /**
     * The site's own `change_alias`: lower-cased, accents folded, every run of punctuation
     * or whitespace collapsed to a single underscore. `Solo Leveling!` -> `solo_leveling`.
     */
    private fun searchSlug(query: String): String {
        val folded = StringBuilder()
        for (ch in query.trim().lowercase()) {
            val replacement = ACCENTS.entries.firstOrNull { ch in it.key }?.value
            folded.append(replacement ?: ch)
        }
        return folded.toString()
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')
    }

    private fun Element.toManga(): Manga? {
        val anchor = selectFirst("a.list-story-item") ?: selectFirst("h3 a") ?: selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { return null }
        val path = href.toPathOrNull() ?: return null
        // Sponsored rows sit in the grid wearing the same classes as real cards and link to
        // a short redirect (`/3QZAlLr`). Every real title lives under /manga/<slug>.
        if (!path.startsWith("/manga/")) return null
        val title = (selectFirst("h3 a")?.text() ?: anchor.attr("title").ifBlank { anchor.text() }).trim()
        if (title.isEmpty()) return null
        val image = selectFirst("img")
        val cover = image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src").orEmpty()
        return Manga(
            id = nyoraId(path).toString(),
            title = title,
            url = path,
            publicUrl = href,
            coverUrl = cover,
            source = MangaSourceRef.Parser(sourceName),
        )
    }

    // -- details -----------------------------------------------------------

    override suspend fun getDetails(url: String): MangaDetails {
        val path = url.toPathOrNull() ?: url
        val document = fetchDocument(baseUrl + path)
        val info = document.select("div.manga-info-top li").associate { row ->
            row.text().substringBefore(':').trim().lowercase() to row.text().substringAfter(':').trim()
        }

        val manga = Manga(
            id = nyoraId(path).toString(),
            title = document.selectFirst("h1")?.text()?.trim()
                ?: document.ogContent("og:title"),
            altTitles = info["alternative"]?.split('/', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                .orEmpty(),
            url = path,
            publicUrl = baseUrl + path,
            coverUrl = document.ogContent("og:image"),
            state = when (info["status"]?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                else -> null
            },
            authors = info["author(s)"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "Updating" }
                .orEmpty(),
            // The 2026 redesign moved the summary to div.description; the older
            // #panel-story-info-description markup is kept as a fallback for mirrors
            // still serving the previous skin.
            description = document.selectFirst("div.description, #panel-story-info-description, div.panel-story-info-description")
                ?.text()?.substringAfter("Description :")?.trim()
                ?: document.ogContent("og:description"),
            // Only the genres listed for THIS title — the page also renders the site's full
            // genre menu, and selecting every /genre/ link would tag it with all of them.
            tags = document.select("div.manga-info-top li:contains(Genres) a").mapNotNull { it.toTag() },
            source = MangaSourceRef.Parser(sourceName),
        )
        return MangaDetails(manga = manga, chapters = chapters(path))
    }

    private fun Element.toTag(): MangaTag? {
        val title = text().trim().ifEmpty { return null }
        return MangaTag(key = attr("href").trimEnd('/').substringAfterLast('/'), title = title)
    }

    /**
     * Chapters come from the JSON API the title page loads them with — newest first, and
     * paginated by `offset`/`limit`. The default page is 50, which silently truncated long
     * series to their newest 50 chapters (Martial Peak returned 50 of 3877), so walk the
     * pages until the API says there are no more. `page`/`per_page` are ignored by the
     * endpoint; only `offset` and `limit` do anything.
     */
    private suspend fun chapters(mangaPath: String): List<MangaChapter> {
        val slug = mangaPath.trimEnd('/').substringAfterLast('/')
        val all = mutableListOf<ChapterDto>()
        var offset = 0
        while (true) {
            val body = fetchBody("$baseUrl/api/manga/$slug/chapters?offset=$offset&limit=$CHAPTER_PAGE_SIZE", asJson = true)
            val response = runCatching { json.decodeFromString<ChaptersResponse>(body) }.getOrNull() ?: break
            all += response.data.chapters
            offset += response.data.chapters.size
            val pagination = response.data.pagination
            if (response.data.chapters.isEmpty() || pagination?.hasMore != true) break
            if (offset >= (pagination.total ?: 0)) break
            if (all.size > CHAPTER_HARD_CAP) break // a runaway API must not hang a details request
        }
        return all
            .asReversed() // API returns newest first; Nyora numbers ascending
            .mapIndexed { index, dto ->
                MangaChapter(
                    id = nyoraId("$mangaPath/${dto.slug}").toString(),
                    title = dto.name,
                    number = dto.number ?: (index + 1f),
                    url = "$mangaPath/${dto.slug}",
                    uploadDate = dto.updatedAt.toEpochMillis(),
                    index = index,
                )
            }
    }

    // -- reader ------------------------------------------------------------

    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val path = chapter.url.toPathOrNull() ?: chapter.url
        return fetchDocument(baseUrl + path)
            .select("div.container-chapter-reader img")
            .mapNotNull { image ->
                val src = image.attr("data-src").takeIf { it.isNotBlank() } ?: image.attr("src")
                src.takeIf { it.isNotBlank() }?.let { MangaPage(url = it, headers = imageHeaders) }
            }
    }

    // -- plumbing ----------------------------------------------------------

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchBody(url, asJson = false), url)

    private suspend fun fetchBody(url: String, asJson: Boolean): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "$baseUrl/")
            .apply { if (asJson) header("Accept", "application/json").header("X-Requested-With", "XMLHttpRequest") }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("$sourceName request failed with HTTP ${response.code}: $url")
            response.body?.string().orEmpty()
        }
    }

    /** Absolute site URL -> the path we store, so a domain move never invalidates saved urls. */
    private fun String.toPathOrNull(): String? = when {
        startsWith("/") -> trimEnd('/')
        startsWith("http") -> runCatching {
            okhttp3.HttpUrl.Companion.let { java.net.URI(this).path }.trimEnd('/')
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun Document.ogContent(property: String): String =
        selectFirst("meta[property=$property]")?.attr("content")?.trim().orEmpty()

    /** Matches [KotatsuParserExtensionService.nyoraId] so ids stay stable across engines. */
    private fun nyoraId(mangaUrl: String): Long {
        var h = 1125899906842597L
        for (c in sourceName + mangaUrl) h = 31 * h + c.code
        return h
    }

    private fun String?.toEpochMillis(): Long {
        if (this.isNullOrBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(substringBefore('.').removeSuffix("Z"))
                ?.time ?: 0L
        }.getOrDefault(0L)
    }

    @Serializable
    private class ChaptersResponse(val data: ChaptersData)

    @Serializable
    private class ChaptersData(
        val chapters: List<ChapterDto> = emptyList(),
        val pagination: Pagination? = null,
    )

    @Serializable
    private class Pagination(
        val total: Int? = null,
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    @Serializable
    private class ChapterDto(
        @SerialName("chapter_name") val name: String = "",
        @SerialName("chapter_slug") val slug: String = "",
        @SerialName("chapter_num") val number: Float? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
    )

    private companion object {
        const val DEFAULT_DOMAIN = "www.manganato.gg"

        val DOMAINS = mapOf(
            "MANGANATO" to "www.manganato.gg",
            "MANGAKAKALOT" to "www.mangakakalot.gg",
        )

        // Largest page the chapters endpoint honours; 3877 chapters is then 8 requests.
        const val CHAPTER_PAGE_SIZE = 500
        const val CHAPTER_HARD_CAP = 20_000

        // Browse and search render different cards: `list-comic-item-wrap` on the manga
        // lists (which also contains hidden ad placeholders wearing the same class) and
        // `story_item` on the search results page.
        const val SELECT_CARD = "div.list-comic-item-wrap:not([hidden]), div.story_item"

        val ACCENTS = mapOf(
            "àáạảãâầấậẩẫăằắặẳẵ" to 'a',
            "èéẹẻẽêềếệểễ" to 'e',
            "ìíịỉĩ" to 'i',
            "òóọỏõôồốộổỗơờớợởỡ" to 'o',
            "ùúụủũưừứựửữ" to 'u',
            "ỳýỵỷỹ" to 'y',
            "đ" to 'd',
        )
    }
}
