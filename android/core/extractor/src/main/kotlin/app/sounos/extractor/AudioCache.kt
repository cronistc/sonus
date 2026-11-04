package app.sounos.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Áudio das músicas em disco, um arquivo por música, sempre crescendo do começo para o fim.
 *  - a fila baixa o COMEÇO das próximas e a PRÓXIMA inteira (início instantâneo);
 *  - ao tocar, o download continua de onde parou, e o player lê o arquivo enquanto ele cresce;
 *  - se a URL expirar no meio, pede uma nova e continua (sem recomeçar).
 * Um único escritor por música (trava por música); leitores podem ler ao mesmo tempo.
 */
class AudioCache(
    private val dir: File,
    private val client: OkHttpClient,
    val headBytes: Int = 384 * 1024,
    val maxTotalBytes: Long = 3L * 1024 * 1024 * 1024,
    private val maxAgeMs: Long = Long.MAX_VALUE,              // sem prazo (pedido do dono): só sai por espaço
) {
    init { dir.mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val active = ConcurrentHashMap<String, AtomicInteger>()
    private val trimLock = Any()
    /** Músicas que não podem ser apagadas na limpeza (tocando agora e próximas da fila). */
    @Volatile var protectedIds: Set<String> = emptySet()

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    fun file(id: String) = File(dir, safe(id) + ".audio")
    private fun lenFile(id: String) = File(dir, safe(id) + ".len")
    private fun usedFile(id: String) = File(dir, safe(id) + ".used")
    private fun itagFile(id: String) = File(dir, safe(id) + ".itag")

    fun length(id: String): Long = file(id).length()
    /** Tamanho total do áudio (-1 se ainda não conhecido). */
    fun totalLength(id: String): Long = runCatching { lenFile(id).readText().trim().toLong() }.getOrDefault(-1)
    fun isComplete(id: String): Boolean = totalLength(id).let { it > 0 && length(id) >= it }
    fun isFreshComplete(id: String, now: Long = System.currentTimeMillis()): Boolean =
        isComplete(id) && (maxAgeMs == Long.MAX_VALUE || now - lastUsed(id, file(id)) < maxAgeMs)
    fun hasHead(id: String): Boolean = length(id) >= minOf(headBytes.toLong(), totalLength(id).takeIf { it > 0 } ?: headBytes.toLong())

    /** Atualiza a data de uso quando a pessoa realmente ouve a música. */
    fun markHeard(id: String) {
        runCatching { usedFile(id).writeText(System.currentTimeMillis().toString()) }
    }

    /** Remove o que venceu e reduz o cache por LRU. */
    fun cleanExpiredAndTrim(now: Long = System.currentTimeMillis()): Boolean = synchronized(trimLock) {
        val before = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val keep = protectedIds.map(::safe).toSet() + active.keys
        val files = dir.listFiles { f -> f.name.endsWith(".audio") } ?: return@synchronized false
        for (f in files) {
            val id = f.nameWithoutExtension
            if (id in keep) continue
            if (maxAgeMs != Long.MAX_VALUE && now - lastUsed(id, f) >= maxAgeMs) deleteEntry(id)
        }
        trimLocked()
        val after = dir.listFiles()?.sumOf { it.length() } ?: 0L
        after < before
    }

    fun trimToLimit() = synchronized(trimLock) { trimLocked() }

    /**
     * Garante os primeiros [upTo] bytes em disco (Long.MAX_VALUE = música inteira).
     * [refresh] devolve um áudio novo quando a URL expira (403/410).
     */
    suspend fun fill(
        id: String,
        audio: ResolvedAudio,
        upTo: Long = Long.MAX_VALUE,
        refresh: (suspend () -> ResolvedAudio)? = null,
        onProgress: (have: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        active.computeIfAbsent(safe(id)) { AtomicInteger() }.incrementAndGet()
        try {
        locks.getOrPut(id) { Mutex() }.withLock {
            if (isExpired(id, System.currentTimeMillis())) deleteEntry(id)
            var current = audio
            if (!alinhaItag(id, current)) return@withLock true
            if (current.contentLength > 0) lenFile(id).writeText(current.contentLength.toString())
            var retries = 0
            while (true) {
                ensureActive()
                val total = totalLength(id)
                val target = if (total > 0) minOf(upTo, total) else upTo
                val have = length(id)
                if (have >= target) { file(id).setLastModified(System.currentTimeMillis()); return@withLock true }
                val end = if (target == Long.MAX_VALUE) "" else (target - 1).toString()
                val req = Request.Builder().url(current.url)
                    .header("User-Agent", OkHttpDownloader.USER_AGENT)
                    .header("Range", "bytes=$have-$end")
                    .build()
                val ok = runCatching {
                    client.newCall(req).execute().use { r ->
                        when {
                            r.code == 403 || r.code == 410 -> false            // URL expirou
                            r.code == 416 -> {
                                val full = parseTotal(r.header("Content-Range"))
                                full?.let { lenFile(id).writeText(it.toString()) }
                                full != null && length(id) >= full
                            }
                            r.code != 206 && !(r.code == 200 && have == 0L) -> false
                            else -> {
                                if (total <= 0) {
                                    (parseTotal(r.header("Content-Range"))
                                        ?: if (r.code == 200) r.header("Content-Length")?.toLongOrNull() else null)
                                        ?.let { lenFile(id).writeText(it.toString()) }
                                }
                                val t = totalLength(id)
                                r.body!!.byteStream().use { input ->
                                    FileOutputStream(file(id), true).use { out ->
                                        val buf = ByteArray(64 * 1024); var got = have; var eof = false
                                        val stop = if (t > 0) minOf(target, t) else target
                                        while (got < stop) {
                                            ensureActive()
                                            val n = input.read(buf, 0, minOf(buf.size.toLong(), stop - got).toInt())
                                            if (n < 0) { eof = true; break }
                                            out.write(buf, 0, n); got += n
                                            onProgress(got, t)
                                        }
                                        if (eof && t <= 0) lenFile(id).writeText(got.toString())
                                        val finalTotal = totalLength(id)
                                        if (finalTotal > 0) got >= minOf(target, finalTotal) else eof || got >= target
                                    }
                                }
                            }
                        }
                    }
                }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; false }
                if (!ok) {
                    if (refresh == null || retries++ >= 2) return@withLock false
                    current = refresh()
                    if (!alinhaItag(id, current)) return@withLock true
                    if (current.contentLength > 0) lenFile(id).writeText(current.contentLength.toString())
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }.also { synchronized(trimLock) { trimLocked() } }
        } finally {
            active.computeIfPresent(safe(id)) { _, count -> if (count.decrementAndGet() <= 0) null else count }
        }
    }

    /** Copia o áudio completo para outro lugar (download: armazenamento privado do app). */
    fun exportTo(id: String, target: File): Boolean = isComplete(id) && runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        file(id).copyTo(tmp, overwrite = true)
        tmp.renameTo(target)
    }.getOrDefault(false)

    /** Apaga o cache de áudio, menos o que está tocando/na fila e [guardar] (as músicas completas da biblioteca). */
    fun clear(guardar: Set<String> = emptySet()): Long {
        var freed = 0L
        val keep = (protectedIds + guardar).map(::safe).toSet() + active.keys
        dir.listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension
            if (id !in keep) { freed += f.length(); f.delete() }
        }
        return freed
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    private fun trimLocked() {
        val keep = protectedIds.map(::safe).toSet() + active.keys
        val listed = dir.listFiles { f -> f.name.endsWith(".audio") } ?: return
        // Snapshot único (uso, tamanho): reler do disco durante o sort quebra o contrato do TimSort.
        val files = listed.map { Triple(it, lastUsed(it.nameWithoutExtension, it), it.length()) }
        var total = files.sumOf { it.third }
        val limite = EspacoLivre.limite(dir, maxTotalBytes, total)
        for ((f, _, size) in files.sortedBy { it.second }) {
            if (total <= limite) break
            if (f.nameWithoutExtension in keep) continue
            total -= size; deleteEntry(f.nameWithoutExtension)
        }
    }

    private fun lastUsed(id: String, audio: File): Long =
        runCatching { usedFile(id).readText().trim().toLong() }.getOrDefault(audio.lastModified())

    private fun isExpired(id: String, now: Long): Boolean {
        val audio = file(id)
        return maxAgeMs != Long.MAX_VALUE && audio.exists() && now - lastUsed(id, audio) >= maxAgeMs
    }

    private fun deleteEntry(id: String) {
        file(id).delete(); lenFile(id).delete(); usedFile(id).delete(); itagFile(id).delete()
    }

    /**
     * O arquivo é de UM stream (itag): bytes de outra qualidade (Wi-Fi ↔ dados móveis) não se emendam, dava áudio corrompido.
     * false = já está inteiro noutra qualidade (serve como está); pela metade noutra qualidade, recomeça do zero.
     */
    private fun alinhaItag(id: String, a: ResolvedAudio): Boolean {
        val novo = a.itag.toString()
        val antes = runCatching { itagFile(id).readText().trim() }.getOrNull()
        if (antes != null && antes != novo && length(id) > 0) {
            if (isComplete(id)) return false
            deleteEntry(id)
        }
        runCatching { itagFile(id).writeText(novo) }
        return true
    }

    private fun parseTotal(contentRange: String?): Long? = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
}
