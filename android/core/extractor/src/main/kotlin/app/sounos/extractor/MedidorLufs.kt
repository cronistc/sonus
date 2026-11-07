package app.sounos.extractor

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Volume percebido de uma música em LUFS (ITU-R BS.1770-4 / EBU R128 — o mesmo padrão do Spotify e do YouTube).
 *
 *  - Filtro "K": realça os agudos (+4 dB acima de ~1,7 kHz) e corta o grave profundo (< 38 Hz), como o ouvido percebe.
 *    Medir o volume "cru" (RMS) achava alta demais a música com muito grave e baixa demais a de voz.
 *  - Blocos de 400 ms (andando de 100 em 100 ms), todos com o mesmo peso — o tamanho dos pedaços que chegam não muda
 *    o resultado.
 *  - Silêncio e trechos muito baixos não contam: fora o que está abaixo de −70 LUFS e, depois, o que está 10 LU abaixo
 *    da média (portão relativo).
 *
 * PCM 16 bits intercalado. Os canais somam com peso 1 (esquerdo/direito).
 */
class MedidorLufs(val sampleRate: Int, val channels: Int) {
    // filtro K: estágio 1 (prateleira de agudos) e estágio 2 (passa-altas), coeficientes para esta taxa de amostragem
    private val s1: DoubleArray
    private val s2: DoubleArray
    init {
        var k = tan(PI * 1681.974450955533 / sampleRate); var q = 0.7071752369554196
        val vh = 10.0.pow(3.999843853973347 / 20); val vb = vh.pow(0.4996667741545416)
        var a0 = 1 + k / q + k * k
        s1 = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        k = tan(PI * 38.13547087602444 / sampleRate); q = 0.5003270373238773
        a0 = 1 + k / q + k * k
        s2 = doubleArrayOf(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    }
    // estado dos filtros por canal: x1, x2, y1, y2 de cada estágio
    private val e1 = Array(channels) { DoubleArray(4) }
    private val e2 = Array(channels) { DoubleArray(4) }
    private val porSub = maxOf(1, sampleRate / 10)          // 100 ms
    private var acc = 0.0
    private var quadros = 0
    private val subs = ArrayList<Double>()                   // energia média de cada 100 ms (soma dos canais)

    /** Quantos segundos já foram medidos. */
    val segundos: Double get() = subs.size / 10.0

    fun add(pcm: ShortArray, n: Int) {
        var i = 0
        while (i + channels <= n) {
            var soma = 0.0
            for (c in 0 until channels) {
                val y = biquad(biquad(pcm[i + c] / 32768.0, s1, e1[c]), s2, e2[c])
                soma += y * y
            }
            acc += soma
            if (++quadros == porSub) { subs.add(acc / porSub); acc = 0.0; quadros = 0 }
            i += channels
        }
    }

    private fun biquad(x: Double, c: DoubleArray, e: DoubleArray): Double {
        val y = c[0] * x + c[1] * e[0] + c[2] * e[1] - c[3] * e[2] - c[4] * e[3]
        e[1] = e[0]; e[0] = x; e[3] = e[2]; e[2] = y
        return y
    }

    /** Volume integrado (LUFS) do que foi medido, ou null se ainda não há 400 ms de som. */
    fun integrada(): Double? {
        if (subs.size < 4) return null
        val blocos = DoubleArray(subs.size - 3) { j -> (subs[j] + subs[j + 1] + subs[j + 2] + subs[j + 3]) / 4 }
        val lufs = { z: Double -> -0.691 + 10 * log10(z + 1e-20) }
        val absolutos = blocos.filter { lufs(it) > -70.0 }
        if (absolutos.isEmpty()) return null
        val limite = lufs(absolutos.average()) - 10.0
        val validos = absolutos.filter { lufs(it) > limite }
        return lufs((validos.ifEmpty { absolutos }).average())
    }

    companion object {
        /** Volume que todas as músicas buscam (o mesmo do Spotify "normal" e do YouTube). */
        const val ALVO_LUFS = -14.0
        /** Ganho para levar uma música medida em [lufs] ao alvo: abaixa até 12 dB, aumenta até 12 dB (o limitador segura os picos). */
        fun ganhoPara(lufs: Double) = (ALVO_LUFS - lufs).coerceIn(-12.0, 12.0)
    }
}
