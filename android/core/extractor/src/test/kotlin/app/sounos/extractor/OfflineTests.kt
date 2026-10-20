package app.sounos.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
