package app.sounos.engine

import android.media.MediaDataSource
import app.sounos.extractor.ByteReader
import app.sounos.extractor.WebmSplice

/**
 * Arquivo virtual para o MediaExtractor: cabeçalho remendado + original a partir de um Cluster.
 * Não é dono do leitor original (quem abriu fecha): close() aqui não fecha nada.
 */
class SplicedWebmSource(private val splice: WebmSplice, private val reader: ByteReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        splice.readAt(position, buffer, offset, size, reader)
    override fun getSize(): Long = splice.size
    override fun close() {}
}

/** Repassa a outra fonte sem fechá-la: o MediaExtractor fecha a fonte ao ser solto, e a original é compartilhada. */
class NoCloseSource(private val inner: MediaDataSource) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = inner.readAt(position, buffer, offset, size)
    override fun getSize(): Long = inner.size
    override fun close() {}
}
