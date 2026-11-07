package app.sounos.motor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Diário do motor: TUDO o que a pessoa faz no app, uma linha por acontecimento, guardado só no celular (nada sai do
 * aparelho; é para a personalização do próprio app). Pedido do dono em 04/11/2026: "o app guarda todo tipo de dado do
 * usuário, mas não manda para lugar nenhum".
 *
 * Cada linha: {"at": quando (ms), "hora": 0–23, "dia": 1=domingo…7=sábado, "tipo": …, + os dados do acontecimento}.
 * Tipos: escuta (cada música ouvida, com como terminou), tocar, pausar, continuar, avancar, voltar, posicao (arrastou a
 * barra), aleatorio, repetir, fila (reordenou), baixar, apagarDownload, buscar, escolheuNaBusca, abriuArtista,
 * abriuAlbum, abriuPlaylist, abriuLetra, compartilhou, seguiu/deixouDeSeguir, playlistCriada/Apagada/Renomeada,
 * adicionouNaPlaylist/tirouDaPlaylist, abriuApp/fechouApp.
 *
 * Arquivos: files/motor/diario/AAAA-MM.jsonl (um por mês) e 0000-antes.jsonl (escutas de antes de 04/11/2026, copiadas
 * uma vez pelo Motor.importarAntigas). Nada é apagado, a não ser que passe de 200 MB juntos
 * (aí sai o mês mais antigo) — ~300 bytes por linha: anos de uso.
 */
class Diario(private val dir: File, private val io: CoroutineScope) {
    private val lock = Mutex()
    private val mes = SimpleDateFormat("yyyy-MM", Locale.ROOT)

    fun anotar(tipo: String, dados: JSONObject = JSONObject(), at: Long = System.currentTimeMillis()) {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val linha = JSONObject(dados.toString()).put("at", at).put("hora", c.get(Calendar.HOUR_OF_DAY))
            .put("dia", c.get(Calendar.DAY_OF_WEEK)).put("tipo", tipo)
        io.launch {
            lock.withLock {
                runCatching {
                    dir.mkdirs()
                    File(dir, mes.format(Date(at)) + ".jsonl").appendText(linha.toString() + "\n")
                    limitar()
                }.onFailure { android.util.Log.w("Sonus", "diário: falha ao anotar $tipo", it) }
            }
        }
    }

    private var conferidoEm = 0L
    private fun limitar() {
        if (System.currentTimeMillis() - conferidoEm < 3_600_000L) return     // confere o tamanho no máximo 1×/hora
        conferidoEm = System.currentTimeMillis()
        val meses = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }?.toMutableList() ?: return
        while (meses.size > 1 && meses.sumOf { it.length() } > MAX_BYTES) meses.removeAt(0).delete()
    }

    /** Linhas dos últimos [dias] dias (para métricas), da mais antiga à mais nova. */
    suspend fun ultimos(dias: Int): List<JSONObject> = lock.withLock {
        val desde = System.currentTimeMillis() - dias * 86_400_000L
        val meses = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()
            .filter { it.name.startsWith("0000") || it.name.removeSuffix(".jsonl") >= mes.format(Date(desde)) }   // 0000-antes: escutas de antes do diário
        meses.flatMap { f ->
            f.useLines { linhas -> linhas.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.filter { it.optLong("at") >= desde }.toList() }
        }
    }

    companion object { private const val MAX_BYTES = 200L * 1024 * 1024 }
}
