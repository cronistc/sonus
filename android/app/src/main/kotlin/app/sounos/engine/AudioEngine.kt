package app.sounos.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.Process
import app.sounos.extractor.ByteReader
import app.sounos.extractor.FileByteReader
import app.sounos.extractor.WebmIndex
import app.sounos.extractor.WebmSplice
import java.io.File
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
        /** Procurou/está esperando o áudio voltar a tocar (ex.: trecho ainda não baixado). Sempre volta a false. */
        fun onBuffering(buffering: Boolean) {}
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
        set(v) { field = v; session?.setVolume(v) }

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
        @Volatile private var lastPosUs = 0L
        @Volatile private var awaitHead = false      // depois de um seek: ainda esperando o AudioTrack começar a andar
        private var bufferingNow = false
        @Volatile private var eosReached = false     // saída decodificada até o fim (trava a posição de andar para trás)
        val loudness = Loudness().apply { reset(knownGainDb) }
        private val lock = Object()
        private val ts = AudioTimestamp()
        // pular silêncio (só mexe na thread de áudio)
        private var leading = false          // ainda descartando o silêncio do começo
        private var leadSkippedUs = 0L
        private var heldFrames = 0L          // silêncio do final segurado (ainda não escrito no AudioTrack)
        // seek rápido em WebM: índice (Cues) lido uma vez; cada seek longe abre um extractor novo a partir do cluster certo
        private var rafReader: FileByteReader? = null
        private var origReader: ByteReader? = null
        private var origSize = -1L
        private var webmIndex: WebmIndex? = null
        private var patchedHeader: ByteArray? = null
        private var fastSeekTried = false
        private var curClusterOffset = -1L   // cluster onde começa o extractor atual (-1 = arquivo original)
        // decodificador assíncrono: os callbacks (thread "sounos-codec") só anotam índices; tudo o resto é nesta thread
        private val freeIn = ArrayDeque<Int>()              // buffers de entrada livres (guardados por `lock`)
        private val readyOut = ArrayDeque<Out>()            // saídas prontas, na ordem (guardadas por `lock`)
        private var flushing = 0                            // flush() em andamento: callbacks de antes dele são descartados
        @Volatile private var codecError: Throwable? = null

        /** Saída do decodificador: um buffer com um ou mais trechos de PCM, ou uma troca de formato. */
        private inner class Out(val index: Int, val infos: List<MediaCodec.BufferInfo>, val format: MediaFormat? = null)

        private inner class CodecCallback : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = offer { freeIn.addLast(index) }
            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
                offer { readyOut.addLast(Out(index, listOf(copyInfo(info)))) }
            /**
             * Android 15+ com "multiple-frames": vários pacotes decodificados num buffer só. Sem `override` porque o
             * compileSdk (34) ainda não conhece o método; a assinatura é a mesma do MediaCodec.Callback do Android 15.
             */
            @Suppress("unused")
            fun onOutputBuffersAvailable(c: MediaCodec, index: Int, infos: java.util.ArrayDeque<MediaCodec.BufferInfo>) =
                offer { readyOut.addLast(Out(index, infos.map(::copyInfo))) }
            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = offer { readyOut.addLast(Out(-1, emptyList(), format)) }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { codecError = e; synchronized(lock) { lock.notifyAll() } }
        }

        private inline fun offer(add: () -> Unit) {
            synchronized(lock) { if (flushing == 0) { add(); lock.notifyAll() } }
        }

        /** Prepara (uma vez) leitor + índice + cabeçalho remendado. false = sem seek rápido (não é WebM, falhou, etc.). */
        private fun prepareFastSeek(): Boolean {
            if (fastSeekTried) return webmIndex != null
            fastSeekTried = true
            try {
                when (source) {
                    is Source.LocalFile -> {
                        val f = FileByteReader(File(source.path)).also { rafReader = it }
                        origReader = f.reader; origSize = f.length
                    }
                    is Source.Growing -> {
                        val d = source.data
                        origReader = { p, b, o, l -> d.readAt(p, b, o, l) }; origSize = d.size
                    }
                }
                val r = origReader ?: return false
                val idx = WebmIndex.parse(r) ?: return false
                patchedHeader = WebmSplice.patchedHeader(idx, r) ?: return false
                webmIndex = idx
            } catch (t: Throwable) { webmIndex = null; patchedHeader = null }
            return webmIndex != null
        }

        /**
         * Tenta abrir um MediaExtractor novo já posicionado no cluster do cue de [seekUs] (pula a varredura linear).
         * Devolve o novo extractor (do mesmo formato do atual) ou null — nesse caso nada foi alterado.
         */
        private fun openSplicedExtractor(seekUs: Long, cur: MediaExtractor): MediaExtractor? {
            if (!prepareFastSeek()) return null
            val idx = webmIndex ?: return null
            val header = patchedHeader ?: return null
            val r = origReader ?: return null
            val cue = idx.cueFor(seekUs) ?: return null
            if (cue.clusterOffset == curClusterOffset) return null          // mesmo cluster: o seekTo normal já é rápido
            if (origSize > 0 && cue.clusterOffset >= origSize) return null
            var nx: MediaExtractor? = null
            val t0 = SystemClock.elapsedRealtime()
            try {
                val splice = WebmSplice.create(header, cue.clusterOffset, origSize, r) ?: return null
                nx = MediaExtractor()
                nx.setDataSource(SplicedWebmSource(splice, r))
                val ai = (0 until nx.trackCount).firstOrNull { nx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: run { nx.release(); return null }
                val nf = nx.getTrackFormat(ai)
                val of = (0 until cur.trackCount).map { cur.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                if (of == null || nf.getString(MediaFormat.KEY_MIME) != of.getString(MediaFormat.KEY_MIME) ||
                    nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) != of.getInteger(MediaFormat.KEY_SAMPLE_RATE) ||
                    nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) { nx.release(); return null }
                nx.selectTrack(ai)
                nx.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                curClusterOffset = cue.clusterOffset
                return nx
            } catch (t: Throwable) {
                runCatching { nx?.release() }
                // música ainda baixando e o trecho não chegou (a leitura já esperou o prazo): é falha do download,
                // igual ao caminho antigo daria — não cai no seekTo normal para esperar tudo de novo
                if (running && source is Source.Growing && SystemClock.elapsedRealtime() - t0 > 10_000)
                    throw java.io.IOException("o trecho da música não chegou", t)
                return null
            }
        }

        fun changePlaying(p: Boolean) {
            playing = p
            if (!p) setBuffering(false)
            // a thread de áudio pode soltar o AudioTrack a qualquer momento: nunca deixar estourar na main
            runCatching { track?.let { if (p) it.play() else it.pause() } }
            synchronized(lock) { lock.notifyAll() }
        }
        fun setVolume(v: Float) { runCatching { track?.setVolume(v) } }
        fun seek(us: Long) {
            pendingSeekUs = us.coerceAtLeast(0); ended = false; awaitHead = true
            setBuffering(playing)
            synchronized(lock) { lock.notifyAll() }
        }

        /** Avisa a main só quando muda (na ordem), para a tela mostrar "carregando" enquanto o trecho não chega. */
        private fun setBuffering(b: Boolean) {
            synchronized(this) {
                if (bufferingNow == b) return
                bufferingNow = b
                main.post { callbacks.onBuffering(b) }
            }
        }
        fun finish() { running = false; setBuffering(false); synchronized(lock) { lock.notifyAll() }; (source as? Source.Growing)?.data?.close() }

        /** Microssegundos realmente tocados (o que a pessoa está ouvindo agora). */
        fun positionUs(): Long {
            val t = track ?: return baseUs
            // o AudioTrack pode ser solto pela thread de áudio durante a leitura (IllegalStateException): usa a última posição
            return try {
                var frames = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                if (playing && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.getTimestamp(ts)) {
                    frames = ts.framePosition + (System.nanoTime() - ts.nanoTime) * sampleRate / 1_000_000_000L
                }
                frames = frames.coerceIn(0, framesWritten)
                (baseUs + frames * 1_000_000L / sampleRate).coerceAtMost(if (durationUs > 0) durationUs else Long.MAX_VALUE)
                    .let { if (eosReached && pendingSeekUs < 0 && it < lastPosUs) lastPosUs else it }   // depois do fim, nunca volta
                .also { lastPosUs = it }
            } catch (e: Exception) {
                lastPosUs.takeIf { it > 0 } ?: baseUs
            }
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var ex: MediaExtractor? = null
            var codec: MediaCodec? = null
            var cbThread: HandlerThread? = null
            try {
                var cx = MediaExtractor()          // extractor em uso (troca no seek rápido); `ex` espelha para o finally
                ex = cx
                when (source) {
                    is Source.LocalFile -> cx.setDataSource(source.path)
                    is Source.Growing -> cx.setDataSource(NoCloseSource(source.data))   // quem fecha é o Session (a fonte é compartilhada entre extractors)
                }
                val idx = (0 until cx.trackCount).first { cx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                cx.selectTrack(idx)
                val fmt = cx.getTrackFormat(idx)
                durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                codec = MediaCodec.createDecoderByType(mime)
                // Android 15+: vários pacotes por chamada (~250 ms) em vez de um a cada 20 ms — o custo do MediaCodec é por chamada
                val multi = Build.VERSION.SDK_INT >= 35 && QUEUE_INPUT_BUFFERS != null && runCatching {
                    codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported(FEATURE_MULTIPLE_FRAMES)
                }.getOrDefault(false)
                if (multi) {
                    val batch = sampleRate * channels * 2 * BATCH_MS / 1000
                    fmt.setInteger(KEY_BATCH_THRESHOLD, batch)
                    fmt.setInteger(KEY_BATCH_MAX, batch * 2)
                    fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(MULTI_INPUT_BYTES, runCatching { fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)))
                }
                val cbHandler = Handler(HandlerThread("sounos-codec", Process.THREAD_PRIORITY_AUDIO).also { it.start(); cbThread = it }.looper)
                codec.setCallback(CodecCallback(), cbHandler)
                codec.configure(fmt, null, null, 0)
                codec.start()
                android.util.Log.i("Sonus", "motor: decodificador ${codec.name} multi=$multi")
                var at = newTrack(sampleRate, channels)
                val dur = durationUs / 1000
                main.post { if (running) callbacks.onReady(dur) }

                var pcm = ShortArray(8192)
                val batch = ArrayList<MediaCodec.BufferInfo>()
                var inEos = false
                var outEos = false
                var needBase = true
                var eosT0 = -1L                  // vigia do fim: início da espera, último movimento do head, prazo máximo
                var stoppedByUs = false; var sawHead = false   // stop() só no caso travado (head nunca saiu do zero)
                var eosTick = 0L; var eosHead = 0L; var eosMoveAt = 0L; var eosDeadline = 0L
                leading = skipSilence && pendingSeekUs < 0      // começa do zero (se vier de um seek, o bloco do seek decide)

                while (running) {
                    // pausado: dorme até tocar, procurar ou fechar
                    synchronized(lock) { while (running && !playing && pendingSeekUs < 0) lock.wait(250) }
                    if (!running) break
                    codecError?.let { throw it }

                    val seekUs = pendingSeekUs
                    if (seekUs >= 0) {
                        eosReached = false           // antes de liberar o seek: a trava de posição não pode segurar o valor novo
                        pendingSeekUs = -1
                        // seek rápido: extractor novo a partir do cluster do cue; qualquer falha cai no seekTo normal
                        val fast = openSplicedExtractor(seekUs, cx)
                        if (fast != null) { val old = cx; cx = fast; ex = fast; runCatching { old.release() } }
                        else cx.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        // flush assíncrono: o que os callbacks anotaram antes dele perde a validade; start() sai na
                        // thread dos callbacks DEPOIS dos callbacks antigos já enfileirados (que são descartados)
                        synchronized(lock) { flushing++; freeIn.clear(); readyOut.clear() }
                        codec.flush()
                        val c = codec
                        cbHandler.post {
                            val go = synchronized(lock) { --flushing == 0 }
                            if (go) try { c.start() } catch (t: Throwable) { if (running) { codecError = t; synchronized(lock) { lock.notifyAll() } } }
                        }
                        at.pause(); at.flush()
                        framesWritten = 0; baseUs = seekUs; needBase = true
                        inEos = false; outEos = false; ended = false; eosT0 = -1L; stoppedByUs = false; sawHead = false
                        heldFrames = 0; leadSkippedUs = 0
                        leading = skipSilence && seekUs <= 100_000      // voltou ao início: pula o silêncio de novo
                        // sempre play() depois do flush (também com o track parado pelo stop() do fim)
                        if (playing) runCatching { at.play() }
                    }
                    // buffering: depois do seek, até o head do AudioTrack sair do zero
                    if (awaitHead) {
                        val h = runCatching { at.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
                        if (outEos || (h > 0 && pendingSeekUs < 0)) { awaitHead = false; setBuffering(false) }
                        else setBuffering(playing && !outEos)
                    }
                    if (!outEos && playing && at.playState != AudioTrack.PLAYSTATE_PLAYING) at.play()

                    // entrada: enche todo buffer livre (no modo multi, ~250 ms de pacotes por buffer)
                    while (!inEos && running && pendingSeekUs < 0) {
                        val i = synchronized(lock) { freeIn.removeFirstOrNull() } ?: break
                        val buf = codec.getInputBuffer(i)!!
                        if (!multi) {
                            val n = cx.readSampleData(buf, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                            else { codec.queueInputBuffer(i, 0, n, cx.sampleTime, 0); cx.advance() }
                            continue
                        }
                        batch.clear()
                        var off = 0
                        var firstUs = 0L
                        while (true) {
                            val size = cx.sampleSize
                            if (size < 0) break
                            if (batch.isNotEmpty() && (off + size > buf.capacity() || cx.sampleTime - firstUs >= BATCH_MS * 1000L)) break
                            val n = cx.readSampleData(buf, off)
                            if (n < 0) break
                            if (batch.isEmpty()) firstUs = cx.sampleTime
                            batch.add(MediaCodec.BufferInfo().apply { set(off, n, cx.sampleTime, 0) })
                            off += n
                            cx.advance()
                        }
                        if (batch.isEmpty()) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else try { QUEUE_INPUT_BUFFERS!!.invoke(codec, i, java.util.ArrayDeque(batch)) }
                        catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException ?: e }
                    }

                    // saída: um buffer por vez (pode ter vários trechos de PCM)
                    val item = if (outEos) null else synchronized(lock) { readyOut.removeFirstOrNull() }
                    if (item != null && item.format != null) {
                        val f = item.format
                        val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (sr != sampleRate || ch != channels) {
                            track = null; runCatching { at.release() }; sampleRate = sr; channels = ch
                            at = newTrack(sr, ch); framesWritten = 0; heldFrames = 0
                        }
                    } else if (item != null) {
                        val ob = codec.getOutputBuffer(item.index)
                        var eosFlag = false
                        for (info in item.infos) {
                            if (info.size > 0 && ob != null && running && pendingSeekUs < 0) {
                                if (needBase) { baseUs = maxOf(info.presentationTimeUs, 0); needBase = false }
                                ob.clear(); ob.position(info.offset); ob.limit(info.offset + info.size)
                                val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val n = sb.remaining()
                                if (pcm.size < n) pcm = ShortArray(n)
                                sb.get(pcm, 0, n)
                                handleBuffer(at, pcm, n, channels)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosFlag = true
                        }
                        codec.releaseOutputBuffer(item.index, false)
                        if (eosFlag) {
                            outEos = true; finishHeld(at, channels)
                            eosReached = true
                            // MODE_STREAM: se o head nunca saiu do zero desde o flush, o limiar de início travou o track;
                            // stop() toca o que sobrou. Se já está tocando, mantém o fim natural (stop() zeraria o head).
                            val h0 = runCatching { at.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(1L)
                            if (h0 == 0L && framesWritten > 0) { stoppedByUs = runCatching { at.stop() }.isSuccess }
                        }
                    } else if (!outEos) {
                        // nada a fazer: dorme até o decodificador entregar algo (ou seek/pausa/fim acordarem)
                        synchronized(lock) {
                            if (running && playing && pendingSeekUs < 0 && codecError == null && readyOut.isEmpty() && (inEos || freeIn.isEmpty()))
                                lock.wait(if (awaitHead) 10L else 500L)     // depois de um seek, olha o head como antes (~a cada 10 ms)
                        }
                    }

                    if (outEos && !ended) {
                        // espera o hardware tocar o que falta no buffer e avisa que acabou
                        val now = SystemClock.elapsedRealtime()
                        val played = runCatching { at.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(framesWritten)
                        if (eosT0 < 0) {
                            eosT0 = now; eosTick = now; eosHead = played; eosMoveAt = now
                            eosDeadline = now + maxOf(0L, framesWritten - played) * 1000L / sampleRate + 2_000L
                        } else {
                            val gap = now - eosTick                       // ficou pausado/travado fora daqui: não conta
                            if (gap > 500) { eosMoveAt += gap; eosDeadline += gap }
                            eosTick = now
                            if (played != eosHead) { eosHead = played; eosMoveAt = now }
                        }
                        // vigia: se o head parar > 1,5 s ou passar do prazo (tempo que falta + 2 s), dá a música por acabada
                        if (played > 0) sawHead = true
                        var done = played >= framesWritten - 32 || (playing && (now - eosMoveAt > 1_500 || now > eosDeadline))
                        if (!done && stoppedByUs && playing) {
                            // depois do nosso stop(), o Android zera o head ao terminar de drenar
                            if (sawHead && played == 0L) done = true
                            else if (now - eosMoveAt > 300 && at.playState == AudioTrack.PLAYSTATE_STOPPED) done = true
                        }
                        if (done) { ended = true; awaitHead = false; setBuffering(false); main.post { if (running) callbacks.onEnded() } }
                        else if (playing) sleep(10)
                        if (ended) synchronized(lock) { while (running && pendingSeekUs < 0) lock.wait(250) }
                    }
                }
            } catch (t: Throwable) {
                if (running) main.post { callbacks.onError(t) }
            } finally {
                runCatching { codec?.stop() }; runCatching { codec?.release() }
                cbThread?.quitSafely()
                runCatching { ex?.release() }
                runCatching { rafReader?.close() }
                val old = track
                track = null                       // primeiro desliga a referência; depois solta (a main lê `track`)
                runCatching { old?.stop() }; runCatching { old?.release() }
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
                .setBufferSizeInBytes(maxOf(min * 2, sr * ch * 2 / 2))       // ~500 ms: menos vezes acordando o processador
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
                if (w > 0) { off += w; framesWritten += w / channels }
                if (off >= n || !running || pendingSeekUs >= 0 || !playing) continue
                if (awaitHead) {
                    // depois de um seek: o "carregando" some assim que o head andar (não espera o buffer esvaziar)
                    val h = runCatching { at.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
                    if (h > 0 && pendingSeekUs < 0) { awaitHead = false; setBuffering(false) }
                }
                // buffer cheio: espera esvaziar metade (poucas acordadas por segundo); pausa/seek/fim acordam na hora
                synchronized(lock) { lock.wait(if (awaitHead) 10L else maxOf(10L, at.bufferSizeInFrames * 500L / sampleRate)) }
            }
        }
    }
}

private const val BATCH_MS = 250                        // modo multi: ~quanto áudio por chamada ao MediaCodec
private const val MULTI_INPUT_BYTES = 64 * 1024         // buffer de entrada que cabe um lote de pacotes
private const val FEATURE_MULTIPLE_FRAMES = "multiple-frames"                       // CodecCapabilities.FEATURE_MultipleFrames (API 35)
private const val KEY_BATCH_THRESHOLD = "buffer-batch-threshold-output-size"        // MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE
private const val KEY_BATCH_MAX = "buffer-batch-max-output-size"                    // MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE
/** MediaCodec.queueInputBuffers (API 35) por reflexão, enquanto o compileSdk é 34. */
private val QUEUE_INPUT_BUFFERS: java.lang.reflect.Method? =
    if (Build.VERSION.SDK_INT < 35) null
    else runCatching { MediaCodec::class.java.getMethod("queueInputBuffers", Int::class.javaPrimitiveType, java.util.ArrayDeque::class.java) }.getOrNull()

private const val SILENCE_PEAK = 100                    // ≈ -50 dBFS (0,003 de 32768)
private const val MAX_LEAD_US = 10_000_000L             // no máximo 10 s de silêncio cortados no começo
private const val TAIL_WINDOW_US = 15_000_000L          // só corta o final nos últimos 15 s
private const val MIN_TAIL_SILENCE_US = 1_000_000L      // silêncio contínuo até o fim para cortar

/** O BufferInfo do callback pode ser reaproveitado pelo sistema: guarda uma cópia. */
private fun copyInfo(i: MediaCodec.BufferInfo) = MediaCodec.BufferInfo().apply { set(i.offset, i.size, i.presentationTimeUs, i.flags) }
