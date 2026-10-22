package app.sounos.extractor

import java.io.File
import java.security.MessageDigest

/** LRU compartilhado por capas de navegação e metadados guardados em disco. */
class DiskCacheBudget(
    private val roots: List<File>,
    val maxBytes: Long = 4L * 1024 * 1024 * 1024,   // imagens + dados: até 4 GB (encolhe se faltar espaço)
) {
    data class Trim(val beforeBytes: Long, val afterBytes: Long, val removedFiles: Int) {
        val removedBytes: Long get() = beforeBytes - afterBytes
    }

    init { roots.forEach(File::mkdirs) }

    @Synchronized
    fun trim(): Trim {
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".up") }.toList() }
        val before = files.sumOf(File::length)
        var total = before
        var removed = 0
        val limite = roots.firstOrNull()?.let { EspacoLivre.limite(it, maxBytes, before) } ?: maxBytes
        for (file in files.sortedBy(File::lastModified)) {
            if (total <= limite) break
            val size = file.length()
            if (file.delete()) { total -= size; removed++ }
        }
        return Trim(before, total, removed)
    }

    fun sizeBytes(): Long = roots.sumOf { root -> root.walkTopDown().filter(File::isFile).sumOf(File::length) }

    @Synchronized
    fun clear(): Long {
        var freed = 0L
        roots.forEach { root ->
            root.listFiles()?.forEach { file ->
                if (file.isDirectory) file.deleteRecursively()
                else { freed += file.length(); file.delete() }
            }
        }
        return freed
    }

    companion object {
        fun keyHash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Arquivos JSON atômicos cuja última leitura ou gravação alimenta o LRU compartilhado. */
class DiskMetadataCache(
    private val directory: File,
    private val budget: DiskCacheBudget,
) {
    init { directory.mkdirs() }

    @Synchronized
    fun read(key: String): String? {
        val file = file(key)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching { file.readText(Charsets.UTF_8).also { file.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }

    @Synchronized
    fun write(key: String, value: String): DiskCacheBudget.Trim {
        val target = file(key)
        val tmp = File(directory, target.name + ".tmp")
        runCatching {
            tmp.writeText(value, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target))
            }
            target.setLastModified(System.currentTimeMillis())
        }.onFailure { tmp.delete(); throw it }
        return budget.trim()
    }

    @Synchronized
    fun clear(): Long {
        var freed = 0L
        directory.listFiles()?.filter { it.isFile }?.forEach { freed += it.length(); it.delete() }
        return freed
    }

    private fun file(key: String) = File(directory, DiskCacheBudget.keyHash(key) + ".json")
}
