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
 * Letras via LRCLIB (gratuito, aberto, letras sincronizadas linha a linha).
 * A letra é escolhida pela DURAÇÃO (±3 s), para bater com a versão que está tocando.
 * Se a versão tocando for um pouco mais longa (introdução de clipe), compensa a diferença.
 * Guardada no aparelho para sempre (funciona offline e não pede de novo).
 */
class Lyrics(private val dir: File, private val client: OkHttpClient) {
    init { dir.mkdirs() }

    data class Result(val synced: List<Pair<Double, String>>?, val plain: String?) {
        fun toJson(): JSONObject = JSONObject()
            .put("synced", synced?.let { l -> JSONArray().apply { l.forEach { put(JSONArray().put(it.first).put(it.second)) } } } ?: JSONObject.NULL)
            .put("plain", plain ?: JSONObject.NULL)
    }

    private fun file(t: Track) = File(dir, t.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun cached(t: Track): Result? = runCatching { parseStored(JSONObject(file(t).readText())) }.getOrNull()

    suspend fun get(t: Track): Result = withContext(Dispatchers.IO) {
        cached(t)?.let { return@withContext it }
        val result = runCatching { fetch(t) }.getOrNull() ?: return@withContext Result(null, null)   // sem rede: não guarda "vazio"
        file(t).writeText(result.toJson().toString())
        result
    }

    private fun fetch(t: Track): Result {
        val title = cleanTitle(t.title)
        val dur = t.durationSec.toInt()
        // 1) busca exata com duração
        request("https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", t.artist)
            .addQueryParameter("duration", dur.toString()).build().toString())
            ?.let { o -> toResult(JSONObject(o), dur)?.let { return it } }
        // 2) busca aberta: melhor candidato pela duração, preferindo os sincronizados
        val list = request("https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", t.artist).build().toString())
            ?.let { JSONArray(it) } ?: return Result(null, null)
        val items = (0 until list.length()).map { list.getJSONObject(it) }
        val best = items.filter { abs(it.optDouble("duration", 0.0) - dur) <= 15 && !it.isNull("syncedLyrics") }
            .minByOrNull { abs(it.optDouble("duration", 0.0) - dur) }
            ?: items.firstOrNull { !it.isNull("plainLyrics") }
        return best?.let { toResult(it, dur) } ?: Result(null, null)
    }

    private fun toResult(o: JSONObject, playingDur: Int): Result? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced == null && plain == null) return null
        // versão tocando mais longa por uns segundos (introdução) → empurra a letra
        val diff = playingDur - o.optDouble("duration", playingDur.toDouble())
        val shift = if (diff in 2.0..15.0) diff else 0.0
        return Result(synced?.let { parseLrc(it, shift) }, plain)
    }

    private fun request(url: String): String? =
        client.newCall(Request.Builder().url(url).header("User-Agent", "Sonus 0.1 (${OkHttpDownloader.USER_AGENT})").build()).execute().use { r ->
            when { r.isSuccessful -> r.body?.string(); r.code == 404 -> null; else -> throw IllegalStateException("LRCLIB ${r.code}") }
        }

    private fun parseStored(o: JSONObject) = Result(
        o.optJSONArray("synced")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getString(1) } } },
        o.optString("plain").takeIf { it.isNotBlank() && it != "null" },
    )

    companion object {
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
