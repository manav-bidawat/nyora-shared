package com.nyora.hasan72341.shared.extension

import com.nyora.hasan72341.shared.model.ContentRating
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaPage
import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.model.MangaState
import com.nyora.hasan72341.shared.model.MangaTag
import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.model.MangaSource as NyoraMangaSource
import org.koitharu.kotatsu.parsers.model.Manga as LibManga
import org.koitharu.kotatsu.parsers.model.MangaChapter as LibMangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * MangaExtensionService backed by a native kotatsu-parsers-redo [org.koitharu.kotatsu.parsers.MangaParser].
 * The parser is resolved from the source id (`parser:<ENUM_NAME>`) against [MangaParserSource].
 */
class KotatsuParserExtensionService(
    source: NyoraMangaSource,
    networkConfig: HelperNetworkConfig,
) : MangaExtensionService {

    private val parserName = source.id.removePrefix("parser:").ifBlank { source.name }

    private val parserSource = MangaParserSource.entries.firstOrNull { it.name == parserName }
        ?: error("Unknown native parser source: $parserName")

    private val context = KotatsuLoaderContext(networkConfig)
    private val parser = context.newParserInstance(parserSource)

    override val supportsLatest: Boolean
        get() = SortOrder.UPDATED in parser.availableSortOrders

    override fun getHeaders(): Map<String, String> =
        parser.getRequestHeaders().toMap()

    override suspend fun getPopular(page: Int): MangaSearchPage =
        toPage(parser.getList(offsetFor(page), pickOrder(SortOrder.POPULARITY), MangaListFilter.EMPTY))

    override suspend fun getLatest(page: Int): MangaSearchPage =
        toPage(parser.getList(offsetFor(page), pickOrder(SortOrder.UPDATED), MangaListFilter.EMPTY))

    override suspend fun search(query: String, page: Int, filters: List<SourceFilter>): MangaSearchPage =
        toPage(parser.getList(offsetFor(page), pickOrder(SortOrder.RELEVANCE), MangaListFilter(query = query)))

    override suspend fun getDetails(url: String): MangaDetails {
        val seed = LibManga(
            id = url.hashCode().toLong(),
            title = "",
            altTitles = emptySet(),
            url = url,
            publicUrl = url,
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = parserSource,
        )
        val full = parser.getDetails(seed)
        return MangaDetails(
            manga = full.toNyora(),
            chapters = full.chapters.orEmpty().map { it.toNyora() },
        )
    }

    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val libChapter = LibMangaChapter(
            id = chapter.id.toLongOrNull() ?: chapter.url.hashCode().toLong(),
            title = chapter.title.ifBlank { null },
            number = chapter.number,
            volume = chapter.volume,
            url = chapter.url,
            scanlator = chapter.scanlator,
            uploadDate = chapter.uploadDate,
            branch = chapter.branch,
            source = parserSource,
        )
        return parser.getPages(libChapter).map { page ->
            val resolved = page.url.ifBlank { parser.getPageUrl(page) }
            MangaPage(url = resolved)
        }
    }

    private fun offsetFor(page: Int): Int = (page - 1).coerceAtLeast(0) * PAGE_STEP

    private fun pickOrder(preferred: SortOrder): SortOrder {
        val available = parser.availableSortOrders
        return if (preferred in available) preferred else available.firstOrNull() ?: preferred
    }

    private fun toPage(list: List<LibManga>): MangaSearchPage =
        MangaSearchPage(entries = list.map { it.toNyora() }, hasNextPage = list.isNotEmpty())

    private fun LibManga.toNyora(): Manga = Manga(
        id = id.toString(),
        title = title,
        altTitles = altTitles.toList(),
        url = url,
        publicUrl = publicUrl,
        rating = rating,
        isNsfw = contentRating == org.koitharu.kotatsu.parsers.model.ContentRating.ADULT,
        contentRating = contentRating?.let { ContentRating.valueOf(it.name) },
        coverUrl = coverUrl.orEmpty(),
        largeCoverUrl = largeCoverUrl,
        state = state?.let { MangaState.valueOf(it.name) },
        authors = authors.toList(),
        source = MangaSourceRef.Parser(parserSource.name),
        description = description.orEmpty(),
        tags = tags.map { MangaTag(key = it.key, title = it.title) },
    )

    private fun LibMangaChapter.toNyora(): MangaChapter = MangaChapter(
        id = id.toString(),
        title = title.orEmpty(),
        number = number,
        volume = volume,
        url = url,
        scanlator = scanlator,
        uploadDate = uploadDate,
        branch = branch,
    )

    private companion object {
        const val PAGE_STEP = 20
        const val RATING_UNKNOWN = -1f
    }
}
