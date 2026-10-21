package app.sounos.data

import app.sounos.extractor.OkHttpDownloader
import app.sounos.extractor.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Letras em cadeia ordenada; vence a primeira SINCRONIZADA: LRCLIB → NetEase.
 * Sem sincronizada em nenhuma, devolve a melhor letra simples (LRCLIB primeiro, depois NetEase).
 * Nunca mistura fontes numa mesma música e prefere "sem letra" a uma letra errada
 * (título ≥ 0.8, artista e duração ±3 s conferem — ver [LyricsMatch]).
 * Guardada no aparelho (funciona offline); "sem letra em lugar nenhum" vale por 3 dias.
 */
class Lyrics(private val dir: File, private val client: OkHttpClient) {
    init { dir.mkdirs() }

    data class Result(val synced: List<Pair<Double, String>>?, val plain: String?) {
        fun toJson(): JSONObject = JSONObject()
            .put("synced", synced?.let { l -> JSONArray().apply { l.forEach { put(JSONArray().put(it.first).put(it.second)) } } } ?: JSONObject.NULL)
            .put("plain", plain ?: JSONObject.NULL)
    }

    private val netease = NetEaseSource(client)

    private fun file(t: Track) = File(dir, t.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Cache válido: letra guardada, ou negativo com menos de 3 dias (arquivos antigos vazios entram na mesma regra). */
    fun cached(t: Track): Result? = runCatching {
        val f = file(t)
        val r = parseStored(JSONObject(f.readText()))
        if (r.synced == null && r.plain == null && System.currentTimeMillis() - f.lastModified() > NEGATIVE_TTL_MS) null else r
    }.getOrNull()

    suspend fun get(t: Track): Result = withContext(Dispatchers.IO) {
        cached(t)?.let { return@withContext it }
        val out = runCatching { fetch(t) }.getOrNull() ?: return@withContext Result(null, null)
        val result = Result(out.found?.synced, out.found?.plain)
        // falha de rede em alguma fonte: não guarda "vazio" nem resultado parcial (só letra sincronizada é definitiva)
        if (!out.failed || result.synced != null) {
            val json = result.toJson().put("source", out.found?.source ?: "none").put("ts", System.currentTimeMillis())
            file(t).writeText(json.toString())
        }
        result
    }

    private class Outcome(val found: Found?, val failed: Boolean)

    private fun fetch(t: Track): Outcome {
        val title = cleanTitle(t.title)
        val dur = t.durationSec.toInt()
        val deadline = Deadline(TOTAL_BUDGET_MS)
        var failed = false
        var plain: Found? = null

        // 1) LRCLIB
        try {
            lrclib(t, title, dur, deadline)?.let { if (it.synced != null) return Outcome(it, false); plain = it }
        } catch (e: Exception) { failed = true }
        // 2) NetEase (cada fonte falha isoladamente e cai para a próxima)
        try {
            netease.find(t, title, dur, deadline)?.let { n ->
                if (n.synced != null) return Outcome(withShift(n, dur), failed)
                if (plain?.plain == null) plain = n
            }
        } catch (e: Exception) { failed = true }
        return Outcome(plain?.takeIf { it.plain != null || it.synced != null }, failed)
    }

    private fun lrclib(t: Track, title: String, dur: Int, deadline: Deadline): Found? {
        // a) busca exata com duração
        get("https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", t.artist)
            .addQueryParameter("duration", dur.toString()).build().toString(), deadline)
            ?.let { o -> toFound(JSONObject(o), dur)?.let { return it } }
        // b) busca aberta: confere título/artista; melhor candidato pela duração, preferindo os sincronizados
        val list = get("https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", t.artist).build().toString(), deadline)
            ?.let { JSONArray(it) } ?: return null
        val items = (0 until list.length()).map { list.getJSONObject(it) }.filter {
            LyricsMatch.titleOk(title, it.optString("trackName")) && LyricsMatch.artistOk(t.artist, listOf(it.optString("artistName")))
        }
        val best = items.filter { abs(it.optDouble("duration", 0.0) - dur) <= 15 && !it.isNull("syncedLyrics") }
            .minByOrNull { abs(it.optDouble("duration", 0.0) - dur) }
            ?: items.firstOrNull { !it.isNull("plainLyrics") }
        return best?.let { toFound(it, dur) }
    }

    private fun toFound(o: JSONObject, playingDur: Int): Found? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced == null && plain == null) return null
        val d = o.optDouble("duration", playingDur.toDouble())
        val shift = shiftFor(playingDur, d)
        return Found(synced?.let { parseLrc(it, shift) }, plain, "lrclib", d)
    }

    /** Versão tocando mais longa por uns segundos (introdução) → empurra a letra. */
    private fun shiftFor(playingDur: Int, sourceDur: Double): Double {
        val diff = playingDur - sourceDur
        return if (diff in 2.0..15.0) diff else 0.0
    }

    private fun withShift(f: Found, playingDur: Int): Found {
        val shift = f.sourceDurationSec?.let { shiftFor(playingDur, it) } ?: 0.0
        return if (shift == 0.0 || f.synced == null) f else f.copy(synced = f.synced.map { (it.first + shift) to it.second })
    }

    private fun get(url: String, deadline: Deadline): String? =
        httpGet(client, url, deadline, mapOf("User-Agent" to "Sonus 0.1 (${OkHttpDownloader.USER_AGENT})"))

    private fun parseStored(o: JSONObject) = Result(
        o.optJSONArray("synced")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getString(1) } } },
        o.optString("plain").takeIf { it.isNotBlank() && it != "null" },
    )

    companion object {
        private const val TOTAL_BUDGET_MS = 10_000L
        private const val NEGATIVE_TTL_MS = 3L * 24 * 3600 * 1000
        private val TAG = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        fun parseLrc(lrc: String, shift: Double = 0.0): List<Pair<Double, String>> =
            lrc.lines().flatMap { line ->
                val times = TAG.findAll(line).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toDouble() }.toList()
                val text = TAG.replace(line, "").trim().ifEmpty { "♪" }
                times.map { (it + shift) to text }
            }.sortedBy { it.first }

        /** "Trevo (Tu) (feat. TIAGO IORC)" → "Trevo (Tu)" — participações atrapalham a busca da letra. */
        fun cleanTitle(t: String) = t.replace(Regex("""\s*[\(\[]\s*(feat|ft|with|part)\.?\s[^)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "").trim()
    }
}
