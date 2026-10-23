package app.sounos

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.sounos.data.Json
import app.sounos.extractor.MusicPicks
import app.sounos.extractor.CoverStore
import app.sounos.extractor.SearchFilter
import app.sounos.extractor.TitleNormalizer
import app.sounos.extractor.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import app.sounos.player.PlayerController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * Ponte entre a interface (JavaScript) e o app (Kotlin).
 *  - JS → app:  SounosNative.call("comando", "{...json...}")
 *  - app → JS:  window.__sounos.emit("evento", {...})   (estado, progresso, letra, download, resultados)
 */
class Bridge(
    private val web: WebView,
    private val batteryOptimizationIgnored: () -> Boolean,
    private val requestBatteryOptimization: (manual: Boolean) -> Unit,
    private val notificationPermissionGranted: () -> Boolean,
    private val requestNotificationPermission: () -> Unit,
) {
    private val app = SounosApp.instance
    private val player get() = app.player
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> android.util.Log.e("Sonus", "tarefa em segundo plano falhou", e) })
    private val listener: (String, JSONObject) -> Unit = { name, payload -> send(name, payload) }
    private val updListener: (JSONObject) -> Unit = { send("update", it) }
    private val searchPages = ConcurrentHashMap<String, Page>()
    private val searchPageLocks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var pageActive = true

    // Wi-Fi ↔ dados móveis: com "Baixar só no Wi-Fi", o botão de baixar some nos dados móveis
    private val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
    private fun metered() = runCatching { connectivity.isActiveNetworkMetered }.getOrDefault(false)
    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = sendNetwork()
        override fun onLost(network: android.net.Network) = sendNetwork()
    }
    private var lastMetered: Boolean? = null
    private fun sendNetwork() {
        val m = metered()
        if (m != lastMetered) { lastMetered = m; send("network", JSONObject().put("metered", m)) }
    }

    fun attach() {
        player.addListener(listener); app.updater.addListener(updListener)
        runCatching { connectivity.registerDefaultNetworkCallback(netCallback) }
    }
    fun detach() {
        player.removeListener(listener); app.updater.removeListener(updListener); scope.cancel()
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
    }
    fun setPageActive(active: Boolean) { pageActive = active }

    fun send(name: String, payload: Any) {
        // Com a tela apagada, evita acordar a WebView quatro vezes por segundo só para atualizar o tempo.
        if (name == "progress" && (!pageActive || !app.getSystemService(android.os.PowerManager::class.java).isInteractive)) return
        val js = "window.__sounos&&window.__sounos.emit(${JSONObject.quote(name)},$payload)"
        main.post {
            if (name == "progress" && (!pageActive || !app.getSystemService(android.os.PowerManager::class.java).isInteractive)) return@post
            web.evaluateJavascript(js, null)
        }
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
            "openingReady" -> {
                main.post {
                    web.alpha = 1f
                    // a palavra nativa fica por cima mais um instante (está no mesmo lugar do 1º quadro da animação):
                    // assim nunca aparece um quadro vazio na troca
                    main.postDelayed({
                        (web.parent as? android.view.ViewGroup)?.findViewWithTag<android.view.View>("sonus-opening-word")?.visibility = android.view.View.GONE
                    }, 220)
                }
                "{}"
            }
            "getLyrics" -> onMain { player.lyricsJson().toString() }
            "getProgress" -> onMain { player.progress().toString() }
            "getHistory" -> app.library.history().toString()
            // correções de metadados para playlists e buscas recentes (só manda a lista quando ela mudou)
            "trackFixes" -> app.library.fixesRev().let { rev ->
                JSONObject().put("rev", rev).apply { if (a.optInt("rev", -1) != rev) put("fixes", app.library.fixes()) }.toString()
            }
            "getUpdate" -> app.updater.state().toString()
            "getBatteryOptimization" -> JSONObject().put("enabled", batteryOptimizationIgnored()).toString()
            "getNotificationPermission" -> JSONObject().put("granted", notificationPermissionGranted()).toString()
            "getOffline" -> JSONObject().put("offline", !player.isOnline).toString()
            "getMineTracks" -> mineTracks().toString()
            "getCoverArtistKeys" -> JSONObject().put("keys", JSONArray(if (BuildConfig.DEBUG) app.covers.knownArtistKeys() else emptyList<String>())).toString()
            "saveData" -> {
                val key = a.optString("key")
                val value = a.optString("value")
                val saved = app.userData.save(key, value)
                if (saved && key == "sounos.playlists") app.io.launch {
                    val migration = app.reconcilePinnedCovers(value)
                    val trim = app.cacheBudget.trim()
                    if (migration.first > 0 || trim.removedFiles > 0) android.util.Log.i("Sonus", "cache migração_pin arquivos=${migration.first} bytes=${migration.second} lru_removidos=${trim.removedFiles}")
                }
                JSONObject().put("ok", saved).toString()
            }
            "loadData" -> JSONObject().put("value", app.userData.load(a.optString("key")) ?: JSONObject.NULL).toString()
            "getDownloads" -> app.library.downloadedMeta().toString()
            "getNetwork" -> JSONObject().put("metered", metered()).toString()
            "getStorage" -> JSONObject()
                .put("deviceBytes", runCatching { android.os.StatFs(app.filesDir.path).totalBytes }.getOrDefault(0L))
                .put("freeBytes", runCatching { android.os.StatFs(app.filesDir.path).availableBytes }.getOrDefault(0L))
                .put("downloadsBytes", app.library.downloadsBytes())
                .put("downloadsCount", app.library.downloadedMeta().length())
                .put("cacheBytes", app.audio.sizeBytes() + app.cacheBudget.sizeBytes())
                .toString()
            else -> { main.post { runCatching { command(method, a) }.onFailure { android.util.Log.w("Sonus", "comando $method falhou", it) } }; "{}" }
        }
    }

    private fun command(m: String, a: JSONObject) {
        when (m) {
            "search" -> cachedAsync(a, "search", a.getString("q")) {
                val q = a.getString("q")
                val page = app.source.search(q, SearchFilter.SONGS)
                val nextPage = page.nextPage
                if (nextPage == null) searchPages.remove(q) else searchPages[q] = nextPage
                app.resolver.prefetch(page.items.take(1))                       // início instantâneo do 1º (os outros disputavam a rede com as capas)
                JSONObject().put("items", Json.tracks(page.items)).put("suggestion", page.serviceSuggestion ?: JSONObject.NULL)
                    .put("hasMore", nextPage != null)
            }
            "searchMore" -> async(a) {
                val q = a.getString("q")
                searchPageLocks.computeIfAbsent(q) { Mutex() }.withLock {
                    val next = searchPages[q]
                    if (next == null) JSONObject().put("items", JSONArray()).put("hasMore", false)
                    else {
                        val page = app.source.search(q, SearchFilter.SONGS, next)
                        val nextPage = page.nextPage
                        if (nextPage == null) searchPages.remove(q) else searchPages[q] = nextPage
                        JSONObject().put("items", Json.tracks(page.items)).put("hasMore", nextPage != null)
                    }
                }
            }
            "searchArtists" -> cachedAsync(a, "searchArtists", a.getString("q")) {
                JSONObject().put("items", JSONArray().apply {
                    app.source.searchArtists(a.getString("q")) { android.util.Log.i("Sonus", it) }.take(8).forEach { r ->
                        put(JSONObject().put("name", r.name).put("url", r.url)
                            .put("thumb", r.thumbs.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL))
                    }
                })
            }
            "searchAlbums" -> cachedAsync(a, "searchAlbums", a.getString("q")) {
                JSONObject().put("items", JSONArray().apply {
                    app.source.searchAlbums(a.getString("q")).take(12).forEach { r ->
                        put(JSONObject().put("name", r.name).put("artist", r.artist).put("url", r.url)
                            .put("thumb", r.thumbs.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL))
                    }
                })
            }
            "album" -> cachedAsync(a, "album", a.getString("url")) {
                val album = app.source.album(a.getString("url"))
                JSONObject().put("name", album.name).put("artist", album.artist)
                    .put("thumb", album.thumbs.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL)
                    .put("items", Json.tracks(album.items))
            }
            // página do artista: foto do canal oficial + músicas dele (busca de músicas filtrada pelo artista)
            "artist" -> cachedAsync(a, "artist", a.getString("name")) {
                val name = a.getString("name")
                val thumb = a.optString("thumb").takeIf { it.isNotBlank() }
                val norm = { s: String -> TitleNormalizer.key("", s) }
                val target = norm(name)
                val started = System.nanoTime()
                val (artists, page) = coroutineScope {
                    val ar = async {
                        runCatching {
                            app.covers.resolveArtistPhoto(
                                name,
                                hintUrl = thumb,
                                lookup = { q -> app.source.searchArtist(q) { android.util.Log.i("Sonus", it) } },
                                trace = { android.util.Log.i("Sonus", "foto artista $it") },
                            )
                        }.getOrNull()
                    }
                    val so = async {
                        val searchStarted = System.nanoTime()
                        app.source.search(name, SearchFilter.SONGS).also {
                            android.util.Log.i("Sonus", "Bridge.artist.searchSongs_ms=${(System.nanoTime() - searchStarted) / 1_000_000} resultados=${it.items.size}")
                        }
                    }
                    ar.await() to so.await()
                }
                // nome inteiro ou palavras inteiras ("Menos É Mais" ↔ "Grupo Menos É Mais"); "Ana" não pega "Mariana"
                val within = { long: String, short: String -> short.length > 2 && " $long ".contains(" $short ") }
                val by = { t: Track -> t.artists.ifEmpty { listOf(t.artist) }.map(norm).any { it == target || within(it, target) || within(target, it) } }
                val songs = page.items.filter(by).toMutableList()
                var next = page.nextPage
                var pages = 0
                val moreStarted = System.nanoTime()
                while (songs.size < 30 && next != null && pages < 2) {
                    pages++
                    val more = runCatching { app.source.search(name, SearchFilter.SONGS, next) }.getOrNull() ?: break
                    more.items.filter(by).filter { m -> songs.none { it.id == m.id } }.forEach(songs::add)
                    next = more.nextPage
                }
                if (pages > 0) android.util.Log.i("Sonus", "Bridge.artist.moreSongs_ms=${(System.nanoTime() - moreStarted) / 1_000_000} paginas=$pages")
                app.resolver.prefetch(songs.take(2))
                android.util.Log.i("Sonus", "Bridge.artist_ms=${(System.nanoTime() - started) / 1_000_000} nome=$name")
                JSONObject().put("name", artists?.name ?: name).put("url", artists?.channelUrl ?: JSONObject.NULL)
                    .put("thumb", artists?.url ?: JSONObject.NULL)
                    .put("items", Json.tracks(MusicPicks.clean(songs)))
            }
            "suggest" -> cachedAsync(a, "suggest", a.getString("q")) { JSONObject().put("items", JSONArray(app.source.suggestions(a.getString("q")).take(6))) }
            "prefetch" -> app.resolver.prefetch(Json.tracks(a.optJSONArray("tracks")))
            "playQueue" -> {
                requestBatteryOptimization(false)
                val shuffleMode = if (a.has("shuffleMode")) a.optBoolean("shuffleMode") else null
                player.playQueue(Json.tracks(a.getJSONArray("tracks")), a.optInt("index"),
                    a.optJSONObject("context") ?: JSONObject().put("type", "playlist"), a.optBoolean("shufflePrepared"), shuffleMode)
            }
            "playSingle" -> { requestBatteryOptimization(false); player.playQueue(listOf(Json.track(a.getJSONObject("track"))), 0, a.optJSONObject("context") ?: JSONObject().put("type", "radio")) }
            "playIndex" -> { requestBatteryOptimization(false); player.playIndex(a.getInt("index")) }
            "playSuggestion" -> { requestBatteryOptimization(false); player.playSuggestion(Json.track(a.getJSONObject("track"))) }
            "toggle" -> { requestBatteryOptimization(false); player.toggle() }
            "play" -> { requestBatteryOptimization(false); player.play() }
            "requestBatteryOptimization" -> requestBatteryOptimization(true)
            "requestNotificationPermission" -> requestNotificationPermission()
            "pause" -> player.pause()
            "next" -> player.next()
            "prev" -> player.prev()
            "seek" -> a.optDouble("sec").takeIf { it.isFinite() && it >= 0 }?.let { player.seek((it * 1000).toLong()) }
            "setRepeat" -> player.setRepeat(PlayerController.Repeat.valueOf(a.getString("mode").uppercase()))
            "setShuffle" -> player.setShuffle(a.getBoolean("on"))
            "move" -> player.move(a.getInt("from"), a.getInt("to"))
            "download" -> player.download(Json.track(a.getJSONObject("track")))
            "removeDownload" -> {
                app.library.removeDownload(a.getString("id")); app.io.launch { app.reconcilePinnedCovers(); app.cacheBudget.trim() }
                send("state", player.state()); send("library", JSONObject())
            }
            // Configurações › Músicas baixadas › Remover todas (a interface pede confirmação antes)
            "removeAllDownloads" -> {
                app.library.downloadedMeta().keys().asSequence().toList().forEach(app.library::removeDownload)
                app.io.launch { app.reconcilePinnedCovers(); app.cacheBudget.trim() }
                send("state", player.state()); send("library", JSONObject())
                send("storage", JSONObject(call("getStorage", "{}")))
            }
            "setSettings" -> { app.library.saveSettings(a); player.applySettings(a); app.applyStartTheme(a.optString("theme", "gradient"))
                (web.context as? MainActivity)?.applyWindowTheme(a.optString("theme", "gradient")) }
            // atualizações
            "checkUpdate" -> app.updater.check()
            "installUpdate" -> app.updater.install()
            "allowInstalls" -> app.updater.openInstallPermission()
            // links da tela "Apoie o Sonus" abrem no navegador do celular (só https)
            "openUrl" -> a.optString("url").takeIf { it.startsWith("https://") }?.let { u ->
                app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "seenUpdate" -> app.updater.clearJustUpdated()
            "setUpdateUrl" -> app.updater.setTestUrl(a.optString("url").ifBlank { null })      // só em build de teste
            "clearCache" -> app.io.launch {
                app.audio.clear(); app.cacheBudget.clear()
                send("library", JSONObject())
                send("storage", JSONObject(call("getStorage", "{}")))
            }
        }
    }

    private fun mineTracks(): JSONObject {
        val known = linkedMapOf<String, app.sounos.extractor.Track>()
        app.library.cachedTracks().forEach { (id, track) -> known[id] = track }
        val history = runCatching { app.library.history() }.getOrNull() ?: JSONArray()
        for (i in 0 until history.length()) {
            runCatching { Json.track(history.getJSONObject(i).getJSONObject("track")) }.getOrNull()?.let { track ->
                if (app.audio.isFreshComplete(track.id)) {
                    if (known.putIfAbsent(track.id, track) == null) app.library.markCached(track)
                }
            }
        }
        val downloaded = app.library.downloadedMeta().keys().asSequence().mapNotNull { id ->
            app.library.downloadedTrack(id)?.takeIf { app.library.isDownloaded(id) }
        }.distinctBy { it.id }.toList()
        val downloadedIds = downloaded.map { it.id }.toSet()
        val cached = known.values.filter { app.audio.isFreshComplete(it.id) && it.id !in downloadedIds }.distinctBy { it.id }
        app.library.pruneCached(known.values.filter { app.audio.isFreshComplete(it.id) }.map { it.id }.toSet())
        return JSONObject().put("downloaded", Json.tracks(downloaded)).put("cached", Json.tracks(cached))
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

    /** Entrega o último valor do disco e atualiza a interface quando a busca nova terminar. */
    private fun cachedAsync(a: JSONObject, method: String, value: String, work: suspend () -> JSONObject) {
        val req = a.optString("reqId")
        val cacheKey = metadataKey(method, value)
        scope.launch {
            val readStarted = System.nanoTime()
            val cached = runCatching {
                withContext(Dispatchers.IO) { app.metadata.read(cacheKey)?.let(::JSONObject) }
            }.onFailure { android.util.Log.w("Sonus", "cache metadados leitura falhou metodo=$method", it) }.getOrNull()
            val readMs = (System.nanoTime() - readStarted) / 1_000_000
            android.util.Log.i("Sonus", "metadados metodo=$method etapa=disco_ms=$readMs cache=${cached != null}")
            if (cached != null) {
                val first = JSONObject(cached.toString())
                if (method == "search") first.put("hasMore", false) // o cursor do NewPipe não sobrevive à sessão
                send("reply", first.put("reqId", req))
            }

            val networkStarted = System.nanoTime()
            val fresh = runCatching { withContext(Dispatchers.IO) { work() } }
            val networkMs = (System.nanoTime() - networkStarted) / 1_000_000
            if (fresh.isFailure) {
                android.util.Log.i("Sonus", "metadados metodo=$method etapa=rede_ms=$networkMs resultado=falha cache=${cached != null}")
                if (cached == null) send("reply", JSONObject().put("error", fresh.exceptionOrNull()?.message ?: "erro").put("reqId", req))
                return@launch
            }

            val result = fresh.getOrThrow()
            val writeStarted = System.nanoTime()
            val trim = runCatching { withContext(Dispatchers.IO) { app.metadata.write(cacheKey, result.toString()) } }
                .onFailure { android.util.Log.w("Sonus", "cache metadados gravação falhou metodo=$method", it) }.getOrNull()
            val writeMs = (System.nanoTime() - writeStarted) / 1_000_000
            android.util.Log.i("Sonus", "metadados metodo=$method etapa=gravar_ms=$writeMs rede_ms=$networkMs bytes=${result.toString().toByteArray().size} lru_removidos=${trim?.removedBytes ?: 0}")
            if (cached == null) {
                send("reply", JSONObject(result.toString()).put("reqId", req))
            } else if (method == "search" || cached.toString() != result.toString()) {
                send("metadataUpdated", JSONObject().put("method", method).put("cacheKey", cacheKey).put("payload", result))
            }
        }
    }

    private fun metadataKey(method: String, value: String): String {
        val stable = if (method == "artist") CoverStore.artistKey(value)
        else value.trim().lowercase(Locale.ROOT)
        return "$method:$stable"
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
