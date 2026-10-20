package app.sounos.extractor

import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Escolhe o que entra na fila e nas indicações: primeiro os canais dos artistas
 * (canais "Topic" do YouTube Music, canais verificados, "Oficial"); fora covers, karaokê,
 * reações, aulas e coletâneas de horas. Nunca a mesma música duas vezes (nem em outro upload).
 */
object MusicPicks {
    private val JUNK = Regex(
        """\b(cover|covers|karaok[eê]|reac[tc](t|tion|ting|ao|ão)?|reagindo|tutorial|aula|como tocar|playback|""" +
            """mais tocadas|as melhores|melhores m[uú]sicas|top \d+|cd completo|[aá]lbum completo|full album|""" +
            """sele[cç][aã]o|sequ[eê]ncia|nonstop|non stop|playlist|coletanea|coletânea|pra ouvir|para ouvir|mashup|""" +
            """speed ?up|sped up|slowed|8d audio|nightcore|bass boost(ed)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OFFICIAL_NAME = Regex("""(\s-\sTopic$|oficial|official|vevo)""", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("""[\(\[].*?[\)\]]""")
    private val FEAT = Regex("""\s(feat\.?|ft\.?|part\.?|participa[cç][aã]o)\s.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_NOISE = Regex("""\b(oficial|official|vevo|tv|music|channel|canal)\b""", RegexOption.IGNORE_CASE)

    /** Mesma música em uploads diferentes (clipe, áudio, ao vivo, remaster) → mesma chave. */
    fun songKey(t: Track): String {
        val title = FEAT.replace(BRACKETS.replace(t.title, " "), "")
        return TitleNormalizer.key(ARTIST_NOISE.replace(t.artist, " "), title)
    }

    /** Canal do próprio artista? (pontua a ordem; não exclui ninguém sozinho) */
    fun officialScore(item: StreamInfoItem): Int {
        val up = item.uploaderName.orEmpty()
        var s = 0
        if (up.endsWith(" - Topic", ignoreCase = true)) s += 3
        if (item.isUploaderVerified) s += 2
        if (OFFICIAL_NAME.containsMatchIn(up) || OFFICIAL_NAME.containsMatchIn(item.name)) s += 1
        return s
    }

    fun isJunk(t: Track, durationSec: Long = t.durationSec, query: String? = null): Boolean {
        if (durationSec > 0 && (durationSec < 60 || durationSec > 600)) return true
        val m = JUNK.find(t.title) ?: return false
        return query == null || !query.contains(m.value, ignoreCase = true)   // a pessoa pediu "cover"? então vale
    }

    /** Remove lixo, repetidas e o que já está tocando/na fila. Mantém a ordem recebida. */
    fun clean(list: List<Track>, exclude: Collection<Track> = emptyList()): List<Track> {
        val seenIds = exclude.mapTo(HashSet()) { it.id }
        val seenKeys = exclude.mapTo(HashSet()) { songKey(it) }
        return list.filter { t -> !isJunk(t) && seenIds.add(t.id) && seenKeys.add(songKey(t)) }
    }
}
