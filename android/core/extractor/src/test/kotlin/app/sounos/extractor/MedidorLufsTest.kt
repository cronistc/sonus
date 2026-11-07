package app.sounos.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Casos do EBU Tech 3341 / ITU-R BS.1770 (tolerância de 0,1 LU). */
class MedidorLufsTest {
    private fun seno(freq: Double, dbfs: Double, seg: Double, sr: Int, canais: Int, soEsquerdo: Boolean = false): ShortArray {
        val amp = 10.0.pow(dbfs / 20) * 32767
        val n = (seg * sr).toInt()
        return ShortArray(n * canais) { k ->
            val i = k / canais; val c = k % canais
            if (soEsquerdo && c > 0) 0 else (amp * sin(2 * PI * freq * i / sr)).toInt().toShort()
        }
    }
    private fun mede(sr: Int, canais: Int, vararg partes: ShortArray, pedaco: Int = 4096): Double? {
        val m = MedidorLufs(sr, canais)
        for (p in partes) { var o = 0; while (o < p.size) { val n = minOf(pedaco, p.size - o); m.add(p.copyOfRange(o, o + n), n); o += n } }
        return m.integrada()
    }

    @Test fun senoEstereoMenos23() = assertEquals(-23.0, mede(48000, 2, seno(1000.0, -23.0, 20.0, 48000, 2))!!, 0.1)

    @Test fun senoUmCanalZeroDbfs() = assertEquals(-3.01, mede(48000, 2, seno(997.0, 0.0, 10.0, 48000, 2, soEsquerdo = true))!!, 0.1)

    @Test fun portaoRelativoIgnoraTrechosBaixos() {
        val sr = 48000
        // 10 s a −36, 60 s a −23, 10 s a −36 (EBU caso 4): os trechos baixos ficam fora → −23
        assertEquals(-23.0, mede(sr, 2, seno(1000.0, -36.0, 10.0, sr, 2), seno(1000.0, -23.0, 60.0, sr, 2), seno(1000.0, -36.0, 10.0, sr, 2))!!, 0.1)
    }

    @Test fun tamanhoDosPedacosNaoMuda() {
        val s = seno(1000.0, -20.0, 5.0, 44100, 2)
        assertEquals(mede(44100, 2, s, pedaco = 882)!!, mede(44100, 2, s, pedaco = 44100)!!, 0.01)
    }

    @Test fun silencioNaoTemVolume() = assertNull(mede(48000, 2, ShortArray(48000 * 2 * 3)))

    @Test fun ganhoLimitado() {
        assertEquals(-6.0, MedidorLufs.ganhoPara(-8.0), 1e-9)
        assertEquals(12.0, MedidorLufs.ganhoPara(-40.0), 1e-9)
        assertEquals(-12.0, MedidorLufs.ganhoPara(5.0), 1e-9)
    }
}
