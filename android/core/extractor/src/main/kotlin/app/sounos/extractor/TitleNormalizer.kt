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
    // "Mary Jane | Lyric Vídeo": o mesmo ruído depois de uma barra
    private val BAR_NOISE = Regex(
        """\s*\|\s*(official\s*(music\s*)?(video|audio)|lyric(s)?\s*v[ií]deo|v[ií]deo\s*(oficial|clipe)|clipe\s*oficial|""" +
            """[áa]udio\s*oficial|lyrics?|letra|visualizer)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
    // "com" solto só vale entre parênteses: "Gin Com Suco De Laranja" é o nome da música, não uma participação
    private val FEATURE = Regex(
        """(?:[\(\[]\s*(?:feat(?:uring)?\.?|ft\.?|part\.?|com)\s+([^\)\]]+?)\s*[\)\]])|(?:\s+)(?:feat(?:uring)?\.|ft\.|feat|featuring|part\.)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val AND = Regex("""\s+(?:e|and|y)\s+""", RegexOption.IGNORE_CASE)
    /** Crédito de produtor ("Prod.Nifour") não é o artista principal. */
    private val PRODUCER = Regex("""^\s*prod(?:\.|uzido por|uced by)\s*""", RegexOption.IGNORE_CASE)

    data class Clean(val artist: String, val title: String, val artists: List<String> = listOf(artist))

    fun cleanArtist(uploader: String): String = TOPIC.replace(uploader, "").trim()

    /**
     * official = a música veio do YouTube Music (canal "Topic", byline de artista): aí o nome do artista é
     * confiável e "Música - Ao Vivo" nunca é "Artista - Música".
     */
    fun clean(rawTitle: String, rawUploader: String, official: Boolean = TOPIC.containsMatchIn(rawUploader)): Clean {
        var artist = cleanArtist(rawUploader)
        var title = BAR_NOISE.replace(NOISE.replace(rawTitle, ""), "").let { SPACES.replace(it, " ") }.trim()
        val featured = FEATURE.findAll(title).flatMap { match ->
            splitList((match.groups[1]?.value ?: match.groups[2]?.value).orEmpty())
        }.toList()
        title = FEATURE.replace(title, " ").replace(Regex("""[\(\[]\s*[\)\]]"""), " ")
            .let { SPACES.replace(it, " ") }.trim().trimEnd('-', '–', ':', '|').trim()
        // "Artista - Música" quando o canal não é do artista (ex.: canal de clipes ou selo)
        val dash = title.indexOf(" - ")
        if (dash > 0 && !official) {
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

    /**
     * Separa participações: "Justin Bieber e MØ", "Sia, Diplo & Labrinth" → nomes separados.
     * "&" sem vírgula fica junto: é nome de dupla ("Zezé Di Camargo & Luciano").
     * loneAnd = "e" sem vírgula também separa; desligado para nomes de artista vindos do serviço,
     * onde "Kid Abelha e Os Abóboras Selvagens" é uma banda só.
     */
    fun splitList(raw: String, loneAnd: Boolean = true): List<String> {
        // bandas com vírgula no nome ficam inteiras: viram um marcador "\u0000<n>" durante a divisão
        val kept = mutableListOf<String>()
        var text = raw
        for (band in COMMA_BANDS) {
            val at = text.indexOf(band, ignoreCase = true).takeIf { it >= 0 } ?: continue
            kept += text.substring(at, at + band.length)
            text = text.replaceRange(at, at + band.length, "\u0000${kept.size - 1}")
        }
        val restore = { piece: String -> Regex("\u0000(\\d+)").replace(piece) { kept[it.groupValues[1].toInt()] } }
        val commas = text.split(',').map(String::trim).filter(String::isNotBlank)
        if (commas.isEmpty()) return emptyList()
        val list = commas.size > 1
        val last = commas.last().let { if (list) it.split(Regex("""\s+&\s+""")) else listOf(it) }
            .flatMap { if (list || loneAnd) it.split(AND) else listOf(it) }
        return (commas.dropLast(1) + last).map { restore(it).trim() }.filter(String::isNotBlank)
    }

    private val COMMA_BANDS = listOf("Earth, Wind & Fire", "Crosby, Stills, Nash & Young", "Crosby, Stills & Nash", "Tyler, The Creator",
        "Emerson, Lake & Palmer", "Peter, Paul and Mary", "Blood, Sweat & Tears")

    /**
     * Arruma uma música já salva sem internet, com as regras atuais: separa artistas que ficaram juntos
     * ("Raça Negra, Péricles"; participação "Justin Bieber e MØ") e tira o ruído do título.
     */
    fun repairSaved(title: String, artists: List<String>): Pair<String, List<String>> {
        val names = artists.flatMapIndexed { i, name -> splitList(name, loneAnd = i > 0) }
        val fixed = producersLast(mergeArtists(names))
        return BAR_NOISE.replace(title, "").trim().ifBlank { title } to fixed.ifEmpty { artists }
    }

    /** Coloca créditos de produtor depois dos cantores. */
    fun producersLast(names: List<String>): List<String> {
        val (prod, rest) = names.partition { PRODUCER.containsMatchIn(it) }
        return if (rest.isEmpty()) names else rest + prod
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
