package app.sounos.extractor

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Leitor de bytes do arquivo original: (posição, buffer, offset, tamanho) -> bytes lidos, ou -1 no fim. */
typealias ByteReader = (Long, ByteArray, Int, Int) -> Int

/** Lê um [File] (inteiro) como [ByteReader]. Precisa ser fechado. */
class FileByteReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val length: Long get() = synchronized(raf) { raf.length() }
    val reader: ByteReader = { pos, buf, off, len ->
        synchronized(raf) { raf.seek(pos); raf.read(buf, off, len) }
    }
    override fun close() { runCatching { raf.close() } }
}

/** Lê exatamente [len] bytes (ou devolve null se faltar). */
fun ByteReader.readFully(pos: Long, len: Int): ByteArray? {
    if (len < 0 || pos < 0) return null
    val out = ByteArray(len)
    var got = 0
    while (got < len) {
        val n = invoke(pos + got, out, got, len - got)
        if (n <= 0) return null
        got += n
    }
    return out
}

/**
 * Índice de um WebM/Matroska: onde começa o Segment, o TimecodeScale, o primeiro Cluster e os Cues
 * (tempo -> posição do Cluster). Serve para pular direto para um trecho sem o MediaExtractor varrer cluster por cluster.
 * Puro Kotlin: nunca lança; devolve null se algo faltar/estiver malformado ou o começo do arquivo ainda não chegou.
 */
