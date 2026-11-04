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
import java.util.Locale

/**
 * Qual é a capa de verdade de uma faixa?
 *
 * Muitas faixas do app são CLIPES (o catálogo do artista e a busca trazem vídeos): a miniatura deles é um quadro do
 * vídeo (16:9, cortado ao meio vira "RAUS DE AMOR / A CARTA /") ou uma imagem quadrada qualquer que o YouTube Music
 * associa ao vídeo (às vezes de outra edição do álbum). A capa certa é a da VERSÃO EM MÚSICA (ATV) da mesma faixa.
 *
 *  1. pergunta ao YouTube Music o tipo do vídeo (`next`): música (ATV) → a capa que veio já é a do álbum;
 *  2. clipe com "counterpart" (o YouTube Music liga o clipe à música) → capa da música;
 *  3. senão, busca a música (filtro Músicas) com o mesmo título-base, o mesmo artista e duração parecida (±4 s).
 *
 * Resposta: [Resultado.musica] = é música (nada a trocar) · [Resultado.capa] = URL da capa da versão em música ·
 * ambos vazios = clipe sem versão em música encontrada (usa o que houver, sempre preferindo imagem quadrada).
 */
class CapaDaMusica(private val client: OkHttpClient) {
    data class Resultado(val musica: Boolean, val capa: String?, val videoId: String? = null)   // videoId = a versão em música (null: nenhuma achada)

    @Volatile private var clientVersion: String? = null
    private fun version(): String = clientVersion ?: runCatching { YoutubeParsingHelper.getYoutubeMusicClientVersion() }
        .getOrDefault(FALLBACK_VERSION).also { clientVersion = it }

    fun resolver(track: Track): Resultado? {
        val videoId = track.videoId ?: return null
        val first = runCatching { primeiroDaFila(videoId) }.getOrNull() ?: return null
        val (principal, contraparte) = when {
            first.has("playlistPanelVideoRenderer") -> first.getObject("playlistPanelVideoRenderer") to null
            first.has("playlistPanelVideoWrapperRenderer") -> first.getObject("playlistPanelVideoWrapperRenderer").let { w ->
                w.path("primaryRenderer", "playlistPanelVideoRenderer") to
                    w.getArray("counterpart").getObject(0).path("counterpartRenderer", "playlistPanelVideoRenderer")
            }
            else -> return null
        }
        if (principal.getString("videoId") != videoId) return null
        if (tipo(principal) == "MUSIC_VIDEO_TYPE_ATV") return Resultado(true, null)
        contraparte?.takeIf { tipo(it) == "MUSIC_VIDEO_TYPE_ATV" }?.let { c -> maiorThumb(c)?.let { return Resultado(false, it, c.getString("videoId")) } }
        val titulo = principal.getObject("title").getArray("runs").texto() ?: track.title
        val dur = duracao(principal.getObject("lengthText").getArray("runs").texto()).takeIf { it > 0 } ?: track.durationSec
        val achada = runCatching { buscarMusica(titulo, track.artist, dur) }.getOrNull()
            // pot-pourri ("A / B / C"): sem a versão em música do conjunto, a capa da primeira música
            ?: titulo.substringBefore(" / ").takeIf { titulo.contains(" / ") && it.isNotBlank() }
                ?.let { primeira -> runCatching { buscarMusica(primeira, track.artist, 0, comDuracao = false) }.getOrNull() }
        return Resultado(false, achada?.first, achada?.second)
    }

