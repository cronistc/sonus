package app.sounos.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Cópia de segurança antes de atualizar o app (files/backups/pre-<versão>-<hora>/):
 *  - shared_prefs/sounos.library.xml (histórico, metadados dos downloads, volumes, ajustes) + library.json (o mesmo, em JSON);
 *  - arquivos de files/userdata (espelho das playlists etc.).
 * Os áudios baixados NUNCA são copiados (a atualização não mexe neles). Só acrescenta; só apaga snapshots antigos
 * desta própria pasta (mantém os 3 mais novos).
 */
object Backups {
    private const val TAG = "Sonus"
    private const val KEEP = 3
    private const val PREFS = "sounos.library"

    private fun root(c: Context) = File(c.filesDir, "backups")

    @Synchronized fun snapshot(c: Context, versionCode: Long): File? = runCatching {
        val dir = File(root(c), "pre-$versionCode-${System.currentTimeMillis()}").apply { mkdirs() }
        File(c.applicationInfo.dataDir, "shared_prefs/$PREFS.xml").takeIf { it.isFile }?.copyTo(File(dir, "$PREFS.xml"), overwrite = true)
        File(dir, "library.json").writeText(dumpPrefs(c).toString())
        File(c.filesDir, "userdata").listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.let { list ->
            val ud = File(dir, "userdata").apply { mkdirs() }
            list.forEach { it.copyTo(File(ud, it.name), overwrite = true) }
        }
        prune(c)
        Log.i(TAG, "snapshot pré-atualização em ${dir.name}")
        dir
    }.onFailure { Log.w(TAG, "snapshot falhou", it) }.getOrNull()

    private fun stamp(f: File) = f.name.substringAfterLast('-').toLongOrNull() ?: 0L
    private fun snapshots(c: Context): List<File> =
        root(c).listFiles()?.filter { it.isDirectory && it.name.startsWith("pre-") }?.sortedByDescending(::stamp) ?: emptyList()

    private fun prune(c: Context) { snapshots(c).drop(KEEP).forEach { it.deleteRecursively() } }

    /** Todas as chaves das preferências, com o tipo, para poder restaurar fielmente. */
    private fun dumpPrefs(c: Context): JSONObject {
        val o = JSONObject()
        for ((k, v) in c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all) {
            val e = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                else -> null
            }
            if (e != null) o.put(k, e)
        }
        return o
    }

    private fun empty(s: String?) = s.isNullOrBlank() || s == "[]" || s == "{}"

    /**
     * No início do app: se as preferências estão sem histórico e sem downloads mas há snapshot com eles,
     * recoloca do snapshot mais novo que os tenha (só o que falta, mais histórico/downloads). Também recoloca
     * arquivos de userdata que não existam. Nunca apaga nada.
     */
    @Synchronized fun restoreIfEmpty(c: Context) = runCatching {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snaps = snapshots(c)
        if (snaps.isEmpty()) return@runCatching
        // arquivos do espelho: só os que não existem
        val ud = File(c.filesDir, "userdata").apply { mkdirs() }
        for (s in snaps) File(s, "userdata").listFiles()?.forEach { f ->
            val dst = File(ud, f.name)
            if (f.isFile && !dst.exists()) { f.copyTo(dst); Log.i(TAG, "userdata ${f.name} restaurado de ${s.name}") }
        }
        if (!empty(prefs.getString("history", null)) || !empty(prefs.getString("downloads", null))) return@runCatching
        for (s in snaps) {
            val dump = runCatching { JSONObject(File(s, "library.json").readText()) }.getOrNull() ?: continue
            val has = { k: String -> !empty(dump.optJSONObject(k)?.optString("v")) }
            if (!has("history") && !has("downloads")) continue
            val ed = prefs.edit()
            for (k in dump.keys()) {
                val e = dump.getJSONObject(k)
                if (prefs.contains(k) && k != "history" && k != "downloads") continue
                when (e.getString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                }
            }
            ed.commit()
            Log.w(TAG, "preferências vazias: restauradas do snapshot ${s.name}")
            return@runCatching
        }
    }.onFailure { Log.w(TAG, "restauro do snapshot falhou", it) }.let { }

}
