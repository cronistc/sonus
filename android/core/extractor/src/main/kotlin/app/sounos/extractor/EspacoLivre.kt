package app.sounos.extractor

import java.io.File

/**
 * Limite "inteligente" dos caches: o máximo configurado só vale se o celular tiver espaço sobrando.
 * Sempre deixa livre pelo menos 2 GB ou 10% do armazenamento (o que for maior); se o espaço apertar,
 * o cache encolhe (sai o usado há mais tempo) em vez de encher o aparelho.
 */
object EspacoLivre {
    fun limite(dir: File, maximo: Long, usadoPeloCache: Long): Long = runCatching {
        val total = dir.totalSpace.takeIf { it > 0 } ?: return maximo
        val reserva = maxOf(2L * 1024 * 1024 * 1024, total / 10)
        val podeCrescer = (dir.usableSpace - reserva).coerceAtLeast(0)
        minOf(maximo, usadoPeloCache + podeCrescer)
    }.getOrDefault(maximo)
}
