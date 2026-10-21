package app.sounos

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.sounos.data.Json
import app.sounos.extractor.MusicPicks
import app.sounos.extractor.SearchFilter
import app.sounos.extractor.TitleNormalizer
import app.sounos.extractor.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import app.sounos.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ponte entre a interface (JavaScript) e o app (Kotlin).
 *  - JS → app:  SounosNative.call("comando", "{...json...}")
 *  - app → JS:  window.__sounos.emit("evento", {...})   (estado, progresso, letra, download, resultados)
 */
class Bridge(private val web: WebView) {
    private val app = SounosApp.instance
    private val player get() = app.player
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener: (String, JSONObject) -> Unit = { name, payload -> send(name, payload) }
    private val updListener: (JSONObject) -> Unit = { send("update", it) }

    fun attach() { player.addListener(listener); app.updater.addListener(updListener) }
    fun detach() { player.removeListener(listener); app.updater.removeListener(updListener); scope.cancel() }

    fun send(name: String, payload: Any) {
        val js = "window.__sounos&&window.__sounos.emit(${JSONObject.quote(name)},$payload)"
        main.post { web.evaluateJavascript(js, null) }
    }

    @JavascriptInterface
    fun call(method: String, args: String?): String {
        val a = runCatching { JSONObject(args?.ifBlank { "{}" } ?: "{}") }.getOrDefault(JSONObject())
        // pedido malformado nunca derruba o app (nem a música)
        return runCatching { sync(method, a) }.getOrElse { android.util.Log.w("Sonus", "leitura $method falhou", it); "{}" }
    }

    private fun sync(method: String, a: JSONObject): String {
        return when (method) {
            "getState" -> onMain { player.state().toString() }
            "getLyrics" -> onMain { player.lyricsJson().toString() }
            "getProgress" -> onMain { player.progress().toString() }
            "getHistory" -> app.library.history().toString()
            "getUpdate" -> app.updater.state().toString()
            "saveData" -> JSONObject().put("ok", app.userData.save(a.optString("key"), a.optString("value"))).toString()
            "loadData" -> JSONObject().put("value", app.userData.load(a.optString("key")) ?: JSONObject.NULL).toString()
            "getDownloads" -> app.library.downloadedMeta().toString()
            "getStorage" -> JSONObject()
                .put("downloadsBytes", app.library.downloadsBytes())
                .put("downloadsCount", app.library.downloadedMeta().length())
                .put("cacheBytes", app.audio.sizeBytes() + app.cacheDir.resolve("covers").walk().sumOf { it.length() })
                .toString()
            else -> { main.post { runCatching { command(method, a) }.onFailure { android.util.Log.w("Sonus", "comando $method falhou", it) } }; "{}" }
        }
    }

