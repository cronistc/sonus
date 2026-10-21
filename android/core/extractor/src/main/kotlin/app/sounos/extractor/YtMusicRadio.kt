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

/**
 * Rádio do YouTube Music (a fila "a seguir" do app oficial), só com MÚSICAS.
 *
 * O YouTube Music marca cada item: ATV = música (capa quadrada do álbum), OMV = clipe oficial,
 * UGC = vídeo de usuário. Ficam só as ATV; quando vem um clipe, o próprio YouTube Music manda a
 * versão em música ("counterpart") e é ela que entra. Vídeos nunca entram.
 */
class YtMusicRadio(private val client: OkHttpClient) {
    @Volatile private var clientVersion: String? = null

    private fun version(): String = clientVersion ?: runCatching { YoutubeParsingHelper.getYoutubeMusicClientVersion() }
        .getOrDefault(FALLBACK_VERSION).also { clientVersion = it }

    fun radio(videoId: String, hl: String = "pt", gl: String = "BR"): List<Track> {
        val body = JsonWriter.string().`object`()
            .`object`("context").`object`("client")
                .value("clientName", "WEB_REMIX").value("clientVersion", version()).value("hl", hl).value("gl", gl)
            .end().end()
            .value("videoId", videoId).value("playlistId", "RDAMVM$videoId")
            .value("isAudioOnly", true).value("enablePersistentPlaylistPanel", true)
            .end().done()
        val txt = client.newCall(Request.Builder().url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("User-Agent", UA)
            .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            check(r.isSuccessful) { "YouTube Music: HTTP ${r.code}" }
            r.body!!.string()
        }
        val items = JsonParser.`object`().from(txt).path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
            "watchNextTabbedResultsRenderer").getArray("tabs").getObject(0)
            .path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer").getArray("contents")
        return items.mapNotNull { o ->
            val it = o as? JsonObject ?: return@mapNotNull null
            when {
                it.has("playlistPanelVideoRenderer") -> it.getObject("playlistPanelVideoRenderer").takeIf(::isSong)
                it.has("playlistPanelVideoWrapperRenderer") -> it.getObject("playlistPanelVideoWrapperRenderer").let { w ->
                    w.path("primaryRenderer", "playlistPanelVideoRenderer").takeIf(::isSong)
                        ?: w.getArray("counterpart").getObject(0).path("counterpartRenderer", "playlistPanelVideoRenderer").takeIf(::isSong)
                }
                else -> null
            }?.let(::toTrack)
        }
    }

    private fun isSong(r: JsonObject) = r.has("videoId") &&
        r.path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig")
            .getString("musicVideoType") == "MUSIC_VIDEO_TYPE_ATV"

    private fun toTrack(r: JsonObject): Track? {
        val id = r.getString("videoId") ?: return null
        val title = r.getObject("title").getArray("runs").text() ?: return null
        val artist = r.getObject("longBylineText").getArray("runs").getObject(0).getString("text") ?: ""
        val thumbs = r.getObject("thumbnail").getArray("thumbnails").mapNotNull { t ->
            (t as? JsonObject)?.let { Thumb(it.getString("url") ?: return@let null, it.getInt("width"), it.getInt("height")) }
        }
        val url = "https://www.youtube.com/watch?v=$id"
        return Track(Track.idFor(url), url, title, TitleNormalizer.cleanArtist(artist), null, duration(r.getObject("lengthText").getArray("runs").text()), thumbs)
    }

    private fun JsonArray.text() = (this.firstOrNull() as? JsonObject)?.getString("text")
    private fun JsonObject.path(vararg keys: String): JsonObject = keys.fold(this) { o, k -> o.getObject(k) }
    private fun duration(s: String?): Long = s?.split(":")?.mapNotNull { it.trim().toLongOrNull() }?.fold(0L) { a, n -> a * 60 + n } ?: 0

    companion object {
        private const val FALLBACK_VERSION = "1.20241016.01.00"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    }
}
