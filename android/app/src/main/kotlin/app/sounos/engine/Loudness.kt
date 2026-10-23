package app.sounos.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.math.log10
import kotlin.math.pow

/**
 * Volume equilibrado (de verdade): mede o volume médio da própria música e aplica um ganho para
 * todas ficarem perto do mesmo nível ([TARGET_DB], em dBFS RMS).
 *  - Se o ganho da música já é conhecido (medido antes, quando ela ficou inteira em disco na fila,
 *    ou numa vez anterior), ele é aplicado desde o primeiro segundo.
 *  - Se não, mede enquanto toca e vai ajustando devagar (no máximo 4 dB por segundo), para não "pular".
 *  - Trechos de silêncio não contam na média. O ganho fica entre −9 dB e +18 dB, e um limitador de pico
 *    (ataque rápido, soltura lenta) mais um corte suave evitam distorção quando a música é aumentada.
 */
class Loudness {
    private var sum = 0.0
    private var blocks = 0L
    private var known = false
    var gainDb = 0f
        private set
    private var env = 1.0   // redução de ganho do limitador (1 = sem redução)

    fun reset(knownGainDb: Float?) {
        sum = 0.0; blocks = 0
        known = knownGainDb != null
        gainDb = knownGainDb ?: 0f
        env = 1.0
    }

    /** Mede e aplica o ganho no próprio buffer (PCM 16 bits intercalado). */
    fun process(pcm: ShortArray, n: Int, sampleRate: Int, channels: Int, enabled: Boolean) {
        if (n <= 0) return
        var acc = 0.0
        for (i in 0 until n) { val v = pcm[i] / 32768.0; acc += v * v }
        val ms = acc / n
        if (10 * log10(ms + 1e-12) > GATE_DB) { sum += ms; blocks++ }
        if (!known && blocks > 0) {
            val desired = desiredGain(10 * log10(sum / blocks)).toFloat()
            val step = MAX_RATE_DB_PER_S * n / (sampleRate.toFloat() * channels)
            gainDb += (desired - gainDb).coerceIn(-step, step)
        }
        if (!enabled || abs(gainDb) < 0.05f) return
        val g = 10.0.pow(gainDb / 20.0)
        val atk = 1.0 - exp(-1.0 / (ATTACK_S * sampleRate))
        val rel = 1.0 - exp(-1.0 / (RELEASE_S * sampleRate))
        var e = env
        var i = 0
        while (i + channels <= n) {
            var pk = 0
            for (c in 0 until channels) { val a = abs(pcm[i + c].toInt()); if (a > pk) pk = a }
            val need = if (pk * g > CEIL) CEIL / (pk * g) else 1.0
            e += (need - e) * (if (need < e) atk else rel)
            val k = g * e
            for (c in 0 until channels) {
                var v = pcm[i + c] * k
                val a = abs(v)
                if (a > KNEE) v = (if (v < 0) -1.0 else 1.0) * (KNEE + SPAN * tanh((a - KNEE) / SPAN))   // corte suave do que o limitador não alcançou
                pcm[i + c] = v.toInt().toShort()
            }
            i += channels
        }
        env = e
    }

    /** Ganho medido nesta reprodução (para guardar e usar da próxima vez), se já tiver dados suficientes. */
    fun measuredGainDb(): Float? = if (blocks > 400) desiredGain(10 * log10(sum / blocks)).toFloat() else null

    companion object {
        const val TARGET_DB = -14.0
        private const val GATE_DB = -50.0
        private const val MAX_RATE_DB_PER_S = 4f
        private const val CEIL = 30_500.0
        private const val KNEE = 29_500.0
        private const val SPAN = 32_767.0 - KNEE
        private const val ATTACK_S = 0.0005
        private const val RELEASE_S = 0.15
        fun desiredGain(levelDb: Double) = (TARGET_DB - levelDb).coerceIn(-9.0, 18.0)

        /**
         * Mede o volume de um arquivo inteiro sem tocar (roda em segundo plano, com a música da fila
         * já baixada). Devolve o ganho a aplicar, ou null se não conseguir decodificar.
         */
        fun analyzeFile(file: File): Float? = runCatching {
            val ex = MediaExtractor(); ex.setDataSource(file.path)
            val idx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var inEos = false; var outEos = false
            var sum = 0.0; var blocks = 0L
            var pcm = ShortArray(0)
            while (!outEos) {
                if (!inEos) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val sb = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                            .order(ByteOrder.nativeOrder()).asShortBuffer()
                        val n = sb.remaining(); if (pcm.size < n) pcm = ShortArray(n); sb.get(pcm, 0, n)
                        var acc = 0.0; for (k in 0 until n) { val v = pcm[k] / 32768.0; acc += v * v }
                        val ms = acc / n
                        if (10 * log10(ms + 1e-12) > GATE_DB) { sum += ms; blocks++ }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                }
            }
            codec.stop(); codec.release(); ex.release()
            if (blocks < 50) null else desiredGain(10 * log10(sum / blocks)).toFloat()
        }.getOrNull()
    }
}
