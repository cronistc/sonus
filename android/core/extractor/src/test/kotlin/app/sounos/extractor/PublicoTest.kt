package app.sounos.extractor

import org.junit.Test
import org.junit.Assert.assertEquals

class PublicoTest {
    @Test fun leOPublicoDoYouTubeMusic() {
        assertEquals(5_450_000L, YtMusicArtist.publico("Artista  •  5,45 mi ouvintes mensais"))
        assertEquals(845_000L, YtMusicArtist.publico("Artista  •  845 mil ouvintes mensais"))
        assertEquals(225_000_000L, YtMusicArtist.publico("Artista  •  225 mi ouvintes mensais"))
        assertEquals(868L, YtMusicArtist.publico("Artista  •  868 inscritos"))
        assertEquals(5L, YtMusicArtist.publico("Artista  •  5 inscritos"))
        assertEquals(0L, YtMusicArtist.publico("Artista"))
        assertEquals(1_200_000L, YtMusicArtist.publico("Artist • 1.2M monthly audience"))
    }
}
