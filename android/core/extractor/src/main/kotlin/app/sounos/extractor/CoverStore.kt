package app.sounos.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.Properties

/**
 * Capas em diretórios separados: as fixadas por playlists/downloads ficam permanentes; as demais
 * compartilham o LRU de imagens e metadados. O índice por URL permite reutilizar a mesma imagem
 * quando telas diferentes usam IDs distintos.
 */
class CoverStore(
    private val pinnedDir: File,
    private val browseDir: File,
    private val client: OkHttpClient,
    private val browseMaxBytes: Long = 300L * 1024 * 1024,
    private val budget: DiskCacheBudget = DiskCacheBudget(listOf(browseDir), browseMaxBytes),
    /** descobre a capa da versão em música de um clipe (null nos testes: sem rede) */
    private val capaDaMusica: CapaDaMusica? = null,
) {
    /** um arquivo de capa foi trocado por outra imagem (o app esquece as cores calculadas dele) */
    @Volatile var aoTrocarCapa: (File) -> Unit = {}

    init { pinnedDir.mkdirs(); browseDir.mkdirs() }

    data class ArtistPhoto(
        val name: String,
        val url: String?,
        val file: File?,
        val channelUrl: String? = null,
        val alternatives: List<String> = listOfNotNull(url),
    )

    private val artistIndexFile = File(browseDir, "artist-urls.properties")
    private val artistIndexLock = Any()
    private val artistUrls = Properties()
    private var artistIndexLoaded = false
    private val aliasesFile = File(browseDir, "cover-urls.properties")
    private val aliasesLock = Any()
    private val aliases = Properties()
    private var aliasesLoaded = false
    private val artistLookupMisses = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---------- capa oficial conferida, por faixa (files/covers/capas-conferidas.properties) ----------
    // id → "m" (é música: a capa que veio é a do álbum) · "c <url>" (clipe: capa da versão em música) ·
    //      "v" (clipe sem versão em música achada) · "ok.<id>" = URL já instalada nos arquivos da faixa
    private val conferidasFile = File(pinnedDir, "capas-conferidas.properties")
    private val conferidas = Properties()
    private var conferidasLoaded = false
    private val conferindo = kotlinx.coroutines.sync.Semaphore(3)           // poucas consultas ao YouTube Music ao mesmo tempo
    private val semResposta = java.util.concurrent.ConcurrentHashMap<String, Long>()   // falhou (sem rede): tenta de novo depois
    private val tentou = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()     // baixar de novo uma vez por abertura

    private fun <T> comConferidas(block: () -> T): T = synchronized(conferidas) {
        if (!conferidasLoaded) { conferidasLoaded = true; if (conferidasFile.isFile) runCatching { conferidasFile.inputStream().use(conferidas::load) } }
        block()
    }
    private fun entradaConferida(id: String): String? = comConferidas { conferidas.getProperty(id) }
    /** URL da capa oficial (versão em música) já descoberta para esta faixa, se houver. */
    fun capaConferida(id: String): String? = entradaConferida(id)?.takeIf { it.startsWith("c ") }?.substring(2)
    /** A faixa ainda não teve a capa conferida (ou a capa oficial ainda não foi instalada nos arquivos dela). */
    fun precisaConferir(id: String): Boolean = capaDaMusica != null && id.startsWith("yt:") && comConferidas {
        val e = conferidas.getProperty(id)
        e == null || e == "v" || (e.startsWith("c ") && conferidas.getProperty("ok.$id") != e.substring(2))
    }
    private fun anotar(id: String, valor: String) = comConferidas { conferidas.setProperty(id, valor); savePropertiesLocked(conferidas, conferidasFile) }
    private fun instalada(id: String, url: String) = comConferidas { conferidas.setProperty("ok.$id", url); savePropertiesLocked(conferidas, conferidasFile) }

    /** Confere (uma vez por faixa) se é música ou clipe e qual a capa da versão em música. */
    private suspend fun conferir(track: Track, trace: (String) -> Unit): String? {
        // "v" = clipe sem versão em música pela regra antiga (só a mesma duração): confere de novo UMA vez com a regra
        // nova (aceita outra duração e a 1ª música de pot-pourri); se ainda não achar, fica "v2" e não volta mais
        entradaConferida(track.id)?.let { if (it != "v") return it }
        val resolver = capaDaMusica ?: return null
        if ((semResposta[track.id] ?: 0) > System.currentTimeMillis()) return null
        val r = conferindo.withPermit { runCatching { resolver.resolver(track) }.getOrNull() }
        if (r == null) { semResposta[track.id] = System.currentTimeMillis() + 10 * 60_000L; return null }
        val valor = when { r.musica -> "m"; r.capa != null -> "c ${r.capa}"; else -> "v2" }
        anotar(track.id, valor)
        trace("id=${track.id} etapa=conferida resultado=${valor.take(60)}")
        return valor
    }

    fun artistNeedsRecheck(name: String): Boolean = needsArtistRecheck(artistKey(name))
    private fun needsArtistRecheck(key: String): Boolean = synchronized(artistIndexLock) {
        loadArtistIndexLocked()
        artistUrls.getProperty("_recheck.$key") == "true"
    }

    private fun name(trackId: String) = trackId.replace(':', '_') + ".img"
    fun pinnedFile(trackId: String) = File(pinnedDir, name(trackId))
    fun browseFile(trackId: String) = File(browseDir, name(trackId))
    fun largeBrowseFile(trackId: String) = File(browseDir, name(trackId) + ".large")

    // ---------- capinha (linhas da busca: 44 px na tela ≈ 125 px no A54) ----------
    // Baixa ~10 KB em vez dos ~60 KB da capa de lista (400 px): com internet fraca a busca inteira aparecia em ~5 s.
    fun miniFile(trackId: String) = File(browseDir, name(trackId) + ".mini")

    /** Capinha local: a primeira que basta (a menor serve; nunca decodifica a de 1000 px para uma linha de 44 px). */
    fun localMini(trackId: String, hint: String? = null): File? {
        val mini = miniFile(trackId).takeIf { it.isFile && it.length() > 0 }
        val outra = listOf(browseFile(trackId), pinnedFile(trackId), largeBrowseFile(trackId))
            .firstOrNull { it.isFile && it.length() > 0 && resolution(it) >= MINI_MIN_PIXELS }
        return (outra ?: mini)?.also(::touch)
    }

    /** Baixa só a capinha (capa oficial já conhecida, senão a quadrada que veio; quadro de vídeo por último). */
    suspend fun getMini(track: Track): File? = withContext(Dispatchers.IO) {
        if (!validId(track.id)) return@withContext null
        localMini(track.id, track.thumbs.maxByOrNull { it.width * it.height }?.url)?.let { return@withContext it }
        lockFor(track.id + "|mini").withLock {
            localMini(track.id)?.let { return@withLock it }
            val target = miniFile(track.id)
            val urls = (listOfNotNull(capaConferida(track.id)) + track.thumbs.sortedByDescending { kotlin.math.abs(it.width - it.height) <= 2 }.map { it.url })
                .flatMap { imageCandidates(it, MINI_MIN_PIXELS) }.distinct()
            val (videos, quadradas) = urls.partition { runCatching { java.net.URI(it).host.orEmpty() }.getOrDefault("").endsWith("ytimg.com") }
            for (u in quadradas) if (download(u, target, MINI_MIN_PIXELS)) return@withLock target
            val video = track.videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            for (u in videos + listOfNotNull(video)) if (download(u, target, MINI_MIN_PIXELS, largo = true)) return@withLock target
            null
        }
    }

    /** Capa local. Prefere arquivos que atendem ao tamanho pedido; nunca escolhe um alias menor se há um bom. */
    fun local(trackId: String, hint: String? = null, large: Boolean = false): File? {
        val pinned = pinnedFile(trackId)
        val browse = browseFile(trackId)
        val largeBrowse = largeBrowseFile(trackId)
        val aliases = if (capaConferida(trackId) != null) emptyList() else listOfNotNull(aliasFor(hint, large), aliasFor(hint, !large))
        val ordered = if (large) listOf(largeBrowse, pinned) + aliases + listOf(browse)
            else listOf(browse, pinned) + aliases + listOf(largeBrowse)
        val files = ordered.distinct().filter { it.isFile && it.length() > 0 }
        val minPixels = minPixels(large)
        val sized = files.map { it to resolution(it) }.filter { it.second > 0 }
        return (sized.filter { it.second >= minPixels }.maxByOrNull { it.second }?.first
            ?: sized.maxByOrNull { it.second }?.first)?.also(::touch)
    }

    /**
     * Qualidade de uma capa: o menor lado em pixels, lido do cabeçalho. Imagem que NÃO é quadrada (quadro de vídeo
     * 16:9) vale só 1/4: antes um 1280×720 contava como 720 e passava como capa de player para sempre — o centro
     * recortado mostrava o texto do clipe cortado. Assim qualquer capa quadrada boa vence e o quadro de vídeo fica
     * só como último recurso.
     */
    fun resolution(file: File): Int = imageSize(file)?.let { (w, h) -> qualidade(w, h) } ?: 0

    fun meetsMinimum(file: File, large: Boolean): Boolean = resolution(file) >= minPixels(large)

    private fun minPixels(large: Boolean) = if (large) LARGE_MIN_PIXELS else LIST_MIN_PIXELS

    /** Atualiza o LRU somente para arquivos da área descartável. */
    fun touch(file: File) {
        if (file.parentFile == browseDir) file.setLastModified(System.currentTimeMillis())
    }

    /** Fixa no armazenamento permanente somente se a capa já estiver no cache. */
    fun pinLocal(trackId: String, hint: String? = null): File? {
        val existing = local(trackId, hint, large = false) ?: return null
        val target = pinnedFile(trackId)
        if (existing == target) return existing
        if (target.isFile && resolution(target) >= resolution(existing)) return target
        return copyBetter(existing, target)
    }

    /** Move capas antigas da área permanente para o LRU, preservando downloads e playlists. */
    fun migrateLegacyPinned(keepIds: Set<String>): Pair<Int, Long> {
        val keepNames = keepIds.map(::name).toSet()
        var moved = 0
        var bytes = 0L
        val oldIndex = File(pinnedDir, artistIndexFile.name)
        if (oldIndex.isFile) {
            if (artistIndexFile.exists()) { oldIndex.delete(); moved++ }
            else if (oldIndex.renameTo(artistIndexFile) || runCatching { oldIndex.copyTo(artistIndexFile, overwrite = false); oldIndex.delete() }.isSuccess) moved++
        }
        pinnedDir.listFiles { file -> file.isFile && file.name.endsWith(".img") }?.forEach { source ->
            if (source.name in keepNames) return@forEach
            val target = File(browseDir, source.name)
            if (target.length() >= source.length()) {
                source.delete()
                updateAliasesForMove(source, target)
                moved++
                return@forEach
            }
            val oldSize = source.length()
            val ok = source.renameTo(target) || runCatching {
                source.copyTo(target, overwrite = true).also { target.setLastModified(source.lastModified()) }
                source.delete()
                true
            }.getOrDefault(false)
            if (ok) { updateAliasesForMove(source, target); moved++; bytes += oldSize }
        }
        return moved to bytes
    }

    private fun aliasFor(url: String?, large: Boolean): File? {
        if (url.isNullOrBlank()) return null
        val key = cacheUrlKey(url) ?: return null
        return synchronized(aliasesLock) {
            loadAliasesLocked()
            if (aliasesFile.isFile) aliasesFile.setLastModified(System.currentTimeMillis())
            val ref = aliases.getProperty("$key:${if (large) "large" else "small"}")
                ?: aliases.getProperty(key) ?: return@synchronized null
            when {
                ref.startsWith("p:") -> File(pinnedDir, ref.removePrefix("p:"))
                ref.startsWith("b:") -> File(browseDir, ref.removePrefix("b:"))
                else -> null
            }
        }?.takeIf { it.isFile && it.length() > 0 }?.also(::touch)
    }

    private fun rememberAlias(url: String, file: File, large: Boolean) {
        val key = cacheUrlKey(url) ?: return
        val ref = when (file.parentFile) {
            pinnedDir -> "p:${file.name}"
            browseDir -> "b:${file.name}"
            else -> return
        }
        synchronized(aliasesLock) {
            loadAliasesLocked()
            val aliasKey = "$key:${if (large) "large" else "small"}"
            if (aliases.getProperty(aliasKey) == ref) return
            aliases.setProperty(aliasKey, ref)
            savePropertiesLocked(aliases, aliasesFile)
        }
    }

    private fun updateAliasesForMove(from: File, to: File) = synchronized(aliasesLock) {
        loadAliasesLocked()
        val oldRef = if (from.parentFile == browseDir) "b:${from.name}" else "p:${from.name}"
        val newRef = if (to.parentFile == browseDir) "b:${to.name}" else "p:${to.name}"
        var changed = false
        aliases.stringPropertyNames().forEach { key ->
            if (aliases.getProperty(key) == oldRef) { aliases.setProperty(key, newRef); changed = true }
        }
        if (changed) savePropertiesLocked(aliases, aliasesFile)
    }

    private fun preserveLegacyLarge(trackId: String, from: File) {
        val to = largeBrowseFile(trackId)
        if (to.length() > 0 || !from.isFile || resolution(from) < LARGE_MIN_PIXELS) return
        if (!runCatching { from.copyTo(to, overwrite = false) }.isSuccess) return
        val oldRef = "b:${from.name}"
        val newRef = "b:${to.name}"
        synchronized(aliasesLock) {
            loadAliasesLocked()
            var changed = false
            aliases.stringPropertyNames().filter { it.endsWith(":large") }.forEach { key ->
                if (aliases.getProperty(key) == oldRef) { aliases.setProperty(key, newRef); changed = true }
            }
            if (changed) savePropertiesLocked(aliases, aliasesFile)
        }
    }

    private fun loadAliasesLocked() {
        if (aliasesLoaded) return
        if (aliasesFile.isFile) runCatching { aliasesFile.inputStream().use(aliases::load) }
        aliasesLoaded = true
    }

    private fun savePropertiesLocked(properties: Properties, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            FileOutputStream(tmp).use { properties.store(it, null) }
            if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) }
            target.setLastModified(System.currentTimeMillis())
        }.onFailure { tmp.delete() }
    }

    /** Chaves persistidas para a auditoria percorrer também fotos antigas fora de “Seus artistas”. */
    fun knownArtistKeys(): List<String> = synchronized(artistIndexLock) {
        loadArtistIndexLocked()
        artistUrls.stringPropertyNames().filterNot { it.startsWith("_recheck.") }.sorted()
    }

    /** Regras de nome/foto mudaram: toda foto de artista é conferida de novo quando aparecer (a antiga fica até a nova chegar). */
    fun recheckAllArtists() = synchronized(artistIndexLock) {
        loadArtistIndexLocked()
        artistUrls.stringPropertyNames().filterNot { it.startsWith("_recheck.") }.forEach { artistUrls.setProperty("_recheck.$it", "true") }
        persistArtistIndexLocked()
    }

    /** Resolve e persiste a URL pelo nome; o perfil e a rota de imagem compartilham esta busca. */
    suspend fun resolveArtistPhoto(
        name: String,
        hintUrl: String? = null,
        lookup: suspend (String) -> ArtistResult? = { null },
        trace: (String) -> Unit = {},
    ): ArtistPhoto? = withContext(Dispatchers.IO) {
        val key = artistKey(name)
        if (key.isBlank()) return@withContext null
        val id = "artist:$key"
        val started = System.nanoTime()
        val waitStarted = System.nanoTime()
        lockFor(id).withLock {
            trace("chave=$key etapa=lock_ms=${elapsedMs(waitStarted)}")
            val indexStarted = System.nanoTime()
            val recheck = needsArtistRecheck(key)
            val indexed = readArtistUrl(key)
            val cached = if (recheck) null else {
                browseFile(id).takeIf { it.length() > 0 && resolution(it) >= ARTIST_MIN_PIXELS }?.also(::touch)
                    ?: pinnedFile(id).takeIf { it.length() > 0 && resolution(it) >= ARTIST_MIN_PIXELS }
            }
            trace("chave=$key etapa=disco_indice_ms=${elapsedMs(indexStarted)} imagem=${cached != null} url=${indexed != null}")

            var matchedName = name
            var channelUrl: String? = null
            var found: ArtistResult? = null
            var searched = false
            // Uma entrada marcada para revisão não pode ser validada por uma pista antiga da UI.
            val hint = hintUrl?.takeIf { !recheck && allowedUrl(it) && !isPlaceholderPhoto(it) }?.let(::artistImageUrl)
            val youtubeUrls = mutableListOf<String>()
            val url = indexed?.takeIf { allowedUrl(it) && !isPlaceholderPhoto(it) }?.let(::artistImageUrl) ?: hint ?: run {
                val missUntil = artistLookupMisses[key] ?: 0L
                if (missUntil > System.currentTimeMillis()) {
                    trace("chave=$key etapa=searchArtists_ms=0 resultado=negativo_em_cache")
                    null
                } else {
                    searched = true
                    val searchStarted = System.nanoTime()
                    found = runCatching { lookup(name) }.getOrNull()
                        ?.takeIf { artistKey(it.name) == key && it.thumbs.any { thumb -> thumb.url.isNotBlank() && !isPlaceholderPhoto(thumb.url) } }
                    trace("chave=$key etapa=searchArtists_ms=${elapsedMs(searchStarted)} encontrado=${found != null}")
                    matchedName = found?.name?.ifBlank { name } ?: name
                    channelUrl = found?.url
                    found?.thumbs.orEmpty().sortedByDescending { it.width * it.height }
                        .map { artistImageUrl(it.url) }
                        .filter { allowedUrl(it) && !isPlaceholderPhoto(it) }
                        .distinct().also(youtubeUrls::addAll).firstOrNull()
                        ?: DeezerArtist(client).photo(name).also { trace("chave=$key etapa=deezer encontrado=${it != null}") }   // reserva: sem foto no YouTube Music
                }
            }

            if (url != null) {
                artistLookupMisses.remove(key)
                val persistStarted = System.nanoTime()
                saveArtistUrl(key, url)
                val matchedKey = artistKey(matchedName)
                if (matchedKey.isNotBlank() && matchedKey != key) saveArtistUrl(matchedKey, url)
                trace("chave=$key etapa=indice_gravar_ms=${elapsedMs(persistStarted)}")
            } else if (searched) {
                artistLookupMisses[key] = System.currentTimeMillis() + 5 * 60 * 1000L
            }
            trace("chave=$key etapa=resolver_ms=${elapsedMs(started)} origem=${when { indexed != null -> "indice"; hintUrl != null -> "pista"; url != null -> "busca"; else -> "vazio" }}")
            val alternatives = (youtubeUrls + listOfNotNull(url)).distinct()
            ArtistPhoto(matchedName, url, cached, channelUrl, alternatives)
        }
    }

    /** Baixa a foto resolvida para o LRU, em tamanho de avatar (~400 px). */
    suspend fun getArtistPhoto(
        name: String,
        hintUrl: String? = null,
        lookup: suspend (String) -> ArtistResult? = { null },
        trace: (String) -> Unit = {},
    ): ArtistPhoto? = withContext(Dispatchers.IO) {
        val key = artistKey(name)
        if (key.isBlank()) return@withContext null
        val totalStarted = System.nanoTime()
        val photo = resolveArtistPhoto(name, hintUrl, lookup, trace) ?: return@withContext null
        if (photo.file != null || photo.url == null) {
            trace("chave=$key etapa=total_ms=${elapsedMs(totalStarted)} origem=${if (photo.file != null) "disco" else "sem_url"}")
            return@withContext photo
        }

        val id = "artist:$key"
        val lockStarted = System.nanoTime()
        var sharedCache = false
        lockFor(id).withLock {
            trace("chave=$key etapa=download_lock_ms=${elapsedMs(lockStarted)}")
            val target = browseFile(id)
            val recheck = needsArtistRecheck(key)
            if (target.length() > 0 && resolution(target) >= ARTIST_MIN_PIXELS && !recheck) {
                touch(target)
                sharedCache = true
                return@withLock photo.copy(file = target)
            }
            val downloadStarted = System.nanoTime()
            val staged = File(browseDir, target.name + ".refresh")
            staged.delete()
            val oldPhoto = listOf(target, pinnedFile(id)).filter { it.isFile && it.length() > 0 }.maxByOrNull(::resolution)
            val sources = linkedSetOf<String>().apply {
                addAll(photo.alternatives)
                add(photo.url)
            }
            // Um URL salvo pode ter expirado. Reconsulta o YouTube Music e guarda todas as fotos
            // utilizáveis para tentar as alternativas antes de recorrer ao Deezer.
            if (sources.size < 2 || sources.all { runCatching { java.net.URI(it).host.orEmpty().endsWith("dzcdn.net") }.getOrDefault(false) }) {
                runCatching { lookup(name) }.getOrNull()
                    ?.takeIf { artistKey(it.name) == key }
                    ?.thumbs.orEmpty().sortedByDescending { it.width * it.height }
                    .map { artistImageUrl(it.url) }
                    .filter { allowedUrl(it) && !isPlaceholderPhoto(it) }
                    .forEach(sources::add)
            }
            // Deezer também é reserva quando o YouTube tinha uma foto, mas o arquivo falhou.
            runCatching { DeezerArtist(client).photo(name) }.getOrNull()?.let(sources::add)
            var ok = false
            var installedUrl: String? = null
            for (source in sources) {
                for (candidate in imageCandidates(source, ARTIST_MIN_PIXELS)) {
                    if (download(candidate, staged, ARTIST_MIN_PIXELS)) { ok = true; installedUrl = source; break }
                }
                if (ok) break
            }
            val stagedResolution = if (ok) resolution(staged) else 0
            val oldResolution = oldPhoto?.let(::resolution) ?: 0
            // foto marcada para conferir é suspeita: a nova (validada) entra mesmo com menos pixels; senão a marca nunca saía
            if (ok && oldPhoto != null && stagedResolution < oldResolution && !recheck) ok = false
            val preserved = if (ok && recheck && oldPhoto != null) {
                val backup = File(pinnedDir, target.name + ".unverified")
                backup.isFile || runCatching { oldPhoto.copyTo(backup, overwrite = false) }.isSuccess
            } else true
            val installed = ok && preserved && (staged.renameTo(target) || runCatching {
                staged.copyTo(target, overwrite = true); staged.delete(); true
            }.getOrDefault(false))
            if (installed) {
                installedUrl?.let { saveArtistUrl(key, it); rememberAlias(it, target, large = false) }
                hintUrl?.let { rememberAlias(it, target, large = false) }
                if (recheck) clearArtistRecheck(key)
                val trimStarted = System.nanoTime()
                // Falha ao podar o cache não pode invalidar a capa já baixada.
                runCatching { budget.trim() }
                    .onSuccess { trace("chave=$key etapa=lru_ms=${elapsedMs(trimStarted)} removidos_bytes=${it.removedBytes}") }
                    .onFailure { trace("chave=$key etapa=lru_falhou erro=${it}") }
            } else staged.delete()
            trace("chave=$key etapa=download_ms=${elapsedMs(downloadStarted)} bytes=${if (installed) target.length() else 0} ok=$installed")
            photo.copy(file = target.takeIf { installed } ?: oldPhoto)
        }.also {
            val origin = when { it.file == null -> "falha"; sharedCache -> "disco_compartilhado"; else -> "rede" }
            trace("chave=$key etapa=total_ms=${elapsedMs(totalStarted)} origem=$origin")
        }
    }

    private fun readArtistUrl(key: String): String? = synchronized(artistIndexLock) {
        loadArtistIndexLocked()
        if (artistIndexFile.isFile) artistIndexFile.setLastModified(System.currentTimeMillis())
        artistUrls.getProperty(key)?.takeUnless { artistUrls.getProperty("_recheck.$key") == "true" }
    }

    private fun saveArtistUrl(key: String, url: String) = synchronized(artistIndexLock) {
        loadArtistIndexLocked()
        val canonical = artistImageUrl(url)
        if (artistUrls.getProperty(key) == canonical) return@synchronized
        artistUrls.setProperty(key, canonical)
        persistArtistIndexLocked()
        Unit
    }

    private fun clearArtistRecheck(key: String) = synchronized(artistIndexLock) {
        loadArtistIndexLocked()
        if (artistUrls.remove("_recheck.$key") != null) persistArtistIndexLocked()
    }

    private fun persistArtistIndexLocked() {
        val tmp = File(browseDir, artistIndexFile.name + ".tmp")
        runCatching {
            FileOutputStream(tmp).use { artistUrls.store(it, null) }
            if (!tmp.renameTo(artistIndexFile)) {
                // renameTo não substitui em todas as implementações; preserva o arquivo íntegro.
                val backup = File(browseDir, artistIndexFile.name + ".old")
                if (artistIndexFile.exists() && !artistIndexFile.renameTo(backup)) return
                if (!tmp.renameTo(artistIndexFile) && backup.exists()) backup.renameTo(artistIndexFile)
                else backup.delete()
            }
            if (artistIndexFile.isFile) artistIndexFile.setLastModified(System.currentTimeMillis())
        }.onFailure { tmp.delete() }
    }

    private fun loadArtistIndexLocked() {
        if (artistIndexLoaded) return
        val source = artistIndexFile.takeIf { it.isFile } ?: File(pinnedDir, artistIndexFile.name)
        if (source.isFile) runCatching { source.inputStream().use(artistUrls::load) }
        var changed = false
        artistUrls.stringPropertyNames().filterNot { it.startsWith("_recheck.") }.forEach { key ->
            val old = artistUrls.getProperty(key) ?: return@forEach
            // boneco cinza salvo como foto: apaga para a tela mostrar a inicial do artista
            if (isPlaceholderPhoto(old)) {
                artistUrls.remove(key); artistUrls.remove("_recheck.$key"); changed = true
                browseFile("artist:$key").delete(); pinnedFile("artist:$key").delete()
                return@forEach
            }
            val normalized = artistImageUrl(old)
            val path = runCatching { java.net.URI(old).rawPath.orEmpty() }.getOrDefault("")
            val repeatedSize = Regex("(?:=[^/?&#=]*){2,}$").containsMatchIn(path)
            if (normalized != old) { artistUrls.setProperty(key, normalized); changed = true }
            // Migra somente os registros sabidamente suspeitos; guarda a foto velha até a nova
            // passar pelo mesmo download validado que as demais capas.
            // Tribalistas: conferida UMA vez (a cópia ".unverified" da foto velha marca que já foi). Antes era a cada
            // abertura do app, e a marca só saía com download bem-sucedido.
            if (repeatedSize || (key == "tribalistas" && !File(pinnedDir, browseFile("artist:$key").name + ".unverified").isFile)) {
                val marker = "_recheck.$key"
                if (artistUrls.getProperty(marker) != "true") { artistUrls.setProperty(marker, "true"); changed = true }
            }
        }
        artistIndexLoaded = true
        if (changed) persistArtistIndexLocked()
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000

    /**
     * Devolve a capa, baixando se preciso.
     * pin fixa em disco; large pede qualidade de player e fica independente da fixação.
     */
    /**
     * conferirAntes = false: não espera a consulta "clipe ou música?" ao YouTube Music (~0,5–1 s cada, 3 por vez) —
     * usa o que já foi conferido ou a capa que veio com a faixa. Para listas (busca, fila): cada capinha esperava a
     * vez dessa consulta e a lista ficava sem capa por segundos. Quem chama confere depois e troca se for clipe.
     */
    suspend fun get(track: Track, pin: Boolean, large: Boolean = false, conferirAntes: Boolean = true, trace: (String) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        if (!validId(track.id)) return@withContext null
        val totalStarted = System.nanoTime()
        val lockStarted = System.nanoTime()
        lockFor(track.id).withLock {                   // uma capa por vez por música (sem arquivo pela metade)
            trace("id=${track.id} etapa=lock_ms=${elapsedMs(lockStarted)}")
            val diskStarted = System.nanoTime()
            val hint = track.thumbs.maxByOrNull { it.width * it.height }?.url
            val target = if (large && !pin) largeBrowseFile(track.id) else if (pin) pinnedFile(track.id) else browseFile(track.id)
            val entrada = if (!track.id.startsWith("yt:")) null else if (conferirAntes) conferir(track, trace) else entradaConferida(track.id)
            val oficial = entrada?.takeIf { it.startsWith("c ") }?.substring(2)
            val trocar = oficial != null && comConferidas { conferidas.getProperty("ok.${track.id}") } != oficial
            val cached = local(track.id, hint, large)
            trace("id=${track.id} etapa=disco_ms=${elapsedMs(diskStarted)} imagem=${cached != null} pixels=${cached?.let(::resolution) ?: 0} alvo=${minPixels(large)} trocar=$trocar")
            // capa que veio é de outra imagem (clipe): baixa a da versão em música e troca todos os arquivos da faixa
            if (trocar) {
                val staged = File(browseDir, name(track.id) + ".oficial")
                staged.delete()
                val ok = imageCandidates(oficial!!, minPixels(large)).any { download(it, staged, minPixels(large)) }
                if (ok) {
                    val trocados = listOf(pinnedFile(track.id), browseFile(track.id), largeBrowseFile(track.id)).filter { it.isFile && it.length() > 0 }
                    trocados.filter { it != target }.forEach { f -> if (f.parentFile == pinnedDir) staged.copyTo(f, overwrite = true) else f.delete() }
                    if (!staged.renameTo(target)) { staged.copyTo(target, overwrite = true); staged.delete() }
                    rememberAlias(oficial, target, large)
                    instalada(track.id, oficial)
                    (trocados + target).distinct().forEach { runCatching { aoTrocarCapa(it) } }
                    trace("id=${track.id} etapa=capa_oficial ok=true pixels=${resolution(target)}")
                    return@withLock target
                }
                staged.delete()
                trace("id=${track.id} etapa=capa_oficial ok=false")
            }
            if (cached != null) {
                // capa fraca (pequena ou quadro de vídeo) que já tentamos melhorar nesta abertura: não insiste a cada pedido
                if (!meetsMinimum(cached, large) && !tentou.add("${track.id}|$large")) return@withLock cached
                if (meetsMinimum(cached, large)) {
                    if (pin && cached != target && resolution(cached) > resolution(target)) {
                        val promoted = copyBetter(cached, target)
                        if (promoted != null) return@withLock promoted
                    }
                    if (!pin) touch(cached)
                    return@withLock cached
                }
            }
            // Listas pedem ao menos 226 px; player/álbum tentam 544 px ou mais antes de usar o cache menor.
            fun install(url: String, largo: Boolean = false): Boolean {
                val downloadStarted = System.nanoTime()
                if (!download(url, target, minPixels(large), largo)) {
                    trace("id=${track.id} etapa=download_ms=${elapsedMs(downloadStarted)} bytes=0 ok=false")
                    return false
                }
                rememberAlias(url, target, large)
                if (oficial == null) hint?.let { rememberAlias(it, target, large) }
                if (!pin) {
                    val trimStarted = System.nanoTime()
                    // Falha ao podar o cache não pode invalidar a capa já baixada.
                    runCatching { budget.trim() }
                        .onSuccess { trace("id=${track.id} etapa=lru_ms=${elapsedMs(trimStarted)} removidos_bytes=${it.removedBytes}") }
                        .onFailure { trace("id=${track.id} etapa=lru_falhou erro=${it}") }
                }
                trace("id=${track.id} etapa=download_ms=${elapsedMs(downloadStarted)} bytes=${target.length()} pixels=${resolution(target)} ok=true")
                return true
            }
            // 1º capas quadradas (a oficial, se houver, e as que vieram com a faixa); 2º quadro do vídeo, só se não
            // houver nada melhor — aceito com o menor lado de verdade (senão uma faixa sem capa quadrada ficaria sem nada)
            val todas = (listOfNotNull(oficial).flatMap { imageCandidates(it, minPixels(large)) } + candidates(track, browse = !large)).distinct()
            val (videos, quadradas) = todas.partition { runCatching { java.net.URI(it).host.orEmpty() }.getOrDefault("").endsWith("ytimg.com") }
            for (url in quadradas) if (install(url)) { oficial?.let { instalada(track.id, it) }; return@withLock target }
            if (cached == null || resolution(cached) == 0) for (url in videos) if (install(url, largo = true)) return@withLock target
            if (track.id.startsWith("album:") && track.title.isNotBlank()) {
                val alternatives = runCatching { DeezerAlbum(client).covers(track.title, track.artist) }.getOrDefault(emptyList())
                    .flatMap { imageCandidates(it, minPixels(large)) }
                trace("id=${track.id} etapa=deezer_album_urls=${alternatives.size}")
                for (url in alternatives) if (install(url)) return@withLock target
            }
            // Mantém a imagem antiga disponível se nenhuma fonte conseguiu uma resolução melhor.
            cached
        }.also { trace("id=${track.id} etapa=total_ms=${elapsedMs(totalStarted)} origem=${if (it == null) "vazio" else "disco_ou_rede"}") }
    }

    /**
     * Troca a capa salva de uma música (metadados corrigidos trouxeram capa melhor). Baixa primeiro;
     * só apaga a antiga se a nova chegou. Devolve os arquivos trocados (para esquecer as cores antigas).
     */
    suspend fun replace(track: Track): List<File> = withContext(Dispatchers.IO) {
        if (!validId(track.id)) return@withContext emptyList()
        lockFor(track.id).withLock {
            val staged = File(browseDir, name(track.id) + ".new")
            val downloaded = candidates(track).any { download(it, staged, LIST_MIN_PIXELS) }
            if (!downloaded) { staged.delete(); return@withLock emptyList() }
            val pinned = pinnedFile(track.id)
            val changed = listOf(pinned, browseFile(track.id), largeBrowseFile(track.id)).filter { it.length() > 0 }
            if (changed.maxOfOrNull(::resolution)?.let { it > resolution(staged) } == true) {
                staged.delete()
                return@withLock emptyList()
            }
            if (pinned.length() > 0) staged.copyTo(pinned, overwrite = true)
            browseFile(track.id).delete()
            val large = largeBrowseFile(track.id)
            large.delete()
            if (pinned.length() == 0L) staged.renameTo(large) else staged.delete()
            changed
        }
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private fun lockFor(id: String) = locks.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }

    /** Menor lado abaixo de 600 px (as capas do app são exibidas em até ~1000 px). */
    fun isSmall(f: File): Boolean = imageSize(f)?.let { minOf(it.first, it.second) < 600 } ?: false

    /** Move da navegação para a área fixada (ex.: a pessoa tocou ou curtiu a música). */
    fun promote(trackId: String): File? = promoteFile(browseFile(trackId), pinnedFile(trackId))

    private fun promoteFile(from: File, to: File): File? {
        if (to.length() > 0) return to
        if (from.length() == 0L) return null
        val moved = from.renameTo(to) || (runCatching { from.copyTo(to, overwrite = true) }.isSuccess && from.delete())
        if (!moved) return null
        updateAliasesForMove(from, to)
        return to
    }

    /** Substitui um arquivo de menor resolução atomicamente e restaura o anterior se falhar. */
    private fun copyBetter(from: File, to: File): File? {
        if (!from.isFile || from.length() == 0L) return null
        if (to.isFile && resolution(to) > resolution(from)) return to
        val staged = File(to.parentFile, to.name + ".upgrade")
        val backup = File(to.parentFile, to.name + ".previous")
        staged.delete(); backup.delete()
        if (!runCatching { from.copyTo(staged, overwrite = false) }.isSuccess) return null
        if (resolution(staged) < resolution(from)) { staged.delete(); return null }
        val hadTarget = to.isFile
        if (hadTarget && !to.renameTo(backup)) { staged.delete(); return null }
        if (!staged.renameTo(to)) {
            if (hadTarget) backup.renameTo(to)
            staged.delete()
            return null
        }
        backup.delete()
        return to
    }

    /** Limpar cache (configurações): só a área de navegação. As fixadas ficam. */
    fun clearBrowse(): Long {
        var freed = 0L
        browseDir.listFiles()?.forEach { freed += it.length(); it.delete() }
        return freed
    }

    private fun download(url: String, target: File, minDimension: Int, largo: Boolean = false): Boolean {
        if (!allowedUrl(url)) return false
        val tmp = File(target.parentFile, target.name + "." + Thread.currentThread().id + ".tmp")
        return runCatching {
            val fetched = client.newCall(Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT).build()).execute().use { r ->
                val type = r.header("Content-Type").orEmpty()
                if (!r.isSuccessful || !type.startsWith("image/") || !allowedUrl(r.request.url.toString())) return@use false
                val body = r.body ?: return@use false
                if (body.contentLength() > MAX_BYTES) return@use false
                val bytes = body.source().let { src ->
                    src.request(MAX_BYTES + 1)
                    if (src.buffer.size > MAX_BYTES) return@use false
                    src.readByteArray()
                }
                // Não guarda respostas vazias, placeholders nem uma miniatura abaixo do tamanho solicitado.
                if (bytes.size < 1024) return@use false
                tmp.writeBytes(bytes)
                true
            }
            if (!fetched) return@runCatching false
            val candidatePixels = resolution(tmp)
            val ladoMenor = if (largo) imageSize(tmp)?.let { minOf(it.first, it.second) } ?: 0 else candidatePixels
            if (ladoMenor < minDimension) return@runCatching false
            val existingPixels = if (target.isFile) resolution(target) else 0
            if (candidatePixels < existingPixels || candidatePixels == existingPixels && tmp.length() < target.length()) return@runCatching false
            tmp.renameTo(target) || run { tmp.delete(); false }
        }.getOrElse { false }.also { if (!it) tmp.delete() }
    }

    companion object {
        private const val MAX_BYTES = 6L * 1024 * 1024
        private const val LIST_MIN_PIXELS = 226
        private const val MINI_MIN_PIXELS = 120
        private const val LARGE_MIN_PIXELS = 544
        private const val ARTIST_MIN_PIXELS = 226

        /** Menor lado; se a imagem não for quadrada (diferença > 3 %), vale 1/4 — ver [resolution]. */
        fun qualidade(w: Int, h: Int): Int {
            val menor = minOf(w, h); val maior = maxOf(w, h)
            if (menor <= 0) return 0
            return if (maior - menor <= maior * 0.03) menor else menor / 4
        }
        /** Só ids de música/artista do app (sem "../" nem caminhos). */
        fun validId(id: String) = id.length in 3..96 && Regex("""[A-Za-z0-9:_\-.]+""").matches(id) && ".." !in id
        /** Só baixa capa dos servidores de imagem do YouTube/Google (e do Deezer, para foto de artista), por HTTPS. */
        fun allowedUrl(u: String): Boolean {
            val url = runCatching { java.net.URI(u) }.getOrNull() ?: return false
            val h = url.host?.lowercase() ?: return false
            return url.scheme == "https" && (h == "i.ytimg.com" || h.endsWith(".ytimg.com") || h.endsWith(".googleusercontent.com") || h.endsWith(".ggpht.com") || h.endsWith(".dzcdn.net"))
        }

        /** Mesma imagem com URLs de tamanhos diferentes compartilha uma chave de disco. */
        fun cacheUrlKey(raw: String): String? {
            if (!allowedUrl(raw)) return null
            val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (host == "i.ytimg.com") {
                Regex("""/vi/([^/]+)/""").find(uri.path.orEmpty())?.groupValues?.getOrNull(1)?.let { return "yt:$it" }
            }
            val path = uri.rawPath.orEmpty().replace(Regex("(=[^/?&#=]*)+$"), "")
            return "img:${host.lowercase(Locale.ROOT)}$path"
        }

        /** Chave estável para foto e índice, igual à normalização usada pela interface. */
        fun artistKey(name: String): String {
            val folded = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
                .replace("ø", "o").replace("æ", "ae").replace("œ", "oe").replace("ß", "ss")
                .replace("đ", "d").replace("ł", "l").replace("þ", "th")
            val ascii = folded.replace(Regex("[^a-z0-9]"), "")
            if (ascii.isNotEmpty()) return ascii
            // nome sem letras latinas (아이유, 宇多田ヒカル): código das letras, senão todos viram o mesmo artista
            val hex = folded.codePoints().filter(Character::isLetterOrDigit).toArray()
                .joinToString("") { Integer.toHexString(it) }.take(80)
            return if (hex.isEmpty()) "" else "u$hex"
        }

        /**
         * Para COMPARAR nomes de artista (não para guardar foto): "Henrique e Juliano", "Henrique & Juliano" e
         * "Henrique and Juliano" são o mesmo artista. Sem isto o canal "Henrique & Juliano" não era achado ao abrir
         * "Henrique e Juliano" e a página ficava vazia (vale para toda dupla/banda com "e", "&", "and", "y", "+").
         */
        fun mesmoNome(a: String, b: String): Boolean {
            val ka = artistKey(a); val kb = artistKey(b)
            return ka == kb || (ka.isNotEmpty() && semConectivos(a) == semConectivos(b))
        }
        fun semConectivos(nome: String): String = artistKey(nome.replace(Regex("""(?i)\s+(?:e|&|and|y|\+)\s+|\s*[&+]\s*"""), " "))

        /** Foto genérica (boneco cinza) que o YouTube Music usa para artista sem foto: não é foto. */
        private val PLACEHOLDER_PHOTOS = listOf("25G_BXEqKPiyGZRRRGdIHaGUsVpAxwB7K0IIMkRpURNnPZ_So48IK5xLKCoF6gZ6tGRt2zRW4D61kBQ")
        fun isPlaceholderPhoto(url: String?): Boolean = url != null && PLACEHOLDER_PHOTOS.any { it in url }

        /** Pede avatares de canais no tamanho que a tela inicial e o perfil realmente exibem. */
        fun artistImageUrl(raw: String): String {
            if (!allowedUrl(raw)) return raw
            val host = runCatching { java.net.URI(raw).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
            if (!(host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com"))) return raw
            val queryAt = raw.indexOf('?').takeIf { it >= 0 } ?: raw.length
            val path = raw.substring(0, queryAt)
            val query = raw.substring(queryAt)
            // tira TODOS os sufixos de tamanho do fim (havia URLs salvas com "=s400-c=s400-c", que não baixam)
            val base = path.replace(Regex("(=[^/?&#=]*)+$"), "")
            return "$base=s400-c$query"
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

        private val GOOGLE_SUFFIXES = Regex("""(=[^/?&#=]*)+$""")

        /** URLs do mesmo retrato/capa em tamanhos sucessivamente maiores, preservando a origem. */
        private fun imageCandidates(raw: String, minDimension: Int): List<String> {
            if (!allowedUrl(raw)) return emptyList()
            val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return listOf(raw)
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            val origin = "https://$host" + if (uri.port > 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty()
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            // grande: 1000 px — o tamanho que a capa do player ocupa na tela do A54 (351 × 2,81); 1200 só pesava mais (~30%)
            val sizes = if (minDimension >= LARGE_MIN_PIXELS) listOf(1000, 800, 640, LARGE_MIN_PIXELS)
                else if (minDimension <= MINI_MIN_PIXELS) listOf(160, 144, MINI_MIN_PIXELS)
                else listOf(400, 300, 256, LIST_MIN_PIXELS)
            val resized = when {
                host.endsWith(".googleusercontent.com") -> {
                    val base = path.replace(GOOGLE_SUFFIXES, "")
                    sizes.map { "$origin$base=w$it-h$it-l90-rj$query" }
                }
                host.endsWith(".ggpht.com") -> {
                    val base = path.replace(GOOGLE_SUFFIXES, "")
                    sizes.map { "$origin$base=s$it-c$query" }
                }
                host.endsWith(".dzcdn.net") -> {
                    sizes.map { size ->
                        val replacement = if (minDimension >= LARGE_MIN_PIXELS) size else when (size) {
                            400 -> 500; 300 -> 400; 256 -> 250; else -> size
                        }
                        origin + path.replace(Regex("\\d+x\\d+"), "${replacement}x$replacement") + query
                    }
                }
                else -> emptyList()
            }
            return (resized + if (resized.isEmpty()) listOf(raw) else emptyList()).distinct()
        }

        /**
         * Ordem de tentativa, da melhor para a pior:
         *  1. capa quadrada do YouTube Music (lh3.googleusercontent) pedida em 1000×1000
         *  2. as capas informadas, da maior para a menor
         *  3. URLs montadas pelo ID do vídeo (nunca expiram): maxresdefault → hqdefault
         */
        fun candidates(track: Track, browse: Boolean = false): List<String> {
            val square = { t: Thumb -> t.width > 0 && kotlin.math.abs(t.width - t.height) <= 2 }
            val sorted = track.thumbs.sortedWith(compareByDescending<Thumb> { square(it) }.thenByDescending { it.width * it.height })
            val minDimension = if (browse) LIST_MIN_PIXELS else LARGE_MIN_PIXELS
            val thumbs = sorted.flatMap { imageCandidates(it.url, minDimension) }
            val video = track.videoId?.let { id ->
                (if (browse) listOf("hqdefault.jpg") else listOf("maxresdefault.jpg", "hq720.jpg", "sddefault.jpg", "hqdefault.jpg"))
                    .map { "https://i.ytimg.com/vi/$id/$it" }
            }.orEmpty()
            // Não pede mqdefault: o menor lado é 180 px e ficava borrado nas listas.
            return (thumbs + video).distinct()
        }
    }
}
