package app.sounos.extractor

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as YT

/** Uma imagem de capa com o tamanho informado pelo serviço (0 = desconhecido). */
data class Thumb(val url: String, val width: Int, val height: Int)

/** Música como o app enxerga: já com título/artista limpos e as capas disponíveis. */
data class Track(
    val id: String,              // "yt:<videoId>" — chave estável para banco, capas e downloads
    val url: String,
    val title: String,
    val artist: String,
    val artistUrl: String?,
    val durationSec: Long,
    val thumbs: List<Thumb>,
) {
    val videoId: String? get() = YOUTUBE_ID.find(url)?.groupValues?.get(1)

    companion object {
        private val YOUTUBE_ID = Regex("""(?:v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})""")
        fun idFor(url: String): String = YOUTUBE_ID.find(url)?.groupValues?.get(1)?.let { "yt:$it" } ?: "url:${url.hashCode()}"
    }
}

/** Filtros de busca do YouTube Music. */
enum class SearchFilter(val youtube: String) {
    SONGS(YT.MUSIC_SONGS),
    VIDEOS(YT.MUSIC_VIDEOS),
    ALBUMS(YT.MUSIC_ALBUMS),
    PLAYLISTS(YT.MUSIC_PLAYLISTS),
    ARTISTS(YT.MUSIC_ARTISTS),
    ALL(YT.ALL),
}

data class SearchPage(
    val items: List<Track>,
    val nextPage: Page?,
    /** "Você quis dizer" vindo do próprio serviço (o app combina isso com o ranking pelo gosto). */
    val serviceSuggestion: String?,
    val corrected: Boolean,
)

/** Artista encontrado na busca (canal do YouTube Music). */
data class ArtistResult(val name: String, val url: String, val thumbs: List<Thumb>)

/** Álbum encontrado no YouTube Music. */
data class AlbumResult(val name: String, val artist: String, val url: String, val thumbs: List<Thumb>)

/** Página completa de um álbum, com suas faixas já no formato do player. */
data class Album(val name: String, val artist: String, val thumbs: List<Thumb>, val items: List<Track>)

enum class AudioQuality { AUTO, HIGH, SAVER }

/** URL de áudio pronta para tocar. Expira (o YouTube assina a URL com prazo). */
data class ResolvedAudio(
    val url: String,
    val mimeType: String,
    val codec: String?,
    val bitrateKbps: Int,
    val contentLength: Long,     // -1 = desconhecido
    val expiresAtMs: Long,
    val itag: Int,
)

/** Tudo que o player precisa de uma música: metadados, áudio e relacionadas (para rádio/sugestões). */
data class ResolvedTrack(
    val track: Track,
    val audio: ResolvedAudio,
    val related: List<Track>,
    val resolvedAtMs: Long,
    /** rádio que ainda estava chegando quando a música começou (o player troca as indicações quando ele chegar) */
    val relatedLater: kotlinx.coroutines.Deferred<List<Track>?>? = null,
)
