package app.sounos.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.grack.nanojson.JsonParser
import okhttp3.OkHttpClient

/** Testes que não precisam de internet. */
class OfflineTests {

    @Test fun `remove Topic e ruido do titulo`() {
        val c = TitleNormalizer.clean("Trevo (Tu) (Official Video)", "ANAVITÃ\u0093RIA - Topic".replace("Ã\u0093", "Ó"))
        assertEquals("ANAVITÓRIA", c.artist)
        assertEquals("Trevo (Tu)", c.title)
    }

    @Test fun `separa Artista - Musica quando o canal e de terceiros`() {
        val c = TitleNormalizer.clean("Banda Exemplo - Luz da Cidade [Lyrics]", "Canal de Clipes")
        assertEquals("Banda Exemplo", c.artist)
        assertEquals("Luz da Cidade", c.title)
    }

    @Test fun `Musica - Artista nao troca os dois`() {
        val c = TitleNormalizer.clean("Aliança - Tribalistas", "Tribalistas")
        assertEquals("Tribalistas", c.artist)
        assertEquals("Aliança", c.title)
    }

    @Test fun `participacoes saem do titulo e preservam os artistas principais`() {
        val feat = TitleNormalizer.clean("Coração Partido (feat. Camisa 10)", "Menos É Mais - Topic")
        assertEquals("Coração Partido", feat.title)
        assertEquals("Menos É Mais", feat.artist)
        assertEquals(listOf("Menos É Mais", "Camisa 10"), feat.artists)

        val part = TitleNormalizer.clean("Música (part. Dilsinho)", "Sorriso Maroto")
        assertEquals(listOf("Sorriso Maroto", "Dilsinho"), part.artists)
        assertEquals("Música", part.title)

        val com = TitleNormalizer.clean("Música (com Péricles)", "Grupo Exemplo")
        assertEquals(listOf("Grupo Exemplo", "Péricles"), com.artists)
        assertEquals("Música", com.title)

        val ft = TitleNormalizer.clean("Música ft. Ludmilla", "Artista Exemplo")
        assertEquals(listOf("Artista Exemplo", "Ludmilla"), ft.artists)
        assertEquals("Música", ft.title)
    }

    @Test fun `nomes de duplas e bandas nao sao divididos`() {
        assertEquals(listOf("Henrique & Juliano"), TitleNormalizer.clean("X", "Henrique & Juliano").artists)
        assertEquals(listOf("Menos É Mais"), TitleNormalizer.clean("X", "Menos É Mais").artists)
    }

    @Test fun `byline da radio usa somente runs ligados a artistas`() {
        val json = JsonParser.`object`().from(
            """{"runs":[
                {"text":"Menos É Mais","navigationEndpoint":{"browseEndpoint":{"browseId":"UCmenos"}}},
                {"text":" • "},
                {"text":"Camisa 10","navigationEndpoint":{"browseEndpoint":{"browseId":"UCcamisa"}}},
                {"text":" • "},
                {"text":"Álbum","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb123"}}},
                {"text":" • 2024"}
            ]}""",
        )
        val radio = YtMusicRadio(OkHttpClient.Builder().build())
        assertEquals(listOf("Menos É Mais", "Camisa 10"), radio.artistsFromRuns(json.getArray("runs")))
    }

    @Test fun `chave de deduplicacao ignora acento e pontuacao`() {
        assertEquals(TitleNormalizer.key("Maré Alta", "Coletivo-Demo!"), TitleNormalizer.key("mare alta", "coletivo demo"))
    }

    @Test fun `prazo da URL vem do parametro expire com folga de 10 min`() {
        val url = "https://rr1---sn.googlevideo.com/videoplayback?expire=1800000000&itag=251&clen=3456789"
        assertEquals(1_800_000_000_000L - 600_000, NewPipeSource.expiresAt(url))
        assertEquals("3456789", NewPipeSource.queryParam(url, "clen"))
    }

    @Test fun `capa tenta a maior resolucao primeiro e tem alternativas pelo ID`() {
        val t = Track("yt:dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "x", "y", null, 1,
            listOf(Thumb("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 60, 60), Thumb("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", 320, 180)))
        val c = CoverStore.candidates(t)
        assertEquals("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj", c.first())
        assertTrue(c.last().endsWith("/hqdefault.jpg"))
        assertEquals("yt:dQw4w9WgXcQ", Track.idFor(t.url))
    }
}
