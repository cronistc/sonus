package app.sounos.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.nio.ByteOrder

/**
 * Player de áudio do Sonus, feito do zero:
 *   arquivo (inteiro ou ainda baixando) → MediaExtractor (separa os pacotes do WebM/MP4)
 *   → MediaCodec (decodifica Opus/AAC em PCM) → volume equilibrado → AudioTrack (alto-falante/fone).
 * Uma thread por música. A posição vem do que o hardware JÁ TOCOU (AudioTrack.getTimestamp),
 * não do que foi decodificado — é isso que mantém a letra no tempo certo, inclusive no Bluetooth.
 */
class AudioEngine(private val callbacks: Callbacks) {

    interface Callbacks {
        fun onReady(durationMs: Long)
        fun onEnded()
        fun onError(error: Throwable)
    }

    sealed class Source {
        data class LocalFile(val path: String) : Source()
        class Growing(val data: MediaDataSource) : Source()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: Session? = null
    @Volatile var normalize: Boolean = true
    /** "Pular silêncio": corta o silêncio do começo e do fim da música. */
    @Volatile var skipSilence: Boolean = true
    @Volatile var duckVolume: Float = 1f
        set(v) { field = v; session?.track?.setVolume(v) }

    fun load(source: Source, startMs: Long, playWhenReady: Boolean, knownGainDb: Float?) {
        session?.finish()
        session = Session(source, startMs * 1000, playWhenReady, knownGainDb).also { it.start() }
    }

    fun play() = session?.changePlaying(true)
    fun pause() = session?.changePlaying(false)
    fun seekTo(ms: Long) = session?.seek(ms * 1000)
    fun release() { session?.finish(); session = null }

    val isPlaying get() = session?.playing == true
    fun positionMs(): Long = session?.positionUs()?.div(1000) ?: 0
    fun durationMs(): Long = session?.durationUs?.div(1000) ?: 0
    fun currentGainDb(): Float = session?.loudness?.gainDb ?: 0f
    fun measuredGainDb(): Float? = session?.loudness?.measuredGainDb()

