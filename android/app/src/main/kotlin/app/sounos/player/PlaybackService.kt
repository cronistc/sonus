package app.sounos.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import app.sounos.MainActivity
import app.sounos.R
import app.sounos.SounosApp

/**
 * Mantém a música tocando com o app fechado: notificação de mídia, controles na tela de bloqueio,
 * botões do fone/carro (MediaSession) e pausa quando o fone é desconectado.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSession
    private val player get() = SounosApp.instance.player

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause() }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tocando agora", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        session = MediaSession(this, "Sonus").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = player.play()
                override fun onPause() = player.pause()
                override fun onSkipToNext() = player.next()
                override fun onSkipToPrevious() = player.prev()
                override fun onSeekTo(pos: Long) = player.seek(pos)
                override fun onStop() = player.pause()
            })
            setSessionActivity(PendingIntent.getActivity(this@PlaybackService, 0, Intent(this@PlaybackService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.prev()
        }
        refresh()
        return START_NOT_STICKY
    }

    // capa da notificação: decodificada uma vez por música e reduzida (antes: imagem inteira, várias vezes por troca)
    private var coverId: String? = null
    private var coverBmp: android.graphics.Bitmap? = null
    private fun coverFor(id: String): android.graphics.Bitmap? {
        if (id == coverId && coverBmp != null) return coverBmp
        val f = SounosApp.instance.covers.local(id) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2
        coverBmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        coverId = id.takeIf { coverBmp != null }
        return coverBmp
    }

    private fun refresh() {
        val t = player.currentTrack
        if (t == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        val playing = player.isPlaying
        val artistLine = t.artists.ifEmpty { listOf(t.artist) }.joinToString(", ")
        val cover = coverFor(t.id)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artistLine)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, player.durationMs)
            .apply { if (cover != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover) }
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, player.positionMs(), if (playing) 1f else 0f)
            .build())

        fun action(icon: Int, title: String, act: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(this, act.hashCode(), Intent(this, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)).build()

        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sounos)
            .setContentTitle(t.title).setContentText(artistLine)
            .setLargeIcon(cover)
            .setContentIntent(session.controller.sessionActivity)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_n_prev, "Voltar", ACTION_PREV))
            .addAction(if (playing) action(R.drawable.ic_n_pause, "Pausar", ACTION_TOGGLE) else action(R.drawable.ic_n_play, "Tocar", ACTION_TOGGLE))
            .addAction(action(R.drawable.ic_n_next, "Passar", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()

        // Mantém o serviço em primeiro plano durante a pausa e a troca de faixa.
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisy) }
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "playback"
        private const val ID = 1
        const val ACTION_TOGGLE = "app.sounos.TOGGLE"
        const val ACTION_NEXT = "app.sounos.NEXT"
        const val ACTION_PREV = "app.sounos.PREV"
        private var started = false

        /** Chamado a cada mudança de estado: liga o serviço quando começa a tocar e atualiza a notificação. */
        fun update(ctx: Context) {
            val p = SounosApp.instance.player
            if (p.currentTrack == null) return
            if (!started && !p.isPlaying) return
            started = true
            runCatching { ctx.startForegroundService(Intent(ctx, PlaybackService::class.java)) }
        }
    }
}
