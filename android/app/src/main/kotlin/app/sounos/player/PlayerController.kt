package app.sounos.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
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
import app.sounos.extractor.QueuePrefetcher
import app.sounos.extractor.ResolvedAudio
import app.sounos.extractor.ResolvedTrack
import app.sounos.extractor.Track
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engine = AudioEngine(this)

    val queue = mutableListOf<Track>()
    private var original = listOf<Track>()
    var index = -1; private set
    var repeat = Repeat.OFF; private set
    var shuffle = false; private set
    var context: JSONObject = JSONObject().put("type", "radio"); private set
    var suggestions = listOf<Track>(); private set
    var current: ResolvedTrack? = null; private set
    var durationMs = 0L; private set
    var loading = false; private set
    var colors = listOf("#2F6BFF", "#1D4ED8", "#60A5FA", "#60A5FA"); private set
    var lyrics: Lyrics.Result? = null; private set
    private var lyricsFor: String? = null
    private var token = 0
    private var fillJob: Job? = null
    private var resumeAtMs = 0L                     // sessão restaurada: continua de onde parou
    private val listeners = mutableListOf<(String, JSONObject) -> Unit>()

    private val prefetcher = QueuePrefetcher(app.resolver, app.audio, app.covers) { r ->
        // a próxima ficou inteira em disco: mede o volume dela agora, para tocar já equilibrada
        if (app.library.gain(r.track.id) == null) app.io.launch {
            Loudness.analyzeFile(app.audio.file(r.track.id))?.let { app.library.setGain(r.track.id, it) }
        }
    }

    init { restoreSession(); applySettings(app.library.settings()) }

    // ---------------- eventos para a interface ----------------
    fun addListener(l: (String, JSONObject) -> Unit) { listeners += l }
    fun removeListener(l: (String, JSONObject) -> Unit) { listeners -= l }
    private fun emit(name: String, payload: JSONObject) = listeners.toList().forEach { it(name, payload) }
    private fun emitState() { emit("state", state()); PlaybackService.update(app) }

    val currentTrack: Track? get() = queue.getOrNull(index)
    val isPlaying get() = engine.isPlaying
    fun positionMs() = if (current == null) resumeAtMs else engine.positionMs()

    fun state(): JSONObject = JSONObject()
        .put("queue", Json.tracks(queue)).put("index", index)
        .put("current", currentTrack?.let { Json.track(it) } ?: JSONObject.NULL)
        .put("playing", engine.isPlaying).put("loading", loading)
        .put("repeat", repeat.name.lowercase()).put("shuffle", shuffle)
        .put("context", context).put("suggestions", Json.tracks(suggestions))
        .put("colors", JSONArray(colors)).put("durationMs", durationMs).put("positionMs", positionMs())
        .put("downloaded", currentTrack?.let { app.library.isDownloaded(it.id) } ?: false)

    // pending = ainda procurando (a tela não deve concluir "sem letra" e sair do modo letra)
    @Volatile private var lyricsPending = false
    fun lyricsJson(): JSONObject = JSONObject().put("id", lyricsFor ?: JSONObject.NULL).put("lyrics", lyrics?.toJson() ?: JSONObject.NULL)
        .put("pending", lyricsPending)

    // ---------------- comandos ----------------
    fun playQueue(list: List<Track>, start: Int, ctx: JSONObject) {
        if (list.isEmpty()) return
        queue.clear(); queue.addAll(list); original = list.toList()
        index = start.coerceIn(0, list.lastIndex); context = ctx; resumeAtMs = 0
        if (shuffle) shuffleRest()
        startCurrent()
    }

    fun playIndex(i: Int) { if (i in queue.indices) { index = i; resumeAtMs = 0; startCurrent() } }

    fun playSuggestion(t: Track) {
        val existing = queue.indexOfFirst { it.id == t.id }
        if (existing >= 0) { queue.removeAt(existing); if (existing < index) index-- }
        queue.add(index + 1, t); index++
        resumeAtMs = 0
        startCurrent()
    }

    fun toggle() = if (engine.isPlaying) pause() else play()

    fun play() {
        if (index < 0 || loading) return
        if (current == null) { startCurrent(resumeAtMs); return }
        if (!requestFocus()) return
        engine.play(); emitState(); ticker()
    }

    fun pause() { engine.pause(); emitState(); saveSession() }

    fun next(auto: Boolean = false) {
        if (index < 0) return
        if (auto && repeat == Repeat.ONE) { engine.seekTo(0); engine.play(); renewSuggestions(); return }
        when {
            index + 1 < queue.size -> index++
            isRadio() && topUpRadio() -> index++
            repeat == Repeat.ALL || !auto -> index = 0
            else -> { engine.pause(); engine.seekTo(0); emitState(); return }
        }
        resumeAtMs = 0
        startCurrent()
    }

    fun prev() {
        if (engine.positionMs() > 3000 || repeat == Repeat.ONE) { engine.seekTo(0); emitState(); return }
        index = if (index > 0) index - 1 else if (repeat == Repeat.ALL) queue.lastIndex else 0
        resumeAtMs = 0
        startCurrent()
    }

    fun seek(ms: Long) { if (current == null) resumeAtMs = ms else engine.seekTo(ms); emit("progress", progress()) }

    fun setRepeat(r: Repeat) {
        repeat = r
        if (r == Repeat.ONE) computeSuggestions()
        updatePrefetch(); emitState(); saveSession()
    }

    fun setShuffle(on: Boolean) {
        shuffle = on
        val cur = currentTrack
        if (on) shuffleRest()
        else if (cur != null) { queue.clear(); queue.addAll(original); index = queue.indexOfFirst { it.id == cur.id }.coerceAtLeast(0) }
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
                app.covers.get(r.track, pin = true)                       // capa guardada
                runCatching { app.lyrics.get(r.track) }                   // letra guardada
                app.library.markDownloaded(r.track.copy(id = t.id))
                main.post { emit("download", JSONObject().put("id", t.id).put("p", 1).put("done", true)); if (currentTrack?.id == t.id) emitState() }
            }.onFailure { main.post { emit("download", JSONObject().put("id", t.id).put("error", true)) } }
        }
    }

    // ---------------- tocar a música atual ----------------
    private fun startCurrent(startMs: Long = 0) {
        val t = currentTrack ?: return
        val my = ++token
        loading = true; current = null; durationMs = t.durationSec * 1000
        fillJob?.cancel()
        engine.release()
        app.covers.local(t.id)?.let { colors = CoverColors.of(it) }
        emitState()
        scope.launch {
            try {
                val downloaded = app.library.isDownloaded(t.id)
                val resolved = if (downloaded) null else withContext(Dispatchers.IO) { app.resolver.get(t.url, quality(), t) }
                if (my != token) return@launch
                val track = resolved?.track?.copy(id = t.id) ?: t
                current = resolved ?: ResolvedTrack(track, OFFLINE_AUDIO, emptyList(), System.currentTimeMillis())
                val source = when {
                    downloaded -> AudioEngine.Source.LocalFile(app.library.downloadFile(t.id).path)
                    app.audio.isComplete(t.id) -> AudioEngine.Source.LocalFile(app.audio.file(t.id).path)
                    else -> {
                        val done = AtomicBoolean(false); val failed = AtomicBoolean(false)
                        val r = resolved!!
                        fillJob = app.io.launch {
                            // sem internet no meio (ex.: renovar o link) a falha vira "falhou", nunca derruba o app
                            runCatching {
                                app.audio.fill(t.id, r.audio, Long.MAX_VALUE,
                                    refresh = { app.resolver.invalidate(t.url); app.resolver.get(t.url, quality(), t).audio })
                            }.getOrDefault(false).also { ok -> if (ok) done.set(true) else failed.set(true) }
                        }
                        AudioEngine.Source.Growing(GrowingFileSource(app.audio.file(t.id), { app.audio.totalLength(t.id).takeIf { it > 0 } ?: r.audio.contentLength }, { done.get() }, { failed.get() }))
                    }
                }
                requestFocus()
                engine.load(source, startMs, playWhenReady = true, knownGainDb = app.library.gain(t.id))
                app.library.addHistory(track, context)
                launch(Dispatchers.IO) {                                   // capa (fica guardada) + cores do fundo
                    app.covers.get(track, pin = true)?.let { f -> val c = CoverColors.of(f); main.post { if (my == token) { colors = c; emitState() } } }
                }
                loadLyrics(track, my)
                if (isRadio() && index >= queue.size - 2) topUpRadio()
                computeSuggestions()
                updatePrefetch()
                emitState(); saveSession(); ticker()
                // rádio que chegou depois do início (ou música baixada, que não passou pela internet)
                launch {
                    val more = runCatching {
                        resolved?.relatedLater?.await() ?: if (downloaded) track.videoId?.let { id ->
                            MusicPicks.clean(withContext(Dispatchers.IO) { app.source.radio(id) }, exclude = listOf(track)) } else null
                    }.getOrNull()
                    if (my == token && !more.isNullOrEmpty()) {
                        current = current?.copy(related = more)
                        computeSuggestions()
                        if (isRadio() && index >= queue.size - 2) topUpRadio()
                        updatePrefetch(); emitState()
                    }
                }
            } catch (e: Throwable) {
                if (my != token) return@launch
                loading = false
                emit("error", JSONObject().put("message", e.message ?: "erro"))
                emitState()
                main.postDelayed({ if (my == token && queue.size > 1) next(auto = true) }, 1500)
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
        if (durationMs > 0) this.durationMs = durationMs
        loading = false; emitState(); ticker()
    }

    override fun onEnded() {
        currentTrack?.let { t -> engine.measuredGainDb()?.let { app.library.setGain(t.id, it) } }
        next(auto = true)
    }

    private var errorsInARow = 0
    override fun onError(error: Throwable) {
        loading = false
        emit("error", JSONObject().put("message", error.message ?: "erro no áudio"))
        val my = token
        // 3 falhas seguidas (sem internet, por exemplo): para em vez de pular música atrás de música
        if (++errorsInARow >= 3 || queue.size <= 1) { errorsInARow = 0; engine.pause(); emitState(); return }
        main.postDelayed({ if (my == token) next(auto = true) }, 1200)
    }

    // ---------------- auxiliares ----------------
    private fun isRadio() = context.optString("type") == "radio"

    /** Música solta: quando a fila está acabando, emenda músicas relacionadas (lista que nunca acaba). */
    private fun topUpRadio(): Boolean {
        val related = MusicPicks.clean(current?.related.orEmpty(), exclude = queue).take(10)
        if (related.isEmpty()) return false
        queue.addAll(related); original = original + related
        return true
    }

    private fun computeSuggestions() {
        suggestions = MusicPicks.clean(current?.related.orEmpty(), exclude = listOfNotNull(currentTrack)).take(12)
    }

    private fun renewSuggestions() {
        suggestions = MusicPicks.clean(current?.related.orEmpty(), exclude = listOfNotNull(currentTrack)).shuffled().take(12)
        emit("suggestionsRenewed", JSONObject()); emitState()
    }

    private fun shuffleRest() {
        val head = queue.take(index + 1); val rest = queue.drop(index + 1).shuffled()
        queue.clear(); queue.addAll(head + rest)
    }

    private fun upcoming(): List<Track> = when {
        repeat == Repeat.ONE -> emptyList()
        repeat == Repeat.ALL -> (queue.drop(index + 1) + queue.take(index)).take(3)
        else -> queue.drop(index + 1).take(3)
    }

    private fun updatePrefetch() = prefetcher.onQueueChanged(upcoming().filterNot { app.library.isDownloaded(it.id) }, quality(), keep = currentTrack?.id)

    private fun quality(): AudioQuality = when (app.library.settings().optString("quality", "auto")) {
        "high" -> AudioQuality.HIGH
        "saver" -> AudioQuality.SAVER
        else -> if ((app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) AudioQuality.SAVER else AudioQuality.HIGH
    }

    fun progress(): JSONObject = JSONObject().put("pos", positionMs() / 1000.0).put("dur", durationMs / 1000.0).put("playing", engine.isPlaying)

    private var ticking = false
    private fun ticker() {
        if (ticking) return
        ticking = true
        main.post(object : Runnable {
            override fun run() {
                emit("progress", progress())
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
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnGain = engine.isPlaying; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.duckVolume = .25f
                AudioManager.AUDIOFOCUS_GAIN -> { engine.duckVolume = 1f; if (resumeOnGain) { resumeOnGain = false; play() } }
            }
        }.build()
    private fun requestFocus() = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    // ---------------- sessão (continuar de onde parou) ----------------
    fun saveSession() {
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
