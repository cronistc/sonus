package app.sounos

import android.app.Application
import app.sounos.data.Backups
import app.sounos.data.Library
import app.sounos.data.Lyrics
import app.sounos.data.Json
import app.sounos.extractor.AudioCache
import app.sounos.extractor.CoverStore
import app.sounos.extractor.DiskCacheBudget
import app.sounos.extractor.DiskMetadataCache
import app.sounos.extractor.NewPipeSource
import app.sounos.extractor.Sounos
import app.sounos.extractor.StreamResolveCache
import app.sounos.player.PlayerController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONArray

/** Tudo que o app compartilha (um de cada), criado uma vez quando o app abre. */
class SounosApp : Application() {
    lateinit var source: NewPipeSource; private set
    lateinit var resolver: StreamResolveCache; private set
    lateinit var audio: AudioCache; private set
    lateinit var covers: CoverStore; private set
    lateinit var cacheBudget: DiskCacheBudget; private set
    lateinit var metadata: DiskMetadataCache; private set
    lateinit var library: Library; private set
    lateinit var userData: app.sounos.data.UserData; private set
    lateinit var lyrics: Lyrics; private set
    lateinit var player: PlayerController; private set
    lateinit var motor: app.sounos.motor.Motor; private set
    lateinit var updater: app.sounos.update.Updater; private set
    // erro inesperado numa tarefa fica no log; nunca derruba o app (nem a música)
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> android.util.Log.e("Sonus", "tarefa em segundo plano falhou", e) })

    override fun onCreate() {
        super.onCreate()
        instance = this
        // antes de tudo: se as preferências sumiram mas há snapshot de antes de uma atualização, recoloca (só acrescenta)
        Backups.restoreIfEmpty(this)
        library = Library(this)
        userData = app.sounos.data.UserData(File(filesDir, "userdata"))
        val downloadCheck = io.launch { runCatching { library.checkDownloads() } }
        val s = library.settings()
        applyStartTheme(s.optString("theme", "gradient"))
        Sounos.init(language = s.optString("language", "pt-BR").substringBefore('-'), country = s.optString("country", "BR"))
        source = NewPipeSource()
        resolver = StreamResolveCache(source)
        audio = AudioCache(File(cacheDir, "audio"), Sounos.http, maxTotalBytes = 6L * 1024 * 1024 * 1024) // cache de música: até 6 GB, sem prazo (encolhe se faltar espaço)
        cacheBudget = DiskCacheBudget(listOf(File(cacheDir, "covers"), File(cacheDir, "metadata")))
        metadata = DiskMetadataCache(File(cacheDir, "metadata"), cacheBudget)
        covers = CoverStore(File(filesDir, "covers"), File(cacheDir, "covers"), Sounos.http, budget = cacheBudget) // capas fixadas ficam fora do LRU
        io.launch {
            downloadCheck.join()
            val migration = reconcilePinnedCovers()
            val trim = cacheBudget.trim()
            if (migration.first > 0 || trim.removedFiles > 0) android.util.Log.i("Sonus", "cache migração_pin arquivos=${migration.first} bytes=${migration.second} lru_removidos=${trim.removedFiles}")
        }
        lyrics = Lyrics(File(filesDir, "lyrics"), Sounos.http)
        motor = app.sounos.motor.Motor(File(filesDir, "motor"), io, Sounos.http)     // motor de entendimento: aprende o uso, no celular
        player = PlayerController(this)
        // a sessão restaurada não emite estado: cria a MediaSession já pausada, para o play do fone/carro funcionar ao abrir
        runCatching { app.sounos.player.MediaSessionHolder.sync(this) }
        io.launch {
            while (true) {
                if (runCatching { audio.cleanExpiredAndTrim() }.getOrDefault(false)) player.notifyLibraryChanged()
                delay(60L * 60 * 1000)
            }
        }
        updater = app.sounos.update.Updater(this)
        // regras de nome/capa melhoraram: corrige aos poucos o que já está salvo
        app.sounos.data.MetadataRepair(this).start()
    }

    /**
     * A abertura do sistema (antes de qualquer código do app) usa values/ ou values-night/: avisa o Android
     * qual dos dois combina com o tema escolhido, para o app já abrir claro ou escuro, sem trocar no meio.
     */
    fun applyStartTheme(theme: String) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val mode = if (theme == "light") android.app.UiModeManager.MODE_NIGHT_NO else android.app.UiModeManager.MODE_NIGHT_YES
        runCatching { getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(mode) }
    }

    /** Mantém permanentemente só as capas de downloads e playlists atuais. */
    fun reconcilePinnedCovers(playlistData: String? = null): Pair<Int, Long> {
        val keep = linkedSetOf<String>()
        library.downloadedMeta().keys().forEach { if (CoverStore.validId(it)) keep += it }
        runCatching {
            val lists = (playlistData ?: userData.load("sounos.playlists"))?.let { JSONArray(it) } ?: JSONArray()
            for (i in 0 until lists.length()) {
                val tracks = lists.optJSONObject(i)?.optJSONArray("tracks") ?: continue
                for (j in 0 until tracks.length()) {
                    val item = tracks.optJSONObject(j) ?: continue
                    val track = Json.track(item)
                    if (CoverStore.validId(track.id)) {
                        keep += track.id
                        covers.pinLocal(track.id, track.thumbs.maxByOrNull { it.width * it.height }?.url)
                    }
                }
            }
        }.onFailure { android.util.Log.w("Sonus", "não foi possível listar capas das playlists", it) }
        return covers.migrateLegacyPinned(keep)
    }

    companion object {
        lateinit var instance: SounosApp; private set
    }
}
