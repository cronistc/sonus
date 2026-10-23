package app.sounos.data

import android.content.Context
import app.sounos.extractor.Track
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
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
        val out = JSONArray().put(JSONObject().put("track", withFixes(Json.track(t))).put("source", source).put("at", System.currentTimeMillis()))
        for (i in 0 until minOf(arr.length(), 300)) {
            val e = arr.getJSONObject(i)
            if (e.getJSONObject("track").getString("id") != t.id) out.put(e)
        }
        prefs.edit().putString("history", out.toString()).apply()
    }
    fun history(): JSONArray = JSONArray(prefs.getString("history", "[]"))

    // ---------- músicas completas em cache ----------
    @Synchronized fun markCached(t: Track) {
        val all = runCatching { JSONObject(prefs.getString("cacheTracks", "{}")) }.getOrDefault(JSONObject())
        all.put(t.id, withFixes(Json.track(t)))
        prefs.edit().putString("cacheTracks", all.toString()).apply()
    }
    fun cachedTracks(): Map<String, Track> {
        val all = runCatching { JSONObject(prefs.getString("cacheTracks", "{}")) }.getOrDefault(JSONObject())
        return all.keys().asSequence().mapNotNull { id ->
            runCatching { id to Json.track(all.getJSONObject(id)) }.getOrNull()
        }.toMap()
    }
    @Synchronized fun pruneCached(keep: Set<String>) {
        val all = runCatching { JSONObject(prefs.getString("cacheTracks", "{}")) }.getOrDefault(JSONObject())
        all.keys().asSequence().toList().filterNot(keep::contains).forEach(all::remove)
        prefs.edit().putString("cacheTracks", all.toString()).apply()
    }

    // ---------- downloads ----------
    fun downloadFile(id: String) = File(downloadsDir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".audio")
    fun isDownloaded(id: String) = downloadFile(id).length() > 0 && downloadedMeta().has(id)
    @Synchronized fun markDownloaded(t: Track) {
        val o = downloadedMeta().put(t.id, JSONObject().put("track", withFixes(Json.track(t))).put("at", System.currentTimeMillis()))
        prefs.edit().putString("downloads", o.toString()).apply()
    }
    @Synchronized fun removeDownload(id: String) {
        downloadFile(id).delete()
        prefs.edit().putString("downloads", downloadedMeta().apply { remove(id) }.toString()).apply()
    }
    /**
     * Consistência ao abrir o app, SEM apagar nada: metadado sem arquivo ganha "missing": true (a interface pode
     * oferecer baixar de novo); quando o arquivo volta, a marca sai. Arquivo sem metadado fica como está.
     */
    @Synchronized fun checkDownloads() {
        val meta = downloadedMeta()
        var changed = false
        for (id in meta.keys().asSequence().toList()) {
            val e = meta.optJSONObject(id) ?: continue
            val missing = downloadFile(id).length() <= 0L
            if (missing != e.optBoolean("missing", false)) {
                if (missing) e.put("missing", true) else e.remove("missing")
                changed = true
            }
        }
        if (changed) prefs.edit().putString("downloads", meta.toString()).apply()
    }
    fun downloadedMeta(): JSONObject = JSONObject(prefs.getString("downloads", "{}"))
    fun downloadedTrack(id: String): Track? = downloadedMeta().optJSONObject(id)?.getJSONObject("track")?.let(Json::track)
    fun downloadsBytes(): Long = downloadsDir.listFiles()?.sumOf { it.length() } ?: 0

    // ---------- volume medido ----------
    fun gain(id: String): Float? = prefs.getFloat("gain:$id", Float.NaN).takeIf { !it.isNaN() }
    fun setGain(id: String, db: Float) = prefs.edit().putFloat("gain:$id", db).apply()

    // ---------- última sessão ----------
    fun saveSession(o: JSONObject) = prefs.edit().putString("session", withFixes(o).toString()).apply()
    fun session(): JSONObject? = prefs.getString("session", null)?.let { runCatching { withFixes(JSONObject(it)) }.getOrNull() }

    /** A música que ainda está na memória (fila, tocando agora) pode ser anterior à correção: aplica ao gravar. */
    private fun <T : Any> withFixes(v: T): T {
        val all = fixes()
        if (all.length() > 0) walkTracks(v) { o -> all.optJSONObject(o.getString("id"))?.let { copyFields(it, o) } ?: false }
        return v
    }

    // ---------- correção de metadados já salvos ----------
    /** Dados corrigidos por música (id → música). Valem também para o que ainda está na memória do player. */
    // em memória: a sessão é gravada com frequência e não pode reler/analisar o arquivo toda vez
    @Volatile private var fixesCache: JSONObject? = null
    fun fixes(): JSONObject = fixesCache ?: runCatching { JSONObject(prefs.getString("fixes", "{}")) }.getOrDefault(JSONObject()).also { fixesCache = it }

    /** Muda a cada correção nova: a interface só busca a lista quando ela mudou. */
    fun fixesRev(): Int = prefs.getInt("fixesRev", 0)

    fun applyFix(t: Track) = applyFix(Json.track(t))

    /** Guarda a correção e reescreve toda cópia dessa música: histórico, cache, baixadas e sessão. */
    @Synchronized fun applyFix(fixed: JSONObject) {
        val id = fixed.getString("id")
        val all = JSONObject(fixes().toString()).put(id, fixed)
        // limite: as correções mais antigas saem (as cópias já foram reescritas)
        while (all.length() > 3000) all.remove(all.keys().next())
        fixesCache = all
        val e = prefs.edit().putString("fixes", all.toString()).putInt("fixesRev", fixesRev() + 1)
        for (key in listOf("history", "cacheTracks", "downloads", "session")) {
            val raw = prefs.getString(key, null) ?: continue
            val v = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: continue
            if (patch(v, id, fixed)) e.putString(key, v.toString())
        }
        e.apply()
    }

    /** Reescreve, sem internet, todas as músicas salvas com uma regra (ex.: separar artistas). */
    @Synchronized fun rewriteAll(fix: (JSONObject) -> Boolean) {
        val e = prefs.edit()
        for (key in listOf("history", "cacheTracks", "downloads", "session")) {
            val raw = prefs.getString(key, null) ?: continue
            val v = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: continue
            if (walkTracks(v, fix)) e.putString(key, v.toString())
        }
        e.apply()
    }

    /** Ids de todas as músicas salvas aqui (para a correção pela internet). */
    fun savedIds(): List<String> {
        val ids = linkedSetOf<String>()
        for (key in listOf("downloads", "history", "cacheTracks", "session")) {
            val raw = prefs.getString(key, null) ?: continue
            runCatching { JSONTokener(raw).nextValue() }.getOrNull()?.let { walkTracks(it) { o -> ids += o.getString("id"); false } }
        }
        return ids.toList()
    }

    // ---------- configurações vindas da interface ----------
    fun saveSettings(o: JSONObject) = prefs.edit().putString("settings", o.toString()).apply()
    fun settings(): JSONObject = runCatching { JSONObject(prefs.getString("settings", "{}")!!) }.getOrDefault(JSONObject())

    companion object {
        private val FIELDS = listOf("title", "artist", "artists", "artistUrl", "thumb", "thumbs")

        /** Chama fix para cada objeto que parece uma música ({id, url, title}); true = algo mudou. */
        fun walkTracks(v: Any?, fix: (JSONObject) -> Boolean): Boolean = when (v) {
            is JSONObject -> {
                var changed = v.has("id") && v.has("url") && v.has("title") && fix(v)
                for (k in v.keys().asSequence().toList()) if (walkTracks(v.opt(k), fix)) changed = true
                changed
            }
            is JSONArray -> (0 until v.length()).fold(false) { acc, i -> walkTracks(v.opt(i), fix) || acc }
            else -> false
        }

        fun patch(v: Any?, id: String, fixed: JSONObject) = walkTracks(v) { o -> o.optString("id") == id && copyFields(fixed, o) }

        /** Copia os campos de metadado (não mexe em id, url, duração). */
        fun copyFields(from: JSONObject, to: JSONObject): Boolean {
            var changed = false
            for (f in FIELDS) {
                if (!from.has(f)) continue
                val nv = from.get(f)
                if (to.opt(f)?.toString() != nv.toString()) { to.put(f, nv); changed = true }
            }
            return changed
        }
    }
}
