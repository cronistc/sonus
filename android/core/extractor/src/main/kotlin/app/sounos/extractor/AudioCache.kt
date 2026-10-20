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
    private val maxTotalBytes: Long = 800L * 1024 * 1024,
) {
    init { dir.mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** Músicas que não podem ser apagadas na limpeza (tocando agora e próximas da fila). */
    @Volatile var protectedIds: Set<String> = emptySet()

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    fun file(id: String) = File(dir, safe(id) + ".audio")
    private fun lenFile(id: String) = File(dir, safe(id) + ".len")

    fun length(id: String): Long = file(id).length()
    /** Tamanho total do áudio (-1 se ainda não conhecido). */
    fun totalLength(id: String): Long = runCatching { lenFile(id).readText().trim().toLong() }.getOrDefault(-1)
    fun isComplete(id: String): Boolean = totalLength(id).let { it > 0 && length(id) >= it }
    fun hasHead(id: String): Boolean = length(id) >= minOf(headBytes.toLong(), totalLength(id).takeIf { it > 0 } ?: headBytes.toLong())

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
        locks.getOrPut(id) { Mutex() }.withLock {
            var current = audio
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
                            r.code == 416 -> true                              // já tem tudo
                            r.code != 206 && !(r.code == 200 && have == 0L) -> false
                            else -> {
                                if (total <= 0) parseTotal(r.header("Content-Range"))?.let { lenFile(id).writeText(it.toString()) }
                                val t = totalLength(id)
                                r.body!!.byteStream().use { input ->
                                    FileOutputStream(file(id), true).use { out ->
                                        val buf = ByteArray(64 * 1024); var got = have
                                        val stop = if (t > 0) minOf(target, t) else target
                                        while (got < stop) {
                                            ensureActive()
                                            val n = input.read(buf, 0, minOf(buf.size.toLong(), stop - got).toInt()); if (n < 0) break
                                            out.write(buf, 0, n); got += n
                                            onProgress(got, t)
                                        }
                                    }
                                }
                                true
                            }
                        }
                    }
                }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; false }
                if (!ok) {
                    if (refresh == null || retries++ >= 2) return@withLock false
                    current = refresh()
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }.also { trim() }
    }

    /** Copia o áudio completo para outro lugar (download: armazenamento privado do app). */
    fun exportTo(id: String, target: File): Boolean = isComplete(id) && runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        file(id).copyTo(tmp, overwrite = true)
        tmp.renameTo(target)
    }.getOrDefault(false)

    fun clear(): Long {
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension
            if (protectedIds.none { safe(it) == id }) { freed += f.length(); f.delete() }
        }
        return freed
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    private fun trim() {
        val keep = protectedIds.map(::safe).toSet()
        val files = dir.listFiles { f -> f.name.endsWith(".audio") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxTotalBytes) break
            if (f.nameWithoutExtension in keep) continue
            total -= f.length(); f.delete(); File(dir, f.nameWithoutExtension + ".len").delete()
        }
    }

    private fun parseTotal(contentRange: String?): Long? = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
}