    private fun command(m: String, a: JSONObject) {
        when (m) {
            "search" -> async(a) {
                val page = app.source.search(a.getString("q"), SearchFilter.SONGS)
                app.resolver.prefetch(page.items.take(1))                       // início instantâneo do 1º (os outros disputavam a rede com as capas)
                JSONObject().put("items", Json.tracks(page.items)).put("suggestion", page.serviceSuggestion ?: JSONObject.NULL)
            }
            "searchArtists" -> async(a) {
                JSONObject().put("items", JSONArray().apply {
                    app.source.searchArtists(a.getString("q")).take(8).forEach { r ->
                        put(JSONObject().put("name", r.name).put("url", r.url)
                            .put("thumb", r.thumbs.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL))
                    }
                })
            }
            // página do artista: foto do canal oficial + músicas dele (busca de músicas filtrada pelo artista)
            "artist" -> async(a) {
                val name = a.getString("name")
                val norm = { s: String -> TitleNormalizer.key("", s) }
                val target = norm(name)
                val (artists, page) = coroutineScope {
                    val ar = async { runCatching { app.source.searchArtists(name) }.getOrDefault(emptyList()) }
                    val so = async { app.source.search(name, SearchFilter.SONGS) }
                    ar.await() to so.await()
                }
                val by = { t: Track -> norm(t.artist).let { it == target || it.contains(target) || target.contains(it) && it.length > 2 } }
                val songs = page.items.filter(by).toMutableList()
                var next = page.nextPage
                var pages = 0
                while (songs.size < 30 && next != null && pages++ < 2) {
                    val more = runCatching { app.source.search(name, SearchFilter.SONGS, next) }.getOrNull() ?: break
                    more.items.filter(by).filter { m -> songs.none { it.id == m.id } }.forEach(songs::add)
                    next = more.nextPage
                }
                val art = artists.firstOrNull { norm(it.name) == target } ?: artists.firstOrNull()
                app.resolver.prefetch(songs.take(2))
                JSONObject().put("name", art?.name ?: name).put("url", art?.url ?: JSONObject.NULL)
                    .put("thumb", art?.thumbs?.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL)
                    .put("items", Json.tracks(MusicPicks.clean(songs)))
            }
            "suggest" -> async(a) { JSONObject().put("items", JSONArray(app.source.suggestions(a.getString("q")).take(6))) }
            "prefetch" -> app.resolver.prefetch(Json.tracks(a.optJSONArray("tracks")))
            "playQueue" -> player.playQueue(Json.tracks(a.getJSONArray("tracks")), a.optInt("index"), a.optJSONObject("context") ?: JSONObject().put("type", "playlist"))
            "playSingle" -> player.playQueue(listOf(Json.track(a.getJSONObject("track"))), 0, a.optJSONObject("context") ?: JSONObject().put("type", "radio"))
            "playIndex" -> player.playIndex(a.getInt("index"))
            "playSuggestion" -> player.playSuggestion(Json.track(a.getJSONObject("track")))
            "toggle" -> player.toggle()
            "play" -> player.play()
            "pause" -> player.pause()
            "next" -> player.next()
            "prev" -> player.prev()
            "seek" -> a.optDouble("sec").takeIf { it.isFinite() && it >= 0 }?.let { player.seek((it * 1000).toLong()) }
            "setRepeat" -> player.setRepeat(PlayerController.Repeat.valueOf(a.getString("mode").uppercase()))
            "setShuffle" -> player.setShuffle(a.getBoolean("on"))
            "move" -> player.move(a.getInt("from"), a.getInt("to"))
            "download" -> player.download(Json.track(a.getJSONObject("track")))
            "removeDownload" -> { app.library.removeDownload(a.getString("id")); send("state", player.state()) }
            "setSettings" -> { app.library.saveSettings(a); player.applySettings(a) }
            // atualizações
            "checkUpdate" -> app.updater.check()
            "installUpdate" -> app.updater.install()
            "allowInstalls" -> app.updater.openInstallPermission()
            // links da tela "Apoie o Sonus" abrem no navegador do celular (só https)
            "openUrl" -> a.optString("url").takeIf { it.startsWith("https://") }?.let { u ->
                app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "seenUpdate" -> app.updater.clearJustUpdated()
            "setUpdateUrl" -> app.updater.setTestUrl(a.optString("url").ifBlank { null })      // só em build de teste
            "clearCache" -> app.io.launch { app.audio.clear(); app.covers.clearBrowse(); send("storage", JSONObject(call("getStorage", "{}"))) }
        }
    }

    /** Busca na rede em segundo plano; responde com o mesmo reqId que a interface mandou. */
    private fun async(a: JSONObject, work: suspend () -> JSONObject) {
        val req = a.optString("reqId")
        scope.launch {
            val out = runCatching { withContext(Dispatchers.IO) { work() } }
                .getOrElse { JSONObject().put("error", it.message ?: "erro") }
            send("reply", out.put("reqId", req))
        }
    }

    private fun onMain(block: () -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var out = "{}"
        val latch = CountDownLatch(1)
        main.post { runCatching { out = block() }; latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
        return out
    }
}
