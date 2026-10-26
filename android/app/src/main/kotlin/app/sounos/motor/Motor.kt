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

    /** Artistas de que a pessoa mais gosta pelo uso: tempo ouvido e músicas até o fim pesam; pular tira. */
    suspend fun artistasPreferidos(n: Int): List<String> = lock.withLock {
        val a = carregar().getJSONObject("artistas")
        a.keys().asSequence().map { a.getJSONObject(it) }
            .sortedByDescending { it.optLong("ouviuMs") / 60000.0 + it.optLong("completas") * 2 - it.optLong("puladas") * 1.5 }
            .map { it.optString("nome") }.filter(String::isNotBlank).take(n).toList()
    }

    // ---------- Novidades ----------
    /**
     * Lista de novidades: lançamentos recentes. Para quem acabou de chegar, quase tudo vem dos lançamentos do país
     * (genérico). Quanto mais artistas a pessoa segue e ouve, mais a lista puxa para eles. (Gêneros entram no peso
     * quando o motor for além da 0.1.)
     * Devolve os lançamentos (para os cartões) e as músicas deles, embaralhadas (para tocar).
     */
    suspend fun novidades(source: NewPipeSource, seguidos: List<Pair<String, String?>>): JSONObject = coroutineScope {
        val ano = Calendar.getInstance().get(Calendar.YEAR)
        val preferidos = artistasPreferidos(6).filter { p -> seguidos.none { CoverStore.artistKey(it.first) == CoverStore.artistKey(p) } }.take(4)
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
        val escolhidos = (daPessoa + genericos.await().shuffled().filter { g -> daPessoa.none { it.browseId == g.browseId } })
            .take(total).shuffled()
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

}
