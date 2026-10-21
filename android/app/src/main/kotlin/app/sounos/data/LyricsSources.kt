package app.sounos.data

import app.sounos.extractor.Track
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Resultado de uma fonte de letras. Nunca mistura fontes: tudo aqui vem da mesma [source]. */
internal data class Found(
    val synced: List<Pair<Double, String>>?,
    val plain: String?,
    val source: String,
    val sourceDurationSec: Double? = null,   // duração da faixa na fonte (para compensar introdução)
)

/** Orçamento total de tempo da busca; cada requisição respeita o menor entre o teto da fonte e o que sobra. */
internal class Deadline(totalMs: Long, private val perCallMs: Long = 4_000) {
    private val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMs)
    fun callTimeoutMs(): Long {
        val left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
        if (left <= 200) throw IOException("orçamento de tempo esgotado")
        return min(perCallMs, left)
    }
}

/** GET com timeout curto. 404 → null; outros erros HTTP lançam (a fonte "falhou", segue para a próxima). */
internal fun httpGet(client: OkHttpClient, url: String, deadline: Deadline, headers: Map<String, String>): String? {
    val c = client.newBuilder().callTimeout(deadline.callTimeoutMs(), TimeUnit.MILLISECONDS).build()
    val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    return c.newCall(req).execute().use { r ->
        when {
            r.isSuccessful -> r.body?.string()
            r.code == 404 -> null
            else -> throw IOException("HTTP ${r.code}")
        }
    }
}

/** Regras de casamento título/artista/versão, compartilhadas por todas as fontes. */
internal object LyricsMatch {
    private val VERSION_TAGS = Regex("""\b(live|ao vivo|remix|acoustic|acustico|instrumental|karaoke|cover|demo|unplugged|sped up|slowed|reverb|8d|edit)\b""")
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|;|\+|\bfeat\.?|\bft\.?|\bx\b|\be\b|\band\b|\bcom\b)\s*""")

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun squash(s: String) =
        s.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().replace(Regex("\\s+"), " ")

    /** Sem acentos, sem (parênteses)/[colchetes], sem "feat./part." e sem sufixo " - remaster/live". */
    fun title(raw: String): String {
        var s = fold(raw)
        s = s.replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ")
        s = s.replace(Regex("""\s+-\s+.*(remaster|version|live|ao vivo|remix|mono|stereo|edit).*$"""), " ")
        s = s.replace(Regex("""(?<=\w)\s+(feat|ft|featuring|part|participacao|with)\b\.?\s.*$"""), " ")
        return squash(s)
    }

    fun artist(raw: String): String =
        squash(fold(raw).replace(Regex("""\s*-\s*topic\b"""), " ").replace(Regex("""\b(vevo|oficial|official)\b"""), " "))

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    private fun tags(raw: String) = VERSION_TAGS.findAll(fold(raw)).map { it.value }.toSet()

    /** Título parecido (≥ 0.8) e sem versão "estranha" (ao vivo/remix/etc.) que a faixa tocando não tem. */
    fun titleOk(wanted: String, candidate: String): Boolean =
        similarity(title(wanted), title(candidate)) >= 0.8 && (tags(candidate) - tags(wanted)).isEmpty()

    /** Algum artista da faixa contém (ou está contido em) algum artista do candidato. */
    fun artistOk(wanted: String, candidates: List<String>): Boolean {
        val w = (listOf(artist(wanted)) + ARTIST_SPLIT.split(fold(wanted)).map { artist(it) }).filter { it.length >= 2 }
        val c = candidates.flatMap { listOf(artist(it)) + ARTIST_SPLIT.split(fold(it)).map { p -> artist(p) } }.filter { it.length >= 2 }
        return w.any { a -> c.any { b -> a.contains(b) || b.contains(a) } }
    }

    fun durationOk(playingSec: Int, candidateSec: Double, tolSec: Double = 3.0): Boolean =
        playingSec <= 0 || abs(candidateSec - playingSec) <= tolSec
}

/** NetEase Cloud Music: endpoints públicos, sem chave. Só aceita resultado que passe em todas as regras de casamento. */
internal class NetEaseSource(private val client: OkHttpClient) {
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Referer" to "https://music.163.com/",
    )

    private data class Cand(val id: Long, val durSec: Double)

    fun find(t: Track, title: String, durSec: Int, deadline: Deadline): Found? {
        val firstArtist = t.artist.split(Regex("""\s*(?:,|&|;|\+)\s*""")).first().replace(Regex("""(?i)\s*-\s*topic$"""), "").trim()
        // /api/search/get (sem "/web", que devolve payload criptografado)
        val url = "https://music.163.com/api/search/get".toHttpUrl().newBuilder()
            .addQueryParameter("s", "$title $firstArtist".trim()).addQueryParameter("type", "1")
            .addQueryParameter("limit", "10").addQueryParameter("offset", "0").build().toString()
        val songs = JSONObject(httpGet(client, url, deadline, headers) ?: return null)
            .optJSONObject("result")?.optJSONArray("songs") ?: return null
        val cands = (0 until songs.length()).mapNotNull { i ->
            val s = songs.optJSONObject(i) ?: return@mapNotNull null
            val artists = s.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
            val d = s.optDouble("duration", 0.0) / 1000.0   // NetEase usa milissegundos
            if (!LyricsMatch.titleOk(title, s.optString("name"))) return@mapNotNull null
            if (!LyricsMatch.artistOk(t.artist, artists)) return@mapNotNull null
            if (d > 0 && !LyricsMatch.durationOk(durSec, d)) return@mapNotNull null
            Cand(s.optLong("id"), d)
        }.sortedBy { if (it.durSec > 0 && durSec > 0) abs(it.durSec - durSec) else 0.0 }.take(3)

        for (c in cands) {
            val lyr = httpGet(client, "https://music.163.com/api/song/lyric?id=${c.id}&lv=1&kv=1&tv=-1", deadline, headers) ?: continue
            parseLyric(JSONObject(lyr), c.durSec)?.let { return it }
        }
        return null
    }

    private fun parseLyric(o: JSONObject, durSec: Double): Found? {
        if (o.optBoolean("nolyric") || o.optBoolean("uncollected")) return null
        val raw = o.optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (raw.isBlank() || raw.contains("纯音乐")) return null   // "música instrumental"
        val lines = raw.lines().filter { l ->
            val body = TS.replace(l, "").trim()
            !body.startsWith("{") && !CREDIT.containsMatchIn(body)
        }
        val dur = durSec.takeIf { it > 0 }
        if (lines.any { TS.containsMatchIn(it) }) {
            val parsed = Lyrics.parseLrc(lines.joinToString("\n")).dropWhile { it.second == "♪" }
            if (parsed.count { it.second != "♪" } < 4) return null
            return Found(parsed, parsed.filter { it.second != "♪" }.joinToString("\n") { it.second }, "netease", dur)
        }
        // sem timestamps: só texto
        val plain = lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return if (plain.lines().size >= 4) Found(null, plain, "netease", dur) else null
    }

    private companion object {
        val TS = Regex("""\[\d+:\d+(?:[.:]\d+)?]""")
        // créditos de metadados no começo da letra ("作词 : ...", "composer: ...")
        val CREDIT = Regex("""^\s*(作词|作曲|编曲|制作人|词|曲|混音|母带|监制|composer|lyricist|arranger|producer|lyrics by|music by|written by)\s*[:：]""", RegexOption.IGNORE_CASE)
    }
}
