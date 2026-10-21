package app.sounos.extractor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Fonte de música via NewPipe Extractor (YouTube / YouTube Music).
 * Tudo roda em Dispatchers.IO; nada aqui toca a interface.
 */
class NewPipeSource(private val service: StreamingService = ServiceList.YouTube) {

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.SONGS, page: Page? = null): SearchPage =
        withContext(Dispatchers.IO) {
            val handler = service.searchQHFactory.fromQuery(query, listOf(filter.youtube), "")
            if (page == null) {
                val info = SearchInfo.getInfo(service, handler)
                SearchPage(
                    items = info.relatedItems.mapNotNull(::toTrack).sortedBy { if (MusicPicks.isJunk(it, query = query)) 1 else 0 },
                    nextPage = info.nextPage,
                    serviceSuggestion = info.searchSuggestion?.takeIf { it.isNotBlank() },
                    corrected = info.isCorrectedSearch,
                )
            } else {
                val more = SearchInfo.getMoreItems(service, handler, page)
                SearchPage(more.items.mapNotNull(::toTrack), more.nextPage, null, false)
            }
        }

    /** Artistas (canais "Topic"/oficiais) para a seção de artistas dos resultados. */
    suspend fun searchArtists(query: String, trace: (String) -> Unit = {}): List<ArtistResult> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val handler = service.searchQHFactory.fromQuery(query, listOf(SearchFilter.ARTISTS.youtube), "")
        val artists = SearchInfo.getInfo(service, handler).relatedItems.mapNotNull { item ->
            (item as? org.schabi.newpipe.extractor.channel.ChannelInfoItem)?.let {
                ArtistResult(TitleNormalizer.cleanArtist(it.name), it.url, it.thumbnails.map { image ->
                    val thumb = toThumb(image)
                    thumb.copy(url = CoverStore.artistImageUrl(thumb.url))
                })
            }
        }
        trace("NewPipe.searchArtists_ms=${(System.nanoTime() - started) / 1_000_000} resultados=${artists.size}")
        artists
    }

    /** Busca uma vez e escolhe o canal que mais se aproxima do nome pedido. */
    suspend fun searchArtist(query: String, trace: (String) -> Unit = {}): ArtistResult? {
        val artists = searchArtists(query, trace)
        val target = CoverStore.artistKey(query)
        if (target.isBlank()) return artists.firstOrNull()
        val targetWords = TitleNormalizer.key("", query).split(' ').filter { it.isNotBlank() }.toSet()
        val ranked = artists.map { artist ->
            val key = CoverStore.artistKey(artist.name)
            val words = TitleNormalizer.key("", artist.name).split(' ').filter { it.isNotBlank() }.toSet()
            val overlap = targetWords.intersect(words).size
            val score = when {
                key == target -> 10_000 - kotlin.math.abs(key.length - target.length)
                key.contains(target) || target.contains(key) -> 5_000 - kotlin.math.abs(key.length - target.length)
                else -> overlap * 500 - kotlin.math.abs(key.length - target.length)
            }
            artist to score
        }
        return ranked.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first ?: artists.firstOrNull()
    }

    /** Álbuns do YouTube Music; o resultado da busca vem como uma playlist especial. */
    suspend fun searchAlbums(query: String): List<AlbumResult> = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(query, listOf(SearchFilter.ALBUMS.youtube), "")
        SearchInfo.getInfo(service, handler).relatedItems.mapNotNull { item ->
            (item as? PlaylistInfoItem)?.let {
                AlbumResult(it.name, it.uploaderName ?: "", it.url, albumThumbs(it.thumbnails.map(::toThumb)))
            }
        }
    }

    /** Dados e músicas de um álbum. As capas são mantidas quadradas para a interface. */
    suspend fun album(url: String): Album = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(service, url)
        Album(
            name = info.name,
            artist = info.uploaderName ?: "",
            thumbs = albumThumbs(info.thumbnails.map(::toThumb)),
            items = info.relatedItems.mapNotNull(::toTrack),
        )
    }

    /** Sugestões do próprio serviço enquanto a pessoa digita (o app mistura com histórico e gosto). */
    suspend fun suggestions(prefix: String): List<String> = withContext(Dispatchers.IO) {
        service.suggestionExtractor.suggestionList(prefix)
    }

    /** Metadados + melhor áudio + relacionadas. É a chamada "cara" (~0,5–1,5 s): por isso existe o StreamResolveCache. */
    // known = a música como veio da busca/lista: as capas QUADRADAS do YouTube Music vêm de lá
    // (a página do vídeo só traz miniaturas 16:9), então elas entram primeiro.
    suspend fun resolve(url: String, quality: AudioQuality = AudioQuality.HIGH, known: Track? = null): ResolvedTrack = withContext(Dispatchers.IO) {
        // o rádio do YouTube Music (só artistas, canais oficiais) é pedido junto, sem atrasar o início
        val radioJob = Track.idFor(url).removePrefix("yt:").takeIf { Track.idFor(url).startsWith("yt:") }
            ?.let { id -> CoroutineScope(Dispatchers.IO).async { runCatching { radio(id) }.getOrNull() } }
        val info = StreamInfo.getInfo(service, url)
        val audio = pickAudio(info.audioStreams, quality)
            ?: error("Nenhum áudio tocável para $url")
        val clean = TitleNormalizer.clean(info.name, info.uploaderName ?: "")
        val artists = TitleNormalizer.mergeArtists(known?.artists.orEmpty(), clean.artists)
        val primaryArtist = known?.artist?.takeIf { it.isNotBlank() } ?: clean.artist
        val track = Track(
            id = Track.idFor(info.url),
            url = info.url,
            title = clean.title,
            artist = primaryArtist,
            artistUrl = info.uploaderUrl,
            durationSec = info.duration,
            thumbs = (known?.thumbs.orEmpty() + info.thumbnails.map(::toThumb)).distinctBy { it.url },
            artists = TitleNormalizer.mergeArtists(listOf(primaryArtist), artists),
        )
        // reserva imediata: "relacionados" do vídeo, só os que são MÚSICA (canais "Topic" do YouTube Music)
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().filter(::isSong).mapNotNull(::toTrack)
        // o rádio quase sempre já chegou junto; se não, a música começa sem esperar e o rádio entra depois
        val quick = radioJob?.let { withTimeoutOrNull(300) { it.await() } }
        val later = if (quick == null && radioJob != null)
            CoroutineScope(Dispatchers.IO).async { radioJob.await()?.takeIf { it.isNotEmpty() }?.let { MusicPicks.clean(it, exclude = listOf(track)) } }
        else null
        val picks = MusicPicks.clean(quick?.takeIf { it.isNotEmpty() } ?: related, exclude = listOf(track))
        ResolvedTrack(track, audio, picks, System.currentTimeMillis(), later)
    }

    private fun isSong(s: StreamInfoItem) = s.uploaderName.orEmpty().endsWith(" - Topic", ignoreCase = true)
    private val ytm by lazy { YtMusicRadio(Sounos.http) }

    /** Rádio do YouTube Music a partir de uma música (a mesma lista que o app oficial toca em seguida). */
    // Só músicas (com capa quadrada): rádio do YouTube Music; se ele falhar, o mix do YouTube filtrado para músicas.
    suspend fun radio(videoId: String): List<Track> = withContext(Dispatchers.IO) {
        val loc = org.schabi.newpipe.extractor.NewPipe.getPreferredLocalization()
        val gl = org.schabi.newpipe.extractor.NewPipe.getPreferredContentCountry().countryCode
        runCatching { ytm.radio(videoId, loc.languageCode, gl) }.getOrNull()?.takeIf { it.size >= 5 }
            ?: PlaylistInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId").relatedItems
                .filterIsInstance<StreamInfoItem>().filter(::isSong).mapNotNull(::toTrack)
    }

    // ---------- conversões ----------

    private fun toTrack(item: InfoItem): Track? {
        val s = item as? StreamInfoItem ?: return null
        val clean = TitleNormalizer.clean(s.name, s.uploaderName ?: "")
        return Track(
            id = Track.idFor(s.url),
            url = s.url,
            title = clean.title,
            artist = clean.artist,
            artistUrl = s.uploaderUrl,
            durationSec = s.duration,
            thumbs = s.thumbnails.map(::toThumb),
            artists = clean.artists,
        )
    }

    private fun toThumb(i: Image) = Thumb(i.url, i.width.coerceAtLeast(0), i.height.coerceAtLeast(0))

    private fun albumThumbs(thumbs: List<Thumb>): List<Thumb> {
        val squares = thumbs.filter { it.width > 0 && it.height > 0 && kotlin.math.abs(it.width - it.height) * 20 <= maxOf(it.width, it.height) }
        return squares.ifEmpty { thumbs }
    }

    companion object {
        /**
         * Escolhe o áudio: só arquivos diretos (progressivos), que permitem começar a tocar
         * pelos primeiros bytes e baixar o resto em paralelo.
         *  - HIGH:  maior bitrate, preferindo Opus (melhor qualidade por kbps)
         *  - SAVER: o mais leve que ainda soa bem (≥ 48 kbps)
         *  - AUTO:  igual ao HIGH (quem decide entre HIGH/SAVER é o app, conforme Wi-Fi ou dados)
         */
        fun pickAudio(streams: List<AudioStream>, quality: AudioQuality): ResolvedAudio? {
            val playable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotBlank() }
                .filter { it.audioTrackType.let { type -> type == null || type.name == "ORIGINAL" } }   // ignora dublagens automáticas
            if (playable.isEmpty()) return null
            val kbps = { s: AudioStream -> (if (s.averageBitrate > 0) s.averageBitrate else s.bitrate / 1000).coerceAtLeast(0) }
            val isOpus = { s: AudioStream -> s.format == MediaFormat.WEBMA_OPUS || s.codec?.contains("opus") == true }
            val chosen = when (quality) {
                AudioQuality.SAVER -> playable.filter { kbps(it) >= 48 }.minByOrNull(kbps) ?: playable.minByOrNull(kbps)
                else -> playable.maxWithOrNull(compareBy<AudioStream>(kbps).thenBy { if (isOpus(it)) 1 else 0 })
            } ?: return null
            val url = chosen.content
            return ResolvedAudio(
                url = url,
                mimeType = chosen.format?.mimeType ?: "audio/*",
                codec = chosen.codec,
                bitrateKbps = kbps(chosen),
                contentLength = chosen.itagItem?.contentLength?.takeIf { it > 0 } ?: queryParam(url, "clen")?.toLongOrNull() ?: -1,
                expiresAtMs = expiresAt(url),
                itag = chosen.itag,
            )
        }

        /** O YouTube coloca o prazo na própria URL (`expire=<segundos>`). Guardamos com 10 min de folga. */
        fun expiresAt(url: String, now: Long = System.currentTimeMillis()): Long {
            val exp = queryParam(url, "expire")?.toLongOrNull()
            return if (exp != null) exp * 1000 - 10 * 60_000 else now + 5 * 3_600_000
        }

        fun queryParam(url: String, name: String): String? =
            Regex("""[?&]${Regex.escape(name)}=([^&]+)""").find(url)?.groupValues?.get(1)
    }
}
