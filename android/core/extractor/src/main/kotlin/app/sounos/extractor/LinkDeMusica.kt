package app.sounos.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.net.URI
import java.net.URLDecoder

/**
 * Link de outro app de música → a mesma música (ou álbum) no Sonus.
 *
 * Quem não tem Spotify, Deezer, Apple Music, Tidal, YT Music… e toca num link deles abre no Sonus.
 *  1. [ler]: o que o link aponta — título, artista e duração:
 *     YouTube/YT Music: o próprio vídeo · Spotify: metadados da página (og:title, music:musician_description,
 *     music:duration) · Deezer: API pública · Apple Music: iTunes lookup · Tidal e outros: og:title "Artista - Música".
 *     Também artistas (todas as fontes; YouTube: o canal) e playlists públicas (até [MAX_FAIXAS] músicas): Deezer pela API,
 *     Spotify pela página "embed", Apple Music pela página (as ~50 primeiras), YouTube/YT Music pela própria lista.
 *     Links curtos (spotify.link, deezer.page.link, on.soundcloud…) são seguidos até o endereço final.
 *  2. NewPipeSource.[encontrar]: a mesma faixa no YouTube Music — título-base igual, mesmo artista, duração parecida
 *     (a mesma regra da capa oficial, [CapaDaMusica.chaveTitulo]).
 */
class LinkDeMusica(private val client: OkHttpClient) {
    /**
     * tipo: "musica" | "album" | "artista" | "playlist" · videoId/playlistUrl/canalUrl quando o link já é do YouTube ·
     * faixas: as músicas de uma playlist de outro app (cada uma procurada depois, como um link de música)
     */
    data class Pedido(val tipo: String, val titulo: String = "", val artista: String = "", val durSec: Long = 0,
                      val videoId: String? = null, val playlistUrl: String? = null, val servico: String = "",
                      val canalUrl: String? = null, val faixas: List<Pedido> = emptyList())

    fun ler(texto: String): Pedido? {
        val url = primeiraUrl(texto) ?: return null
        sonus(url)?.let { return it }
        youtube(url, host(url))?.let { return it }
        // playlist do Spotify: direto na lista (a página normal nem sempre traz os metadados)
        if (host(url) == "open.spotify.com" && url.contains("/playlist/")) spotifyPlaylist(url)?.let { return it }
        // links curtos e páginas: segue os redirecionamentos e lê a página uma vez só
        val (final, html) = baixar(url) ?: return null
        val h = host(final)
        youtube(final, h)?.let { return it }
        return when {
            (h == "spotify.com" || h.endsWith(".spotify.com")) -> spotify(final, html)
            (h == "deezer.com" || h.endsWith(".deezer.com")) -> deezer(final)
            (h == "apple.com" || h.endsWith(".apple.com")) -> apple(final, html)
            else -> generico(final, html, h)
        }
    }

