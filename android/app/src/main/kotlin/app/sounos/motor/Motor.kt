package app.sounos.motor

import app.sounos.extractor.CoverStore
import app.sounos.extractor.NewPipeSource
import app.sounos.extractor.Release
import app.sounos.extractor.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Calendar

/**
 * Motor de entendimento — 0.1. Aprende o uso de quem ouve, tudo no próprio celular (nada sai do aparelho).
 *
 * Por enquanto ele só OBSERVA e guarda (nada no app muda por causa disto, exceto o peso das Novidades):
 * cada música ouvida vira uma "escuta" com quanto tocou e como terminou —
 *   completa (chegou ao fim) · pulou (avançou) · voltou (voltou para a anterior) · trocou (escolheu outra) · saiu (fechou o app).
 * Com isso ele soma, por música, por artista e por gênero: quantas vezes tocou, chegou ao fim, foi pulada e o tempo
 * ouvido; e a duração das músicas ouvidas até o fim (média e faixas de duração).
 * O gênero vem do Deezer (gênero do álbum), consultado uma vez por música e guardado.
 *
 * Arquivos em files/motor: escutas.jsonl (cada escuta, uma linha) e perfil.json (as somas).
 */
class Motor(private val dir: File, private val io: CoroutineScope, private val http: OkHttpClient) {
    private val lock = Mutex()
    private val perfilFile = File(dir, "perfil.json")
    private val escutasFile = File(dir, "escutas.jsonl")
    private var perfil: JSONObject? = null

    /** Música que está tocando agora (a escuta em andamento). Fica também em atual.json: se o app for fechado no
     *  meio dela, na próxima abertura vira "saiu" no ponto em que parou (a menos que a pessoa volte para ela). */
    private var atual: Pair<Track, JSONObject>? = null
    private var ultimaPos = 0L
    private var ultimaDur = 0L
    private val atualFile = File(dir, "atual.json")

    fun comecou(t: Track, contexto: JSONObject) {
        if (atual?.first?.id == t.id) return                     // mesma música (retomada depois de pausar/reabrir)
        atual = t to contexto; ultimaPos = 0; ultimaDur = t.durationSec * 1000
        guardarAtual()
    }

    /** Até onde a música atual tocou (chamado quando o player salva a sessão: pausa, a cada tanto, ao sair). */
    fun marcar(posMs: Long, durMs: Long) {
        if (atual == null) return
        ultimaPos = posMs; if (durMs > 0) ultimaDur = durMs
        guardarAtual()
    }

    private fun guardarAtual() = io.launch {
        lock.withLock {
            val (t, ctx) = atual ?: run { atualFile.delete(); return@withLock }
            runCatching {
                dir.mkdirs()
                atualFile.writeText(JSONObject().put("track", app.sounos.data.Json.track(t)).put("contexto", ctx)
                    .put("pos", ultimaPos).put("dur", ultimaDur).toString())
            }
        }
    }

    /** Ao abrir o app: a música restaurada é a mesma da escuta guardada? continua; senão a guardada termina como "saiu". */
    fun retomar(restaurada: Track?) {
        val salvo = runCatching { JSONObject(atualFile.readText()) }.getOrNull() ?: return
        val t = runCatching { app.sounos.data.Json.track(salvo.getJSONObject("track")) }.getOrNull() ?: return
        atual = t to (salvo.optJSONObject("contexto") ?: JSONObject())
        ultimaPos = salvo.optLong("pos"); ultimaDur = salvo.optLong("dur")
        if (restaurada?.id != t.id) terminou(ultimaPos, ultimaDur, "saiu")
    }

    /** Uma música nova vai começar: fecha a anterior (se era outra) com o motivo da troca. */
    fun trocando(novoId: String, posMs: Long, durMs: Long, fim: String) {
        if (atual == null || atual?.first?.id == novoId) return
        // posição 0 = a música não chegou a carregar nesta abertura (estava pausada): vale a última posição guardada
        terminou(if (posMs > 0) posMs else ultimaPos, durMs, fim)
    }

