package app.sounos.extractor

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

/** Catálogo oficial de músicas, álbuns e singles de uma página de artista do YouTube Music. */
class YtMusicArtist(private val client: OkHttpClient) {
    @Volatile private var clientVersion: String? = null

    private fun version(): String = clientVersion ?: runCatching { YoutubeParsingHelper.getYoutubeMusicClientVersion() }
        .getOrDefault(FALLBACK_VERSION).also { clientVersion = it }

    /** Seções da aba Música da página do artista (faixas, carrosséis de álbuns/singles…). */
    private fun artistSections(artistId: String): List<JsonObject> {
        val page = browse(artistId)
        val tab = page.path("contents", "singleColumnBrowseResultsRenderer").getArray("tabs")
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.path("tabRenderer").getBoolean("selected") }
            ?.getObject("tabRenderer") ?: error("A página de artista não tem a aba Música")
        return tab.path("content", "sectionListRenderer").getArray("contents").mapNotNull { it as? JsonObject }
    }

    /**
     * Lançamentos da página do artista (carrosséis "Álbuns" e "Singles e EPs"), do mais novo ao mais antigo.
     * Uma requisição só: os carrosséis já trazem os mais recentes; a discografia completa não é necessária aqui.
     */
    fun releases(artistId: String): List<Release> = artistSections(artistId).flatMap { section ->
        val shelf = section.getObject("musicCarouselShelfRenderer") ?: return@flatMap emptyList()
        val label = shelf.path("header", "musicCarouselShelfBasicHeaderRenderer").getObject("title")?.getArray("runs")
            ?.firstOrNull()?.let { (it as? JsonObject)?.getString("text") }.orEmpty()
        val shelfKind = when {
            label.contains("álbu", true) || label.contains("album", true) -> "Álbum"
            label.contains("single", true) || label.contains("EP") -> "Single"
            else -> return@flatMap emptyList()
        }
        shelf.getArray("contents").mapNotNull { value ->
            (value as? JsonObject)?.getObject("musicTwoRowItemRenderer")?.let { parseRelease(it, shelfKind) }
        }
    }.distinctBy { it.url ?: it.title }.sortedByDescending { it.year ?: 0 }

    /** Um lançamento (capa de álbum/single/EP) como aparece nas grades e carrosséis do YouTube Music. */
    private fun parseRelease(r: JsonObject, fallbackKind: String): Release? {
        val title = r.getObject("title")?.getArray("runs")?.firstOrNull()?.let { (it as? JsonObject)?.getString("text") }?.trim()
        if (title.isNullOrBlank()) return null
        val sub = r.getObject("subtitle")?.getArray("runs")?.mapNotNull { (it as? JsonObject)?.getString("text")?.trim() }.orEmpty()
        val year = sub.firstNotNullOfOrNull { s -> s.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt() }
        val kind = when {
            sub.any { it.equals("EP", true) } -> "EP"
            sub.any { it.contains("single", true) } -> "Single"
            sub.any { it.contains("álbum", true) || it.contains("album", true) } -> "Álbum"
            else -> fallbackKind
        }
        // "Single • Artista e Outro": os nomes vêm depois do tipo (separadores "•", "e", "," não contam)
        val artist = sub.filterNot { it == "•" || it == "," || it == "e" || it == "&" || it.isBlank() || it == kind || it.toIntOrNull() != null ||
            it.equals("Single", true) || it.equals("EP", true) || it.contains("álbum", true) }.firstOrNull().orEmpty()
        val listId = r.path("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
            "playNavigationEndpoint", "watchPlaylistEndpoint").getString("playlistId")
        val thumbs = r.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail").getArray("thumbnails").mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            Thumb(o.getString("url") ?: return@mapNotNull null, o.getInt("width"), o.getInt("height"))
        }
        val browseId = r.path("navigationEndpoint", "browseEndpoint").getString("browseId")?.takeIf { it.startsWith("MPRE") }
        return Release(title, kind, year, listId?.let { "https://www.youtube.com/playlist?list=$it" }, thumbs, artist, browseId)
    }

    /** Lançamentos novos do país (álbuns e singles) — a base genérica de "Novidades". */
    fun newReleases(): List<Release> {
        val out = mutableListOf<Release>()
        fun walk(v: Any?) {
            when (v) {
                is JsonObject -> { v.getObject("musicTwoRowItemRenderer")?.let { r -> parseRelease(r, "Álbum")?.let(out::add) }; v.values.forEach(::walk) }
                is JsonArray -> v.forEach(::walk)
            }
        }
        walk(browse("FEmusic_new_releases_albums"))
        return out.filter { it.browseId != null }.distinctBy { it.browseId }
    }

    /**
     * Faixas de um álbum pela playlist dele (OLAK…) no YouTube Music: cada faixa vem com o ARTISTA creditado
     * (a playlist do YouTube comum traz o canal que subiu o vídeo, ex.: "DjavanOficial", que não é página de artista).
     */
    fun albumPlaylistTracks(playlistId: String, fallbackArtist: String): List<Track> {
        val rows = JsonArray()
        fun walk(v: Any?) {
            when (v) {
                is JsonObject -> { if (v.has("musicResponsiveListItemRenderer")) rows.add(v) else v.values.forEach(::walk) }
                is JsonArray -> v.forEach(::walk)
            }
        }
        walk(browse("VL$playlistId"))
        return parseTracks(rows, fallbackArtist)
    }

    /** Faixas de um lançamento (página do álbum/single). */
    fun releaseTracks(browseId: String, artist: String): List<Track> = albumTracks(browseId, artist)

    fun tracks(artistId: String, artistName: String, trace: (String) -> Unit = {}): List<Track> {
        val started = System.nanoTime()
        trace("YtMusicArtist.inicio id=$artistId versao=${version()}")
        val sections = artistSections(artistId)
        trace("YtMusicArtist.artista secoes=${sections.size} tipos=${sections.joinToString(",") { if (it.has("musicShelfRenderer")) "faixas" else if (it.has("musicCarouselShelfRenderer")) "carrossel" else "outro" }}")
        val topShelf = sections.firstOrNull { it.has("musicShelfRenderer") }?.getObject("musicShelfRenderer")
        val topEndpoint = topShelf?.getObject("bottomEndpoint")?.path("browseEndpoint")
        val topTracks = if (topEndpoint?.getString("browseId") != null) {
            playlistTracks(topEndpoint.getString("browseId")!!, topEndpoint.getString("params"), artistName, trace)
        } else topShelf?.getArray("contents")?.let { parseTracks(it, artistName) }.orEmpty()
        trace("YtMusicArtist.top endpoint=${topEndpoint?.getString("browseId")} faixas=${topTracks.size}")

        val discography = sections.flatMap { section ->
            val shelf = section.getObject("musicCarouselShelfRenderer") ?: return@flatMap emptyList()
            val heading = shelf.path("header", "musicCarouselShelfBasicHeaderRenderer")
            val label = heading.getObject("title")?.getArray("runs")?.firstOrNull()?.let { (it as? JsonObject)?.getString("text") }.orEmpty()
            if (!label.contains("álbum", true) && !label.contains("single", true) && !label.contains("EP", true)) return@flatMap emptyList()
            val endpoint = heading.path("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint")
            val browseId = endpoint.getString("browseId") ?: return@flatMap emptyList()
            trace("YtMusicArtist.discografia tipo=$label id=$browseId")
            discographyItems(browseId, endpoint.getString("params"))
        }.distinctBy { it.id }

        // As capas de discografia se sobrepõem aos dez primeiros itens mostrados na página do artista.
        // Do lançamento mais novo ao mais antigo: a página do artista abre com as músicas mais atuais.
        val refs = discography.distinctBy { it.id }.sortedByDescending { it.year ?: 0 }
        val pool = Executors.newFixedThreadPool(ALBUM_WORKERS)
        val albums = try {
            refs.map { ref -> pool.submit(Callable { runCatching { albumTracks(ref.id, artistName) }.getOrDefault(emptyList()) }) }
                .flatMap { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val combined = deduplicate(albums + topTracks)       // mais atuais primeiro; as populares que não estão em lançamento vêm depois
        trace("YtMusicArtist.artist=${artistName} top=${topTracks.size} discografia=${refs.size} faixas_album=${albums.size} unicas=${combined.size} ms=${(System.nanoTime() - started) / 1_000_000}")
        return combined
    }

    private data class AlbumRef(val id: String, val year: Int? = null)

    private fun discographyItems(browseId: String, params: String?): List<AlbumRef> {
        val items = mutableListOf<AlbumRef>()
        var result = browse(browseId, params)
        var pages = 0
        val seenTokens = hashSetOf<String>()
        while (pages++ < MAX_PAGES) {
            val sections = discographySections(result)
            for (sectionValue in sections) {
                val section = sectionValue as? JsonObject ?: continue
                val grid = section.getObject("gridRenderer") ?: continue
                for (itemValue in grid.getArray("items")) {
                    val item = (itemValue as? JsonObject)?.getObject("musicTwoRowItemRenderer") ?: continue
                    val endpoint = item.path("navigationEndpoint", "browseEndpoint")
                    val id = endpoint.getString("browseId") ?: continue
                    val year = item.getObject("subtitle")?.getArray("runs")?.mapNotNull { (it as? JsonObject)?.getString("text")?.trim() }
                        ?.firstNotNullOfOrNull { t -> t.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt() }
                    if (id.startsWith("MPRE") || id.startsWith("MPAD")) items += AlbumRef(id, year)
                }
            }
            val token = selectedDiscographyContinuation(result)
                ?: result.path("continuationContents", "sectionListContinuation").getString("continuation")
                ?: sections.asSequence().mapNotNull { it as? JsonObject }.mapNotNull { it.getObject("gridRenderer") }
                    .mapNotNull(::nextToken).firstOrNull()
            if (token.isNullOrBlank() || !seenTokens.add(token)) break
            val before = items.size
            result = browse(continuation = token)
            // Alguns servidores repetem a grade completa no primeiro "Mais"; não peça a mesma grade outra vez.
            if (pages > 1 && items.size == before) break
        }
        return items.distinctBy { it.id }
    }

    private fun discographySections(page: JsonObject): JsonArray {
        val tabContents = page.path("contents", "singleColumnBrowseResultsRenderer").getArray("tabs")
            .mapNotNull { it as? JsonObject }.firstOrNull()
            ?.path("tabRenderer", "content", "sectionListRenderer")?.getArray("contents")
        if (!tabContents.isNullOrEmpty()) return tabContents
        val continuationContents = page.path("continuationContents", "sectionListContinuation").getArray("contents")
        if (!continuationContents.isNullOrEmpty()) return continuationContents
        return page.path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer").getArray("contents")
    }

    private fun selectedDiscographyContinuation(page: JsonObject): String? {
        val sectionList = page.path("contents", "singleColumnBrowseResultsRenderer").getArray("tabs")
            .mapNotNull { it as? JsonObject }.firstOrNull()
            ?.path("tabRenderer", "content", "sectionListRenderer") ?: return null
        val starts = sectionList.path("header", "musicSideAlignedItemRenderer").getArray("startItems")
        for (startValue in starts) {
            val chips = (startValue as? JsonObject)?.path("chipCloudRenderer")?.getArray("chips") ?: continue
            for (chipValue in chips) {
                val chip = (chipValue as? JsonObject)?.getObject("chipCloudChipRenderer") ?: continue
                if (!chip.getBoolean("isSelected")) continue
                return chip.path("navigationEndpoint", "browseSectionListReloadEndpoint", "continuation", "reloadContinuationData")
                    .getString("continuation")
            }
        }
        return null
    }

    private fun playlistTracks(browseId: String, params: String?, artist: String, trace: (String) -> Unit): List<Track> =
        collectShelfTracks(browse(browseId, params), "musicPlaylistShelfRenderer", artist, trace)

    private fun albumTracks(browseId: String, artist: String): List<Track> =
        collectShelfTracks(browse(browseId), "musicShelfRenderer", artist) {}

    private fun collectShelfTracks(first: JsonObject, shelfName: String, artist: String, trace: (String) -> Unit): List<Track> {
        val result = mutableListOf<Track>()
        var page = first
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val sections = discographySections(page)
            val shelves = sections.mapNotNull { (it as? JsonObject)?.getObject(shelfName) }
            val rawTracks = shelves.sumOf { it.getArray("contents").size }
            shelves.forEach { shelf -> result += parseTracks(shelf.getArray("contents"), artist) }
            trace("YtMusicArtist.lista shelf=$shelfName pagina=$pages secoes=${sections.size} prateleiras=${shelves.size} brutas=$rawTracks parseadas=${result.size}")
            val token = shelves.asSequence().mapNotNull(::nextToken).firstOrNull()
                ?: page.path("continuationContents", "sectionListContinuation").getString("continuation")
            if (token.isNullOrBlank()) break
            page = browse(continuation = token)
        }
        return result
    }

    private fun parseTracks(items: JsonArray, fallbackArtist: String): List<Track> = items.mapNotNull { value ->
        val r = (value as? JsonObject)?.getObject("musicResponsiveListItemRenderer") ?: return@mapNotNull null
        val columns = r.getArray("flexColumns").mapNotNull { (it as? JsonObject)?.getObject("musicResponsiveListItemFlexColumnRenderer") }
        val titleRuns = columns.firstOrNull()?.path("text")?.getArray("runs") ?: return@mapNotNull null
        val titleRun = titleRuns.firstOrNull() as? JsonObject ?: return@mapNotNull null
        val rawTitle = titleRun.getString("text")?.trim().orEmpty()
        if (rawTitle.isBlank()) return@mapNotNull null
        val watch = titleRun.path("navigationEndpoint", "watchEndpoint")
        val overlayWatch = r.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
        val videoId = watch.getString("videoId") ?: overlayWatch.getString("videoId")
            ?: r.path("playlistItemData").getString("videoId") ?: return@mapNotNull null
        val type = watch.path("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig").getString("musicVideoType")
            ?: overlayWatch.path("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig").getString("musicVideoType")
        if (type != null && type !in ACCEPTED_TYPES) return@mapNotNull null

        val credited = columns.getOrNull(1)?.path("text")?.getArray("runs")?.let(YtMusicRadio(client)::artistsFromRuns).orEmpty()
        val firstArtist = credited.firstOrNull() ?: fallbackArtist
        val clean = TitleNormalizer.clean(rawTitle, firstArtist, official = true)
        val artists = TitleNormalizer.producersLast(TitleNormalizer.mergeArtists(credited, clean.artists, listOf(fallbackArtist)))
        val thumbArray = r.path("thumbnail", "musicThumbnailRenderer", "thumbnail").getArray("thumbnails")
        val thumbs = thumbArray.mapNotNull thumbLoop@{ thumb ->
            val t = thumb as? JsonObject ?: return@thumbLoop null
            val url = t.getString("url") ?: return@thumbLoop null
            Thumb(url, t.getInt("width"), t.getInt("height"))
        }
        val durationText = r.getArray("fixedColumns").firstOrNull()?.let { it as? JsonObject }
            ?.path("musicResponsiveListItemFixedColumnRenderer", "text")?.getArray("runs")
            ?.firstOrNull()?.let { (it as? JsonObject)?.getString("text") }
            ?: r.getObject("lengthText")?.getArray("runs")?.firstOrNull()?.let { (it as? JsonObject)?.getString("text") }
        val duration = durationText?.split(":")?.mapNotNull(String::toLongOrNull)?.fold(0L) { acc, part -> acc * 60 + part } ?: 0L
        val url = "https://www.youtube.com/watch?v=$videoId"
        Track(Track.idFor(url), url, clean.title, artists.firstOrNull() ?: fallbackArtist, null, duration, thumbs, artists)
    }

    private fun deduplicate(items: List<Track>): List<Track> {
        val out = mutableListOf<Track>()
        val ids = hashSetOf<String>()
        for (item in items) {
            if (!ids.add(item.id)) continue
            val title = titleKey(item.title)
            val duplicate = out.any { existing ->
                if (titleKey(existing.title) != title) false
                else existing.durationSec > 0L && item.durationSec > 0L &&
                    abs(existing.durationSec - item.durationSec) <= maxOf(5L, (maxOf(existing.durationSec, item.durationSec) * .03).toLong())
            }
            if (!duplicate) out += item
        }
        return out
    }

    private fun titleKey(value: String): String = Normalizer.normalize(
        value.replace(Regex("""\b(remaster(?:izada|izado|ed)?|vers[aã]o\s+remasterizada)(?:\s*\d{4})?\b""", RegexOption.IGNORE_CASE), " "),
        Normalizer.Form.NFD,
    ).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun nextToken(shelf: JsonObject): String? {
        val entry = shelf.getArray("continuations").firstOrNull() as? JsonObject ?: return null
        return entry.path("nextContinuationData").getString("continuation")
            ?: entry.path("nextContinuationData", "continuationCommand").getString("token")
            ?: entry.path("nextContinuationData", "continuation").getString("token")
    }

    /**
     * Busca SÓ artistas no YouTube Music (perfis marcados como "Artista"), com o tamanho do público:
     * ouvintes mensais quando o YouTube Music informa, senão inscritos. subscriberCount = esse público.
     */
    fun searchArtists(query: String): List<ArtistResult> {
        val body = JsonWriter.string().`object`()
            .`object`("context").`object`("client")
                .value("clientName", "WEB_REMIX").value("clientVersion", version()).value("hl", "pt").value("gl", "BR")
            .end().end()
            .value("query", query).value("params", ARTISTS_FILTER).end().done()
        val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("User-Agent", UA).post(body.toRequestBody("application/json".toMediaType())).build()
        val page = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YouTube Music: HTTP ${response.code}" }
            JsonParser.`object`().from(response.body!!.string())
        }
        val out = mutableListOf<ArtistResult>()
        fun walk(v: Any?) {
            when (v) {
                is JsonObject -> { v.getObject("musicResponsiveListItemRenderer")?.let { r -> artistRow(r)?.let(out::add) }; v.values.forEach(::walk) }
                is JsonArray -> v.forEach(::walk)
            }
        }
        walk(page)
        return out.distinctBy { it.url }
    }

    private fun artistRow(r: JsonObject): ArtistResult? {
        val id = r.path("navigationEndpoint", "browseEndpoint").getString("browseId")?.takeIf { it.startsWith("UC") } ?: return null
        val cols = r.getArray("flexColumns").mapNotNull { (it as? JsonObject)?.path("musicResponsiveListItemFlexColumnRenderer", "text")?.getArray("runs") }
        val name = cols.firstOrNull()?.mapNotNull { (it as? JsonObject)?.getString("text") }?.joinToString("")?.trim()
        if (name.isNullOrBlank()) return null
        val info = cols.getOrNull(1)?.mapNotNull { (it as? JsonObject)?.getString("text") }?.joinToString("").orEmpty()
        val thumbs = r.path("thumbnail", "musicThumbnailRenderer", "thumbnail").getArray("thumbnails").mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val url = o.getString("url") ?: return@mapNotNull null
            Thumb(CoverStore.artistImageUrl(url), o.getInt("width"), o.getInt("height"))
        }
        return ArtistResult(TitleNormalizer.cleanArtist(name), "https://www.youtube.com/channel/$id", thumbs, publico(info))
    }

    private fun browse(browseId: String? = null, params: String? = null, continuation: String? = null): JsonObject {
        val writer = JsonWriter.string().`object`()
            .`object`("context").`object`("client")
                .value("clientName", "WEB_REMIX").value("clientVersion", version()).value("hl", "pt").value("gl", "BR")
            .end().end()
        if (browseId != null) writer.value("browseId", browseId)
        if (params != null) writer.value("params", params)
        if (continuation != null) writer.value("continuation", continuation)
        val body = writer.end().done()
        val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("User-Agent", UA).post(body.toRequestBody("application/json".toMediaType())).build()
        val text = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YouTube Music: HTTP ${response.code}" }
            response.body!!.string()
        }
        return JsonParser.`object`().from(text)
    }

    private fun JsonObject.path(vararg keys: String): JsonObject = keys.fold(this) { o, key -> o.getObject(key) }

    companion object {
        /** filtro "Artistas" da busca do YouTube Music */
        private const val ARTISTS_FILTER = "EgWKAQIgAWoMEA4QChADEAQQCRAF"

        /** "Artista • 5,45 mi ouvintes mensais" → 5450000 · "845 mil inscritos" → 845000 · "868 inscritos" → 868 · sem número → 0 */
        fun publico(info: String): Long {
            val m = Regex("""([\d.,]+)\s*(mi|mil|bi|k|m|b)?\b""", RegexOption.IGNORE_CASE)
                .findAll(info.replace('\u00a0', ' ')).lastOrNull { it.groupValues[1].any(Char::isDigit) } ?: return 0
            val raw = m.groupValues[1]
            val n = (if (raw.contains(',')) raw.replace(".", "").replace(',', '.') else if (m.groupValues[2].isEmpty()) raw.replace(".", "") else raw).toDoubleOrNull() ?: return 0
            val mult = when (m.groupValues[2].lowercase()) { "mil", "k" -> 1e3; "mi", "m" -> 1e6; "bi", "b" -> 1e9; else -> 1.0 }
            return (n * mult).toLong()
        }

        private const val FALLBACK_VERSION = "1.20241016.01.00"
        private const val ALBUM_WORKERS = 5
        private const val MAX_PAGES = 30
        private val ACCEPTED_TYPES = setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK")
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    }
}