class WebmIndex(
    /** ns por tick de tempo (padrão 1_000_000 = 1 ms) */
    val timecodeScaleNs: Long,
    /** posição (absoluta) onde começam os dados do Segment; posições dos Cues são relativas a ela */
    val segmentDataStart: Long,
    val segmentSizeFieldPos: Long,
    val segmentSizeFieldLen: Int,
    /** posição absoluta do primeiro Cluster (fim do "cabeçalho") */
    val firstClusterOffset: Long,
    /** cues ordenados por tempo */
    val cues: List<Cue>,
    /** (posição, tamanho total) dos elementos SeekHead/Cues que ficam dentro do cabeçalho: viram Void no splice */
    val voidRanges: List<LongArray>,
) {
    class Cue(val timeUs: Long, /** posição absoluta do Cluster */ val clusterOffset: Long)

    /** Último cue com tempo <= [targetUs] (ou o primeiro, se o alvo for antes de todos). */
    fun cueFor(targetUs: Long): Cue? {
        if (cues.isEmpty()) return null
        var best = cues[0]
        for (c in cues) { if (c.timeUs <= targetUs) best = c else break }
        return best
    }

    companion object {
        private const val ID_EBML = 0x1A45DFA3L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_SEEKHEAD = 0x114D9B74L
        private const val ID_SEEK = 0x4DBBL
        private const val ID_SEEKID = 0x53ABL
        private const val ID_SEEKPOS = 0x53ACL
        private const val ID_INFO = 0x1549A966L
        private const val ID_TIMESCALE = 0x2AD7B1L
        private const val ID_CUES = 0x1C53BB6BL
        const val ID_CLUSTER = 0x1F43B675L
        private const val ID_CUEPOINT = 0xBBL
        private const val ID_CUETIME = 0xB3L
        private const val ID_CUETRACKPOS = 0xB7L
        private const val ID_CUECLUSTERPOS = 0xF1L
        private const val MAX_SMALL = 8L shl 20      // Info/Cues/SeekHead acima disso: desconfia

        /** Cabeçalho de elemento: id, tamanho do payload (-1 = desconhecido) e quantos bytes o id+size ocupam. */
        class Hdr(val id: Long, val size: Long, val len: Int, val sizeLen: Int)

        /** Interpreta id+size a partir de [b] (já lido). */
        fun parseHdr(b: ByteArray, n: Int, at: Int = 0): Hdr? {
            if (at >= n) return null
            val f = b[at].toInt() and 0xFF
            val idLen = Integer.numberOfLeadingZeros(f) - 23
            if (f == 0 || idLen > 4 || at + idLen >= n) return null
            var id = 0L
            for (i in 0 until idLen) id = (id shl 8) or (b[at + i].toLong() and 0xFF)
            val s0 = b[at + idLen].toInt() and 0xFF
            if (s0 == 0) return null
            val sLen = Integer.numberOfLeadingZeros(s0) - 23
            if (at + idLen + sLen > n) return null
            var v = (s0 and (0xFF shr sLen)).toLong()
            var allOnes = v == (0xFF shr sLen).toLong()
            for (i in 1 until sLen) {
                val x = b[at + idLen + i].toLong() and 0xFF
                if (x != 0xFFL) allOnes = false
                v = (v shl 8) or x
            }
            return Hdr(id, if (allOnes) -1 else v, idLen + sLen, sLen)
        }

        private fun uint(b: ByteArray, from: Int, len: Int): Long {
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (b[from + i].toLong() and 0xFF)
            return v
        }

        private fun readHdr(r: ByteReader, pos: Long): Hdr? {
            val b = ByteArray(12)
            var n = 0
            while (n < 12) {
                val k = r(pos + n, b, n, 12 - n)
                if (k <= 0) break
                n += k
            }
            return parseHdr(b, n)
        }

        /** Parse completo. [r] lê o arquivo original. null = não deu (nunca lança). */
        fun parse(r: ByteReader): WebmIndex? = try { parseInternal(r) } catch (e: Throwable) { null }

        private fun parseInternal(r: ByteReader): WebmIndex? {
            val eh = readHdr(r, 0) ?: return null
            if (eh.id != ID_EBML || eh.size < 0 || eh.size > 4096) return null
            val segPos = eh.len + eh.size
            val sh = readHdr(r, segPos) ?: return null
            if (sh.id != ID_SEGMENT) return null
            val dataStart = segPos + sh.len
            val sizeFieldLen = sh.sizeLen
            val sizeFieldPos = segPos + (sh.len - sh.sizeLen)

            var timescale = 1_000_000L
            var cuesPos = -1L; var cuesLen = -1L
            val seekHeads = ArrayList<LongArray>()
            var seekCuesRel = -1L
            var firstCluster = -1L
            var pos = dataStart
            var guard = 0
            while (guard++ < 2000) {
                val h = readHdr(r, pos) ?: return null
                if (h.id == ID_CLUSTER) { firstCluster = pos; break }
                if (h.size < 0 || h.size > (1L shl 40)) return null
                val total = h.len + h.size
                when (h.id) {
                    ID_INFO -> {
                        if (h.size > MAX_SMALL) return null
                        val p = r.readFully(pos + h.len, h.size.toInt()) ?: return null
                        forEachChild(p) { id, from, len -> if (id == ID_TIMESCALE && len in 1..8) timescale = uint(p, from, len) }
                    }
                    ID_SEEKHEAD -> {
                        if (h.size > MAX_SMALL) return null
                        seekHeads += longArrayOf(pos, total)
                        val p = r.readFully(pos + h.len, h.size.toInt()) ?: return null
                        forEachChild(p) { id, from, len ->
                            if (id == ID_SEEK) {
                                var sid = -1L; var spos = -1L
                                forEachChild(p, from, from + len) { cid, cf, cl ->
                                    if (cid == ID_SEEKID) sid = uint(p, cf, cl)
                                    else if (cid == ID_SEEKPOS) spos = uint(p, cf, cl)
                                }
                                if (sid == ID_CUES) seekCuesRel = spos
                            }
                        }
                    }
                    ID_CUES -> { cuesPos = pos; cuesLen = total }
                }
                pos += total
            }
            if (firstCluster < 0 || timescale <= 0) return null

            // Cues depois dos clusters (comum em arquivos gravados ao vivo): acha pelo SeekHead
            if (cuesPos < 0) {
                if (seekCuesRel < 0) return null
                val p = dataStart + seekCuesRel
                val h = readHdr(r, p) ?: return null
                if (h.id != ID_CUES || h.size < 0) return null
                cuesPos = p; cuesLen = h.len + h.size
            }
            val ch = readHdr(r, cuesPos) ?: return null
            if (ch.size < 0 || ch.size > MAX_SMALL) return null
            val cp = r.readFully(cuesPos + ch.len, ch.size.toInt()) ?: return null

            val cues = ArrayList<Cue>()
            forEachChild(cp) { id, from, len ->
                if (id == ID_CUEPOINT) {
                    var t = -1L; var cl = -1L
                    forEachChild(cp, from, from + len) { cid, cf, cn ->
                        if (cid == ID_CUETIME) t = uint(cp, cf, cn)
                        else if (cid == ID_CUETRACKPOS && cl < 0) {
                            forEachChild(cp, cf, cf + cn) { tid, tf, tn -> if (tid == ID_CUECLUSTERPOS && cl < 0) cl = uint(cp, tf, tn) }
                        }
                    }
                    if (t >= 0 && cl >= 0) cues += Cue(t * timescale / 1000L, dataStart + cl)
                }
            }
            if (cues.isEmpty()) return null
            cues.sortBy { it.timeUs }

            val voids = ArrayList<LongArray>()
            for (s in seekHeads) if (s[0] + s[1] <= firstCluster) voids += s
            if (cuesPos + cuesLen <= firstCluster) voids += longArrayOf(cuesPos, cuesLen)
            return WebmIndex(timescale, dataStart, sizeFieldPos, sizeFieldLen, firstCluster, cues, voids)
        }

        /** Percorre os filhos em [from, to) de [b]; ignora (para) se malformado. */
        private inline fun forEachChild(b: ByteArray, from: Int = 0, to: Int = b.size, f: (id: Long, dataFrom: Int, len: Int) -> Unit) {
            var p = from
            while (p < to) {
                val h = parseHdr(b, to, p) ?: return
                if (h.size < 0 || p + h.len + h.size > to) return
                f(h.id, p + h.len, h.size.toInt())
                p += h.len + h.size.toInt()
            }
        }
    }
}