    /**
     * Busca só músicas: mesmo título-base, mesmo artista e duração parecida (±4 s). Sem nenhuma assim, a mesma música
     * do mesmo artista com outra duração (a mais próxima): clipe AO VIVO ou estendido nunca bate com a duração do
     * estúdio, mas a capa é a mesma arte — antes esses ficavam com o quadro do vídeo. Devolve (capa, vídeo da música).
     */
    private fun buscarMusica(titulo: String, artista: String, durSec: Long, comDuracao: Boolean = true): Pair<String, String?>? {
        val base = chaveTitulo(titulo)
        val chaveArtista = CoverStore.artistKey(artista)
        if (base.isBlank()) return null
        val page = post("search", JsonWriter.string().`object`()
            .`object`("context").`object`("client")
                .value("clientName", "WEB_REMIX").value("clientVersion", version()).value("hl", "pt").value("gl", "BR")
            .end().end()
            .value("query", "$artista ${titulo.substringBefore(" (")}").value("params", SONGS_FILTER).end().done())
        val linhas = mutableListOf<JsonObject>()
        fun walk(v: Any?) {
            when (v) {
                is JsonObject -> { v.getObject("musicResponsiveListItemRenderer")?.takeIf { it.isNotEmpty() }?.let(linhas::add); v.values.forEach(::walk) }
                is JsonArray -> v.forEach(::walk)
            }
        }
        walk(page)
        class Achada(val d: Long, val capa: String, val videoId: String?)
        val iguais = linhas.mapNotNull { r ->
            val cols = r.getArray("flexColumns").mapNotNull { (it as? JsonObject)?.path("musicResponsiveListItemFlexColumnRenderer", "text")?.getArray("runs") }
            val t = cols.getOrNull(0)?.mapNotNull { (it as? JsonObject)?.getString("text") }?.joinToString("").orEmpty()
            val sub = cols.getOrNull(1)?.mapNotNull { (it as? JsonObject)?.getString("text") }.orEmpty()
            val d = sub.lastOrNull { Regex("""^\d+:\d\d(:\d\d)?$""").matches(it.trim()) }?.let(::duracao) ?: 0
            val artistaOk = chaveArtista.isBlank() || sub.any { CoverStore.artistKey(it) == chaveArtista }
            if (chaveTitulo(t) != base || !artistaOk) return@mapNotNull null
            maiorThumbDe(r.path("thumbnail", "musicThumbnailRenderer", "thumbnail").getArray("thumbnails"))
                ?.let { Achada(d, it, r.path("playlistItemData").getString("videoId")) }
        }
        val perto = iguais.firstOrNull { durSec <= 0 || it.d <= 0 || kotlin.math.abs(it.d - durSec) <= 4 }
        val escolhida = if (perto != null || comDuracao && iguais.isEmpty()) perto
            else iguais.minByOrNull { if (durSec > 0 && it.d > 0) kotlin.math.abs(it.d - durSec) else Long.MAX_VALUE }
        return escolhida?.let { it.capa to it.videoId }
    }

    private fun primeiroDaFila(videoId: String): JsonObject? {
        val page = post("next", JsonWriter.string().`object`()
            .`object`("context").`object`("client")
                .value("clientName", "WEB_REMIX").value("clientVersion", version()).value("hl", "pt").value("gl", "BR")
            .end().end()
            .value("videoId", videoId).value("playlistId", "RDAMVM$videoId")
            .value("isAudioOnly", true).value("enablePersistentPlaylistPanel", true)
            .end().done())
        return page.path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer")
            .getArray("tabs").getObject(0).path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
            .getArray("contents").firstOrNull() as? JsonObject
    }

    private fun post(endpoint: String, body: String): JsonObject {
        val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("User-Agent", UA).post(body.toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "YouTube Music: HTTP ${r.code}" }
            JsonParser.`object`().from(r.body!!.string())
        }
    }

    private fun tipo(r: JsonObject) = r.path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig")
        .getString("musicVideoType")
    private fun maiorThumb(r: JsonObject) = maiorThumbDe(r.getObject("thumbnail").getArray("thumbnails"))
    private fun maiorThumbDe(a: JsonArray): String? = a.mapNotNull { it as? JsonObject }
        .maxByOrNull { it.getInt("width") * it.getInt("height") }?.getString("url")?.takeIf(CoverStore::allowedUrl)
    private fun JsonArray.texto() = (firstOrNull() as? JsonObject)?.getString("text")
    private fun JsonObject.path(vararg keys: String): JsonObject = keys.fold(this) { o, k -> o.getObject(k) }

    companion object {
        private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        private const val FALLBACK_VERSION = "1.20241016.01.00"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        fun duracao(s: String?): Long = s?.trim()?.split(":")?.mapNotNull { it.trim().toLongOrNull() }
            ?.takeIf { it.isNotEmpty() }?.fold(0L) { a, n -> a * 60 + n } ?: 0

        /** "40 Graus De Amor / A Carta / Fica (Ao Vivo)" e "40 Graus de Amor / A Carta / Fica" → a mesma chave. */
        fun chaveTitulo(t: String): String = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""\((ao vivo|live|acustico|acoustic|official[^)]*|oficial[^)]*|clipe[^)]*|video[^)]*|audio[^)]*|visualizer|lyric[^)]*|letra[^)]*)\)"""), "")
            .replace(Regex("""\[[^]]*]"""), "")
            .replace(Regex("[^a-z0-9]"), "")
    }
}
