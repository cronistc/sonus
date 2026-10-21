package app.sounos.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Capas que não somem.
 *  - FIXADAS (pinnedDir, armazenamento interno que o Android não limpa): tudo que foi tocado, curtido,
 *    está em playlist ou foi baixado. Nunca são apagadas aqui.
 *  - NAVEGAÇÃO (browseDir, pode ser o cacheDir): capas só vistas na busca; LRU com limite de tamanho.
 * O arquivo é nomeado pelo ID da música (não pela URL), então URL nova ou expirada não "perde" a capa.
 * Sempre tenta a MAIOR resolução primeiro e cai para alternativas se uma URL falhar.
 */
class CoverStore(
    private val pinnedDir: File,
    private val browseDir: File,
    private val client: OkHttpClient,
    private val browseMaxBytes: Long = 300L * 1024 * 1024,
) {
    init { pinnedDir.mkdirs(); browseDir.mkdirs() }

    private fun name(trackId: String) = trackId.replace(':', '_') + ".img"
    fun pinnedFile(trackId: String) = File(pinnedDir, name(trackId))
    fun browseFile(trackId: String) = File(browseDir, name(trackId))

    /** Capa local (fixada primeiro). Não acessa a rede. */
    fun local(trackId: String): File? =
        pinnedFile(trackId).takeIf { it.length() > 0 } ?: browseFile(trackId).takeIf { it.length() > 0 }

    /**
     * Devolve a capa, baixando se preciso.
     * pin = true → garante que fica na área fixada (música tocada/curtida/baixada).
     */
    suspend fun get(track: Track, pin: Boolean): File? = withContext(Dispatchers.IO) {
        if (!validId(track.id)) return@withContext null
        lockFor(track.id).withLock {                   // uma capa por vez por música (sem arquivo pela metade)
            // capa pequena guardada antes: só vale trocar pela grande quando ela vai aparecer grande (fixada)
            if (pin) local(track.id)?.let { old -> if (isSmall(old) && upgradeTried.add(track.id)) upgrade(track, old) }
            pinnedFile(track.id).takeIf { it.length() > 0 }?.let { return@withLock it }
            browseFile(track.id).takeIf { it.length() > 0 }?.let { cached ->
                return@withLock if (pin) promote(track.id) ?: cached else cached.also { it.setLastModified(System.currentTimeMillis()) }
            }
            val target = if (pin) pinnedFile(track.id) else browseFile(track.id)
            // busca/listas (não fixada): tamanho médio, que chega rápido; fixada: a maior que existir
            for (url in candidates(track, browse = !pin)) {
                if (download(url, target)) { if (!pin) trimBrowse(); return@withLock target }
            }
            null
        }
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private fun lockFor(id: String) = locks.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }

    private val upgradeTried = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Capa guardada antes em tamanho pequeno: tenta trocar pela grande (uma vez por sessão; se falhar, fica a antiga). */
    private fun upgrade(track: Track, old: File) {
        val tmp = File(old.parentFile, old.name + ".up")
        for (url in candidates(track)) {
            if (!download(url, tmp)) continue
            val ok = (imageSize(tmp)?.let { minOf(it.first, it.second) } ?: 0) > (imageSize(old)?.let { minOf(it.first, it.second) } ?: 0)
            if (ok && tmp.renameTo(old)) return
            tmp.delete(); return
        }
    }

    /** Menor lado abaixo de 600 px (as capas do app são exibidas em até ~1000 px). */
    fun isSmall(f: File): Boolean = imageSize(f)?.let { minOf(it.first, it.second) < 600 } ?: false

    /** Move da navegação para a área fixada (ex.: a pessoa tocou ou curtiu a música). */
    fun promote(trackId: String): File? {
        val from = browseFile(trackId); val to = pinnedFile(trackId)
        if (to.length() > 0) return to
        if (from.length() == 0L) return null
        return if (from.renameTo(to) || (runCatching { from.copyTo(to, overwrite = true) }.isSuccess && from.delete())) to else null
    }

    /** Limpar cache (configurações): só a área de navegação. As fixadas ficam. */
    fun clearBrowse(): Long {
        var freed = 0L
        browseDir.listFiles()?.forEach { freed += it.length(); it.delete() }
        return freed
    }

    private fun download(url: String, target: File): Boolean {
        if (!allowedUrl(url)) return false
        val tmp = File(target.parentFile, target.name + "." + Thread.currentThread().id + ".tmp")
        return runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT).build()).execute().use { r ->
                val type = r.header("Content-Type").orEmpty()
                if (!r.isSuccessful || !type.startsWith("image/") || !allowedUrl(r.request.url.toString())) return false
                if (r.body!!.contentLength() > MAX_BYTES) return false
                val bytes = r.body!!.source().let { src -> src.request(MAX_BYTES + 1); if (src.buffer.size > MAX_BYTES) return false; src.readByteArray() }
                // as "capas" vazias/cinzas do YouTube têm poucos KB: não troca uma capa boa por uma ruim
                if (bytes.size < 1024) return false
                tmp.writeBytes(bytes)
            }
            tmp.renameTo(target) || run { tmp.delete(); false }
        }.getOrElse { tmp.delete(); false }
    }

    private fun trimBrowse() {
        val files = browseDir.listFiles { f -> f.name.endsWith(".img") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= browseMaxBytes) break; total -= f.length(); f.delete() }
    }

    companion object {
        private const val MAX_BYTES = 6L * 1024 * 1024
        /** Só ids de música/artista do app (sem "../" nem caminhos). */
        fun validId(id: String) = id.length in 3..96 && Regex("""[A-Za-z0-9:_\-.]+""").matches(id) && ".." !in id
        /** Só baixa capa dos servidores de imagem do YouTube/Google, por HTTPS. */
        fun allowedUrl(u: String): Boolean {
            val url = runCatching { java.net.URI(u) }.getOrNull() ?: return false
            val h = url.host?.lowercase() ?: return false
            return url.scheme == "https" && (h == "i.ytimg.com" || h.endsWith(".ytimg.com") || h.endsWith(".googleusercontent.com") || h.endsWith(".ggpht.com"))
        }

        /** Largura × altura lidas do cabeçalho (JPEG/PNG), sem decodificar a imagem. */
        fun imageSize(f: File): Pair<Int, Int>? = runCatching {
            f.inputStream().buffered().use { s ->
                val b = ByteArray(30); var n = 0
                while (n < 30) { val r = s.read(b, n, 30 - n); if (r < 0) return null; n += r }
                val u = { o: Int -> b[o].toInt() and 255 }
                // WebP (as miniaturas pequenas do YouTube vêm assim): VP8 / VP8L / VP8X
                if (String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP") {
                    return when (String(b, 12, 4)) {
                        "VP8 " -> ((u(26) or (u(27) shl 8)) and 0x3fff) to ((u(28) or (u(29) shl 8)) and 0x3fff)
                        "VP8L" -> (1 + (u(21) or ((u(22) and 0x3f) shl 8))) to (1 + ((u(22) shr 6) or (u(23) shl 2) or ((u(24) and 0x0f) shl 10)))
                        "VP8X" -> (1 + (u(24) or (u(25) shl 8) or (u(26) shl 16))) to (1 + (u(27) or (u(28) shl 8) or (u(29) shl 16)))
                        else -> null
                    }
                }
                if (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) {
                    val i = { o: Int -> ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255) }
                    return i(16) to i(20)
                }
                if (b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
                f.inputStream().buffered().use { j ->
                    j.skip(2)
                    while (true) {
                        var m = j.read(); while (m == 0xFF) m = j.read()
                        if (m < 0) return null
                        val len = (j.read() shl 8) or j.read()
                        if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                            j.read(); val h = (j.read() shl 8) or j.read(); val w = (j.read() shl 8) or j.read()
                            return w to h
                        }
                        j.skip((len - 2).toLong())
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        }.getOrNull()

        private val GOOGLE_SIZE = Regex("""=w\d+-h\d+[^&/]*$""")

        /**
         * Ordem de tentativa, da melhor para a pior:
         *  1. capa quadrada do YouTube Music (lh3.googleusercontent) pedida em 1200×1200
         *  2. as capas informadas, da maior para a menor
         *  3. URLs montadas pelo ID do vídeo (nunca expiram): maxresdefault → hqdefault
         */
        fun candidates(track: Track, browse: Boolean = false): List<String> {
            if (browse) {
                // listas e busca: ~400 px chegam em ~0,2 s e ficam iguais na tela (linhas de 44–100 px)
                val q = track.thumbs.sortedByDescending { it.width * it.height }.map { it.url }
                val g = q.filter { "googleusercontent.com" in it && GOOGLE_SIZE.containsMatchIn(it) }.map { GOOGLE_SIZE.replace(it, "=w400-h400-l90-rj") }
                val v = track.videoId?.let { listOf("https://i.ytimg.com/vi/$it/mqdefault.jpg") }.orEmpty()   // 16:9 sem tarja preta
                return (g + v + candidates(track, browse = false)).distinct()
            }
            // quadradas (capa de álbum) antes das 16:9 (miniatura de vídeo); dentro de cada grupo, a maior primeiro
            val square = { t: Thumb -> t.width > 0 && kotlin.math.abs(t.width - t.height) <= 2 }
            val sorted = track.thumbs.sortedWith(compareByDescending<Thumb> { square(it) }.thenByDescending { it.width * it.height }).map { it.url }
            val upscaled = sorted.filter { "googleusercontent.com" in it && GOOGLE_SIZE.containsMatchIn(it) }
                .map { GOOGLE_SIZE.replace(it, "=w1200-h1200-l90-rj") }
            // sem capa quadrada: a miniatura grande do vídeo (1280×720; recortada no centro vira a capa em 720×720)
            // vem antes das pequenas que a lista traz (168–336 px, que ficavam borradas nos blocos da fila)
            val squares = sorted.filter { u -> track.thumbs.any { it.url == u && square(it) } }
            val big = track.videoId?.let { listOf("https://i.ytimg.com/vi/$it/maxresdefault.jpg", "https://i.ytimg.com/vi/$it/hq720.jpg", "https://i.ytimg.com/vi/$it/sddefault.jpg") }.orEmpty()
            val small = track.videoId?.let { listOf("https://i.ytimg.com/vi/$it/hqdefault.jpg") }.orEmpty()
            return (upscaled + squares + big + sorted + small).distinct()
        }
    }
}
