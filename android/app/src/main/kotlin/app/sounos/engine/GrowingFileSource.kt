package app.sounos.engine

import android.media.MediaDataSource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Fonte de áudio que lê um arquivo ENQUANTO ele ainda está sendo baixado.
 * Se o player pedir um trecho que ainda não chegou, espera um pouco (sem travar a interface:
 * isso roda na thread do player). É o que permite tocar já com o começo em disco.
 */
class GrowingFileSource(
    private val file: File,
    private val totalSize: () -> Long,
    private val finished: () -> Boolean,
) : MediaDataSource() {
    private var raf: RandomAccessFile? = null
    @Volatile var closed = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        val total = totalSize()
        if (total in 1..position) return -1
        val deadline = System.currentTimeMillis() + 25_000
        while (file.length() <= position) {
            if (closed) return -1
            if (finished() && file.length() <= position) return -1
            if (System.currentTimeMillis() > deadline) throw IOException("áudio não chegou a tempo")
            Thread.sleep(15)
        }
        val r = raf ?: RandomAccessFile(file, "r").also { raf = it }
        r.seek(position)
        val avail = minOf(size.toLong(), file.length() - position).toInt()
        return r.read(buffer, offset, avail)
    }

    override fun getSize(): Long = totalSize().takeIf { it > 0 } ?: -1

    override fun close() {
        closed = true
        runCatching { raf?.close() }
        raf = null
    }
}
