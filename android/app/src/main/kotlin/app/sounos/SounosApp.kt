package app.sounos

import android.app.Application
import app.sounos.data.Backups
import app.sounos.data.Library
import app.sounos.data.Lyrics
import app.sounos.extractor.AudioCache
import app.sounos.extractor.CoverStore
import app.sounos.extractor.NewPipeSource
import app.sounos.extractor.Sounos
import app.sounos.extractor.StreamResolveCache
import app.sounos.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Tudo que o app compartilha (um de cada), criado uma vez quando o app abre. */
class SounosApp : Application() {
    lateinit var source: NewPipeSource; private set
    lateinit var resolver: StreamResolveCache; private set
    lateinit var audio: AudioCache; private set
    lateinit var covers: CoverStore; private set
    lateinit var library: Library; private set
    lateinit var userData: app.sounos.data.UserData; private set
    lateinit var lyrics: Lyrics; private set
    lateinit var player: PlayerController; private set
    lateinit var updater: app.sounos.update.Updater; private set
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // antes de tudo: se as preferências sumiram mas há snapshot de antes de uma atualização, recoloca (só acrescenta)
        Backups.restoreIfEmpty(this)
        library = Library(this)
        userData = app.sounos.data.UserData(File(filesDir, "userdata"))
        io.launch { runCatching { library.checkDownloads() } }
        val s = library.settings()
        Sounos.init(language = s.optString("language", "pt-BR").substringBefore('-'), country = s.optString("country", "BR"))
        source = NewPipeSource()
        resolver = StreamResolveCache(source)
        audio = AudioCache(File(cacheDir, "audio"), Sounos.http)                  // cache (pode ser limpo)
        covers = CoverStore(File(filesDir, "covers"), File(cacheDir, "covers"), Sounos.http)   // fixadas nunca somem
        lyrics = Lyrics(File(filesDir, "lyrics"), Sounos.http)
        player = PlayerController(this)
        io.launch {
            while (true) {
                if (runCatching { audio.cleanExpiredAndTrim() }.getOrDefault(false)) player.notifyLibraryChanged()
                delay(60L * 60 * 1000)
            }
        }
        updater = app.sounos.update.Updater(this)
    }

    companion object {
        lateinit var instance: SounosApp; private set
    }
}
