package app.sounos.extractor

import org.junit.Assert.*
import org.junit.Test

class WebmIndexTest {
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream("webm/$n")!!.readBytes()
    private fun rd(b: ByteArray): ByteReader = { p, buf, o, l ->
        if (p >= b.size) -1 else { val n = minOf(l.toLong(), b.size - p).toInt(); System.arraycopy(b, p.toInt(), buf, o, n); n }
    }
    private fun u32(b: ByteArray, p: Long) = ((b[p.toInt()].toLong() and 0xFF) shl 24) or ((b[p.toInt() + 1].toLong() and 0xFF) shl 16) or
        ((b[p.toInt() + 2].toLong() and 0xFF) shl 8) or (b[p.toInt() + 3].toLong() and 0xFF)

    @Test fun indexaArquivoCompleto() {
        val f = res("a.webm")
        val idx = WebmIndex.parse(rd(f))!!
        assertEquals(1_000_000L, idx.timecodeScaleNs)
        assertEquals(48L, idx.segmentDataStart)
        assertEquals(834L, idx.firstClusterOffset)
        assertTrue("cues=${idx.cues.size}", idx.cues.size in 20..40)
        for (c in idx.cues) assertEquals(WebmIndex.ID_CLUSTER, u32(f, c.clusterOffset))
        for (i in 1 until idx.cues.size) assertTrue(idx.cues[i].timeUs > idx.cues[i - 1].timeUs)
        assertEquals(834L, idx.cues[0].clusterOffset)
        // cue para um alvo no meio
        val mid = idx.cues[10].timeUs + 1000
        assertEquals(idx.cues[10].clusterOffset, idx.cueFor(mid)!!.clusterOffset)
        println("cues=${idx.cues.size} ultimo=${idx.cues.last().timeUs}us voids=${idx.voidRanges.map { it.toList() }}")
    }

    @Test fun arquivoParcial() {
        val f = res("yt_tXNw6nqulJk.webm")
        val idx = WebmIndex.parse(rd(f))
        if (idx != null) {
            val alcanca = idx.cues.count { it.clusterOffset + 4 <= f.size }
            assertTrue(alcanca >= 1 && alcanca <= idx.cues.size)
            // splice só vale para clusters que existem: além do fim, create devolve null sem lançar
            val h = WebmSplice.patchedHeader(idx, rd(f))!!
            val far = idx.cues.last().clusterOffset
            if (far + 4 > f.size) assertNull(WebmSplice.create(h, far, -1, rd(f)))
        }
    }

    @Test fun lixoCurtoRetornaNull() {
        assertNull(WebmIndex.parse(rd(ByteArray(0))))
        assertNull(WebmIndex.parse(rd(ByteArray(5))))
        assertNull(WebmIndex.parse(rd(ByteArray(100) { 0xFF.toByte() })))
        assertNull(WebmIndex.parse(rd(ByteArray(1000) { (it * 31).toByte() })))
        val f = res("a.webm")
        assertNull(WebmIndex.parse(rd(f.copyOf(300))))      // cabeçalho cortado no meio dos Cues
        assertNull(WebmIndex.parse { _, _, _, _ -> throw java.io.IOException("x") })
    }

    @Test fun spliceRemendaCabecalho() {
        val f = res("a.webm")
        val idx = WebmIndex.parse(rd(f))!!
        val h = WebmSplice.patchedHeader(idx, rd(f))!!
        assertEquals(834, h.size)
        // Segment com tamanho desconhecido (8 bytes) no mesmo lugar
        val sp = idx.segmentSizeFieldPos.toInt()
        assertEquals(8, idx.segmentSizeFieldLen)
        assertEquals(0x01, h[sp].toInt()); for (i in 1..7) assertEquals(0xFF, h[sp + i].toInt() and 0xFF)
        // elementos de topo: Info/Tracks intactos, SeekHead e Cues viram Void do mesmo tamanho
        var p = idx.segmentDataStart.toInt()
        val ids = ArrayList<Pair<Long, Int>>()
        while (p < h.size) {
            val e = WebmIndex.parseHdr(h, h.size, p)!!
            ids += e.id to (e.len + e.size.toInt()); p += e.len + e.size.toInt()
        }
        assertEquals(h.size, p)
        assertEquals(listOf(0xECL, 0x1549A966L, 0x1654AE6BL, 0xECL), ids.map { it.first })
        assertEquals(68, ids[0].second); assertEquals(575, ids[3].second)
        // fluxo emendado a partir de um cluster alvo começa com o id de Cluster
        val target = idx.cues[12].clusterOffset
        val sp2 = WebmSplice.create(h, target, f.size.toLong(), rd(f))!!
        assertEquals(h.size + (f.size - target), sp2.size)
        val all = ByteArray(sp2.size.toInt())
        var got = 0
        while (got < all.size) { val n = sp2.readAt(got.toLong(), all, got, minOf(1000, all.size - got), rd(f)); assertTrue(n > 0); got += n }
        assertEquals(WebmIndex.ID_CLUSTER, u32(all, h.size.toLong()))
        assertEquals(-1, sp2.readAt(sp2.size, ByteArray(4), 0, 4, rd(f)))
        // posição que não é Cluster é recusada
        assertNull(WebmSplice.create(h, 900, f.size.toLong(), rd(f)))
        // tamanho do original desconhecido
        assertEquals(-1L, WebmSplice.create(h, target, -1, rd(f))!!.size)
    }

    @Test fun voidTamanhosVariados() {
        for (total in listOf(2, 3, 100, 127, 128, 129, 130, 5000, 20000)) {
            val b = ByteArray(total + 4) { 0x55 }
            assertTrue(WebmSplice.writeVoid(b, 2, total))
            val e = WebmIndex.parseHdr(b, b.size, 2)!!
            assertEquals(0xECL, e.id); assertEquals(total, e.len + e.size.toInt())
        }
        assertFalse(WebmSplice.writeVoid(ByteArray(10), 0, 1))
    }
}
