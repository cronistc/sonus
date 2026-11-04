package app.sounos

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.sounos.data.CoverColors
import app.sounos.data.Json
import app.sounos.extractor.MusicPicks
import app.sounos.extractor.CoverStore
import app.sounos.extractor.SearchFilter
import app.sounos.extractor.TitleNormalizer
import app.sounos.extractor.Track
import app.sounos.extractor.LinkDeMusica
import app.sounos.widget.Widgets
import app.sounos.extractor.Sounos as SonusHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
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
    private val atualizadoEm = ConcurrentHashMap<String, Long>()     // chave de metadados → última vez que veio da rede
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
        bibliotecaMudou()                                 // "Minhas músicas" já pronta antes da 1ª página pedir
    }
    fun detach() {
        player.removeListener(listener); app.updater.removeListener(updListener); scope.cancel()
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
    }
    fun setPageActive(active: Boolean) { pageActive = active }

    // "Minhas músicas" (baixadas + cache inteiro) e o armazenamento custam centenas de ms (histórico inteiro, arquivos
    // no disco) e eram pedidos de forma síncrona a cada desenho: a página de baixadas travava ~1,4 s e a tela inicial
    // offline ~0,7 s. Ficam prontos na memória e são refeitos em segundo plano quando a biblioteca muda.
    @Volatile private var mineCache: String? = null
    @Volatile private var storageCache: Pair<Long, String>? = null
    private val mineGeracao = java.util.concurrent.atomic.AtomicInteger()
    private fun bibliotecaMudou() {
        mineCache = null; storageCache = null
        val g = mineGeracao.incrementAndGet()
        app.io.launch {
            val novo = runCatching { mineTracks().toString() }.getOrNull() ?: return@launch
            if (mineGeracao.get() == g) mineCache = novo
        }
    }

    fun send(name: String, payload: Any) {
        if (name == "library" || name == "offline" || (name == "download" && payload is JSONObject && (payload.optBoolean("done") || payload.has("error")))) bibliotecaMudou()
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
        return runCatching { sync(method, a) }.getOrElse {
            android.util.Log.w("Sonus", "leitura $method falhou", it)
            // pedido com resposta (Sounos.ask) que quebrou antes de começar (ex.: parâmetro faltando): responde com erro,
            // senão a promessa da tela ficava pendurada para sempre
            a.optString("reqId").takeIf(String::isNotBlank)?.let { req -> send("reply", JSONObject().put("error", it.message ?: "erro").put("reqId", req)) }
            "{}"
        }
    }

    private fun sync(method: String, a: JSONObject): String {
        return when (method) {
            "getState" -> onMain { player.state().toString() }
            // de ponta a ponta: altura das barras do sistema em px do aparelho (a interface divide pelo devicePixelRatio)
            "getMedidas" -> JSONObject().put("topo", MainActivity.barraTopo).put("base", MainActivity.barraBase).toString()
            "openingReady" -> {
                main.post {
                    web.alpha = 1f
                    // barras na cor lisa do splash (a mesma do 1º quadro da abertura). A cor da música entra UMA vez, quando
                    // o fundo do app aparece na animação (opening.js chama SounosSyncSystemBars). Antes as barras passavam
                    // por vermelho → azul padrão → vermelho em meio segundo: a abertura "piscava" de cor.
                    (web.context as? MainActivity)?.applyWindowTheme(app.library.settings().optString("theme", "gradient"), opening = true)
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
            "getMineTracks" -> mineCache ?: mineTracks().toString().also { mineCache = it }
            // só as baixadas (rápido): a tela inicial online não precisa conferir o cache no disco a cada desenho
            "getDownloadedTracks" -> JSONObject().put("downloaded", Json.tracks(downloadedTracks())).toString()
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
            // link de música recebido (tocado/compartilhado): entrega uma vez
            "getPendingLink" -> JSONObject().put("texto", MainActivity.linkPendente ?: JSONObject.NULL).also { MainActivity.linkPendente = null }.toString()
            // "Abrir links de música no Sonus" (Android 12+: a pessoa marca os endereços em Abrir por padrão)
            "getLinksStatus" -> linksStatus().toString()
            "temWidget" -> JSONObject().put("tem", Widgets.existe(app)).also { o -> Widgets.contagem(app).forEach { (k, v) -> o.put(k, v) } }.toString()
            // "Adicionar à tela inicial": o Android mostra a confirmação e põe o widget escolhido na tela
            "adicionarWidget" -> {
                val cls = when (a.optString("tipo")) { "capa" -> Widgets.Capa::class.java; "completo" -> Widgets.Completo::class.java; else -> Widgets.Barra::class.java }
                val am = android.appwidget.AppWidgetManager.getInstance(app)
                JSONObject().put("ok", am.isRequestPinAppWidgetSupported && am.requestPinAppWidget(android.content.ComponentName(app, cls), null, null)).toString()
            }
            "getStorage" -> storageCache?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.second ?: JSONObject()
                .put("deviceBytes", runCatching { android.os.StatFs(app.filesDir.path).totalBytes }.getOrDefault(0L))
                .put("freeBytes", runCatching { android.os.StatFs(app.filesDir.path).availableBytes }.getOrDefault(0L))
                .put("downloadsBytes", app.library.downloadsBytes())
                .put("downloadsCount", app.library.downloadedMeta().length())
                .put("cacheBytes", app.audio.sizeBytes() + app.cacheBudget.sizeBytes())
                .toString().also { storageCache = System.currentTimeMillis() to it }
            else -> { main.post { runCatching { command(method, a) }.onFailure { android.util.Log.w("Sonus", "comando $method falhou", it) } }; "{}" }
        }
    }

    // Capas da busca: a lista mostra capinhas (~10 KB, rota mini). No Wi-Fi, depois que elas chegaram, a capa no tamanho
    // da fila (400 px) vem aos poucos — as 4 primeiras logo, o resto 2 de cada vez —, pronta se a música for tocada.
    // Nos dados móveis só as capinhas: a de fila vem quando a música entra de verdade na fila.
    private var capasBusca: kotlinx.coroutines.Job? = null
    private fun capasDaBusca(items: List<app.sounos.extractor.Track>) {
        capasBusca?.cancel()
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (cm.isActiveNetworkMetered || items.isEmpty()) return
        capasBusca = app.io.launch {
            kotlinx.coroutines.delay(1500)
            items.chunked(2).forEachIndexed { i, par ->
                if (i == 2) kotlinx.coroutines.delay(1000)                 // depois das 4 primeiras, um respiro
                par.map { t -> launch { runCatching { app.covers.get(t, pin = false, large = false, conferirAntes = false) } } }.forEach { it.join() }
            }
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
                // 2º e 3º depois que as capas da lista já chegaram: quase sempre a escolha está entre os três primeiros.
                main.postDelayed({ app.resolver.prefetch(page.items.drop(1).take(2)) }, 1500)
                capasDaBusca(page.items)
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
                    // entre artistas do mesmo nível de nome, quem a pessoa mais ouve vem antes (motor de entendimento)
                    val afinidade = app.motor.afinidadeArtistas(app.library.history())
                    val q = a.getString("q")
                    val achados = app.source.searchArtists(q, { android.util.Log.i("Sonus", it) }) { afinidade[it]?.second ?: 0.0 }
                    // artistas que a pessoa já ouve e cujo nome começa com o que foi digitado vêm primeiro,
                    // mesmo que o YouTube Music não os traga para essas letras (ex.: "en" → Enygma)
                    val chave = CoverStore.artistKey(q)
                    val jaOuve = if (chave.isBlank()) emptyList() else afinidade.values
                        .filter { (nome, _) -> nome.lowercase().split(Regex("[\\s&,/-]+")).any { CoverStore.artistKey(it).startsWith(chave) } || CoverStore.artistKey(nome).startsWith(chave) }
                        // nomes de canal do YouTube que ficaram no histórico ("DjavanOficial", "X VEVO", "X - Topic") não são artistas
                        .filterNot { (nome, _) -> Regex("(?i)(oficial|official|vevo|\\s-\\stopic)$").containsMatchIn(nome.trim()) }
                        .sortedByDescending { it.second }.take(3)
                        .filter { (nome, _) -> achados.none { CoverStore.artistKey(it.name) == CoverStore.artistKey(nome) } }
                    jaOuve.forEach { (nome, _) -> put(JSONObject().put("name", nome).put("url", JSONObject.NULL).put("thumb", JSONObject.NULL)) }
                    achados.take(8 - jaOuve.size).forEach { r ->
                        put(JSONObject().put("name", r.name).put("url", r.url)
                            .put("thumb", r.thumbs.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL))
                    }
                })
            }
            // artistas parecidos com o artista pesquisado, para completar a fileira de Artistas da busca;
            // quem a pessoa já ouve sobe (motor de entendimento), o resto fica na ordem do YouTube Music
            "relatedArtists" -> cachedAsync(a, "relatedArtists", a.getString("url")) {
                val afinidade = app.motor.afinidadeArtistas(app.library.history())
                JSONObject().put("items", JSONArray().apply {
                    app.source.relatedArtists(a.getString("url"))
                        .sortedByDescending { afinidade[CoverStore.artistKey(it.name)]?.second ?: 0.0 }
                        .take(10).forEach { r ->
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
            // Página do artista: catálogo do YouTube Music, com álbuns e singles completos.
            "artist" -> cachedAsync(a, "artist", a.getString("name")) {
                val name = a.getString("name")
                val thumb = a.optString("thumb").takeIf { it.isNotBlank() }
                val artistUrl = a.optString("url").takeIf { it.isNotBlank() }
                val started = System.nanoTime()
                val (photo, songs) = coroutineScope {
                    val photoJob = async {
                        runCatching {
                            app.covers.resolveArtistPhoto(
                                name,
                                hintUrl = thumb,
                                lookup = { q -> app.source.searchArtist(q) { android.util.Log.i("Sonus", it) } },
                                trace = { android.util.Log.i("Sonus", "foto artista $it") },
                            )
                        }.getOrNull()
                    }
                    val songsJob = async {
                        val searchStarted = System.nanoTime()
                        runCatching { app.source.artistTracks(name, artistUrl) { android.util.Log.i("Sonus", it) } }
                            .onFailure { android.util.Log.w("Sonus", "catálogo YouTube Music falhou; usando busca filtrada para $name", it) }
                            .getOrElse { fallbackArtistTracks(name) }
                            .also { android.util.Log.i("Sonus", "Bridge.artist.catalogo_ms=${(System.nanoTime() - searchStarted) / 1_000_000} resultados=${it.size}") }
                    }
                    photoJob.await() to songsJob.await()
                }
                app.resolver.prefetch(songs.take(2))
                val unique = songs.distinctBy { it.id }
                android.util.Log.i("Sonus", "Bridge.artist_ms=${(System.nanoTime() - started) / 1_000_000} nome=$name musicas=${unique.size}")
                JSONObject().put("name", photo?.name ?: name).put("url", photo?.channelUrl ?: artistUrl ?: JSONObject.NULL)
                    .put("thumb", photo?.url ?: JSONObject.NULL)
                    .put("items", Json.tracks(unique)).put("total", unique.size)
            }
            // Novidades (tela inicial): lançamentos recentes pesados pelo gosto (motor), com as músicas para tocar.
            "novidades" -> async(a) {
                val seg = a.optJSONArray("seguidos") ?: JSONArray()
                app.motor.novidades(app.source, (0 until seg.length()).mapNotNull { i ->
                    seg.optJSONObject(i)?.let { o -> o.optString("name").takeIf(String::isNotBlank)?.let { it to o.optString("url").takeIf(String::isNotBlank) } }
                }, app.library.history())
            }
            // Seu Mix (tela inicial): mistura montada pelo motor; cores da capa = a cor principal das capas das primeiras músicas.
            "mix" -> async(a) {
                val r = app.motor.mix(app.source, app.library.history())
                val faixas = Json.tracks(r.optJSONArray("musicas"))
                val cores = faixas.asSequence().mapNotNull { t -> app.covers.local(t.id, t.thumbs.firstOrNull()?.url) }
                    .map { f -> CoverColors.of(f) }.map { it.first() }.distinct().take(3).toList()
                r.put("cores", JSONArray(cores))
            }
            // Lançamentos recentes de um artista.
            "artistReleases" -> cachedAsync(a, "artistReleases", a.getString("name")) {
                val name = a.getString("name")
                JSONObject().put("items", JSONArray().apply {
                    app.source.artistReleases(name, a.optString("url").takeIf { it.isNotBlank() }) { android.util.Log.i("Sonus", it) }.forEach { r ->
                        put(JSONObject().put("title", r.title).put("kind", r.kind).put("year", r.year ?: JSONObject.NULL)
                            .put("url", r.url ?: JSONObject.NULL).put("artist", name)
                            .put("thumb", r.thumbs.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL))
                    }
                })
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
            "setSettings" -> {
                val temaAntes = app.library.settings().optString("theme", "gradient")
                app.library.saveSettings(a); player.applySettings(a); app.applyStartTheme(a.optString("theme", "gradient"))
                // a tela manda os ajustes ao abrir: repintar a janela aí punha o azul padrão do Gradiente no meio do splash.
                // Só repinta se o TEMA mudou (as cores da música vêm em seguida por setSystemBars)
                if (a.optString("theme", "gradient") != temaAntes) (web.context as? MainActivity)?.applyWindowTheme(a.optString("theme", "gradient"))
            }
            "setSystemBars" -> (web.context as? MainActivity)?.applyWindowTheme(
                a.optString("theme", app.library.settings().optString("theme", "gradient")),
                statusColor = a.optString("statusColor").takeIf { it.isNotBlank() },
                navigationColor = a.optString("navigationColor").takeIf { it.isNotBlank() },
            )
            "setFollowingArtists" -> {
                val items = a.optJSONArray("items") ?: JSONArray()
                if (app.userData.save("sounos.following", items.toString())) player.setFollowingArtists(items)
            }
            // atualizações
            "checkUpdate" -> app.updater.check()
            "installUpdate" -> app.updater.install()
            "allowInstalls" -> app.updater.openInstallPermission()
            "openLinkSettings" -> {
                fun abrir(acao: String) = app.startActivity(android.content.Intent(acao, android.net.Uri.parse("package:${app.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                // sem a tela "Abrir por padrão" (Android < 12 ou fabricante que a tirou): os detalhes do app
                runCatching { abrir(if (android.os.Build.VERSION.SDK_INT >= 31) android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS else android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
                    .recoverCatching { abrir(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
            }
            // link de outro app → a mesma música, álbum, artista ou playlist no Sonus
            "abrirLink" -> async(a) { abrirLink(a.optString("texto")) }
            // imagem (trecho da letra): base64 de um JPEG/PNG → arquivo em cache/compartilhar → tela de compartilhar do
            // Android com a imagem e o texto (link da música). Sem o próprio Sonus na lista.
            "compartilharImagem" -> runCatching {
                val arq = imagemTemporaria(a.optString("dados"), a.optString("tipo", "image/jpeg"))
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "app.sounos.arquivos", arq)
                val envio = android.content.Intent(android.content.Intent.ACTION_SEND).setType(a.optString("tipo", "image/jpeg"))
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .apply { a.optString("texto").takeIf(String::isNotBlank)?.let { putExtra(android.content.Intent.EXTRA_TEXT, it) } }
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                envio.clipData = android.content.ClipData.newRawUri("", uri)       // prévia da imagem na tela de compartilhar
                val escolha = android.content.Intent.createChooser(envio, a.optString("titulo").ifBlank { null })
                    .putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(app, MainActivity::class.java)))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(escolha)
            }
            // salva a imagem na galeria (Pictures/Sonus); Android 10+ não pede permissão
            "salvarImagem" -> JSONObject().put("ok", runCatching { salvarNaGaleria(a.optString("dados"), a.optString("nome", "Sonus"), a.optString("tipo", "image/jpeg")) }
                .onFailure { android.util.Log.w("Sonus", "salvar imagem falhou", it) }.getOrDefault(false)).toString()
            "copiarTexto" -> onMain {
                val cm = app.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText(a.optString("rotulo", "Sonus"), a.optString("texto")))
                "{}"
            }
            // compartilhar música/álbum/artista: a tela de compartilhar do Android com o texto e o link (sem o próprio Sonus)
            "compartilhar" -> runCatching {
                val envio = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, a.optString("texto"))
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, a.optString("titulo"))
                val escolha = android.content.Intent.createChooser(envio, a.optString("titulo").ifBlank { null })
                    .putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(app, MainActivity::class.java)))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(escolha)
            }
            // links da tela "Apoie o Sonus" abrem no navegador do celular (só https)
            "openUrl" -> a.optString("url").takeIf { it.startsWith("https://") }?.let { u ->
                app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "seenUpdate" -> app.updater.clearJustUpdated()
            "setUpdateUrl" -> app.updater.setTestUrl(a.optString("url").ifBlank { null })      // só em build de teste
            "clearCache" -> app.io.launch {
                // as músicas completas da biblioteca ("Minhas músicas", tocam offline) ficam, como a tela promete
                val minhas = app.library.cachedTracks().keys.filter { app.audio.isFreshComplete(it) }.toSet()
                app.audio.clear(minhas); app.cacheBudget.clear()
                send("library", JSONObject())
                send("storage", JSONObject(call("getStorage", "{}")))
            }
        }
    }

    private fun bytesDe(dados: String): ByteArray = android.util.Base64.decode(dados.substringAfter("base64,"), android.util.Base64.DEFAULT)

    /** Grava a imagem em cache/compartilhar (o provedor de arquivos entrega só essa pasta). Uma por vez: a anterior sai. */
    private fun imagemTemporaria(dados: String, tipo: String): java.io.File {
        val pasta = java.io.File(app.cacheDir, "compartilhar").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        return java.io.File(pasta, "sonus-${System.currentTimeMillis()}.${if (tipo.endsWith("png")) "png" else "jpg"}").apply { writeBytes(bytesDe(dados)) }
    }

    private fun salvarNaGaleria(dados: String, nome: String, tipo: String): Boolean {
        val bytes = bytesDe(dados)
        val ext = if (tipo.endsWith("png")) "png" else "jpg"
        val limpo = nome.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "Sonus" }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val valores = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$limpo.$ext")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, tipo)
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sonus")
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores) ?: return false
            app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            return true
        }
        return false
    }

    /** Link de outro app de música → a mesma música (ou o álbum) no Sonus. */
    private suspend fun abrirLink(texto: String): JSONObject {
        val p = LinkDeMusica(SonusHttp.http).ler(texto) ?: return JSONObject().put("erro", "link")
        val base = JSONObject().put("servico", p.servico).put("titulo", p.titulo).put("artista", p.artista)
        if (p.tipo == "artista") {
            // YouTube: o canal dá o nome; outros apps: o nome (a página do artista acha o canal pelo nome exato)
            val (nome, url) = p.canalUrl?.let { app.source.canal(it) } ?: (p.artista to null)
            if (nome.isBlank()) return base.put("erro", "nao_achei")
            return base.put("tipo", "artista").put("nome", nome).put("url", url ?: JSONObject.NULL)
        }
        val listaUrl = p.playlistUrl
        if (p.tipo == "playlist" && listaUrl != null) {
            // playlist do YouTube / YT Music: a lista já vem com as músicas, sem procurar uma a uma
            val lista = runCatching { app.source.album(listaUrl) }.getOrNull() ?: return base.put("erro", "nao_achei")
            val tracks = lista.items.distinctBy { it.id }.take(LinkDeMusica.MAX_FAIXAS)
            if (tracks.isEmpty()) return base.put("erro", "nao_achei")
            return base.put("tipo", "playlist").put("titulo", lista.name).put("artista", lista.artist).put("tracks", Json.tracks(tracks)).put("total", tracks.size)
        }
        if (p.tipo == "playlist") {
            // cada música da playlist procurada como um link de música (4 de cada vez); as não achadas ficam de fora
            val sem = kotlinx.coroutines.sync.Semaphore(if (p.faixas.all { it.videoId != null }) 8 else 4)   // pelo id é rápido (playlist do Sonus)
            // andamento para a tela ("Procurando 37 de 100") a cada música, no máximo umas 4 vezes por segundo
            val feitas = java.util.concurrent.atomic.AtomicInteger(); var ultimo = 0L
            send("linkProgresso", JSONObject().put("feitas", 0).put("total", p.faixas.size).put("titulo", p.titulo).put("servico", p.servico))
            val achadas = kotlinx.coroutines.coroutineScope {
                p.faixas.map { f -> async { sem.withPermit { runCatching { app.source.encontrar(f) }.getOrNull() }.also {
                    val n = feitas.incrementAndGet(); val agora = System.currentTimeMillis()
                    if (agora - ultimo > 250 || n == p.faixas.size) { ultimo = agora; send("linkProgresso", JSONObject().put("feitas", n).put("total", p.faixas.size).put("titulo", p.titulo).put("servico", p.servico)) }
                } } }.awaitAll()
            }.filterNotNull().distinctBy { it.id }
            if (achadas.isEmpty()) return base.put("erro", "nao_achei")
            return base.put("tipo", "playlist").put("tracks", Json.tracks(achadas)).put("total", p.faixas.size)
        }
        if (p.tipo == "album") {
            p.playlistUrl?.let { return base.put("tipo", "album").put("url", it) }
            val al = app.source.encontrarAlbum(p) ?: return base.put("erro", "nao_achei")
            return base.put("tipo", "album").put("url", al.url).put("titulo", al.name).put("artista", al.artist)
                .put("thumb", al.thumbs.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL)
        }
        val t = app.source.encontrar(p) ?: return base.put("erro", "nao_achei")
        return base.put("tipo", "musica").put("track", Json.track(t))
    }

    /** Quantos endereços de música o Sonus pode abrir (Android 12+: a pessoa marca em "Abrir por padrão"). */
    private fun linksStatus(): JSONObject {
        if (android.os.Build.VERSION.SDK_INT < 31) return JSONObject().put("ligado", true).put("suportado", true)
        return runCatching {
            val dvm = app.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            val st = dvm.getDomainVerificationUserState(app.packageName) ?: return@runCatching JSONObject().put("ligado", false)
            // endereço que outro app já verificou (YouTube, YT Music, Amazon Music instalados) o Android não deixa marcar:
            // a marcação volta sozinha. Esses saem da conta, senão o pedido de "marcar todos" nunca termina.
            val pm = app.packageManager
            fun apps(host: String) = runCatching {
                pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://$host/"))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE), android.content.pm.PackageManager.MATCH_ALL)
                    .map { it.activityInfo.packageName }.toSet()
            }.getOrDefault(emptySet())
            val navegadores = apps("sonus.invalid") + app.packageName
            val mapa = st.hostToStateMap.filter { (host, estado) ->
                estado != android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE || (apps(host) - navegadores).isEmpty()
            }
            val ativos = mapa.values.count { it != android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE }
            JSONObject().put("suportado", true).put("ativos", ativos).put("total", mapa.size)
                .put("ligado", st.isLinkHandlingAllowed && mapa.isNotEmpty() && ativos >= mapa.size)
        }.getOrElse { JSONObject().put("ligado", true) }
    }

    private fun downloadedTracks(): List<app.sounos.extractor.Track> = app.library.downloadedMeta().keys().asSequence().mapNotNull { id ->
        app.library.downloadedTrack(id)?.takeIf { app.library.isDownloaded(id) }
    }.distinctBy { it.id }.toList()

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
        val downloaded = downloadedTracks()
        val downloadedIds = downloaded.map { it.id }.toSet()
        val cached = known.values.filter { app.audio.isFreshComplete(it.id) && it.id !in downloadedIds }.distinctBy { it.id }
        app.library.pruneCached(known.values.filter { app.audio.isFreshComplete(it.id) }.map { it.id }.toSet())
        return JSONObject().put("downloaded", Json.tracks(downloaded)).put("cached", Json.tracks(cached))
    }

    /** Plano de reserva para quando o endpoint de catálogo do YouTube Music estiver indisponível. */
    private suspend fun fallbackArtistTracks(name: String): List<Track> = withContext(Dispatchers.IO) {
        val norm = { value: String -> TitleNormalizer.key("", value).trim() }
        val target = norm(name)
        val matches = { track: Track ->
            track.artists.ifEmpty { listOf(track.artist) }.map(norm).any { artist ->
                artist == target || (target.length > 2 && " $artist ".contains(" $target ")) ||
                    (artist.length > 2 && " $target ".contains(" $artist "))
            }
        }
        val songs = mutableListOf<Track>()
        var page = app.source.search(name, SearchFilter.SONGS)
        var next = page.nextPage
        songs += page.items.filter(matches)
        var pages = 0
        while (next != null && pages++ < 40) {
            page = runCatching { app.source.search(name, SearchFilter.SONGS, next) }.getOrNull() ?: break
            page.items.filter(matches).filter { track -> songs.none { it.id == track.id } }.forEach(songs::add)
            next = page.nextPage
        }
        MusicPicks.clean(songs)
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
            // abriu a mesma página há pouco (artista, álbum, busca): o que está no disco basta, sem refazer na rede
            // (abrir o mesmo artista 5 vezes baixava a discografia inteira 5 vezes)
            // resposta guardada vazia nunca é "recente": tenta de novo (uma falha passageira deixava a página vazia 10 min)
            val vazio = { o: JSONObject -> o.optJSONArray("items")?.length() == 0 }
            val recente = cached != null && !vazio(cached) && method != "search" && System.currentTimeMillis() - (atualizadoEm[cacheKey] ?: 0) < 10 * 60_000L
            if (cached != null) {
                val first = JSONObject(cached.toString())
                if (method == "search") first.put("hasMore", false) // o cursor do NewPipe não sobrevive à sessão
                first.put("isCached", true)
                send("reply", first.put("reqId", req))
                if (recente) return@launch
            }

            val networkStarted = System.nanoTime()
            val fresh = runCatching { withContext(Dispatchers.IO) { work() } }
            val networkMs = (System.nanoTime() - networkStarted) / 1_000_000
            if (fresh.isFailure) {
                android.util.Log.i("Sonus", "metadados metodo=$method etapa=rede_ms=$networkMs resultado=falha cache=${cached != null}")
                if (cached == null) send("reply", JSONObject().put("error", fresh.exceptionOrNull()?.message ?: "erro").put("reqId", req))
                return@launch
            }

            val result = fresh.getOrThrow().put("isCached", false)
            // veio vazio da rede mas o guardado tem conteúdo: fica o guardado (não troca página boa por vazia)
            if (vazio(result) && cached != null && !vazio(cached)) {
                android.util.Log.i("Sonus", "metadados metodo=$method etapa=rede_vazia mantido=cache")
                return@launch
            }
            if (!vazio(result)) atualizadoEm[cacheKey] = System.currentTimeMillis()
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