    /** Fecha a escuta atual: posMs = até onde tocou, durMs = duração, fim = como terminou. */
    fun terminou(posMs: Long, durMs: Long, fim: String) {
        val (t, ctx) = atual ?: return
        atual = null
        guardarAtual()
        if (fim == "erro") return
        val dur = if (durMs > 0) durMs else t.durationSec * 1000
        val ouviu = if (fim == "completa") dur else posMs.coerceIn(0, dur.takeIf { it > 0 } ?: posMs)
        val e = JSONObject().put("at", System.currentTimeMillis()).put("id", t.id).put("titulo", t.title).put("artista", t.artist)
            .put("artistas", JSONArray(t.artists)).put("durMs", dur).put("ouviuMs", ouviu).put("fim", fim)
            .put("contexto", ctx.optString("type"))
        io.launch {
            val generos = runCatching { generos(t) }.getOrDefault(emptyList())
            e.put("generos", JSONArray(generos))
            lock.withLock {
                runCatching { anotar(e) }.onFailure { android.util.Log.w("Sonus", "motor: falha ao guardar escuta", it) }
            }
        }
    }

    private fun carregar(): JSONObject = perfil ?: runCatching { JSONObject(perfilFile.readText()) }.getOrNull()
        ?: JSONObject().put("versao", 1).put("desde", System.currentTimeMillis())
            .put("escutas", 0).put("musicas", JSONObject()).put("artistas", JSONObject()).put("generos", JSONObject())
            .put("duracao", JSONObject()).put("generoDe", JSONObject())
        .also { perfil = it }

    private fun soma(o: JSONObject, chave: String, nome: String, e: JSONObject) {
        val x = o.optJSONObject(chave) ?: JSONObject().put("nome", nome).also { o.put(chave, it) }
        fun inc(k: String, v: Long = 1) = x.put(k, x.optLong(k) + v)
        inc("tocou"); inc("ouviuMs", e.getLong("ouviuMs"))
        when (e.getString("fim")) {
            "completa" -> inc("completas"); "pulou" -> inc("puladas"); "voltou" -> inc("voltou"); "trocou" -> inc("trocou"); "saiu" -> inc("saiu")
        }
        x.put("ultima", e.getLong("at"))
    }

    private fun anotar(e: JSONObject) {
        dir.mkdirs()
        // 1) a escuta, crua (o arquivo gira em ~2 MB)
        if (escutasFile.length() > 2_000_000) escutasFile.renameTo(File(dir, "escutas.1.jsonl"))
        escutasFile.appendText(e.toString() + "\n")
        // 2) as somas
        val p = carregar()
        p.put("escutas", p.optLong("escutas") + 1)
        val musicas = p.getJSONObject("musicas")
        soma(musicas, e.getString("id"), e.getString("titulo"), e)
        musicas.getJSONObject(e.getString("id")).put("artista", e.getString("artista")).put("durMs", e.getLong("durMs"))
        val arts = e.getJSONArray("artistas")
        for (i in 0 until arts.length()) arts.optString(i).takeIf { it.isNotBlank() }?.let { soma(p.getJSONObject("artistas"), CoverStore.artistKey(it), it, e) }
        val gens = e.optJSONArray("generos") ?: JSONArray()
        for (i in 0 until gens.length()) gens.optString(i).takeIf { it.isNotBlank() }?.let { soma(p.getJSONObject("generos"), it.lowercase(), it, e) }
        // duração das músicas ouvidas até o fim: soma, quantidade e faixas de minutos (0–2, 2–3, 3–4, 4–5, 5–7, 7+)
        if (e.getString("fim") == "completa" && e.getLong("durMs") > 0) {
            val d = p.getJSONObject("duracao"); val ms = e.getLong("durMs")
            d.put("completas", d.optLong("completas") + 1).put("somaMs", d.optLong("somaMs") + ms)
            val min = ms / 60000.0
            val faixa = when { min < 2 -> "0-2"; min < 3 -> "2-3"; min < 4 -> "3-4"; min < 5 -> "4-5"; min < 7 -> "5-7"; else -> "7+" }
            val f = d.optJSONObject("faixas") ?: JSONObject().also { d.put("faixas", it) }
            f.put(faixa, f.optLong(faixa) + 1)
            d.put("mediaMs", d.getLong("somaMs") / d.getLong("completas"))
        }
        // não deixa a lista de músicas crescer sem fim: guarda as 3000 mais recentes
        if (musicas.length() > 3000) {
            val velhas = musicas.keys().asSequence().toList().sortedBy { musicas.getJSONObject(it).optLong("ultima") }.take(musicas.length() - 3000)
            velhas.forEach(musicas::remove)
        }
        File(dir, "perfil.json.tmp").apply { writeText(p.toString()) }.renameTo(perfilFile)
    }

