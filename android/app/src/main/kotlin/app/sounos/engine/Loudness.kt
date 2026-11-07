package app.sounos.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.sounos.extractor.MedidorLufs
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.math.pow

/**
 * Volume equilibrado (de verdade): mede o volume PERCEBIDO da música em LUFS ([MedidorLufs], o padrão do Spotify e do
 * YouTube) e aplica um ganho para todas ficarem perto de −14 LUFS.
 *  - Se o ganho da música já é conhecido (medido antes com o arquivo inteiro, ou numa vez anterior), ele é aplicado
 *    desde o primeiro segundo.
 *  - Se não, começa no [palpite] (o ganho típico das músicas da pessoa) e, depois de 3 s medidos, vai ajustando
 *    devagar (no máximo 4 dB por segundo), para não "pular". Antes começava em 0 dB: master alto entrava alto e ia
 *    baixando nos primeiros segundos.
 *  - O ganho fica entre −12 dB e +12 dB, e um limitador de pico (ataque rápido, soltura lenta) mais um corte suave
 *    evitam distorção quando a música é aumentada.
 */
class Loudness {
    private var medidor: MedidorLufs? = null
    private var known = false
    var gainDb = 0f
        private set
    private var env = 1.0   // redução de ganho do limitador (1 = sem redução)

    fun reset(knownGainDb: Float?, palpite: Float = 0f) {
        medidor = null
        known = knownGainDb != null
        gainDb = knownGainDb ?: palpite
        env = 1.0
    }

    /** Mede e aplica o ganho no próprio buffer (PCM 16 bits intercalado). */
    fun process(pcm: ShortArray, n: Int, sampleRate: Int, channels: Int, enabled: Boolean) {
        if (n <= 0) return
        // mede antes de aplicar o ganho (o que conta é o volume da própria música)
        val m = medidor?.takeIf { it.sampleRate == sampleRate && it.channels == channels } ?: MedidorLufs(sampleRate, channels).also { medidor = it }
        m.add(pcm, n)
        if (!known && m.segundos >= 3.0) m.integrada()?.let { lufs ->
            val desired = MedidorLufs.ganhoPara(lufs).toFloat()
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

    /** Ganho medido nesta reprodução (para guardar e usar da próxima vez), com pelo menos [minSeg] segundos de som. */
    fun measuredGainDb(minSeg: Double = 20.0): Float? = medidor?.takeIf { it.segundos >= minSeg }?.integrada()?.let { MedidorLufs.ganhoPara(it).toFloat() }
    /** Segundos de música já medidos nesta reprodução. */
    fun segundosMedidos(): Double = medidor?.segundos ?: 0.0

    companion object {
        private const val MAX_RATE_DB_PER_S = 4f
        private const val CEIL = 30_500.0
        private const val KNEE = 29_500.0
        private const val SPAN = 32_767.0 - KNEE
        private const val ATTACK_S = 0.0005
        private const val RELEASE_S = 0.15
        /**
         * Mede o volume (LUFS) de um arquivo inteiro sem tocar — em segundo plano, com prioridade baixa (não disputa com
         * o som e a tela). Devolve o ganho a aplicar, ou null se não conseguir decodificar.
         */
        fun analyzeFile(file: File): Float? {
            val tid = android.os.Process.myTid()
            val antes = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(0)
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            try { return medirArquivo(file) } finally { runCatching { android.os.Process.setThreadPriority(antes) } }
        }

        private fun medirArquivo(file: File): Float? = runCatching {
            val ex = MediaExtractor(); ex.setDataSource(file.path)
            val idx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var medidor = MedidorLufs(sr, ch)
            val info = MediaCodec.BufferInfo()
            var inEos = false; var outEos = false
            var pcm = ShortArray(0)
            try {
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
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // formato de verdade da saída (pode diferir do contêiner)
                        val f = codec.outputFormat
                        val nsr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); val nch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (nsr != sr || nch != ch) { sr = nsr; ch = nch; medidor = MedidorLufs(sr, ch) }
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val sb = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                                .order(ByteOrder.nativeOrder()).asShortBuffer()
                            val n = sb.remaining(); if (pcm.size < n) pcm = ShortArray(n); sb.get(pcm, 0, n)
                            medidor.add(pcm, n)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    }
                }
            } finally { runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { ex.release() } }
            medidor.takeIf { it.segundos >= 5.0 }?.integrada()?.let { MedidorLufs.ganhoPara(it).toFloat() }
        }.getOrNull()
    }
}
