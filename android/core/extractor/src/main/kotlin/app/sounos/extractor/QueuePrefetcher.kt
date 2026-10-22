package app.sounos.extractor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Deixa a fila pronta ANTES de precisar (comportamento padrão do app, sem opção nas configurações):
 *  - a PRÓXIMA música: endereço resolvido + áudio INTEIRO em disco + capa → começa na hora;
 *  - as 2 seguintes: endereço resolvido + começo do áudio + capa → começam quase na hora.
 * Chamar sempre que a música atual ou a fila mudar; o trabalho anterior é cancelado.
 */
class QueuePrefetcher(
    private val resolver: StreamResolveCache,
    private val audio: AudioCache,
    private val covers: CoverStore,
    /** Limite para baixar a próxima inteira (evita baixar mixes de 1 hora). */
    private val maxFullBytes: Long = 20L * 1024 * 1024,
    /** Chamado quando a próxima ficou inteira em disco (o app usa para medir o volume dela antes de tocar). */
    private val onNextReady: (ResolvedTrack) -> Unit = {},
    private val onCacheChanged: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** keep = a música que está tocando: nunca sai do cache ("Limpar cache" e limite de tamanho). */
    fun onQueueChanged(upcoming: List<Track>, quality: AudioQuality, keep: String? = null, allowPrefetch: Boolean = true) {
        job?.cancel()
        val next3 = upcoming.take(3)
        audio.protectedIds = (next3.map { it.id } + listOfNotNull(keep)).toSet()
        scope.launch { runCatching { audio.trimToLimit() } }
        if (!allowPrefetch || next3.isEmpty()) return
        job = scope.launch {
            next3.forEachIndexed { i, track ->
                if (audio.isFreshComplete(track.id)) return@forEachIndexed       // já está no celular: nada a buscar
                try {
                    val r = resolver.get(track.url, quality, track)
                    covers.get(r.track, pin = true)                      // está na fila da pessoa: capa fica guardada
                    val full = i == 0 && r.audio.contentLength in 1..maxFullBytes
                    val ok = audio.fill(r.track.id, r.audio, if (full) Long.MAX_VALUE else audio.headBytes.toLong(),
                        refresh = { resolver.invalidate(track.url); resolver.get(track.url, quality, track).audio })
                    if (ok && full) onNextReady(r)
                    onCacheChanged()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Falha de rede na pré-carga não interrompe as outras músicas da fila.
                }
            }
        }
    }
}
