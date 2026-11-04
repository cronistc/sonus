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
    /** O que aconteceu durante a escuta atual (pausas, barra arrastada, letra aberta…), junto da escuta no diário. */
    private var extras = JSONObject()

    /** Tudo o que a pessoa faz, linha a linha, só no celular (ver Diario). */
    val diario = Diario(File(dir, "diario"), io)

    /** Uma ação da pessoa (vinda da ponte ou da tela): vai para o diário e conta na escuta em andamento. */
    fun acao(tipo: String, dados: JSONObject = JSONObject()) {
        atual?.first?.let { t -> if (!dados.has("musica")) dados.put("tocando", t.id) }        // o que tocava naquela hora
        diario.anotar(tipo, dados)
        val campo = when (tipo) { "pausar" -> "pausas"; "posicao" -> "arrastou"; "abriuLetra" -> "letra"; "compartilhou" -> "compartilhou"
            "adicionouNaPlaylist" -> "playlist"; "baixar" -> "baixou"; else -> null } ?: return
        if (atual != null && (campo != "playlist" && campo != "baixou" || dados.optString("musica") == atual?.first?.id)) extras.put(campo, extras.optInt(campo) + 1)
    }

    /** Estado do player no começo da escuta (aleatório, repetir, saída de som, rede, se estava baixada). */
    fun estadoDaEscuta(dados: JSONObject) { dados.keys().forEach { extras.put(it, dados.get(it)) } }

    // ---------- sessões (app aberto → fechado) ----------
    private var abertoEm = 0L
    fun abriuApp() {
        if (abertoEm > 0) return
        abertoEm = System.currentTimeMillis(); diario.anotar("abriuApp")
    }
    fun fechouApp() {
        if (abertoEm == 0L) return
        val ms = System.currentTimeMillis() - abertoEm; abertoEm = 0
        diario.anotar("fechouApp", JSONObject().put("durMs", ms))
        io.launch { lock.withLock { runCatching {
            val p = carregar(); val se = p.optJSONObject("sessoes") ?: JSONObject().also { p.put("sessoes", it) }
            se.put("quantas", se.optLong("quantas") + 1).put("somaMs", se.optLong("somaMs") + ms)
            gravarPerfil(p)
        } } }
    }

    fun comecou(t: Track, contexto: JSONObject) {
        if (atual?.first?.id == t.id) return                     // mesma música (retomada depois de pausar/reabrir)
        atual = t to contexto; ultimaPos = 0; ultimaDur = t.durationSec * 1000
        extras = JSONObject().put("comecouEm", System.currentTimeMillis())
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
                    .put("pos", ultimaPos).put("dur", ultimaDur).put("extras", extras).toString())
            }
        }
    }

    /** Ao abrir o app: a música restaurada é a mesma da escuta guardada? continua; senão a guardada termina como "saiu". */
    fun retomar(restaurada: Track?) {
        val salvo = runCatching { JSONObject(atualFile.readText()) }.getOrNull() ?: return
        val t = runCatching { app.sounos.data.Json.track(salvo.getJSONObject("track")) }.getOrNull() ?: return
        atual = t to (salvo.optJSONObject("contexto") ?: JSONObject())
        ultimaPos = salvo.optLong("pos"); ultimaDur = salvo.optLong("dur"); extras = salvo.optJSONObject("extras") ?: JSONObject()
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
        val ex = extras; extras = JSONObject()
        atual = null
        guardarAtual()
        if (fim == "erro") return
        val dur = if (durMs > 0) durMs else t.durationSec * 1000
        val ouviu = if (fim == "completa") dur else posMs.coerceIn(0, dur.takeIf { it > 0 } ?: posMs)
        val e = JSONObject().put("at", System.currentTimeMillis()).put("id", t.id).put("titulo", t.title).put("artista", t.artist)
            .put("artistas", JSONArray(t.artists)).put("durMs", dur).put("ouviuMs", ouviu).put("fim", fim)
            .put("contexto", ctx.optString("type"))
        // de onde veio (nome da playlist/álbum/artista…: só os valores simples do contexto) e o que aconteceu durante ela
        val origem = JSONObject(); ctx.keys().forEach { k -> ctx.opt(k).takeIf { it is String || it is Number || it is Boolean }?.let { origem.put(k, it) } }
        e.put("origem", origem)
        ex.keys().forEach { k -> e.put(k, ex.get(k)) }
        if (fim == "pulou" && ouviu < PULO_CEDO_MS) e.put("pulouCedo", true)
        io.launch {
            val generos = runCatching { generos(t) }.getOrDefault(emptyList())
            e.put("generos", JSONArray(generos))
            diario.anotar("escuta", e, e.getLong("at"))
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
        if (e.optBoolean("pulouCedo")) inc("pulouCedo")
        x.put("ultima", e.getLong("at"))
        // em que horas a pessoa ouve isto (0–23) e as últimas 50 vezes em que tocou
        val c = Calendar.getInstance().apply { timeInMillis = e.getLong("at") }
        val horas = x.optJSONObject("horas") ?: JSONObject().also { x.put("horas", it) }
        val h = c.get(Calendar.HOUR_OF_DAY).toString(); horas.put(h, horas.optLong(h) + 1)
        val quando = x.optJSONArray("quando") ?: JSONArray().also { x.put("quando", it) }
        quando.put(e.getLong("at")); while (quando.length() > 50) quando.remove(0)
    }

    private fun anotar(e: JSONObject) {
        dir.mkdirs()
        // 1) a escuta, crua (o arquivo gira em ~2 MB)
        if (escutasFile.length() > 2_000_000) escutasFile.renameTo(File(dir, "escutas.1.jsonl"))
        escutasFile.appendText(e.toString() + "\n")
        // 2) as somas
        val p = carregar()
        p.put("escutas", p.optLong("escutas") + 1)
        // geral: quantas escutas por hora do dia e por dia da semana
        val c = Calendar.getInstance().apply { timeInMillis = e.getLong("at") }
        for ((nome, v) in listOf("horas" to c.get(Calendar.HOUR_OF_DAY), "dias" to c.get(Calendar.DAY_OF_WEEK))) {
            val o = p.optJSONObject(nome) ?: JSONObject().also { p.put(nome, it) }
            o.put(v.toString(), o.optLong(v.toString()) + 1)
        }
        val fins = p.optJSONObject("fins") ?: JSONObject().also { p.put("fins", it) }
        fins.put(e.getString("fim"), fins.optLong(e.getString("fim")) + 1)
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
        gravarPerfil(p)
    }

    private fun gravarPerfil(p: JSONObject) { dir.mkdirs(); File(dir, "perfil.json.tmp").apply { writeText(p.toString()) }.renameTo(perfilFile) }

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


    // ---------- uma vez só: escutas de antes do diário (escutas.jsonl) entram nas contas novas ----------
    /**
     * Horários, dias, como terminou, "pulou cedo" e as últimas vezes de cada música passaram a ser contados em 04/11/2026;
     * as escutas guardadas desde antes já têm data e fim: são recontadas uma vez (sem somar de novo o que já estava
     * somado: tocou, completas, tempo ouvido) e copiadas para o diário, para ele ter a história inteira.
     */
    fun importarAntigas() = io.launch {
        lock.withLock {
            runCatching {
                val p = carregar()
                if (p.optBoolean("importouAntigas")) return@withLock
                val linhas = listOf(File(dir, "escutas.1.jsonl"), escutasFile).filter { it.exists() }
                    .flatMap { f -> f.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }.sortedBy { it.optLong("at") }
                    .let { todas ->
                        // as que já passaram pelas contas novas (estão no diário) não entram de novo
                        val jaContadas = File(dir, "diario").listFiles { f -> f.name.endsWith(".jsonl") && !f.name.startsWith("0000") }.orEmpty()
                            .flatMap { f -> f.readLines().filter { "\"tipo\":\"escuta\"" in it }.mapNotNull { runCatching { JSONObject(it).optLong("at") }.getOrNull() } }.toSet()
                        todas.filter { it.optLong("at") !in jaContadas }
                    }
                val c = Calendar.getInstance()
                fun soma(grupo: String, chave: String, e: JSONObject) {
                    val x = p.optJSONObject(grupo)?.optJSONObject(chave) ?: return
                    c.timeInMillis = e.getLong("at")
                    val horas = x.optJSONObject("horas") ?: JSONObject().also { x.put("horas", it) }
                    val h = c.get(Calendar.HOUR_OF_DAY).toString(); horas.put(h, horas.optLong(h) + 1)
                    val quando = x.optJSONArray("quando") ?: JSONArray().also { x.put("quando", it) }
                    quando.put(e.getLong("at")); while (quando.length() > 50) quando.remove(0)
                    if (e.optString("fim") == "pulou" && e.optLong("ouviuMs") < PULO_CEDO_MS) x.put("pulouCedo", x.optLong("pulouCedo") + 1)
                }
                val diarioLinhas = StringBuilder()
                linhas.forEach { e ->
                    c.timeInMillis = e.getLong("at")
                    for ((nome, v) in listOf("horas" to c.get(Calendar.HOUR_OF_DAY), "dias" to c.get(Calendar.DAY_OF_WEEK))) {
                        val o = p.optJSONObject(nome) ?: JSONObject().also { p.put(nome, it) }
                        o.put(v.toString(), o.optLong(v.toString()) + 1)
                    }
                    val fins = p.optJSONObject("fins") ?: JSONObject().also { p.put("fins", it) }
                    fins.put(e.optString("fim"), fins.optLong(e.optString("fim")) + 1)
                    soma("musicas", e.optString("id"), e)
                    e.optJSONArray("artistas")?.let { a -> for (i in 0 until a.length()) soma("artistas", CoverStore.artistKey(a.optString(i)), e) }
                    e.optJSONArray("generos")?.let { a -> for (i in 0 until a.length()) soma("generos", a.optString(i).lowercase(), e) }
                    if (e.optString("fim") == "pulou" && e.optLong("ouviuMs") < PULO_CEDO_MS) e.put("pulouCedo", true)
                    diarioLinhas.append(JSONObject(e.toString()).put("hora", c.get(Calendar.HOUR_OF_DAY)).put("dia", c.get(Calendar.DAY_OF_WEEK))
                        .put("tipo", "escuta").put("importada", true).toString()).append('\n')
                }
                // vão para um arquivo à parte no diário (o mês delas pode ser o atual: não mistura com o que já foi anotado)
                if (linhas.isNotEmpty()) File(dir, "diario").apply { mkdirs() }.let { File(it, "0000-antes.jsonl").writeText(diarioLinhas.toString()) }
                p.put("importouAntigas", true)
                gravarPerfil(p)
                android.util.Log.i("Sonus", "motor: ${linhas.size} escutas antigas recontadas")
            }.onFailure { android.util.Log.w("Sonus", "motor: falha ao recontar escutas antigas", it) }
        }
    }

    // ---------- o que mudou nos dados da pessoa (playlists, artistas seguidos): vira linha no diário ----------
    fun dadosMudaram(key: String, antes: String?, depois: String) {
        runCatching {
            when (key) {
                "sounos.following" -> {
                    fun nomes(v: String?) = runCatching { JSONArray(v ?: "[]") }.getOrDefault(JSONArray()).let { a ->
                        (0 until a.length()).mapNotNull { i -> a.opt(i).let { it as? String ?: (it as? JSONObject)?.optString("name") }?.takeIf(String::isNotBlank) } }.toSet()
                    val a = nomes(antes); val d = nomes(depois)
                    (d - a).forEach { acao("seguiu", JSONObject().put("artista", it)) }
                    (a - d).forEach { acao("deixouDeSeguir", JSONObject().put("artista", it)) }
                }
                "sounos.playlists" -> {
                    fun lista(v: String?) = runCatching { JSONArray(v ?: "[]") }.getOrDefault(JSONArray()).let { a ->
                        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("id") } }
                    fun faixas(p: JSONObject?) = p?.optJSONArray("tracks")?.let { t -> (0 until t.length()).associate { i ->
                        val x = t.opt(i); (if (x is JSONObject) x.optString("id") else x.toString()) to x } }.orEmpty()
                    val a = lista(antes); val d = lista(depois)
                    (d.keys - a.keys).forEach { acao("playlistCriada", JSONObject().put("playlist", d[it]?.optString("name"))) }
                    (a.keys - d.keys).forEach { acao("playlistApagada", JSONObject().put("playlist", a[it]?.optString("name"))) }
                    (d.keys intersect a.keys).forEach { id ->
                        val n = d[id]!!.optString("name")
                        if (a[id]!!.optString("name") != n) acao("playlistRenomeada", JSONObject().put("de", a[id]!!.optString("name")).put("para", n))
                        val fa = faixas(a[id]); val fd = faixas(d[id])
                        (fd.keys - fa.keys).forEach { tid -> acao("adicionouNaPlaylist", resumo(fd[tid]).put("playlist", n).put("musica", tid)) }
                        (fa.keys - fd.keys).forEach { tid -> acao("tirouDaPlaylist", resumo(fa[tid]).put("playlist", n).put("musica", tid)) }
                    }
                    (d.keys - a.keys).forEach { id -> faixas(d[id]).forEach { (tid, x) -> acao("adicionouNaPlaylist", resumo(x).put("playlist", d[id]?.optString("name")).put("musica", tid)) } }
                }
            }
        }.onFailure { android.util.Log.w("Sonus", "diário: diferença de $key falhou", it) }
    }

    private fun resumo(x: Any?) = JSONObject().apply {
        (x as? JSONObject)?.let { put("titulo", it.optString("title")).put("artista", it.optString("artist")) }
    }

    // ---------- métricas e comportamentos (por enquanto só calculados e guardados; nada no app usa ainda) ----------
    /**
     * Retrato do uso, recalculado sob demanda: quando ouve, quanto pula, sessões, músicas do momento, esquecidas e
     * rejeitadas, artistas em alta. Só descreve — não é regra de recomendação (essas o dono define antes).
     */
    suspend fun comportamentos(): JSONObject {
        val p = lock.withLock { JSONObject(carregar().toString()) }
        val agora = System.currentTimeMillis(); val dia = 86_400_000L
        val musicas = p.optJSONObject("musicas") ?: JSONObject()
        val lista = musicas.keys().asSequence().map { it to musicas.getJSONObject(it) }.toList()
        fun vezesDesde(o: JSONObject, desde: Long) = o.optJSONArray("quando")?.let { q -> (0 until q.length()).count { q.optLong(it) >= desde } } ?: 0
        fun topo(o: JSONObject?, n: Int) = JSONArray((o ?: JSONObject()).let { x -> x.keys().asSequence().map { it to x.optLong(it) }.sortedByDescending { it.second }.take(n).map { it.first.toInt() }.toList() })
        val fins = p.optJSONObject("fins") ?: JSONObject(); val total = p.optLong("escutas").coerceAtLeast(1)
        val se = p.optJSONObject("sessoes") ?: JSONObject()
        fun item(id: String, o: JSONObject) = JSONObject().put("id", id).put("titulo", o.optString("nome")).put("artista", o.optString("artista"))
        val artistasRecentes = HashMap<String, Int>()
        lista.forEach { (_, o) -> val v = vezesDesde(o, agora - 14 * dia); if (v > 0) artistasRecentes.merge(o.optString("artista"), v, Int::plus) }
        return JSONObject()
            .put("escutas", p.optLong("escutas"))
            .put("horasPreferidas", topo(p.optJSONObject("horas"), 3))          // 0–23
            .put("diasPreferidos", topo(p.optJSONObject("dias"), 2))             // 1=domingo…7=sábado
            .put("taxaCompletas", fins.optLong("completa").toDouble() / total)
            .put("taxaPulos", fins.optLong("pulou").toDouble() / total)
            .put("sessaoMediaMin", if (se.optLong("quantas") > 0) se.optLong("somaMs") / se.optLong("quantas") / 60000.0 else 0.0)
            .put("duracaoPreferidaMs", p.optJSONObject("duracao")?.optLong("mediaMs") ?: 0)
            // muito ouvidas nos últimos 14 dias
            .put("doMomento", JSONArray(lista.map { it to vezesDesde(it.second, agora - 14 * dia) }.filter { it.second >= 3 }
                .sortedByDescending { it.second }.take(20).map { (x, v) -> item(x.first, x.second).put("vezes", v) }))
            // ouvia bastante e parou há mais de 30 dias
            .put("esquecidas", JSONArray(lista.filter { (_, o) -> o.optLong("tocou") >= 5 && agora - o.optLong("ultima") > 30 * dia }
                .sortedByDescending { it.second.optLong("tocou") }.take(20).map { (id, o) -> item(id, o).put("vezes", o.optLong("tocou")) }))
            // pulada cedo (menos de 30 s) 2+ vezes e nunca ouvida até o fim
            .put("rejeitadas", JSONArray(lista.filter { (_, o) -> o.optLong("pulouCedo") >= 2 && o.optLong("completas") == 0L }
                .take(50).map { (id, o) -> item(id, o).put("pulos", o.optLong("pulouCedo")) }))
            // ouvidas até o fim sempre (5+ vezes, nenhum pulo)
            .put("favoritasDeVerdade", JSONArray(lista.filter { (_, o) -> o.optLong("completas") >= 5 && o.optLong("puladas") == 0L }
                .sortedByDescending { it.second.optLong("completas") }.take(30).map { (id, o) -> item(id, o).put("vezes", o.optLong("completas")) }))
            .put("artistasEmAlta", JSONArray(artistasRecentes.entries.sortedByDescending { it.value }.take(10).map { JSONObject().put("artista", it.key).put("vezes", it.value) }))
    }

    /** Tudo o que o motor guarda, para consulta (ponte "getPerfil"): somas + comportamentos. */
    suspend fun perfilCompleto(): JSONObject = JSONObject().put("perfil", lock.withLock { JSONObject(carregar().toString()) })
        .put("comportamentos", comportamentos())

    private companion object { const val PULO_CEDO_MS = 30_000L }
}