    private inner class Session(
        private val source: Source,
        startUs: Long,
        playWhenReady: Boolean,
        knownGainDb: Float?,
    ) : Thread("sounos-audio") {
        @Volatile var running = true
        @Volatile var playing = playWhenReady
        @Volatile var track: AudioTrack? = null
        @Volatile var durationUs = 0L
        @Volatile private var pendingSeekUs = startUs.takeIf { it > 0 } ?: -1L
        @Volatile private var baseUs = 0L
        @Volatile private var framesWritten = 0L
        @Volatile private var sampleRate = 48_000
        @Volatile private var ended = false
        val loudness = Loudness().apply { reset(knownGainDb) }
        private val lock = Object()
        private val ts = AudioTimestamp()
        // pular silêncio (só mexe na thread de áudio)
        private var leading = false          // ainda descartando o silêncio do começo
        private var leadSkippedUs = 0L
        private var heldFrames = 0L          // silêncio do final segurado (ainda não escrito no AudioTrack)

        fun changePlaying(p: Boolean) {
            playing = p
            track?.let { if (p) it.play() else it.pause() }
            synchronized(lock) { lock.notifyAll() }
        }
        fun seek(us: Long) { pendingSeekUs = us.coerceAtLeast(0); ended = false; synchronized(lock) { lock.notifyAll() } }
        fun finish() { running = false; synchronized(lock) { lock.notifyAll() }; (source as? Source.Growing)?.data?.close() }

        /** Microssegundos realmente tocados (o que a pessoa está ouvindo agora). */
        fun positionUs(): Long {
            val t = track ?: return baseUs
            var frames = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
            if (playing && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.getTimestamp(ts)) {
                frames = ts.framePosition + (System.nanoTime() - ts.nanoTime) * sampleRate / 1_000_000_000L
            }
            frames = frames.coerceIn(0, framesWritten)
            return (baseUs + frames * 1_000_000L / sampleRate).coerceAtMost(if (durationUs > 0) durationUs else Long.MAX_VALUE)
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var ex: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                ex = MediaExtractor()
                when (source) {
                    is Source.LocalFile -> ex.setDataSource(source.path)
                    is Source.Growing -> ex.setDataSource(source.data)
                }
                val idx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                ex.selectTrack(idx)
                val fmt = ex.getTrackFormat(idx)
                durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0
                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(fmt, null, null, 0)
                codec.start()
                var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var at = newTrack(sampleRate, channels)
                val dur = durationUs / 1000
                main.post { if (running) callbacks.onReady(dur) }

                val info = MediaCodec.BufferInfo()
                var pcm = ShortArray(8192)
                var inEos = false
                var outEos = false
                var needBase = true
                leading = skipSilence && pendingSeekUs < 0      // começa do zero (se vier de um seek, o bloco do seek decide)

                while (running) {
                    // pausado: dorme até tocar, procurar ou fechar
                    synchronized(lock) { while (running && !playing && pendingSeekUs < 0) lock.wait(250) }
                    if (!running) break

                    val seekUs = pendingSeekUs
                    if (seekUs >= 0) {
                        pendingSeekUs = -1
                        ex.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        codec.flush()
                        at.pause(); at.flush()
                        framesWritten = 0; baseUs = seekUs; needBase = true
                        inEos = false; outEos = false; ended = false
                        heldFrames = 0; leadSkippedUs = 0
                        leading = skipSilence && seekUs <= 100_000      // voltou ao início: pula o silêncio de novo
                        if (playing) at.play()
                    }
                    if (playing && at.playState != AudioTrack.PLAYSTATE_PLAYING) at.play()

                    if (!inEos) {
                        val i = codec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }

                    if (!outEos) {
                        val o = codec.dequeueOutputBuffer(info, 5_000)
                        when {
                            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f = codec.outputFormat
                                val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                if (sr != sampleRate || ch != channels) {
                                    at.release(); sampleRate = sr; channels = ch
                                    at = newTrack(sr, ch); framesWritten = 0; heldFrames = 0
                                }
                            }
                            o >= 0 -> {
                                if (info.size > 0) {
                                    if (needBase) { baseUs = maxOf(info.presentationTimeUs, 0); needBase = false }
                                    val sb = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                                        .order(ByteOrder.nativeOrder()).asShortBuffer()
                                    val n = sb.remaining()
                                    if (pcm.size < n) pcm = ShortArray(n)
                                    sb.get(pcm, 0, n)
                                    handleBuffer(at, pcm, n, channels)
                                }
                                codec.releaseOutputBuffer(o, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outEos = true; finishHeld(at, channels) }
                            }
                        }
                    }

                    if (outEos && !ended) {
                        // espera o hardware tocar o que falta no buffer e avisa que acabou
                        val played = at.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        if (played >= framesWritten - 32 || !playing) {
                            if (played >= framesWritten - 32) { ended = true; main.post { if (running) callbacks.onEnded() } }
                        } else sleep(10)
                        if (ended) synchronized(lock) { while (running && pendingSeekUs < 0) lock.wait(250) }
                    }
                }
            } catch (t: Throwable) {
                if (running) main.post { callbacks.onError(t) }
            } finally {
                runCatching { codec?.stop() }; runCatching { codec?.release() }
                runCatching { ex?.release() }
                runCatching { track?.stop() }; runCatching { track?.release() }
                track = null
                (source as? Source.Growing)?.data?.close()
            }
        }

        /** Pico (0..32768) de uma janela de amostras. */
        private fun peak(pcm: ShortArray, from: Int, to: Int): Int {
            var m = 0
            for (i in from until to) { val a = Math.abs(pcm[i].toInt()); if (a > m) m = a }
            return m
        }

