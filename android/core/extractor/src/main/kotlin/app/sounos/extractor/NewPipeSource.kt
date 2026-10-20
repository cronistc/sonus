package app.sounos.extractor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
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
    suspend fun searchArtists(query: String): List<ArtistResult> = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(query, listOf(SearchFilter.ARTISTS.youtube), "")
        SearchInfo.getInfo(service, handler).relatedItems.mapNotNull { item ->
            (item as? org.schabi.newpipe.extractor.channel.ChannelInfoItem)?.let {
                ArtistResult(TitleNormalizer.cleanArtist(it.name), it.url, it.thumbnails.map(::toThumb))
            }
        }
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
        val track = Track(
            id = Track.idFor(info.url),
            url = info.url,
            title = clean.title,
            artist = clean.artist,
            artistUrl = info.uploaderUrl,
            durationSec = info.duration,
            thumbs = (known?.thumbs.orEmpty() + info.thumbnails.map(::toThumb)).distinctBy { it.url },
        )
        val radio = radioJob?.let { withTimeoutOrNull(2500) { it.await() } }.orEmpty()
        // reserva: "relacionados" do vídeo, com os canais dos artistas primeiro
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>()
            .sortedByDescending(MusicPicks::officialScore).mapNotNull(::toTrack)
        val picks = MusicPicks.clean(if (radio.size >= 8) radio else radio + related, exclude = listOf(track))
        ResolvedTrack(track, audio, picks, System.currentTimeMillis())
    }

    /** Rádio do YouTube Music a partir de uma música (a mesma lista que o app oficial toca em seguida). */
    suspend fun radio(videoId: String): List<Track> = withContext(Dispatchers.IO) {
        PlaylistInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId").relatedItems.mapNotNull(::toTrack)
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
        )
    }

    private fun toThumb(i: Image) = Thumb(i.url, i.width.coerceAtLeast(0), i.height.coerceAtLeast(0))

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
