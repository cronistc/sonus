package app.sounos.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream

/**
 * Espelho nativo dos dados da interface (playlists, histórico de busca, ajustes), em files/userdata/<chave>.json.
 * A interface guarda no localStorage do WebView; aqui fica uma segunda cópia, fora do WebView, que sobrevive
 * a limpeza do WebView, cota estourada e atualizações.
 *  - gravação atômica: arquivo temporário + fsync + rename (nunca existe um arquivo pela metade);
 *  - guarda as 3 versões anteriores (<chave>.json.1..3), giradas só quando o conteúdo muda;
 *  - leitura: se o principal estiver corrompido, usa o backup válido mais novo.
 */
class UserData(private val dir: File) {
    private val lock = Any()

    init { dir.mkdirs() }

    /** Só valores JSON de objeto/array e chaves "sounos.nome". Devolve false se recusou ou falhou. */
    fun save(key: String, value: String): Boolean {
        if (!KEY.matches(key)) return false
        if (value.length > MAX_CHARS || value.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        if (!validJson(value)) return false
        synchronized(lock) {
            return runCatching {
                dir.mkdirs()
                val main = File(dir, "$key.json")
                val bytes = value.toByteArray(Charsets.UTF_8)
                if (main.exists() && main.length() == bytes.size.toLong() && main.readBytes().contentEquals(bytes)) return true   // nada mudou
                val tmp = File(dir, "$key.json.tmp")
                FileOutputStream(tmp).use { o -> o.write(bytes); o.flush(); o.fd.sync() }
                // só gira os backups se o principal atual está íntegro (um principal corrompido não pode empurrar backups bons para fora)
                if (main.exists() && readValid(main) != null) rotate(main)
                if (!tmp.renameTo(main)) { tmp.delete(); error("rename falhou") }
                true
            }.onFailure { Log.w("Sonus", "userdata: gravar $key falhou", it) }.getOrDefault(false)
        }
    }

    /** Valor guardado (ou o backup válido mais novo); null se não há nada utilizável. */
    fun load(key: String): String? {
        if (!KEY.matches(key)) return null
        synchronized(lock) {
            for (n in 0..BACKUPS) {
                val f = File(dir, if (n == 0) "$key.json" else "$key.json.$n")
                readValid(f)?.let { if (n > 0) Log.w("Sonus", "userdata: $key lido do backup $n"); return it }
            }
            return null
        }
    }

    // .2 -> .3, .1 -> .2, principal -> .1 (cópia: o principal continua válido até o rename final)
    private fun rotate(main: File) {
        for (n in BACKUPS downTo 2) {
            val from = File(dir, "${main.name}.${n - 1}")
            if (from.exists()) from.renameTo(File(dir, "${main.name}.$n"))
        }
        val b1 = File(dir, "${main.name}.1"); val t1 = File(dir, "${main.name}.1.tmp")
        main.copyTo(t1, overwrite = true)
        if (!t1.renameTo(b1)) t1.delete()
    }

    private fun readValid(f: File): String? {
        if (!f.isFile || f.length() == 0L || f.length() > MAX_BYTES) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { validJson(it) }
    }

    companion object {
        val KEY = Regex("^sounos\\.[a-zA-Z]+$")
        const val BACKUPS = 3
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_CHARS = MAX_BYTES

        fun validJson(s: String): Boolean = runCatching {
            val t = s.trimStart()
            if (!t.startsWith("[") && !t.startsWith("{")) return false
            val tk = JSONTokener(s)
            val v = tk.nextValue()
            (v is JSONObject || v is JSONArray) && tk.nextClean() == 0.toChar()    // nada sobrando depois do JSON
        }.getOrDefault(false)
    }
}
