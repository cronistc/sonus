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
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import app.sounos.R
import app.sounos.SounosApp

/**
 * Mantém a música tocando com o app fechado: notificação de mídia, controles na tela de bloqueio,
 * botões do fone/carro (MediaSession) e pausa quando o fone é desconectado.
 */
class PlaybackService : Service() {

    private val session get() = MediaSessionHolder.session(this)
    private val player get() = SounosApp.instance.player

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause() }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tocando agora", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
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

    private fun refresh() {
        try { doRefresh() } catch (e: Exception) { android.util.Log.w("Sonus", "refresh da notificação falhou", e) }
    }

    private fun doRefresh() {
        val t = player.currentTrack
        if (t == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        val playing = player.isPlaying
        val artistLine = t.artists.ifEmpty { listOf(t.artist) }.joinToString(", ")
        val cover = MediaSessionHolder.coverFor(t.id)
        MediaSessionHolder.sync(this)

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
        // Android 12+ lança ForegroundServiceStartNotAllowedException (IllegalStateException) se o app está em segundo plano sem permissão.
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
        } catch (e: IllegalStateException) {
            android.util.Log.w("Sonus", "startForeground não permitido; só atualizando a notificação", e)
            getSystemService(NotificationManager::class.java).notify(ID, n)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisy) }
        // a MediaSession é do processo (MediaSessionHolder): não é liberada aqui
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
            // sessão ativa (e pausada) já com o app aberto, para o play do fone/carro funcionar antes do primeiro play
            runCatching { MediaSessionHolder.sync(ctx) }
            if (!started && !p.isPlaying) return
            started = true
            runCatching { ctx.startForegroundService(Intent(ctx, PlaybackService::class.java)) }
        }
    }
}
