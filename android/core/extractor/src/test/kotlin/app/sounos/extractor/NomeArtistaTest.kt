package app.sounos.extractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NomeArtistaTest {
    @Test fun conectivosSaoOMesmoArtista() {
        assertTrue(CoverStore.mesmoNome("Henrique e Juliano", "Henrique & Juliano"))
        assertTrue(CoverStore.mesmoNome("Jorge & Mateus", "Jorge e Mateus"))
        assertTrue(CoverStore.mesmoNome("Simon and Garfunkel", "Simon & Garfunkel"))
        assertTrue(CoverStore.mesmoNome("Zé Neto e Cristiano", "Ze Neto & Cristiano"))
        assertTrue(CoverStore.mesmoNome("Earth, Wind & Fire", "Earth, Wind and Fire"))
        assertTrue(CoverStore.mesmoNome("Djavan", "DJAVAN"))
    }
    @Test fun nomesDiferentesContinuamDiferentes() {
        assertFalse(CoverStore.mesmoNome("Henrique e Juliano", "Henrique"))
        assertFalse(CoverStore.mesmoNome("Marisa Monte", "Tribalistas"))
        assertFalse(CoverStore.mesmoNome("Djavan", "Café & Djavan"))
        // "e" dentro de palavra não é conectivo
        assertFalse(CoverStore.mesmoNome("Elis Regina", "Elis Regin"))
    }
}
