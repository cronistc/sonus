package app.sounos.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import app.sounos.MainActivity
import app.sounos.SounosApp
import kotlinx.coroutines.launch

/**
 * MediaSession única do processo. Existe assim que há uma faixa atual (mesmo pausada e sem serviço),
 * para o play do fone/carro/relógio funcionar logo depois de abrir o app. O PlaybackService só a usa
 * (notificação) e nunca a libera. Uso apenas na thread principal.
 */
object MediaSessionHolder {
    private var session: MediaSession? = null
    private val player get() = SounosApp.instance.player

    fun session(ctx: Context): MediaSession = session ?: MediaSession(ctx.applicationContext, "Sonus").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = player.play()
            override fun onPause() = player.pause()
            override fun onSkipToNext() = player.next()
            override fun onSkipToPrevious() = player.prev()
            override fun onSeekTo(pos: Long) = player.seek(pos)
            override fun onStop() = player.pause()
        })
        setSessionActivity(PendingIntent.getActivity(ctx.applicationContext, 0, Intent(ctx.applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        isActive = true
    }.also { session = it }

    // capa: decodificada uma vez por música e reduzida (antes: imagem inteira, várias vezes por troca)
    private var coverId: String? = null
    private var coverBmp: Bitmap? = null
    // Capa da notificação / tela de bloqueio. Ler e decodificar o JPEG (até 1200 px) era feito na linha principal a cada
    // música nova — travava a tela do app bem na troca. Agora em segundo plano: enquanto não fica pronta, vai sem capa e,
    // pronta, a sessão e a notificação são atualizadas (PlaybackService.update).
    @Volatile private var decodificando: String? = null
    private val principal = android.os.Handler(android.os.Looper.getMainLooper())
    fun coverFor(id: String): Bitmap? {
        if (id == coverId && coverBmp != null) return coverBmp
        if (decodificando == id) return null
        decodificando = id
        val app = SounosApp.instance
        app.io.launch {
            val bmp = runCatching {
                app.covers.local(id)?.let { f ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
                    var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
            principal.post {
                if (decodificando != id) return@post
                decodificando = null
                if (bmp != null) { coverBmp = bmp; coverId = id; PlaybackService.atualizarTudo(app) }
            }
        }
        return null
    }

    // evita refazer os metadados (e a capa) a cada mudança de estado
    private var metaKey: String? = null

    /** Atualiza metadados e estado da sessão com a faixa atual. Sem faixa, não faz nada. */
    fun sync(ctx: Context) {
        val t = player.currentTrack ?: return
        val s = session(ctx)
        val playing = player.isPlaying
        val cover = coverFor(t.id)
        val key = "${t.id}|${player.durationMs}|${cover != null}"
        if (key != metaKey) {
            val artistLine = t.artists.ifEmpty { listOf(t.artist) }.joinToString(", ")
            s.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artistLine)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.durationMs)
                .apply { if (cover != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover) }
                .build())
            metaKey = key
        }
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, player.positionMs(), if (playing) 1f else 0f)
            .build())
    }
}