    // ---------- gênero (Deezer: busca a música → álbum → gêneros do álbum), uma vez por música ----------
    private suspend fun generos(t: Track): List<String> {
        lock.withLock {
            val p = carregar()
            p.getJSONObject("generoDe").optJSONArray(t.id)?.takeIf { it.length() > 0 }?.let { a -> return (0 until a.length()).map(a::getString) }
            // não achou da última vez: tenta de novo depois de 7 dias (o Deezer ganha músicas novas)
            val tentou = p.optJSONObject("semGenero")?.optLong(t.id) ?: 0
            if (System.currentTimeMillis() - tentou < 7L * 24 * 3600 * 1000) return emptyList()
        }
        // busca solta ("artista título"); só vale resultado do mesmo artista; álbum sem gênero → tenta o próximo
        val titulo = t.title.substringBefore(" (").substringBefore(" - ").substringBefore(" / ").trim()
        val q = URLEncoder.encode("${t.artist} $titulo", "UTF-8")
        val chave = CoverStore.artistKey(t.artist)
        val albuns = get("https://api.deezer.com/search/track?q=$q&limit=5")?.optJSONArray("data")?.let { d ->
            (0 until d.length()).mapNotNull { d.optJSONObject(it) }
                .filter { CoverStore.artistKey(it.optJSONObject("artist")?.optString("name").orEmpty()) == chave }
                .mapNotNull { it.optJSONObject("album")?.optLong("id")?.takeIf { id -> id > 0 } }.distinct()
        }.orEmpty()
        var lista = emptyList<String>()
        for (id in albuns.take(3)) {
            lista = get("https://api.deezer.com/album/$id")?.optJSONObject("genres")?.optJSONArray("data")
                ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) } }.orEmpty()
            if (lista.isNotEmpty()) break
        }
        lock.withLock {
            val p = carregar()
            if (lista.isNotEmpty()) { p.getJSONObject("generoDe").put(t.id, JSONArray(lista)); p.optJSONObject("semGenero")?.remove(t.id) }
            else (p.optJSONObject("semGenero") ?: JSONObject().also { p.put("semGenero", it) }).put(t.id, System.currentTimeMillis())
        }
        return lista
    }

    private fun get(url: String): JSONObject? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
    }.getOrNull()

    // ---------- o que o motor sabe do gosto (usado pelas Novidades e pela busca) ----------
    /** Pontos de gosto de uma soma: tempo ouvido e músicas até o fim somam; pular tira. */
    private fun pontos(o: JSONObject) = (o.optLong("ouviuMs") / 60000.0 + o.optLong("completas") * 2 - o.optLong("puladas") * 1.5).coerceAtLeast(0.0)

    /**
     * Afinidade com cada artista (chave → 0..1, 1 = o mais ouvido). Junta o que o motor aprendeu com o histórico de
     * reprodução que já existia (vezes que cada música tocou), para valer desde o primeiro dia.
     */
    suspend fun afinidadeArtistas(historico: JSONArray = JSONArray()): Map<String, Pair<String, Double>> = lock.withLock {
        val soma = HashMap<String, Pair<String, Double>>()
        fun add(nome: String, v: Double) { val k = CoverStore.artistKey(nome); if (k.isBlank() || v <= 0) return
            soma[k] = (soma[k]?.first ?: nome) to ((soma[k]?.second ?: 0.0) + v) }
        val a = carregar().getJSONObject("artistas")
        a.keys().forEach { k -> a.getJSONObject(k).let { add(it.optString("nome"), pontos(it)) } }
        for (i in 0 until historico.length()) historico.optJSONObject(i)?.let { e ->
            e.optJSONObject("track")?.optString("artist")?.takeIf(String::isNotBlank)?.let { add(it, e.optInt("n", 1) * 0.5) }
        }
        val max = soma.values.maxOfOrNull { it.second } ?: return@withLock emptyMap()
        soma.mapValues { (_, v) -> v.first to v.second / max }
    }

    /** Gosto por gênero (gênero em minúsculas → 0..1). Vazio enquanto o motor não conhece nenhum gênero. */
    suspend fun gostoGeneros(): Map<String, Double> = lock.withLock {
        val g = carregar().getJSONObject("generos")
        val m = g.keys().asSequence().associateWith { pontos(g.getJSONObject(it)) }.filterValues { it > 0 }
        val max = m.values.maxOrNull() ?: return@withLock emptyMap()
        m.mapValues { it.value / max }
    }

    /** Artistas de que a pessoa mais gosta pelo uso (nomes, do mais ao menos). */
    suspend fun artistasPreferidos(n: Int, historico: JSONArray = JSONArray()): List<String> =
        afinidadeArtistas(historico).values.sortedByDescending { it.second }.map { it.first }.take(n)

    /** Gêneros de um lançamento (Deezer: busca o álbum do mesmo artista → gêneros), guardados por lançamento. */
    private val deezerVez = kotlinx.coroutines.sync.Semaphore(4)       // poucas consultas ao mesmo tempo (limite do Deezer)
    private suspend fun generosDoLancamento(r: Release): List<String> {
        val id = r.browseId ?: return emptyList()
        lock.withLock { carregar().optJSONObject("generoLanc")?.optJSONArray(id)?.let { a -> return (0 until a.length()).map(a::getString) } }
        val lista = deezerVez.withPermit {
            val q = URLEncoder.encode("${r.artist} ${r.title}", "UTF-8")
            val chave = CoverStore.artistKey(r.artist)
            val album = get("https://api.deezer.com/search/album?q=$q&limit=5")?.optJSONArray("data")?.let { d ->
                (0 until d.length()).mapNotNull { d.optJSONObject(it) }
                    .firstOrNull { chave.isBlank() || CoverStore.artistKey(it.optJSONObject("artist")?.optString("name").orEmpty()) == chave }
            }?.optLong("id")?.takeIf { it > 0 }
            album?.let { get("https://api.deezer.com/album/$it")?.optJSONObject("genres")?.optJSONArray("data") }
                ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) } }.orEmpty()
        }
        lock.withLock { val p = carregar(); (p.optJSONObject("generoLanc") ?: JSONObject().also { p.put("generoLanc", it) }).put(id, JSONArray(lista)) }
        return lista
    }

    // ---------- Novidades ----------
    /**
     * Lista de novidades: lançamentos recentes. Para quem acabou de chegar, quase tudo vem dos lançamentos do país
     * (genérico). Quanto mais artistas a pessoa segue e ouve, mais a lista puxa para eles. (Gêneros entram no peso
     * quando o motor for além da 0.1.)
     * Devolve os lançamentos (para os cartões) e as músicas deles, embaralhadas (para tocar).
     */
    suspend fun novidades(source: NewPipeSource, seguidos: List<Pair<String, String?>>, historico: JSONArray = JSONArray()): JSONObject = coroutineScope {
        val ano = Calendar.getInstance().get(Calendar.YEAR)
        val afinidade = afinidadeArtistas(historico)
        val gosto = gostoGeneros()
        val preferidos = artistasPreferidos(6, historico).filter { p -> seguidos.none { CoverStore.artistKey(it.first) == CoverStore.artistKey(p) } }.take(4)
        val pessoais = (seguidos.take(10) + preferidos.map { it to null }).map { (nome, url) ->
            async(Dispatchers.IO) {
                runCatching { source.artistReleases(nome, url) }.getOrDefault(emptyList())
                    .filter { (it.year ?: 0) >= ano - 1 && it.browseId != null }.take(3).map { it.copy(artist = nome) }
            }
        }
        val genericos = async(Dispatchers.IO) { runCatching { source.newReleases() }.getOrDefault(emptyList()) }
        val meus = pessoais.awaitAll().flatten().distinctBy { it.browseId }.shuffled()
        val peso = (0.15 + 0.07 * (seguidos.size + preferidos.size)).coerceAtMost(0.85)
        val total = 20
        val daPessoa = meus.take((total * peso).toInt().coerceAtLeast(minOf(meus.size, 3)))
        // lançamentos do país: os que combinam com o gosto (artistas que a pessoa já ouve, gêneros que ela mais ouve)
        // ganham a maior parte das vagas; o resto é sorteado, para conhecer coisa nova
        val vagas = (total - daPessoa.size).coerceAtLeast(0)
        val candidatos = genericos.await().filter { g -> daPessoa.none { it.browseId == g.browseId } }.shuffled().take(24)
        val generosLanc = if (gosto.isEmpty()) emptyMap()
            else candidatos.map { r -> async(Dispatchers.IO) { r.browseId to runCatching { generosDoLancamento(r) }.getOrDefault(emptyList()) } }.awaitAll().toMap()
        fun nota(r: Release) = (afinidade[CoverStore.artistKey(r.artist)]?.second ?: 0.0) * 2 +
            (generosLanc[r.browseId]?.sumOf { gosto[it.lowercase()] ?: 0.0 } ?: 0.0)
        val porGosto = candidatos.sortedByDescending(::nota)
        val doGosto = (vagas * 0.7).toInt()
        val genericosEscolhidos = porGosto.take(doGosto) + porGosto.drop(doGosto).shuffled().take(vagas - doGosto)
        val escolhidos = (daPessoa + genericosEscolhidos).take(total).shuffled()
        // músicas: o single inteiro; de álbum/EP, as 2 primeiras
        val musicas = escolhidos.map { r ->
            async(Dispatchers.IO) {
                runCatching { source.releaseTracks(r.browseId!!, r.artist) }.getOrDefault(emptyList()).take(if (r.kind == "Single") 3 else 2)
            }
        }.awaitAll().flatten().distinctBy { it.id }.shuffled()
        JSONObject()
            .put("lancamentos", JSONArray().apply {
                escolhidos.forEach { r ->
                    put(JSONObject().put("title", r.title).put("kind", r.kind).put("year", r.year ?: JSONObject.NULL)
                        .put("url", r.url ?: JSONObject.NULL).put("artist", r.artist)
                        .put("thumb", r.thumbs.maxByOrNull { it.width * it.height }?.url ?: JSONObject.NULL))
                }
            })
            .put("musicas", app.sounos.data.Json.tracks(musicas))
            .put("peso", peso)
    }

    // ---------- Seu Mix ----------
    /**
     * Playlist "Seu Mix" (pedido do dono, 27/10/2026): a pessoa ouve o que gosta, mesmo sem nunca ter ouvido a música.
     * Mistura, ~23 faixas:
     *  - 9 das MAIS OUVIDAS (sorteio com peso pelas vezes que tocou e pelo gosto do artista, para variar a cada mix);
     *  - até 5 NOVIDADES bem próximas: lançamentos (este ano e o anterior) dos artistas que a pessoa MAIS ouve, só
     *    músicas que ela ainda não ouviu;
     *  - até 7 INÉDITAS de artistas que ela já ouve (rádio do YouTube Music das músicas preferidas: mesmo estilo);
     *  - 2 DESCOBERTAS: artista que ela não conhece, do mesmo rádio (mesmo gênero/estilo).
     * Embaralhado sem repetir artista em seguida, as conhecidas espalhadas entre as novas.
     * Devolve as músicas e os artistas principais (para a capa).
     */
    suspend fun mix(source: NewPipeSource, historico: JSONArray): JSONObject = coroutineScope {
        val ano = Calendar.getInstance().get(Calendar.YEAR)
        val afinidade = afinidadeArtistas(historico)
        fun gosto(t: Track) = t.artists.ifEmpty { listOf(t.artist) }.maxOfOrNull { afinidade[CoverStore.artistKey(it)]?.second ?: 0.0 } ?: 0.0
        // histórico: faixa + vezes que tocou (só músicas de verdade: duração razoável, sem karaokê/cover)
        val ouvidas = (0 until historico.length()).mapNotNull { i ->
            historico.optJSONObject(i)?.let { e -> runCatching { app.sounos.data.Json.track(e.getJSONObject("track")) }.getOrNull()?.let { it to e.optInt("n", 1) } }
        }.filterNot { (t, _) -> app.sounos.extractor.MusicPicks.isJunk(t) }
        val jaOuviu = ouvidas.mapTo(HashSet()) { it.first.id }
        val chavesOuvidas = ouvidas.mapTo(HashSet()) { app.sounos.extractor.MusicPicks.songKey(it.first) }
        fun nova(t: Track) = t.id !in jaOuviu && app.sounos.extractor.MusicPicks.songKey(t) !in chavesOuvidas && !app.sounos.extractor.MusicPicks.isJunk(t)

        // 1) mais ouvidas: as 40 do topo, sorteio com peso
        // (a mesma música com dois ids — clipe e áudio — entra uma vez só)
        val topo = ouvidas.sortedByDescending { (t, n) -> n + gosto(t) * 3 }.distinctBy { app.sounos.extractor.MusicPicks.songKey(it.first) }.take(40)
        val favoritas = sorteioComPeso(topo.map { (t, n) -> t to (n + gosto(t) * 3 + 1.0) }, 9)

        // sementes do rádio: 3 das favoritas, de artistas diferentes
        val sementes = favoritas.distinctBy { CoverStore.artistKey(it.artist) }.take(3)
        val preferidos = afinidade.values.sortedByDescending { it.second }.take(4).map { it.first }
        val radioJobs = sementes.map { s -> async(Dispatchers.IO) { runCatching { source.radio(s.videoId ?: return@runCatching emptyList()) }.getOrDefault(emptyList()) } }
        val lancJobs = preferidos.map { nome ->
            async(Dispatchers.IO) {
                runCatching { source.artistReleases(nome, null) }.getOrDefault(emptyList())
                    .filter { (it.year ?: 0) >= ano - 1 && it.browseId != null }.take(2)
                    .flatMap { r -> runCatching { source.releaseTracks(r.browseId!!, nome) }.getOrDefault(emptyList()).take(2) }
            }
        }
        val radio = radioJobs.awaitAll().flatten().filter(::nova).distinctBy { app.sounos.extractor.MusicPicks.songKey(it) }
        // 2) novidades bem próximas (só de quem a pessoa mais ouve)
        val novidades = lancJobs.awaitAll().flatten().filter(::nova).distinctBy { app.sounos.extractor.MusicPicks.songKey(it) }.shuffled().take(5)
        val usadas = (favoritas + novidades).mapTo(HashSet()) { app.sounos.extractor.MusicPicks.songKey(it) }
        // 3) inéditas de artistas que ela já ouve · 4) descobertas (artista desconhecido, mesmo estilo)
        val doRadio = radio.filter { app.sounos.extractor.MusicPicks.songKey(it) !in usadas }
        val ineditas = doRadio.filter { gosto(it) > 0 }.shuffled().take(7)
        val descobertas = doRadio.filter { gosto(it) == 0.0 }.take(6).shuffled().take(2)

        val musicas = misturar(favoritas, novidades + ineditas, descobertas)
        val artistas = musicas.groupingBy { it.artist }.eachCount().entries
            .sortedByDescending { (nome, n) -> n + (afinidade[CoverStore.artistKey(nome)]?.second ?: 0.0) * 4 }
            .map { it.key }.filter(String::isNotBlank).take(3)
        JSONObject().put("musicas", app.sounos.data.Json.tracks(musicas)).put("artistas", JSONArray(artistas))
            .put("partes", JSONObject().put("favoritas", favoritas.size).put("novidades", novidades.size)
                .put("ineditas", ineditas.size).put("descobertas", descobertas.size))
    }

    private fun <T> sorteioComPeso(itens: List<Pair<T, Double>>, n: Int): List<T> {
        val resto = itens.toMutableList(); val out = mutableListOf<T>()
        while (out.size < n && resto.isNotEmpty()) {
            var r = Math.random() * resto.sumOf { it.second }
            val i = resto.indexOfFirst { r -= it.second; r <= 0 }.takeIf { it >= 0 } ?: resto.lastIndex
            out += resto.removeAt(i).first
        }
        return out
    }

    /** Conhecidas espalhadas entre as novas (começa por uma conhecida), descobertas no meio; nunca o mesmo artista em seguida. */
    private fun misturar(conhecidas: List<Track>, novas: List<Track>, descobertas: List<Track>): List<Track> {
        val a = ArrayDeque(conhecidas.shuffled()); val b = ArrayDeque(novas.shuffled())
        val base = mutableListOf<Track>()
        while (a.isNotEmpty() || b.isNotEmpty()) {
            a.removeFirstOrNull()?.let(base::add)
            b.removeFirstOrNull()?.let(base::add)
            if (b.size > a.size + 2) b.removeFirstOrNull()?.let(base::add)
        }
        descobertas.forEachIndexed { i, t -> base.add(minOf(base.size, (base.size * (i + 1)) / (descobertas.size + 1)), t) }
        // separa artistas repetidos em sequência (troca com o próximo diferente)
        for (i in 1 until base.size) if (CoverStore.artistKey(base[i].artist) == CoverStore.artistKey(base[i - 1].artist)) {
            val j = (i + 1 until base.size).firstOrNull { CoverStore.artistKey(base[it].artist) != CoverStore.artistKey(base[i - 1].artist) } ?: continue
            val x = base[i]; base[i] = base[j]; base[j] = x
        }
        return base
    }

}