/**
 * Arquivo "virtual": cabeçalho remendado do original + bytes do original a partir de um Cluster alvo.
 * Os tempos dos Clusters são absolutos, então os tempos de apresentação continuam certos.
 */
class WebmSplice private constructor(
    val header: ByteArray,
    val clusterOffset: Long,
    /** tamanho do arquivo original, ou -1 se ainda desconhecido */
    val originalSize: Long,
) {
    /** tamanho do arquivo virtual, ou -1 se o original ainda não tem tamanho conhecido */
    val size: Long get() = if (originalSize > 0) header.size + (originalSize - clusterOffset) else -1

    /** Lê do arquivo virtual: cabeçalho vem da cópia remendada, o resto do original via [r]. */
    fun readAt(vpos: Long, buf: ByteArray, off: Int, len: Int, r: ByteReader): Int {
        if (len == 0) return 0
        if (vpos < 0) return -1
        val s = size
        if (s > 0 && vpos >= s) return -1
        if (vpos < header.size) {
            val n = minOf(len.toLong(), header.size - vpos).toInt()
            System.arraycopy(header, vpos.toInt(), buf, off, n)
            return n
        }
        return r(clusterOffset + (vpos - header.size), buf, off, len)
    }

    companion object {
        /** Monta a cópia remendada do cabeçalho (ou null). Reaproveitável para qualquer cluster alvo. */
        fun patchedHeader(index: WebmIndex, r: ByteReader): ByteArray? = try {
            val n = index.firstClusterOffset
            if (n <= 0 || n > (16 shl 20)) null
            else r.readFully(0, n.toInt())?.also { h ->
                // (a) Segment com tamanho desconhecido (mesmo número de bytes)
                val sp = index.segmentSizeFieldPos.toInt(); val sl = index.segmentSizeFieldLen
                h[sp] = (0xFF shr (sl - 1)).toByte()
                for (i in 1 until sl) h[sp + i] = 0xFF.toByte()
                // (b) SeekHead e Cues viram Void do mesmo tamanho
                for (v in index.voidRanges) if (!writeVoid(h, v[0].toInt(), v[1].toInt())) return null
            }
        } catch (e: Throwable) { null }

        /** Escreve um elemento Void (0xEC) de exatamente [total] bytes em [at]. */
        fun writeVoid(h: ByteArray, at: Int, total: Int): Boolean {
            if (total < 2 || at < 0 || at + total > h.size) return false
            var k = 1
            while (k <= 8 && total - 1 - k >= (1L shl (7 * k)) - 1) k++
            if (k > 8) return false
            val payload = (total - 1 - k).toLong()
            h[at] = 0xEC.toByte()
            val v = payload or (1L shl (7 * k))          // marcador de tamanho + valor
            for (i in 0 until k) h[at + 1 + i] = (v shr (8 * (k - 1 - i))).toByte()
            for (i in (1 + k) until total) h[at + i] = 0
            return true
        }

        /** Valida que há um Cluster em [clusterOffset] no original e cria o splice. */
        fun create(header: ByteArray, clusterOffset: Long, originalSize: Long, r: ByteReader): WebmSplice? {
            val id = r.readFully(clusterOffset, 4) ?: return null
            if (uintBE(id) != WebmIndex.ID_CLUSTER) return null
            return WebmSplice(header, clusterOffset, originalSize)
        }

        private fun uintBE(b: ByteArray): Long { var v = 0L; for (x in b) v = (v shl 8) or (x.toLong() and 0xFF); return v }
    }
}
