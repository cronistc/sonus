package app.sounos.data

import android.content.Context
import app.sounos.extractor.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * O que fica guardado no aparelho e precisa funcionar offline:
 *  - histórico (o que tocou e DE ONDE: música solta, playlist ou perfil do artista)
 *  - músicas baixadas (áudio no armazenamento privado + dados da música)
 *  - volume medido de cada música (volume equilibrado já certo desde o primeiro segundo)
 *  - a última sessão (fila, posição) para o mini-player continuar de onde parou
 */
class Library(context: Context) {
    private val prefs = context.getSharedPreferences("sounos.library", Context.MODE_PRIVATE)
    val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }

    // ---------- histórico ----------
    @Synchronized fun addHistory(t: Track, source: JSONObject) {
        val arr = JSONArray(prefs.getString("history", "[]"))
        val out = JSONArray().put(JSONObject().put("track", Json.track(t)).put("source", source).put("at", System.currentTimeMillis()))
        for (i in 0 until minOf(arr.length(), 300)) {
            val e = arr.getJSONObject(i)
            if (e.getJSONObject("track").getString("id") != t.id) out.put(e)
        }
        prefs.edit().putString("history", out.toString()).apply()
    }
    fun history(): JSONArray = JSONArray(prefs.getString("history", "[]"))

    // ---------- downloads ----------
    fun downloadFile(id: String) = File(downloadsDir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".audio")
    fun isDownloaded(id: String) = downloadFile(id).length() > 0 && downloadedMeta().has(id)
    @Synchronized fun markDownloaded(t: Track) {
        val o = downloadedMeta().put(t.id, JSONObject().put("track", Json.track(t)).put("at", System.currentTimeMillis()))
        prefs.edit().putString("downloads", o.toString()).apply()
    }
    @Synchronized fun removeDownload(id: String) {
        downloadFile(id).delete()
        prefs.edit().putString("downloads", downloadedMeta().apply { remove(id) }.toString()).apply()
    }
    fun downloadedMeta(): JSONObject = JSONObject(prefs.getString("downloads", "{}"))
    fun downloadedTrack(id: String): Track? = downloadedMeta().optJSONObject(id)?.getJSONObject("track")?.let(Json::track)
    fun downloadsBytes(): Long = downloadsDir.listFiles()?.sumOf { it.length() } ?: 0

    // ---------- volume medido ----------
    fun gain(id: String): Float? = prefs.getFloat("gain:$id", Float.NaN).takeIf { !it.isNaN() }
    fun setGain(id: String, db: Float) = prefs.edit().putFloat("gain:$id", db).apply()

    // ---------- última sessão ----------
    fun saveSession(o: JSONObject) = prefs.edit().putString("session", o.toString()).apply()
    fun session(): JSONObject? = prefs.getString("session", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ---------- configurações vindas da interface ----------
    fun saveSettings(o: JSONObject) = prefs.edit().putString("settings", o.toString()).apply()
    fun settings(): JSONObject = runCatching { JSONObject(prefs.getString("settings", "{}")!!) }.getOrDefault(JSONObject())
}
