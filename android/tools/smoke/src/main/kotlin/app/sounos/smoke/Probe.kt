package app.sounos.smoke

import app.sounos.extractor.*
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    Sounos.init()
    val src = NewPipeSource()
    val q = args.joinToString(" ").ifBlank { "grupo menos é mais coração partido" }
    val first = src.search(q).items.first()
    println("BUSCA: ${first.title} / ${first.artist}")
    var t = System.currentTimeMillis()
    val r = src.resolve(first.url, known = first)
    println("resolve ${System.currentTimeMillis() - t}ms, ${r.related.size} imediatas, depois=${r.relatedLater != null}")
    t = System.currentTimeMillis()
    val later = r.relatedLater?.await()
    if (later != null) println("rádio chegou +${System.currentTimeMillis() - t}ms")
    (later ?: r.related).take(25).forEach { println("  ${it.title} | ${it.artist} (${it.durationSec}s) ${it.thumbs.maxByOrNull { th -> th.width }?.let { th -> "${th.width}x${th.height}" }}") }
}
