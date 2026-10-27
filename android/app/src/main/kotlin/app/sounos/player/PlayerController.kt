package app.sounos.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import app.sounos.SounosApp
import app.sounos.data.CoverColors
import app.sounos.data.Json
import app.sounos.data.Lyrics
import app.sounos.engine.AudioEngine
import app.sounos.engine.GrowingFileSource
import app.sounos.engine.Loudness
import app.sounos.extractor.MusicPicks
import app.sounos.extractor.Sounos
import app.sounos.extractor.AudioQuality
import app.sounos.extractor.CoverStore
import app.sounos.extractor.QueuePrefetcher
import app.sounos.extractor.ResolvedAudio
import app.sounos.extractor.ResolvedTrack
import app.sounos.extractor.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cérebro do player: fila, repetir/aleatório, rádio (música solta), sugestões, download e pré-carregamento.
 * Vive no app (não na tela), então a música continua com a tela desligada ou em outro app.
 * A interface só mostra o estado e manda comandos.
 */
class PlayerController(private val app: SounosApp) : AudioEngine.Callbacks {

    enum class Repeat { OFF, ALL, ONE }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> android.util.Log.e("Sonus", "tarefa em segundo plano falhou", e) })
    val engine = AudioEngine(this)

    val queue = mutableListOf<Track>()
    private var original = listOf<Track>()
    var index = -1; private set
    var repeat = Repeat.OFF; private set
    var shuffle = false; private set
    var context: JSONObject = JSONObject().put("type", "radio"); private set
    var suggestions = listOf<Track>(); private set
    @Volatile private var followedArtistKeys: Set<String> = loadFollowingKeys()
    var current: ResolvedTrack? = null; private set
    var durationMs = 0L; private set
    var loading = false; private set
    private var buffering = false      // motor esperando o áudio voltar após um seek (aparece como `loading` na tela)
    private var playWhenReady = false
    var colors = listOf("#2F6BFF", "#1D4ED8", "#60A5FA", "#60A5FA"); private set
    var lyrics: Lyrics.Result? = null; private set
    private var lyricsFor: String? = null
    private var token = 0
    private var fillJob: Job? = null
    @Volatile private var currentUsesCache = false
    private var resumeAtMs = 0L                     // sessão restaurada: continua de onde parou
    private val listeners = mutableListOf<(String, JSONObject) -> Unit>()
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var online = hasValidatedNetwork()
    val isOnline: Boolean get() = online

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // usa o que o próprio aviso traz: "activeNetwork" pode vir nulo no Samsung mesmo com Wi-Fi funcionando
        override fun onLost(network: Network) = setOnline(false)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            setOnline(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    private val prefetcher = QueuePrefetcher(app.resolver, app.audio, app.covers, onNextReady = { r ->
        // a próxima ficou inteira em disco: mede o volume dela agora, para tocar já equilibrada
        app.library.markCached(r.track)
        if (app.library.gain(r.track.id) == null) app.io.launch {
            Loudness.analyzeFile(app.audio.file(r.track.id))?.let { app.library.setGain(r.track.id, it) }
        }
    }, onCacheChanged = { emit("library", JSONObject()) })

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        restoreSession(); app.motor.retomar(currentTrack); applySettings(app.library.settings())
    }

    // ---------------- eventos para a interface ----------------
    fun addListener(l: (String, JSONObject) -> Unit) { listeners += l; l("offline", JSONObject().put("offline", !online)) }
    fun removeListener(l: (String, JSONObject) -> Unit) { listeners -= l }
    private fun emit(name: String, payload: JSONObject) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { emit(name, payload) }; return }
        listeners.toList().forEach { it(name, payload) }
    }
    fun notifyLibraryChanged() = emit("library", JSONObject())
    private fun emitState() { emit("state", state()); PlaybackService.update(app) }

    val currentTrack: Track? get() = queue.getOrNull(index)
    val isPlaying get() = engine.isPlaying
    fun positionMs() = if (current == null) resumeAtMs else engine.positionMs()

    fun state(): JSONObject = JSONObject()
        .put("queue", Json.tracks(queue)).put("index", index)
        .put("current", currentTrack?.let { Json.track(it) } ?: JSONObject.NULL)
        .put("playing", engine.isPlaying).put("loading", loading || buffering).put("playWhenReady", playWhenReady)
        .put("repeat", repeat.name.lowercase()).put("shuffle", shuffle)
        .put("context", context).put("suggestions", Json.tracks(suggestions))
        .put("colors", JSONArray(colors)).put("durationMs", durationMs).put("positionMs", positionMs())
        .put("downloaded", currentTrack?.let { app.library.isDownloaded(it.id) } ?: false).put("offline", !online)

    // pending = ainda procurando (a tela não deve concluir "sem letra" e sair do modo letra)
    @Volatile private var lyricsPending = false
    fun lyricsJson(): JSONObject = JSONObject().put("id", lyricsFor ?: JSONObject.NULL).put("lyrics", lyrics?.toJson() ?: JSONObject.NULL)
        .put("pending", lyricsPending)

    // ---------------- comandos ----------------
    fun playQueue(list: List<Track>, start: Int, ctx: JSONObject, shufflePrepared: Boolean = false, shuffleMode: Boolean? = null) {
        if (list.isEmpty()) return
        shuffleMode?.let { shuffle = it }
        queue.clear(); queue.addAll(list); original = list.toList()
        index = start.coerceIn(0, list.lastIndex); context = ctx; resumeAtMs = 0
        if (shuffle && !shufflePrepared) shuffleRest()
        startCurrent()
    }

    fun playIndex(i: Int) { if (i in queue.indices) { index = i; resumeAtMs = 0; startCurrent() } }

    fun playSuggestion(t: Track) {
        if (!online && !isAvailableOffline(t)) return
        val existing = queue.indexOfFirst { it.id == t.id }
        if (existing >= 0) { queue.removeAt(existing); if (existing < index) index-- }
        queue.add(index + 1, t); index++
        resumeAtMs = 0
        startCurrent()
    }

    /** Atualizado pela ponte quando a pessoa segue ou deixa de seguir um artista. */
    fun setFollowingArtists(items: JSONArray) {
        followedArtistKeys = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { entry ->
                entry.optString("key").takeIf(String::isNotBlank) ?: entry.optString("name").takeIf(String::isNotBlank)?.let { CoverStore.artistKey(it) }
            }
        }.toSet()
        computeSuggestions()
        if (isRadio() && index >= queue.size - 2) topUpRadio()
        updatePrefetch(); emitState()
    }

    fun toggle() = if (engine.isPlaying || loading && playWhenReady) pause() else play()

    fun play() {
        if (index < 0) return
        if (loading) { playWhenReady = true; engine.play(); emitState(); return }
        val t = currentTrack ?: return
        if (!online && !isAvailableOffline(t)) { skipUnavailableOffline(); return }
        if (currentUsesCache && !app.audio.isFreshComplete(t.id)) { startCurrent(resumeAtMs); return }
        if (current == null) { startCurrent(resumeAtMs); return }
        if (!requestFocus()) return
        currentTrack?.takeUnless { app.library.isDownloaded(it.id) }?.let { app.audio.markHeard(it.id) }
        engine.play(); emitState(); ticker()
    }

    fun pause() { playWhenReady = false; buffering = false; engine.pause(); emitState(); saveSession() }

    /** Como a música atual vai terminar quando a próxima começar (para o motor de entendimento). */
    private var fimPendente = "trocou"

    /** afterError: a faixa não carregou — pula de verdade, mesmo com "repetir uma" (repetir uma faixa quebrada travava o player). */
    fun next(auto: Boolean = false, afterError: Boolean = false) {
        if (index < 0) return
        fimPendente = when { afterError -> "erro"; auto -> "completa"; else -> "pulou" }
        if (auto && repeat == Repeat.ONE && !afterError) {
            app.motor.terminou(durationMs, durationMs, "completa"); currentTrack?.let { app.motor.comecou(it, context) }
            engine.seekTo(0); engine.play(); return
        }
        when {
            index + 1 < queue.size -> index++
            repeat == Repeat.ALL -> index = 0
            isRadio() && topUpRadio() -> index++
            !auto -> index = 0
            else -> { app.motor.terminou(durationMs, durationMs, "completa"); engine.pause(); engine.seekTo(0); emitState(); return }
        }
        resumeAtMs = 0
        startCurrent()
    }

    fun prev() {
        if (engine.positionMs() > 3000 || repeat == Repeat.ONE) { engine.seekTo(0); emitState(); return }
        fimPendente = "voltou"
        index = if (index > 0) index - 1 else if (repeat == Repeat.ALL) queue.lastIndex else 0
        resumeAtMs = 0
        startCurrent()
    }

    // pulo pedido enquanto a música ainda carrega: vale assim que ela ficar pronta (antes se perdia)
    private var seekWhenReadyMs = -1L
    fun seek(ms: Long) {
        if (loading) seekWhenReadyMs = ms                 // ainda preparando o áudio: aplica quando ficar pronto
        if (current == null) resumeAtMs = ms else engine.seekTo(ms)
        emit("progress", progress())
    }

    fun setRepeat(r: Repeat) {
        repeat = r
        updatePrefetch(); emitState(); saveSession()
    }

    fun setShuffle(on: Boolean) {
        val changed = shuffle != on
        shuffle = on
        if (changed && on) {
            shuffleRest()
            if (isRadio()) refreshRadioQueueForShuffle()
        }
        updatePrefetch(); emitState(); saveSession()
    }

    fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val cur = currentTrack
        queue.add(to, queue.removeAt(from))
        index = queue.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
        updatePrefetch(); emitState(); saveSession()
    }

    fun applySettings(s: JSONObject) {
        engine.normalize = s.optBoolean("normalize", true)
        engine.skipSilence = s.optBoolean("skipSilence", true)
        // país e idioma valem na hora para busca e sugestões (antes só depois de reabrir o app)
        Sounos.setRegion(s.optString("language", "pt-BR").substringBefore('-'), s.optString("country", "BR"))
    }

    // ---------------- download (um toque, sem janela) ----------------
    fun download(t: Track) {
        if (app.library.isDownloaded(t.id)) { emit("download", JSONObject().put("id", t.id).put("p", 1).put("done", true)); return }
        // "Baixar só no Wi-Fi": nos dados móveis não baixa
        val metered = (app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
        if (metered && app.library.settings().optBoolean("wifiOnlyDownloads")) {
            emit("download", JSONObject().put("id", t.id).put("error", true).put("reason", "wifi")); return
        }
        app.io.launch {
            runCatching {
                val r = app.resolver.get(t.url, AudioQuality.HIGH, t)
                var last = 0L
                val ok = app.audio.fill(r.track.id, r.audio, Long.MAX_VALUE,
                    refresh = { app.resolver.invalidate(t.url); app.resolver.get(t.url, AudioQuality.HIGH, t).audio }) { have, total ->
                    val now = System.currentTimeMillis()
                    if (total > 0 && now - last > 150) { last = now; main.post { emit("download", JSONObject().put("id", t.id).put("p", have.toDouble() / total)) } }
                }
                check(ok && app.audio.exportTo(r.track.id, app.library.downloadFile(t.id))) { "download falhou" }
                app.covers.get(r.track, pin = true, large = false) { android.util.Log.i("Sonus", "cover $it") } // miniatura guardada junto ao download
                runCatching { app.lyrics.get(r.track) }                   // letra guardada
                app.library.markDownloaded(r.track.copy(id = t.id))
                app.covers.pinLocal(t.id, r.track.thumbs.maxByOrNull { it.width * it.height }?.url)
                main.post {
                    emit("download", JSONObject().put("id", t.id).put("p", 1).put("done", true))
                    emit("library", JSONObject())
                    if (currentTrack?.id == t.id) emitState()
                }
            }.onFailure {
                main.post {
                    emit("download", JSONObject().put("id", t.id).put("error", true))
                    emit("library", JSONObject())
                }
            }
        }
    }

    // ---------------- tocar a música atual ----------------
    private fun startCurrent(startMs: Long = 0) {
        val t = currentTrack ?: return
        if (!online && !isAvailableOffline(t)) { skipUnavailableOffline(); return }
        app.motor.trocando(t.id, positionMs(), durationMs, fimPendente); fimPendente = "trocou"
        app.audio.protectedIds = (upcoming().map { it.id } + t.id).toSet()
        val my = ++token
        loading = true; buffering = false; playWhenReady = true; seekWhenReadyMs = -1; current = null; currentUsesCache = false; durationMs = t.durationSec * 1000
        fillJob?.cancel()
        engine.release()
        app.covers.local(t.id)?.let { colors = CoverColors.of(it) }
        emitState()
        scope.launch {
            try {
                val downloaded = app.library.isDownloaded(t.id)
                // já está inteira no celular (baixada ou no cache): toca NA HORA, sem passar pelo extrator;
                // as sugestões/rádio vêm depois, em segundo plano
                val local = downloaded || app.audio.isFreshComplete(t.id)
                val resolved = if (local || !online) null else try {
                    withContext(Dispatchers.IO) { app.resolver.get(t.url, quality(), t) }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (app.audio.isFreshComplete(t.id)) null else throw e
                }
                if (my != token) return@launch
                val track = resolved?.track?.copy(id = t.id) ?: t
                current = resolved ?: ResolvedTrack(track, OFFLINE_AUDIO, emptyList(), System.currentTimeMillis())
                if (!resolved?.related.isNullOrEmpty()) saveRadio(track, resolved!!.related) else restoreRadio(track, my)
                val source = when {
                    downloaded -> AudioEngine.Source.LocalFile(app.library.downloadFile(t.id).path)
                    app.audio.isFreshComplete(t.id) -> {
                        currentUsesCache = true
                        app.library.markCached(track)
                        emit("library", JSONObject())
                        AudioEngine.Source.LocalFile(app.audio.file(t.id).path)
                    }
                    else -> {
                        val done = AtomicBoolean(false); val failed = AtomicBoolean(false)
                        val r = resolved!!
                        fillJob = app.io.launch {
                            // sem internet no meio (ex.: renovar o link) a falha vira "falhou", nunca derruba o app
                            runCatching {
                                app.audio.fill(t.id, r.audio, Long.MAX_VALUE,
                                    refresh = { app.resolver.invalidate(t.url); app.resolver.get(t.url, quality(), t).audio })
                            }.getOrDefault(false).also { ok ->
                                if (ok) {
                                    done.set(true)
                                    if (app.audio.isFreshComplete(t.id)) {
                                        app.library.markCached(track)
                                        if (my == token) currentUsesCache = true
                                    }
                                    main.post { emit("library", JSONObject()) }
                                } else failed.set(true)
                            }
                        }
                        AudioEngine.Source.Growing(GrowingFileSource(app.audio.file(t.id), { app.audio.totalLength(t.id).takeIf { it > 0 } ?: r.audio.contentLength }, { done.get() }, { failed.get() }))
                    }
                }
                requestFocus()
                engine.load(source, startMs, playWhenReady = playWhenReady, knownGainDb = app.library.gain(t.id))
                app.library.addHistory(track, context)
                app.motor.comecou(track, context)
                launch(Dispatchers.IO) {                                   // capa (fica guardada) + cores do fundo
                    runCatching {
                        app.covers.get(track, pin = app.library.isDownloaded(track.id), large = true) { android.util.Log.i("Sonus", "cover $it") }
                    }.getOrNull()?.let { f ->
                        val c = CoverColors.of(f); main.post { if (my == token) { colors = c; emitState() } }
                    }
                }
                loadLyrics(track, my)
                if (isRadio() && index >= queue.size - 2) topUpRadio()
                computeSuggestions()
                updatePrefetch()
                emitState(); saveSession(); ticker()
                // rádio que chegou depois do início (ou música baixada, que não passou pela internet)
                launch {
                    val more = runCatching {
                        resolved?.relatedLater?.await() ?: if (local && online) track.videoId?.let { id ->
                            MusicPicks.clean(withContext(Dispatchers.IO) { app.source.radio(id) }, exclude = listOf(track)) } else null
                    }.getOrNull()
                    if (my == token && !more.isNullOrEmpty()) {
                        current = current?.copy(related = more)
                        saveRadio(track, more)
                        computeSuggestions()
                        if (isRadio() && index >= queue.size - 2) topUpRadio()
                        updatePrefetch(); emitState()
                    }
                }
            } catch (e: Throwable) {
                if (my != token) return@launch
                loading = false
                playWhenReady = false
                emit("error", JSONObject().put("message", e.message ?: "erro"))
                emitState()
                main.postDelayed({ if (my == token && queue.size > 1) next(auto = true, afterError = true) }, 1500)
            }
        }
    }

    private fun loadLyrics(t: Track, my: Int) {
        lyrics = app.lyrics.cached(t); lyricsFor = t.id
        lyricsPending = lyrics == null
        emit("lyrics", lyricsJson())
        if (lyrics != null) return
        scope.launch {
            val l = runCatching { app.lyrics.get(t) }.getOrNull()
            if (my == token) { lyrics = l; lyricsFor = t.id; lyricsPending = false; emit("lyrics", lyricsJson()) }
        }
    }

    // ---------------- callbacks do motor de áudio ----------------
    override fun onReady(durationMs: Long) {
        errorsInARow = 0
        currentTrack?.takeUnless { app.library.isDownloaded(it.id) }?.let { app.audio.markHeard(it.id) }
        if (durationMs > 0) this.durationMs = durationMs
        if (seekWhenReadyMs >= 0) { engine.seekTo(seekWhenReadyMs); seekWhenReadyMs = -1 }
        loading = false; playWhenReady = engine.isPlaying; emitState(); ticker()
    }

    override fun onBuffering(buffering: Boolean) {
        if (this.buffering == buffering) return
        this.buffering = buffering
        emitState()
    }

    override fun onEnded() {
        buffering = false
        currentTrack?.let { t -> engine.measuredGainDb()?.let { app.library.setGain(t.id, it) } }
        next(auto = true)
    }

    private var errorsInARow = 0
    override fun onError(error: Throwable) {
        loading = false; buffering = false
        playWhenReady = false
        emit("error", JSONObject().put("message", error.message ?: "erro no áudio"))
        val my = token
        // 3 falhas seguidas (sem internet, por exemplo): para em vez de pular música atrás de música
        if (++errorsInARow >= 3 || queue.size <= 1) { errorsInARow = 0; engine.pause(); emitState(); return }
        main.postDelayed({ if (my == token) next(auto = true, afterError = true) }, 1200)
    }

    // ---------------- auxiliares ----------------
    private fun isRadio() = context.optString("type") == "radio"

    private fun isAvailableOffline(t: Track) = runCatching {
        app.library.isDownloaded(t.id) || app.audio.isFreshComplete(t.id)
    }.getOrDefault(false)

    private fun offlineTracks(): List<Track> {
        val known = linkedMapOf<String, Track>()
        app.library.cachedTracks().forEach { (id, track) -> known[id] = track }
        runCatching { app.library.history() }.getOrNull()?.let { history ->
            for (i in 0 until history.length()) runCatching { Json.track(history.getJSONObject(i).getJSONObject("track")) }
                .getOrNull()?.let { known.putIfAbsent(it.id, it) }
        }
        val downloads = runCatching { app.library.downloadedMeta() }.getOrDefault(JSONObject())
        downloads.keys().asSequence().forEach { id -> app.library.downloadedTrack(id)?.let { known[id] = it } }
        queue.forEach { known.putIfAbsent(it.id, it) }
        return known.values.filter(::isAvailableOffline)
    }

    private fun skipUnavailableOffline() {
        if (isRadio() && repeat != Repeat.ONE) topUpRadio()
        val forward = (index + 1 until queue.size).firstOrNull { isAvailableOffline(queue[it]) }
        val wrapped = if (repeat == Repeat.ALL || isRadio()) {
            (0..index.coerceAtMost(queue.lastIndex)).firstOrNull { isAvailableOffline(queue[it]) }
        } else null
        val nextIndex = if (repeat == Repeat.ONE) null else forward ?: wrapped
        if (nextIndex != null) {
            index = nextIndex
            resumeAtMs = 0
            startCurrent()
        } else {
            ++token
            fillJob?.cancel()
            engine.release()
            loading = false; buffering = false
            playWhenReady = false
            current = null
            currentUsesCache = false
            resumeAtMs = 0
            emitState()
            saveSession()
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        return runCatching {
            val network = connectivity.activeNetwork ?: return false
            val caps = connectivity.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun setOnline(available: Boolean) {
        main.post {
            try {
                if (available == online) return@post
                online = available
                emit("offline", JSONObject().put("offline", !online))
                computeSuggestions()
                updatePrefetch()
                if (!online) {
                    val t = currentTrack
                    if (t != null && !isAvailableOffline(t)) skipUnavailableOffline() else emitState()
                } else {
                    emitState()
                    val t = currentTrack ?: return@post
                    val id = t.videoId ?: return@post
                    scope.launch {
                        val more = runCatching { withContext(Dispatchers.IO) { app.source.radio(id) } }.getOrDefault(emptyList())
                        if (online && currentTrack?.id == t.id && more.isNotEmpty()) {
                            val clean = MusicPicks.clean(more, exclude = listOf(t))
                            current = current?.copy(related = clean)
                            saveRadio(t, clean)
                            computeSuggestions()
                            if (isRadio() && index >= queue.size - 2) topUpRadio()
                            updatePrefetch(); emitState()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Sonus", "não foi possível atualizar o modo offline", e)
            }
        }
    }

    /** Música solta: quando a fila está acabando, emenda músicas relacionadas (lista que nunca acaba). */
    private fun topUpRadio(): Boolean {
        val source = current?.related?.takeIf { it.isNotEmpty() } ?: if (online) emptyList() else offlineTracks()
        val related = mixFollowed(MusicPicks.clean(source, exclude = queue)).take(10)
        if (related.isEmpty()) return false
        queue.addAll(related); original = original + related
        return true
    }

    private fun computeSuggestions() {
        val source = current?.related?.takeIf { it.isNotEmpty() } ?: if (online) emptyList() else offlineTracks()
        suggestions = mixFollowed(MusicPicks.clean(source, exclude = listOfNotNull(currentTrack))).take(12)
    }

    /** Uma música seguida entra a cada cinco posições quando o próprio rádio a recomenda. */
    private fun mixFollowed(items: List<Track>): List<Track> {
        if (followedArtistKeys.isEmpty()) return items
        val followed = items.filter { track -> track.artists.ifEmpty { listOf(track.artist) }.any { CoverStore.artistKey(it) in followedArtistKeys } }.toMutableList()
        if (followed.isEmpty()) return items
        val other = items.filterNot { track -> track.artists.ifEmpty { listOf(track.artist) }.any { CoverStore.artistKey(it) in followedArtistKeys } }.toMutableList()
        val mixed = ArrayList<Track>(items.size)
        while (mixed.size < items.size) {
            val addFollowed = mixed.size % 5 == 4 && followed.isNotEmpty()
            val next = if (addFollowed) followed.removeAt(0) else if (other.isNotEmpty()) other.removeAt(0) else followed.removeAt(0)
            mixed += next
        }
        return mixed
    }

    private fun loadFollowingKeys(): Set<String> = runCatching {
        val saved = app.userData.load("sounos.following") ?: return@runCatching emptySet()
        val list = JSONArray(saved)
        (0 until list.length()).mapNotNull { index ->
            list.optJSONObject(index)?.let { entry ->
                entry.optString("key").takeIf(String::isNotBlank) ?: entry.optString("name").takeIf(String::isNotBlank)?.let { CoverStore.artistKey(it) }
            }
        }.toSet()
    }.getOrDefault(emptySet())

    private fun radioCacheKey(track: Track) = "radio:${track.videoId ?: track.id.removePrefix("yt:")}"

    private fun saveRadio(track: Track, related: List<Track>) {
        if (related.isEmpty()) return
        val key = radioCacheKey(track)
        app.io.launch {
            val started = System.nanoTime()
            val trim = runCatching { app.metadata.write(key, JSONObject().put("items", Json.tracks(related)).toString()) }.getOrNull()
            android.util.Log.i("Sonus", "metadados metodo=radio etapa=gravar_ms=${(System.nanoTime() - started) / 1_000_000} itens=${related.size} lru_removidos=${trim?.removedBytes ?: 0}")
        }
    }

    private fun restoreRadio(track: Track, my: Int) {
        val key = radioCacheKey(track)
        app.io.launch {
            val started = System.nanoTime()
            val related = runCatching { app.metadata.read(key)?.let { Json.tracks(JSONObject(it).optJSONArray("items")) } }.getOrNull().orEmpty()
            android.util.Log.i("Sonus", "metadados metodo=radio etapa=disco_ms=${(System.nanoTime() - started) / 1_000_000} cache=${related.isNotEmpty()} itens=${related.size}")
            if (related.isEmpty()) return@launch
            main.post {
                if (my != token || currentTrack?.id != track.id || !current?.related.isNullOrEmpty()) return@post
                current = current?.copy(related = related)
                computeSuggestions()
                if (isRadio() && index >= queue.size - 2) topUpRadio()
                updatePrefetch(); emitState()
            }
        }
    }

    private fun shuffleRest() {
        val head = queue.take(index + 1)
        val oldRest = queue.drop(index + 1)
        var rest = oldRest.shuffled()
        if (rest.size > 1 && rest.map { it.id } == oldRest.map { it.id }) rest = rest.drop(1) + rest.first()
        queue.clear(); queue.addAll(head + rest)
    }

    /** O rádio recebe opções novas ao ligar o aleatório; a faixa atual e o que já tocou ficam fixos. */
    private fun refreshRadioQueueForShuffle() {
        val track = currentTrack ?: return
        val videoId = track.videoId ?: return
        val my = token
        scope.launch {
            val fresh = runCatching { withContext(Dispatchers.IO) { app.source.radio(videoId) } }.getOrDefault(emptyList())
            if (my != token || !shuffle || !isRadio() || currentTrack?.id != track.id || fresh.isEmpty()) return@launch
            val head = queue.take(index + 1)
            val seen = head.mapTo(hashSetOf()) { it.id }
            val existing = queue.drop(index + 1).filter { seen.add(it.id) }
            val incoming = MusicPicks.clean(fresh, exclude = head).filter { seen.add(it.id) }
            val candidates = (existing + incoming).shuffled().take(12)
            if (candidates.map { it.id } == queue.drop(index + 1).map { it.id }) return@launch
            queue.clear(); queue.addAll(head + candidates)
            current = current?.copy(related = fresh)
            computeSuggestions(); updatePrefetch(); emitState(); saveSession()
        }
    }

    private fun upcoming(): List<Track> = when {
        repeat == Repeat.ONE -> emptyList()
        repeat == Repeat.ALL -> (queue.drop(index + 1) + queue.take(index)).take(3)
        else -> queue.drop(index + 1).take(3)
    }

    private fun updatePrefetch() = prefetcher.onQueueChanged(
        upcoming().filterNot { app.library.isDownloaded(it.id) }, quality(), keep = currentTrack?.id, allowPrefetch = online,
    )

    private fun quality(): AudioQuality = when (app.library.settings().optString("quality", "auto")) {
        "high" -> AudioQuality.HIGH
        "saver" -> AudioQuality.SAVER
        else -> if ((app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) AudioQuality.SAVER else AudioQuality.HIGH
    }

    fun progress(): JSONObject = JSONObject().put("pos", positionMs() / 1000.0).put("dur", durationMs / 1000.0).put("playing", engine.isPlaying)
        .put("loading", loading || buffering).put("playWhenReady", playWhenReady)
        .put("id", currentTrack?.id ?: JSONObject.NULL)   // a tela ignora progresso de outra música

    private var ticking = false
    private var ticks = 0
    private fun ticker() {
        if (ticking) return
        ticking = true
        main.post(object : Runnable {
            override fun run() {
                emit("progress", progress())
                if (engine.isPlaying && ++ticks % 40 == 0) app.motor.marcar(positionMs(), durationMs)   // a cada 10 s: até onde tocou
                if (engine.isPlaying || loading) main.postDelayed(this, 250) else { ticking = false; PlaybackService.update(app) }
            }
        })
    }

    // ---------------- foco de áudio (ligação, outro app tocando) ----------------
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var resumeOnGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnGain = engine.isPlaying || loading && playWhenReady; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.duckVolume = .25f
                AudioManager.AUDIOFOCUS_GAIN -> { engine.duckVolume = 1f; if (resumeOnGain) { resumeOnGain = false; play() } }
            }
        }.build()
    private fun requestFocus() = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    // ---------------- sessão (continuar de onde parou) ----------------
    fun saveSession() {
        app.motor.marcar(positionMs(), durationMs)
        if (index < 0) return
        app.library.saveSession(JSONObject().put("queue", Json.tracks(queue)).put("original", Json.tracks(original))
            .put("index", index).put("pos", positionMs()).put("context", context)
            .put("repeat", repeat.name).put("shuffle", shuffle))
    }

    private fun restoreSession() {
        val s = app.library.session() ?: return
        runCatching {
            queue.addAll(Json.tracks(s.getJSONArray("queue")))
            original = Json.tracks(s.optJSONArray("original")).ifEmpty { queue.toList() }
            index = s.getInt("index").coerceIn(-1, queue.lastIndex)
            resumeAtMs = s.optLong("pos")
            context = s.optJSONObject("context") ?: context
            repeat = Repeat.valueOf(s.optString("repeat", "OFF")); shuffle = s.optBoolean("shuffle")
            currentTrack?.let { t -> durationMs = t.durationSec * 1000; app.covers.local(t.id)?.let { colors = CoverColors.of(it) } }
        }
    }

    companion object {
        private val OFFLINE_AUDIO = ResolvedAudio("", "audio/*", null, 0, -1, Long.MAX_VALUE, 0)
    }
}
