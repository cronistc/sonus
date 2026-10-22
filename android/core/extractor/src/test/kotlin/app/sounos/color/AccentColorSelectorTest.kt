package app.sounos.color

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class AccentColorSelectorTest {
    @Test
    fun escolheCorComContrasteNasCapasReais() {
        val directory = File(javaClass.classLoader!!.getResource("covers")!!.toURI())
        val covers = directory.listFiles { file -> file.extension.equals("jpg", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("A coleção precisa conter pelo menos 15 capas", covers.size >= 15)

        var totalNanos = 0L
        val proofItems = mutableListOf<ProofItem>()
        for (file in covers) {
            val image = ImageIO.read(file) ?: error("Imagem inválida: ${file.name}")
            val start = System.nanoTime()
            val result = AccentColorSelector.select(image.width, image.height) { x, y -> image.getRGB(x, y) }
            totalNanos += System.nanoTime() - start
            val repeated = AccentColorSelector.select(image.width, image.height) { x, y -> image.getRGB(x, y) }
            assertEquals("${file.name}: a seleção deve ser determinística", result.color, repeated.color)
            val chosen = result.color
            val chosenHex = "#%06X".format(chosen)
            val borderHex = "#%06X".format(result.borderColor)
            val minimumContrast = AccentColorSelector.minimumBackgroundContrast(chosen)
            println("${file.name}: escolhida $chosenHex; borda $borderHex; ΔE=${"%.1f".format(result.borderDeltaE)}; contraste=${"%.2f".format(minimumContrast)}; pele=${AccentColorSelector.isSkin(chosen)}")
            assertTrue("${file.name}: contraste insuficiente com as bordas (${result.borderDeltaE})", result.borderDeltaE >= AccentColorSelector.MIN_BORDER_DELTA_E)
            assertTrue("${file.name}: contraste insuficiente com um dos temas ($minimumContrast)", minimumContrast >= AccentColorSelector.MIN_BACKGROUND_CONTRAST)
            assertFalse("${file.name}: a cor selecionada parece tom de pele ($chosenHex)", AccentColorSelector.isSkin(chosen))
            proofItems += ProofItem(file.name.removeSuffix(".jpg"), image, chosen)
        }
        println("Tempo médio do seletor: ${totalNanos / covers.size / 1_000_000.0} ms por capa (${covers.size} capas)")
        gerarProva(proofItems)
    }

    private fun gerarProva(items: List<ProofItem>) {
        val columns = 5
        val cardWidth = 220
        val cardHeight = 260
        val imageSize = 190
        val rows = (items.size + columns - 1) / columns
        val canvas = BufferedImage(columns * cardWidth, rows * cardHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = Color(20, 20, 23)
        graphics.fillRect(0, 0, canvas.width, canvas.height)
        graphics.font = Font("SansSerif", Font.PLAIN, 12)
        for ((index, item) in items.withIndex()) {
            val x = index % columns * cardWidth + 15
            val y = index / columns * cardHeight + 12
            graphics.drawImage(item.image, x, y, imageSize, imageSize, null)
            graphics.color = Color(item.color)
            graphics.fillRoundRect(x, y + imageSize + 8, imageSize, 22, 6, 6)
            graphics.color = Color.WHITE
            graphics.drawString(item.title.take(27), x, y + imageSize + 47)
            graphics.drawString("#%06X".format(item.color), x, y + imageSize + 64)
        }
        graphics.dispose()

        var root = File("").absoluteFile
        while (root.parentFile != null && !File(root, "docs").isDirectory) root = root.parentFile
        val destination = File(root, "docs/cores-prova.png")
        check(destination.parentFile?.isDirectory == true) { "Não encontrei a pasta docs para salvar a prova" }
        ImageIO.write(canvas, "png", destination)
        println("Folha de prova: ${destination.absolutePath}")
    }

    private data class ProofItem(val title: String, val image: BufferedImage, val color: Int)
}
