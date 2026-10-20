package app.sounos.extractor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Guarda as músicas já resolvidas até a URL do áudio expirar, e resolve com ANTECEDÊNCIA:
 * próxima da fila, 3 primeiros resultados da busca, música em que o dedo encostou.
 * Pedidos repetidos para a mesma música enquanto ela ainda está sendo resolvida
 * esperam o mesmo resultado (não fazem duas chamadas).
 */
class StreamResolveCache(
    private val source: NewPipeSource,
    private val maxEntries: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val entries = object : LinkedHashMap<String, ResolvedTrack>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedTrack>) = size > maxEntries
    }
    private val inFlight = HashMap<String, Deferred<ResolvedTrack>>()

    /** Música pronta para tocar (do cache se ainda válida; senão resolve agora). */
    suspend fun get(url: String, quality: AudioQuality = AudioQuality.HIGH, known: Track? = null): ResolvedTrack {
        val key = key(url, quality)
        val job = mutex.withLock {
            entries[key]?.takeIf { it.audio.expiresAtMs > clock() }?.let { return it }
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        source.resolve(url, quality, known).also { r -> mutex.withLock { entries[key] = r } }
                    } finally {
                        mutex.withLock { inFlight.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    /** Resolve em segundo plano, sem esperar (erros são ignorados: na hora de tocar tenta de novo). */
    fun prefetch(tracks: List<Track>, quality: AudioQuality = AudioQuality.HIGH) {
        tracks.distinctBy { it.id }.forEach { t -> scope.launch { runCatching { get(t.url, quality, t) } } }
    }

    /** Se uma URL expirou no meio do download/reprodução, joga fora e resolve de novo. */
    suspend fun invalidate(url: String) = mutex.withLock { AudioQuality.entries.forEach { entries.remove(key(url, it)) } }

    suspend fun peek(url: String, quality: AudioQuality = AudioQuality.HIGH): ResolvedTrack? =
        mutex.withLock { entries[key(url, quality)]?.takeIf { it.audio.expiresAtMs > clock() } }

    private fun key(url: String, q: AudioQuality) = "${Track.idFor(url)}|$q"
}
