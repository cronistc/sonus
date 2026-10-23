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

    @Test fun `participacoes com e e virgula viram nomes separados`() {
        assertEquals(listOf("Major Lazer", "Justin Bieber", "MØ"), TitleNormalizer.clean("Cold Water (part. Justin Bieber e MØ)", "Major Lazer", official = true).artists)
        assertEquals(listOf("LSD", "Sia", "Diplo", "Labrinth"), TitleNormalizer.clean("Genius (feat. Sia, Diplo & Labrinth)", "LSD - Topic").artists)
        assertEquals(listOf("Zezé Di Camargo & Luciano"), TitleNormalizer.splitList("Zezé Di Camargo & Luciano"))
        assertEquals(listOf("Raça Negra", "Péricles"), TitleNormalizer.splitList("Raça Negra, Péricles", loneAnd = false))
        assertEquals(listOf("Earth, Wind & Fire", "Ramsey Lewis"), TitleNormalizer.splitList("Earth, Wind & Fire, Ramsey Lewis", loneAnd = false))
        assertEquals(listOf("Kendrick Lamar", "Tyler, The Creator"), TitleNormalizer.splitList("Kendrick Lamar e Tyler, The Creator"))
        assertEquals(listOf("Kid Abelha e Os Abóboras Selvagens"), TitleNormalizer.splitList("Kid Abelha e Os Abóboras Selvagens", loneAnd = false))
    }

    @Test fun `musica salva com artistas juntos e corrigida sem internet`() {
        assertEquals("Cold Water" to listOf("Major Lazer", "Justin Bieber", "MØ"),
            TitleNormalizer.repairSaved("Cold Water", listOf("Major Lazer", "Justin Bieber e MØ")))
        assertEquals("É Tarde Demais" to listOf("Raça Negra", "Péricles"), TitleNormalizer.repairSaved("É Tarde Demais", listOf("Raça Negra, Péricles")))
        assertEquals("Ela ké Leitada" to listOf("Mc Gw", "Prod.Nifour"), TitleNormalizer.repairSaved("Ela ké Leitada", listOf("Prod.Nifour", "Mc Gw")))
        assertEquals("Mary Jane" to listOf("VMZ"), TitleNormalizer.repairSaved("Mary Jane | Lyric Vídeo", listOf("VMZ")))
        assertEquals("X" to listOf("Kid Abelha e Os Abóboras Selvagens"), TitleNormalizer.repairSaved("X", listOf("Kid Abelha e Os Abóboras Selvagens")))
    }

    @Test fun `com solto faz parte do nome da musica`() {
        val c = TitleNormalizer.clean("Gin Com Suco De Laranja", "Ebony - Topic")
        assertEquals("Gin Com Suco De Laranja", c.title)
        assertEquals(listOf("Ebony"), c.artists)
    }

    @Test fun `musica oficial com traco nao vira artista`() {
        val c = TitleNormalizer.clean("Evidências - Ao Vivo", "Chitãozinho & Xororó - Topic")
        assertEquals("Chitãozinho & Xororó", c.artist)
        assertEquals("Evidências - Ao Vivo", c.title)
    }

    @Test fun `ruido depois de barra sai do titulo`() {
        assertEquals("Mary Jane", TitleNormalizer.clean("Mary Jane | Lyric Vídeo", "VMZ").title)
        assertEquals("Lois Lane | Versão Guia", TitleNormalizer.clean("Lois Lane | Versão Guia", "VMZ").title)
    }

    @Test fun `produtor vai para o fim da lista`() {
        assertEquals(listOf("Mc Gw", "Prod.Nifour"), TitleNormalizer.producersLast(listOf("Prod.Nifour", "Mc Gw")))
        assertEquals(listOf("Prod.Nifour"), TitleNormalizer.producersLast(listOf("Prod.Nifour")))
    }

    @Test fun `chave de artista nao junta nomes diferentes`() {
        assertEquals("mo", CoverStore.artistKey("MØ"))
        assertEquals("galcosta", CoverStore.artistKey("Gal Costa"))
        val iu = CoverStore.artistKey("아이유")
        assertTrue(iu.startsWith("u") && iu.length > 1)
        assertTrue(iu != CoverStore.artistKey("宇多田ヒカル"))
        assertTrue(CoverStore.validId("artist:$iu"))
        assertTrue(CoverStore.isPlaceholderPhoto("https://lh3.googleusercontent.com/25G_BXEqKPiyGZRRRGdIHaGUsVpAxwB7K0IIMkRpURNnPZ_So48IK5xLKCoF6gZ6tGRt2zRW4D61kBQ=w544-h544"))
    }

    @Test fun `foto do Deezer so vale com nome igual e sem placeholder`() {
        val json = """{"data":[
          {"name":"Belchior Cover","picture_xl":"https://cdn-images.dzcdn.net/images/artist/aaa/1000x1000-000000-80-0-0.jpg"},
          {"name":"Belchior","nb_fan":5,"picture_xl":"https://cdn-images.dzcdn.net/images/artist//1000x1000-000000-80-0-0.jpg","picture_big":"https://cdn-images.dzcdn.net/images/artist//500x500-000000-80-0-0.jpg"},
          {"name":"BELCHIOR","nb_fan":900,"picture_xl":"https://cdn-images.dzcdn.net/images/artist/bbb/1000x1000-000000-80-0-0.jpg"}]}"""
        assertEquals("https://cdn-images.dzcdn.net/images/artist/bbb/1000x1000-000000-80-0-0.jpg", DeezerArtist.pick(json, "Belchior"))
        assertEquals(null, DeezerArtist.pick(json, "Outro"))
        assertEquals(null, DeezerArtist.pick("""{"data":[{"name":"X","picture_xl":"https://evil.example/a.jpg"}]}""", "X"))
        assertTrue(CoverStore.allowedUrl("https://cdn-images.dzcdn.net/images/artist/bbb/1000x1000.jpg"))
        assertTrue(!CoverStore.allowedUrl("http://cdn-images.dzcdn.net/a.jpg"))
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
