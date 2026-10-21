package app.sounos.extractor

/**
 * Limpa título e artista vindos do YouTube para exibir e para juntar versões repetidas.
 *  - "Artista - Topic"            → "Artista"
 *  - "Música (Official Video)"    → "Música"
 *  - canal ≠ artista e título "Artista - Música" → separa os dois
 */
object TitleNormalizer {
    private val NOISE = Regex(
        """\s*[\(\[]\s*(official\s*(music\s*)?(video|audio|lyric(s)?\s*video|visualizer)|video\s*oficial|clipe\s*oficial|""" +
            """áudio\s*oficial|audio\s*oficial|lyrics?|letra|legendado|lyric\s*video|visualizer|hd|hq|4k|remastered(\s*\d{4})?|""" +
            """ao\s*vivo|live)\s*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
    private val FEATURE = Regex(
        """(?:[\(\[]\s*(?:feat(?:uring)?\.?|ft\.?|part\.?|com)\s+([^\)\]]+?)\s*[\)\]])|(?:\s+)(?:feat(?:uring)?\.?|ft\.?|part\.?|com)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    data class Clean(val artist: String, val title: String, val artists: List<String> = listOf(artist))

    fun cleanArtist(uploader: String): String = TOPIC.replace(uploader, "").trim()

    fun clean(rawTitle: String, rawUploader: String): Clean {
        var artist = cleanArtist(rawUploader)
        var title = NOISE.replace(rawTitle, "").let { SPACES.replace(it, " ") }.trim()
        val featured = FEATURE.findAll(title).mapNotNull { match ->
            (match.groups[1]?.value ?: match.groups[2]?.value)?.trim()?.takeIf { it.isNotBlank() }
        }.toList()
        title = FEATURE.replace(title, " ").replace(Regex("""[\(\[]\s*[\)\]]"""), " ")
            .let { SPACES.replace(it, " ") }.trim().trimEnd('-', '–', ':', '|').trim()
        // "Artista - Música" quando o canal não é do artista (ex.: canal de clipes ou selo)
        val dash = title.indexOf(" - ")
        if (dash > 0) {
            val left = title.substring(0, dash).trim()
            val right = title.substring(dash + 3).trim()
            when {
                right.equals(artist, ignoreCase = true) -> title = left                  // "Música - Artista"
                left.equals(artist, ignoreCase = true) -> title = right                  // "Artista - Música"
                !title.contains(artist, ignoreCase = true) -> { artist = left; title = right }   // canal de terceiros
            }
        }
        artist = artist.ifBlank { rawUploader.trim() }
        val artists = mergeArtists(listOf(artist), featured)
        return Clean(artist, title.ifBlank { rawTitle.trim() }, artists)
    }

    /** Junta nomes na ordem recebida, removendo repetidos sem dividir nomes de bandas. */
    fun mergeArtists(vararg groups: Iterable<String>): List<String> {
        val seen = mutableSetOf<String>()
        return groups.asSequence().flatMap { it.asSequence() }.map(String::trim).filter { name ->
            name.isNotBlank() && seen.add(name.lowercase())
        }.toList()
    }

    /** Chave para reconhecer a mesma música em uploads diferentes (deduplicação). */
    fun key(artist: String, title: String): String =
        (artist + " " + title).lowercase()
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
            .replace(Regex("""\p{M}"""), "")
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .let { SPACES.replace(it, " ") }.trim()
}
