package app.sounos.smoke

import app.sounos.extractor.AudioQuality
import app.sounos.extractor.CoverStore
import app.sounos.extractor.AudioCache
import app.sounos.extractor.NewPipeSource
import app.sounos.extractor.SearchFilter
import app.sounos.extractor.StreamResolveCache
import app.sounos.extractor.Sounos
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis

/**
 * Teste de fumaça contra o YouTube de verdade: busca → resolve o áudio → baixa o começo → baixa a capa.
 * Mede cada etapa (é o caminho do "dar play"). Uso: ./gradlew :tools:smoke:run --args="sua busca"
 */
private fun Track_id(url: String) = app.sounos.extractor.Track.idFor(url)

fun main(args: Array<String>) {
    runBlocking { smoke(args) }
    kotlin.system.exitProcess(0)
}

private suspend fun smoke(args: Array<String>) {
    val query = args.joinToString(" ").ifBlank { "Anavitória Trevo" }
    Sounos.init()
    val source = NewPipeSource()
    val resolver = StreamResolveCache(source)
    val work = File("smoke-cache").apply { mkdirs() }
    val heads = AudioCache(File(work, "audio"), Sounos.http)
    val covers = CoverStore(File(work, "covers-pinned"), File(work, "covers-browse"), Sounos.http)

    println("Busca: \"$query\"")
    lateinit var page: app.sounos.extractor.SearchPage
    var ms = measureTimeMillis { page = source.search(query, SearchFilter.SONGS) }
    println("  ${page.items.size} músicas em $ms ms" + (page.serviceSuggestion?.let { "  (serviço sugeriu: $it)" } ?: ""))
    page.items.take(5).forEachIndexed { i, t -> println("  ${i + 1}. ${t.title} — ${t.artist} (${t.durationSec / 60}:${"%02d".format(t.durationSec % 60)})  ${t.id}") }
    val first = page.items.firstOrNull() ?: error("Nenhum resultado")

    // como o app faz: assim que os resultados chegam, já resolve os 3 primeiros em segundo plano
    resolver.prefetch(page.items.take(3))

    lateinit var resolved: app.sounos.extractor.ResolvedTrack
    ms = measureTimeMillis { resolved = resolver.get(first.url, AudioQuality.HIGH, first) }
    val a = resolved.audio
    println("\nÁudio resolvido em $ms ms: ${a.mimeType} ${a.codec ?: ""} ${a.bitrateKbps} kbps, itag ${a.itag}, " +
        "${if (a.contentLength > 0) "%.1f MB".format(a.contentLength / 1e6) else "tamanho ?"}, " +
        "vale por mais ${(a.expiresAtMs - System.currentTimeMillis()) / 60_000} min")
    ms = measureTimeMillis { resolver.get(first.url, AudioQuality.HIGH) }
    println("  2ª vez (cache): $ms ms")
    println("  relacionadas: ${resolved.related.take(3).joinToString { "${it.title} — ${it.artist}" }}")

    var head: File? = null
    ms = measureTimeMillis { heads.fill(resolved.track.id, a, heads.headBytes.toLong()); head = heads.file(resolved.track.id) }
    println("\nComeço do áudio: ${head?.length()?.div(1024) ?: "FALHOU"} KB em $ms ms")
    ms = measureTimeMillis { heads.fill(resolved.track.id, a, heads.headBytes.toLong()) }
    println("  2ª vez (disco): $ms ms")

    var cover: File? = null
    ms = measureTimeMillis { cover = covers.get(resolved.track, pin = true) }
    val img = cover?.let { runCatching { ImageIO.read(it) }.getOrNull() }
    println("\nCapa: ${cover?.length()?.div(1024) ?: "FALHOU"} KB" + (img?.let { " (${it.width}×${it.height})" } ?: "") + " em $ms ms  → ${cover?.path}")
    println("  1ª opção tentada: ${CoverStore.candidates(resolved.track).firstOrNull()}")
    ms = measureTimeMillis { covers.get(resolved.track, pin = true) }
    println("  2ª vez (fixada no disco): $ms ms")

    // fila: a próxima música (2º resultado) fica inteira em disco antes de precisar
    val queue = page.items.take(4)
    val prefetcher = app.sounos.extractor.QueuePrefetcher(resolver, heads, covers)
    ms = measureTimeMillis {
        prefetcher.onQueueChanged(queue.drop(1), AudioQuality.HIGH)
        val next = queue[1]
        while (true) {
            val r = resolver.peek(next.url)
            if (r != null && heads.isComplete(r.track.id)) break
            kotlinx.coroutines.delay(100)
        }
    }
    val nextR = resolver.get(queue[1].url, AudioQuality.HIGH, queue[1])
    println("\nFila: próxima (\"${nextR.track.title}\") inteira em disco: ${heads.file(nextR.track.id).length() / 1024} KB de ${nextR.audio.contentLength / 1024} KB, pronta em $ms ms (em segundo plano)")
    println("  2ª seguinte, começo em disco: ${heads.hasHead(Track_id(queue[2].url))}")

    val sugg = source.suggestions(query.take(5))
    println("\nSugestões do serviço para \"${query.take(5)}\": ${sugg.take(5)}")
    println("\nOK")
}