    // ---------- o próprio Sonus: SITE?m=vídeo | al=lista | ar=canal | pl=ids da playlist | q=busca (tp=artista|album) ----------
    private fun sonus(url: String): Pedido? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (u.host?.lowercase() != SITE_HOST || !(u.path.orEmpty() == SITE_CAMINHO || u.path.orEmpty().startsWith("$SITE_CAMINHO/"))) return null
        val q = u.rawQuery
        val nome = param(q, "n").orEmpty()
        val artista = param(q, "a").orEmpty()
        param(q, "m")?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }?.let { return Pedido("musica", nome, artista, videoId = it, servico = "Sonus") }
        param(q, "al")?.takeIf { Regex("[A-Za-z0-9_-]+").matches(it) }?.let {
            return Pedido("album", nome, artista, playlistUrl = "https://www.youtube.com/playlist?list=$it", servico = "Sonus")
        }
        // playlist do Sonus: os ids das músicas no próprio link, separados por "."
        param(q, "pl")?.let { pl ->
            val ids = pl.split('.').filter { Regex("[A-Za-z0-9_-]{11}").matches(it) }.distinct().take(MAX_FAIXAS_SONUS)
            if (ids.isNotEmpty()) return Pedido("playlist", nome.ifBlank { "Playlist" }, servico = "Sonus", faixas = ids.map { Pedido("musica", videoId = it, servico = "Sonus") })
        }
        param(q, "ar")?.takeIf { Regex("UC[A-Za-z0-9_-]{22}").matches(it) }?.let {
            return Pedido("artista", artista = nome, canalUrl = "https://www.youtube.com/channel/$it", servico = "Sonus")
        }
        if (param(q, "q") == null) return null
        return when (param(q, "tp")) {
            "artista" -> Pedido("artista", artista = nome, servico = "Sonus")
            "album" -> Pedido("album", nome, artista, servico = "Sonus")
            else -> Pedido("musica", nome, artista, servico = "Sonus")
        }
    }

    // ---------- YouTube / YouTube Music: o vídeo é a própria música ----------
    private fun youtube(url: String, host: String): Pedido? {
        if (!(host.endsWith("youtube.com") || host == "youtu.be")) return null
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val v = param(u.rawQuery, "v") ?: if (host == "youtu.be") u.path.trim('/').takeIf { it.isNotBlank() } else null
        val shorts = Regex("/shorts/([A-Za-z0-9_-]{11})").find(u.path.orEmpty())?.groupValues?.get(1)
        val id = (v ?: shorts)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        if (id != null) return Pedido("musica", videoId = id, servico = if (host.startsWith("music.")) "YouTube Music" else "YouTube")
        // canal / perfil do artista: /channel/UC…, /browse/UC… (YT Music), /@nome, /c/nome, /user/nome
        val caminho = u.path.orEmpty()
        Regex("^/(?:channel|browse)/(UC[A-Za-z0-9_-]{22})").find(caminho)?.let {
            return Pedido("artista", canalUrl = "https://www.youtube.com/channel/${it.groupValues[1]}", servico = if (host.startsWith("music.")) "YouTube Music" else "YouTube")
        }
        Regex("^/(@[^/]+|c/[^/]+|user/[^/]+)").find(caminho)?.let {
            return Pedido("artista", canalUrl = "https://www.youtube.com/${it.groupValues[1]}", servico = "YouTube")
        }
        val lista = param(u.rawQuery, "list") ?: return null
        // álbum oficial (OLAK5uy_…) abre a página do álbum; playlist de alguém vira uma playlist (que a pessoa pode salvar)
        val album = lista.startsWith("OLAK5uy_") || lista.startsWith("MPREb")
        return Pedido(if (album) "album" else "playlist", playlistUrl = "https://www.youtube.com/playlist?list=$lista",
            servico = if (host.startsWith("music.")) "YouTube Music" else "YouTube")
    }

    // ---------- Spotify: metadados da página ----------
    private fun spotify(url: String, html: String): Pedido? {
        val titulo = meta(html, "og:title") ?: return null
        val desc = meta(html, "og:description").orEmpty()                       // "Artista · Álbum · Song · 1987"
        val artista = meta(html, "music:musician_description") ?: desc.split(" · ").firstOrNull().orEmpty()
        if (url.contains("/artist/")) return Pedido("artista", artista = titulo, servico = "Spotify")
        if (url.contains("/playlist/")) return spotifyPlaylist(url) ?: Pedido("playlist", titulo, servico = "Spotify")
        val tipo = when { url.contains("/track/") -> "musica"; url.contains("/album/") -> "album"; else -> return null }
        return Pedido(tipo, titulo, artista, meta(html, "music:duration")?.toLongOrNull() ?: 0, servico = "Spotify")
    }

    // playlist pública do Spotify: a página "embed" traz as músicas (até 100) sem login, no JSON __NEXT_DATA__
    private fun spotifyPlaylist(url: String): Pedido? {
        val id = Regex("/playlist/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1) ?: return null
        val (_, html) = baixar("https://open.spotify.com/embed/playlist/$id") ?: return null
        val dados = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val raiz = runCatching { JsonParser.`object`().from(dados) }.getOrNull() ?: return null
        val entidade = procura(raiz, "entity") as? JsonObject ?: return null
        val faixas = entidade.getArray("trackList").orEmpty().mapNotNull { it as? JsonObject }.take(MAX_FAIXAS).map { t ->
            // vários artistas vêm juntos ("A,\u00a0B"): fica o principal, que é o que a busca precisa
            val artista = t.getString("subtitle").orEmpty().replace('\u00a0', ' ').split(",").first().trim()
            Pedido("musica", t.getString("title").orEmpty(), artista, t.getLong("duration") / 1000, servico = "Spotify")
        }.filter { it.titulo.isNotBlank() }
        if (faixas.isEmpty()) return null
        return Pedido("playlist", entidade.getString("name") ?: entidade.getString("title").orEmpty(), entidade.getString("subtitle").orEmpty(),
            servico = "Spotify", faixas = faixas)
    }

    // ---------- Deezer: API pública ----------
    private fun deezer(url: String): Pedido? {
        val m = Regex("/(track|album|artist|playlist)/(\\d+)").find(url) ?: return null
        val j = json("https://api.deezer.com/${m.groupValues[1]}/${m.groupValues[2]}") ?: return null
        if (j.containsKey("error")) return null
        if (m.groupValues[1] == "artist") return Pedido("artista", artista = j.getString("name").orEmpty(), servico = "Deezer")
        if (m.groupValues[1] == "playlist") {
            val faixas = j.getObject("tracks")?.getArray("data").orEmpty().mapNotNull { it as? JsonObject }.take(MAX_FAIXAS).map { t ->
                Pedido("musica", t.getString("title").orEmpty(), t.getObject("artist")?.getString("name").orEmpty(), t.getLong("duration"), servico = "Deezer")
            }
            return Pedido("playlist", j.getString("title").orEmpty(), j.getObject("creator")?.getString("name").orEmpty(), servico = "Deezer", faixas = faixas)
        }
        return Pedido(if (m.groupValues[1] == "track") "musica" else "album", j.getString("title").orEmpty(),
            j.getObject("artist")?.getString("name").orEmpty(), j.getLong("duration"), servico = "Deezer")
    }

    // ---------- Apple Music: iTunes lookup (?i= ou /song/ é a faixa; sem eles, o álbum) ----------
    private fun apple(url: String, html: String): Pedido? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (u.path.orEmpty().contains("/playlist/")) return applePlaylist(html)
        if (u.path.orEmpty().contains("/artist/")) {
            val id = Regex("/(\\d+)$").find(u.path.orEmpty())?.groupValues?.get(1) ?: return null
            val pais = Regex("^/([a-z]{2})/").find(u.path.orEmpty())?.groupValues?.get(1) ?: "br"
            val r = json("https://itunes.apple.com/lookup?id=$id&country=$pais")?.getArray("results")?.getObject(0)?.takeIf { it.isNotEmpty() } ?: return null
            return Pedido("artista", artista = r.getString("artistName").orEmpty(), servico = "Apple Music")
        }
        val faixa = param(u.rawQuery, "i")
        val id = faixa ?: Regex("/(\\d+)$").find(u.path.orEmpty())?.groupValues?.get(1) ?: return null
        val pais = Regex("^/([a-z]{2})/").find(u.path.orEmpty())?.groupValues?.get(1) ?: "br"
        val r = json("https://itunes.apple.com/lookup?id=$id&country=$pais")?.getArray("results")?.getObject(0)?.takeIf { it.isNotEmpty() } ?: return null
        // links novos de faixa: music.apple.com/br/song/nome/123 (sem ?i=)
        return if (faixa != null || u.path.orEmpty().contains("/song/")) Pedido("musica", r.getString("trackName").orEmpty(), r.getString("artistName").orEmpty(), r.getLong("trackTimeMillis") / 1000, servico = "Apple Music")
        else Pedido("album", r.getString("collectionName").orEmpty(), r.getString("artistName").orEmpty(), servico = "Apple Music")
    }

    // playlist do Apple Music: a página traz as primeiras músicas (umas 50, sem login) no JSON serialized-server-data
    private fun applePlaylist(html: String): Pedido? {
        val dados = Regex("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val raiz = runCatching { JsonParser.any().from(dados) }.getOrNull() ?: return null
        val lista = mutableListOf<JsonObject>()
        fun anda(o: Any?) {
            when (o) {
                is JsonObject -> { if (o.getString("itemKind") == "trackLockup") o.getArray("items")?.forEach { (it as? JsonObject)?.let(lista::add) } else o.values.forEach(::anda) }
                is List<*> -> o.forEach(::anda)
            }
        }
        anda(raiz)
        val faixas = lista.distinctBy { it.getString("id") ?: (it.getString("title") + "|" + it.getString("artistName")) }.take(MAX_FAIXAS).map { t ->
            Pedido("musica", t.getString("title").orEmpty(), t.getString("artistName").orEmpty(), t.getLong("duration") / 1000, servico = "Apple Music")
        }.filter { it.titulo.isNotBlank() }
        if (faixas.isEmpty()) return null
        val nome = meta(html, "og:title").orEmpty().replace(Regex("""\s*([–-]|\b(no|on|en|sur|auf|su))\s+Apple Music$"""), "").trim()
        return Pedido("playlist", nome,
            servico = "Apple Music", faixas = faixas)
    }

    // ---------- Tidal, Amazon, SoundCloud, song.link…: og:title "Artista - Música" ----------
    private fun generico(url: String, html: String, host: String): Pedido? {
        val t = meta(html, "og:title") ?: return null
        val artistaMeta = meta(html, "music:musician_description")
        val (artista, titulo) = when {
            artistaMeta != null -> artistaMeta to t
            t.contains(" - ") -> t.substringBefore(" - ").trim() to t.substringAfter(" - ").trim()
            t.contains(" by ") -> t.substringAfter(" by ").trim() to t.substringBefore(" by ").trim()
            else -> "" to t
        }
        if (url.contains("/artist/") || meta(html, "og:type") == "profile" || meta(html, "og:type") == "music.musician")
            return Pedido("artista", artista = artistaMeta ?: t.removeSuffix(" on TIDAL").trim(), servico = host.removePrefix("www.").removePrefix("listen.").substringBefore('.').replaceFirstChar { it.uppercase() })
        val album = url.contains("/album") || meta(html, "og:type") == "music.album"
        return Pedido(if (album) "album" else "musica", titulo.removeSuffix(" on TIDAL").trim(), artista,
            meta(html, "music:duration")?.toLongOrNull() ?: 0,
            servico = host.removePrefix("www.").removePrefix("listen.").substringBefore('.').replaceFirstChar { it.uppercase() })
    }

    // ---------- utilidades ----------
    private fun baixar(url: String): Pair<String, String>? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "pt-BR,pt;q=0.9").build()).execute().use { r ->
            r.request.url.toString() to (r.body?.string()?.take(600_000) ?: "")
        }
    }.getOrNull()

    /** a primeira chave `nome` em qualquer profundidade do JSON */
    private fun procura(o: Any?, nome: String): Any? = when (o) {
        is JsonObject -> o[nome] ?: o.values.firstNotNullOfOrNull { procura(it, nome) }
        is List<*> -> o.firstNotNullOfOrNull { procura(it, nome) }
        else -> null
    }

    private fun json(url: String): JsonObject? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) JsonParser.`object`().from(r.body!!.string()) else null }
    }.getOrNull()

    companion object {
        /** playlist de outro app: no máximo estas músicas são procuradas (cada uma é uma busca); o mesmo limite do link do Sonus */
        const val MAX_FAIXAS = 100
        /** playlist do Sonus: até estas músicas vêm no link (cada uma é um pedido rápido pelo id) */
        const val MAX_FAIXAS_SONUS = 100
        /** o site do Sonus (links de compartilhar): quem tem o app abre direto nele (App Links, assetlinks.json no site) */
        const val SITE_HOST = "cronistc.github.io"
        const val SITE_CAMINHO = "/s"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        /** o primeiro endereço http(s) do texto (compartilhar manda "Ouça X no Spotify: https://…") */
        fun primeiraUrl(texto: String): String? = Regex("""https?://[^\s<>"']+""").find(texto)?.value?.trimEnd('.', ',', ')', ']')

        fun host(url: String) = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")

        fun param(rawQuery: String?, nome: String): String? = rawQuery?.split('&')?.firstNotNullOfOrNull { p ->
            val kv = p.split('=', limit = 2)
            if (kv[0] == nome && kv.getOrElse(1) { "" }.isNotBlank()) runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrNull() else null
        }

        fun meta(html: String, nome: String): String? {
            val n = Regex.escape(nome)
            val r = Regex("""<meta\s+(?:property|name)="$n"\s+content="([^"]*)"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta\s+content="([^"]*)"\s+(?:property|name)="$n"""", RegexOption.IGNORE_CASE).find(html)
            return r?.groupValues?.get(1)?.let(::desescapar)?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun desescapar(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
    }
}