        /**
         * Aplica "pular silêncio" e escreve no AudioTrack. Janelas de ~10 ms com pico < -50 dBFS são silêncio.
         *  - começo: descarta (máx. 10 s) e avança baseUs, então a posição continua sendo o tempo real da música;
         *  - fim (últimos 15 s): segura o silêncio sem escrever; se voltar som, escreve zeros do que foi segurado
         *    (nada muda no tempo); se chegar ao fim, finishHeld() decide se corta.
         */
        private fun handleBuffer(at: AudioTrack, pcm: ShortArray, n: Int, channels: Int) {
            if (!skipSilence) {
                if (heldFrames > 0) writeSilence(at, channels)
                leading = false
                loudness.process(pcm, n, sampleRate, channels, normalize); writeAll(at, pcm, n, channels); return
            }
            val winFrames = maxOf(1, sampleRate / 100)
            val win = winFrames * channels
            val tailStartUs = if (durationUs > 0) durationUs - TAIL_WINDOW_US else Long.MAX_VALUE
            var out = 0                                   // início do trecho compactado ainda não escrito
            var w = 0
            var r = 0
            while (r < n) {
                val end = minOf(r + win, n)
                val frames = (end - r) / channels
                val silent = peak(pcm, r, end) < SILENCE_PEAK
                if (silent && leading && leadSkippedUs < MAX_LEAD_US) {
                    val us = frames * 1_000_000L / sampleRate
                    baseUs += us; leadSkippedUs += us                 // descartado: a posição acompanha
                } else if (silent && !leading && baseUs + (framesWritten + heldFrames) * 1_000_000L / sampleRate >= tailStartUs) {
                    heldFrames += frames                              // silêncio no final: segura
                } else {
                    leading = false
                    if (heldFrames > 0) {                             // voltou som: escreve antes o silêncio segurado
                        if (w > 0) { loudness.process(pcm, w, sampleRate, channels, normalize); writeAll(at, pcm, w, channels) }
                        w = 0
                        writeSilence(at, channels)
                    }
                    if (w != r) System.arraycopy(pcm, r, pcm, w, end - r)
                    w += end - r
                }
                r = end
            }
            if (w > 0) { loudness.process(pcm, w, sampleRate, channels, normalize); writeAll(at, pcm, w, channels) }
        }

        /** Escreve zeros no lugar do silêncio segurado. */
        private fun writeSilence(at: AudioTrack, channels: Int) {
            val zeros = ShortArray(maxOf(1, sampleRate / 10) * channels)
            var left = heldFrames
            heldFrames = 0
            while (left > 0 && running && pendingSeekUs < 0) {
                val f = minOf(left, (zeros.size / channels).toLong()).toInt()
                writeAll(at, zeros, f * channels, channels)
                left -= f
            }
        }

        /** Fim da decodificação: silêncio segurado ≥ 1 s é cortado (acaba antes); menor que isso é tocado. */
        private fun finishHeld(at: AudioTrack, channels: Int) {
            if (heldFrames <= 0) return
            if (heldFrames * 1_000_000L / sampleRate >= MIN_TAIL_SILENCE_US) heldFrames = 0
            else writeSilence(at, channels)
        }

        private fun newTrack(sr: Int, ch: Int): AudioTrack {
            val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val min = AudioTrack.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min * 2, sr * ch * 2 / 5))       // ~200 ms
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.setVolume(duckVolume); track = it; if (playing) it.play() }
        }

        /** Escreve sem bloquear para sempre: se pausar/procurar/fechar no meio, sai na hora. */
        private fun writeAll(at: AudioTrack, pcm: ShortArray, n: Int, channels: Int) {
            var off = 0
            while (off < n && running && pendingSeekUs < 0) {
                if (!playing) { synchronized(lock) { lock.wait(100) }; continue }
                val w = at.write(pcm, off, n - off, AudioTrack.WRITE_NON_BLOCKING)
                if (w < 0) throw IllegalStateException("AudioTrack.write = $w")
                if (w == 0) sleep(4) else { off += w; framesWritten += w / channels }
            }
        }
    }
}

private const val SILENCE_PEAK = 100                    // ≈ -50 dBFS (0,003 de 32768)
private const val MAX_LEAD_US = 10_000_000L             // no máximo 10 s de silêncio cortados no começo
private const val TAIL_WINDOW_US = 15_000_000L          // só corta o final nos últimos 15 s
private const val MIN_TAIL_SILENCE_US = 1_000_000L      // silêncio contínuo até o fim para cortar
