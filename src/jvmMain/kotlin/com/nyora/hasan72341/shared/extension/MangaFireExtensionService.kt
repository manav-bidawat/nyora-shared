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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import com.nyora.hasan72341.shared.model.MangaSource as NyoraMangaSource

/**
 * Native MangaFire source. MangaFire relaunched on a new backend backed by a clean
 * JSON API (`/api/titles`) — no vrf, no Cloudflare JS challenge, plain CDN images —
 * which the kotatsu-parsers-redo MangaFire (old `/filter` HTML + vrf + image
 * scrambling) no longer matches, so it returns nothing. This is a direct port of the
 * fix verified live in the Mihon extension to the new API. One catalog shared across
 * all MANGAFIRE_* sources; each source differs only in the chapter language it lists.
 *
 * MangaFire blocks ALL datacenter IPs at the Cloudflare edge, so every request MUST go
 * through the residential-proxy-aware OkHttp client ([buildOkHttpClient]) — the same
 * stack [KotatsuLoaderContext]/[KotatsuParserExtensionService] use, whose
 * [CloudflareInterceptor] retries `mangafire.to` through the residential proxy. A bare
 * OkHttpClient would bypass the proxy and 403 on the VM.
 */
class MangaFireExtensionService(
    source: NyoraMangaSource,
    networkConfig: HelperNetworkConfig,
) : MangaExtensionService {

    private val baseUrl = "https://mangafire.to"

    // Source ref name (`MANGAFIRE_<SUFFIX>`) — kept identical to how
    // KotatsuParserExtensionService stamps MangaSourceRef.Parser so downstream code
    // (which keys on the parser enum name) is unaffected.
    private val parserName = source.id.removePrefix("parser:").ifBlank { source.name }

    // MangaFire's own language code, derived from the enum name suffix.
    private val langCode: String = when (parserName.substringAfterLast('_')) {
        "EN" -> "en"
        "ES" -> "es"
        "ESLA" -> "es-la"
        "FR" -> "fr"
        "JA" -> "ja"
        "PT" -> "pt"
        "PTBR" -> "pt-br"
        else -> "en"
    }

    // Residential-proxy-aware client: buildOkHttpClient installs CloudflareInterceptor,
    // which routes a blocked mangafire.to request through NYORA_RESIDENTIAL_PROXY.
    private val client: OkHttpClient = buildOkHttpClient(networkConfig.snapshot())

    private val json = Json { ignoreUnknownKeys = true }

    override val supportsLatest: Boolean = true

    override fun getHeaders(): Map<String, String> = mapOf("Referer" to "$baseUrl/")

    private fun newRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Referer", "$baseUrl/")
        .header("Accept", "application/json")
        .get()
        .build()

    private suspend fun fetchBody(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(newRequest(url)).execute().use { response ->
            if (!response.isSuccessful) error("MangaFire request failed with HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    // -- browse ------------------------------------------------------------
    private suspend fun titles(params: List<Pair<String, String>>): MangaSearchPage {
        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder()
            .apply { params.forEach { addQueryParameter(it.first, it.second) } }
            .build()
        val data = json.decodeFromString<ApiResponse<MangaDto>>(fetchBody(url.toString()))
        return MangaSearchPage(
            entries = data.items.map { it.toNyora() },
            hasNextPage = data.meta?.hasNext ?: false,
        )
    }

    override suspend fun getPopular(page: Int): MangaSearchPage = titles(
        listOf("order[views_30d]" to "desc", "page" to "$page", "limit" to "50"),
    )

    override suspend fun getLatest(page: Int): MangaSearchPage = titles(
        listOf("order[chapter_updated_at]" to "desc", "page" to "$page", "limit" to "50"),
    )

    override suspend fun search(query: String, page: Int, filters: List<SourceFilter>): MangaSearchPage = titles(
        buildList {
            if (query.isNotBlank()) add("keyword" to query)
            add("page" to "$page")
            add("limit" to "50")
        },
    )

    // -- details + chapters ------------------------------------------------
    override suspend fun getDetails(url: String): MangaDetails {
        val hid = getHid(url)
        val details = json.decodeFromString<MangaDetailsResponse>(
            fetchBody("$baseUrl/api/titles/$hid"),
        ).data.toNyora()

        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        var lastPage: Int
        do {
            val chaptersUrl = "$baseUrl/api/titles/$hid/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("language", langCode)
                .addQueryParameter("sort", "number")
                .addQueryParameter("order", "desc")
                .addQueryParameter("page", "$page")
                .addQueryParameter("limit", "200")
                .build()
            val data = json.decodeFromString<ApiResponse<ChapterDto>>(fetchBody(chaptersUrl.toString()))
            data.items.forEach { chapters.add(it.toNyora(details.url, langCode)) }
            lastPage = data.meta?.lastPage ?: 1
            page++
        } while (page <= lastPage)

        // The API returns chapters newest-first (order=desc); the engine's convention
        // (matching kotatsu-parsers) is oldest-first, so reverse for consistency with
        // every other source.
        chapters.reverse()

        return MangaDetails(manga = details, chapters = chapters)
    }

    // -- pages -------------------------------------------------------------
    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val segments = (baseUrl + chapter.url).toHttpUrl().pathSegments
        val last = segments.last()
        val url = if (segments.contains("volume")) {
            "$baseUrl/api/volumes/$last"
        } else {
            "$baseUrl/api/chapters/${last.substringBefore("-")}"
        }
        val data = json.decodeFromString<PagesResponse>(fetchBody(url))
        // Plain CDN images (no scrambling); image requests still need the Referer.
        return data.data.pages.map { MangaPage(url = it.url, headers = mapOf("Referer" to "$baseUrl/")) }
    }

    // MangaFire manga urls look like /title/<hid>-<slug>.
    private fun getHid(url: String): String {
        val lastPart = url.removeSuffix("/").substringAfterLast("/")
        return when {
            lastPart.contains(".") -> lastPart.substringAfterLast(".")
            lastPart.contains("-") -> lastPart.substringBefore("-")
            else -> lastPart
        }
    }

    private fun MangaDto.toNyora(): Manga {
        val relUrl = "/title/$hid${slug?.let { "-$it" } ?: ""}"
        val cover = (poster?.large ?: poster?.medium ?: poster?.small).orEmpty()
        return Manga(
            id = relUrl,
            title = title,
            url = relUrl,
            publicUrl = baseUrl + relUrl,
            coverUrl = cover,
            largeCoverUrl = poster?.large,
            source = MangaSourceRef.Parser(parserName),
        )
    }

    private fun MangaDetailsDto.toNyora(): Manga {
        val relUrl = "/title/$hid${slug?.let { "-$it" } ?: ""}"
        val cover = (poster?.large ?: poster?.medium ?: poster?.small).orEmpty()
        val tags = buildList {
            type?.replaceFirstChar { it.uppercase() }?.let { add(it) }
            genres?.forEach { add(it.title) }
            themes?.forEach { add(it.title) }
        }
        return Manga(
            id = relUrl,
            title = title,
            url = relUrl,
            publicUrl = baseUrl + relUrl,
            coverUrl = cover,
            largeCoverUrl = poster?.large,
            state = when (status?.lowercase()) {
                "releasing" -> MangaState.ONGOING
                "finished" -> MangaState.FINISHED
                "on_hiatus" -> MangaState.PAUSED
                "discontinued" -> MangaState.ABANDONED
                else -> null
            },
            authors = buildList {
                authors?.forEach { add(it.title) }
                artists?.forEach { add(it.title) }
            }.distinct(),
            source = MangaSourceRef.Parser(parserName),
            description = synopsisHtml?.let { Jsoup.parse(it).text() }.orEmpty(),
            tags = tags.map { MangaTag(key = it, title = it) },
        )
    }

    private fun ChapterDto.toNyora(mangaUrl: String, langCode: String): MangaChapter {
        val numStr = number.toString().removeSuffix(".0")
        return MangaChapter(
            id = id.toString(),
            title = buildString {
                append("Ch. ")
                append(numStr)
                if (!name.isNullOrBlank()) {
                    append(" - ")
                    append(name)
                }
            },
            number = number,
            url = "$mangaUrl/$id-chapter-$numStr-$langCode",
            scanlator = type,
            uploadDate = (createdAt ?: 0L) * 1000L,
        )
    }
}

// -- API DTOs (ported from the verified Mihon MangaFire reference) -----------
@Serializable
private class ApiResponse<T>(val items: List<T> = emptyList(), val meta: ApiMeta? = null)

@Serializable
private class ApiMeta(val lastPage: Int = 1, val hasNext: Boolean = false)

@Serializable
private class MangaDto(
    val hid: String,
    val slug: String? = null,
    val title: String,
    val poster: PosterDto? = null,
)

@Serializable
private class MangaDetailsResponse(val data: MangaDetailsDto)

@Serializable
private class MangaDetailsDto(
    val hid: String,
    val slug: String? = null,
    val title: String,
    val type: String? = null,
    val status: String? = null,
    val poster: PosterDto? = null,
    val synopsisHtml: String? = null,
    val authors: List<EntityDto>? = null,
    val artists: List<EntityDto>? = null,
    val genres: List<EntityDto>? = null,
    val themes: List<EntityDto>? = null,
)

@Serializable
private class PosterDto(val small: String? = null, val medium: String? = null, val large: String? = null)

@Serializable
private class EntityDto(val title: String)

@Serializable
private class ChapterDto(
    val id: Int,
    val number: Float,
    val name: String? = null,
    val createdAt: Long? = null,
    val type: String? = null,
)

@Serializable
private class PagesResponse(val data: ChapterDataDto)

@Serializable
private class ChapterDataDto(val pages: List<PageDto>)

@Serializable
private class PageDto(val url: String)
