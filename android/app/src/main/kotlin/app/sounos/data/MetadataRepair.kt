package app.sounos.data

import android.content.Context
import android.util.Log
import app.sounos.SounosApp
import app.sounos.extractor.CoverStore
import app.sounos.extractor.TitleNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Corrige o que já está salvo quando as regras de nome/capa melhoram numa atualização:
 * histórico, músicas em cache, baixadas, sessão, playlists e buscas recentes.
 *  1. na hora, sem internet: aplica as regras novas ao que está guardado (separar artistas, limpar título);
 *     limpa o cache de buscas e marca as fotos de artista para conferir;
 *  2. aos poucos, pela internet: uma música por vez, pede ao YouTube Music os dados atuais dela
 *     (artistas com link, capa quadrada) e reescreve todas as cópias. Retoma de onde parou se o app fechar.
 * Para corrigir de novo numa versão futura, basta aumentar VERSION.
 */
class MetadataRepair(private val app: SounosApp) {
    private val prefs = app.getSharedPreferences("sounos.repair", Context.MODE_PRIVATE)

    fun start() {
        if (prefs.getInt("done", 0) >= VERSION) return
        app.io.launch {
            delay(20_000)   // depois de o app abrir e a música começar
            runCatching { run() }.onFailure { Log.w("Sonus", "correção de metadados parou", it) }
        }
    }

    private suspend fun run() {
        if (prefs.getInt("prepared", 0) < VERSION) {
            app.library.rewriteAll(::repairLocal)
            // playlists e buscas recentes moram na interface: a correção vai pela lista de correções
            for (key in UI_KEYS) {
                val v = app.userData.load(key)?.let { runCatching { org.json.JSONTokener(it).nextValue() }.getOrNull() } ?: continue
                Library.walkTracks(v) { o -> JSONObject(o.toString()).takeIf(::repairLocal)?.let(app.library::applyFix); false }
            }
            runCatching { app.metadata.clear() }
            app.covers.recheckAllArtists()
            val ids = (app.library.savedIds() + uiIds()).filter { it.startsWith("yt:") }.distinct()
            prefs.edit().putString("queue", JSONArray(ids).toString()).putInt("prepared", VERSION).apply()
            Log.i("Sonus", "correção de metadados: ${ids.size} músicas na fila")
        }
        val queue = ArrayDeque(prefs.getString("queue", "[]")!!.let(::JSONArray).let { a -> (0 until a.length()).map(a::getString) })
        var attempts = 0
        var failuresInRow = 0
        while (queue.isNotEmpty()) {
            val id = queue.first()
            val fresh = runCatching { app.source.current(id.removePrefix("yt:")) }
            if (fresh.isFailure && ++attempts < 3) {
                // sem internet ou YouTube recusando: espera e tenta de novo (sem gastar bateria em laço)
                delay(if (++failuresInRow >= 5) 10 * 60_000L else 30_000L)
                if (failuresInRow >= 5) failuresInRow = 0
                continue
            }
            fresh.getOrNull()?.let { runCatching { apply(id, it) }.onFailure { e -> Log.w("Sonus", "correção $id falhou", e) } }
            if (fresh.isSuccess) failuresInRow = 0
            attempts = 0
            queue.removeFirst()
            prefs.edit().putString("queue", JSONArray(queue.toList()).toString()).apply()
            delay(3_000)
        }
        prefs.edit().putInt("done", VERSION).remove("queue").apply()
        Log.i("Sonus", "correção de metadados: concluída (versão $VERSION)")
    }

    private suspend fun apply(id: String, fresh: app.sounos.extractor.Track) {
        if (fresh.id != id || fresh.title.isBlank() || fresh.artist.isBlank()) return
        app.library.applyFix(fresh)
        // capa salva era miniatura de vídeo e agora existe a capa quadrada do álbum: troca
        val square = fresh.thumbs.any { it.width > 0 && kotlin.math.abs(it.width - it.height) <= 2 }
        val file = app.covers.local(id)
        val fileSquare = file?.let(CoverStore::imageSize)?.let { (w, h) -> kotlin.math.abs(w - h) <= 2 } ?: true
        if (square && !fileSquare) app.covers.replace(fresh).forEach(CoverColors::forget)
    }

    private fun uiIds(): List<String> {
        val ids = mutableListOf<String>()
        for (key in UI_KEYS) {
            val v = app.userData.load(key)?.let { runCatching { org.json.JSONTokener(it).nextValue() }.getOrNull() } ?: continue
            Library.walkTracks(v) { o -> ids += o.optString("id"); false }
        }
        return ids
    }

    companion object {
        /** Aumente quando as regras de nome/capa mudarem: tudo que está salvo é corrigido de novo. */
        const val VERSION = 1
        private val UI_KEYS = listOf("sounos.playlists", "sounos.searchHistory")

        /** Regras atuais aplicadas a uma música salva, sem internet. true = mudou. */
        fun repairLocal(o: JSONObject): Boolean {
            val title = o.optString("title")
            val artists = o.optJSONArray("artists")?.let { a -> (0 until a.length()).map(a::optString) }
                ?.filter(String::isNotBlank).orEmpty().ifEmpty { listOf(o.optString("artist")).filter(String::isNotBlank) }
            if (artists.isEmpty()) return false
            val (newTitle, newArtists) = TitleNormalizer.repairSaved(title, artists)
            if (newTitle == title && newArtists == artists && o.optString("artist") == newArtists.first()) return false
            o.put("title", newTitle).put("artist", newArtists.first()).put("artists", JSONArray(newArtists))
            return true
        }
    }
}
