package app.sounos.color

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Seleciona uma cor de destaque com base em amostras da capa, sem depender de APIs Android. */
object AccentColorSelector {
    const val MIN_BORDER_DELTA_E = 18.0
    const val MIN_BACKGROUND_CONTRAST = 3.0
    private const val GRID = 32
    private const val EDGE_FRACTION = .14
    private const val MIN_CHROMA = .075
    private const val BACKGROUND_DARK = 0x0E0E10
    private const val BACKGROUND_LIGHT = 0xFFFFFF

    data class Result(val color: Int, val borderColor: Int, val borderDeltaE: Double)

    /** `pixelAt` recebe coordenadas da imagem e devolve RGB no formato 0xRRGGBB. */
    fun select(width: Int, height: Int, pixelAt: (Int, Int) -> Int): Result {
        if (width <= 0 || height <= 0) return fallbackResult()

        // LinkedHashMap conserva a ordem das primeiras amostras para desempate determinístico.
        val center = LinkedHashMap<Int, Bucket>()
        val border = LinkedHashMap<Int, Bucket>()
        for (gy in 0 until GRID) {
            val y = (((gy + .5) * height / GRID).toInt()).coerceIn(0, height - 1)
            for (gx in 0 until GRID) {
                val x = (((gx + .5) * width / GRID).toInt()).coerceIn(0, width - 1)
                val color = pixelAt(x, y) and 0xFFFFFF
                val onBorder = gx < GRID * EDGE_FRACTION || gx >= GRID * (1 - EDGE_FRACTION) ||
                    gy < GRID * EDGE_FRACTION || gy >= GRID * (1 - EDGE_FRACTION)
                val groups = if (onBorder) border else center
                val key = ((red(color) shr 4) shl 8) or ((green(color) shr 4) shl 4) or (blue(color) shr 4)
                groups.getOrPut(key) { Bucket() }.add(color)
            }
        }

        val borderDominant = border.values.maxByOrNull { it.count }?.color() ?: return fallbackResult()
        val edgeLab = toOklab(borderDominant)
        val candidates = center.values.asSequence()
            .filter { it.count >= 2 }
            .map { it.color() }
            .filterNot(::isSkin)
            .map { color ->
                val lab = toOklab(color)
                val chroma = sqrt(lab.a * lab.a + lab.b * lab.b)
                val edgeDelta = deltaE(lab, edgeLab)
                val darkDelta = deltaE(lab, toOklab(BACKGROUND_DARK))
                val lightDelta = deltaE(lab, toOklab(BACKGROUND_LIGHT))
                val contrast = minimumBackgroundContrast(color)
                Candidate(color, chroma, edgeDelta, min(darkDelta, lightDelta), contrast)
            }
            .filter { it.chroma >= MIN_CHROMA && it.edgeDelta >= MIN_BORDER_DELTA_E }
            .filter { it.backgroundDelta >= 22.0 && it.contrast >= MIN_BACKGROUND_CONTRAST }
            .toList()

        val chosen = candidates.maxByOrNull { it.score }?.color ?: derivedColor(edgeLab)
        return Result(chosen, borderDominant, deltaE(toOklab(chosen), edgeLab))
    }

    fun isSkin(color: Int): Boolean {
        val r = red(color).toDouble()
        val g = green(color).toDouble()
        val b = blue(color).toDouble()
        val y = .299 * r + .587 * g + .114 * b
        val cb = 128.0 - .168736 * r - .331264 * g + .5 * b
        val cr = 128.0 + .5 * r - .418688 * g - .081312 * b
        val ycbcrSkin = cb in 77.0..132.0 && cr in 125.0..180.0 && y in 65.0..245.0 && r >= g && g >= b * .78

        val maxChannel = max(r, max(g, b)) / 255.0
        val minChannel = min(r, min(g, b)) / 255.0
        val saturation = if (maxChannel == 0.0) 0.0 else (maxChannel - minChannel) / maxChannel
        val hue = hue(r / 255.0, g / 255.0, b / 255.0)
        val skinHue = hue in 10.0..50.0 && saturation in .12.. .82 && maxChannel in .25.. .98 && r >= g && g >= b * .78
        return ycbcrSkin || skinHue
    }

    fun deltaE(first: Int, second: Int): Double = deltaE(toOklab(first), toOklab(second))

    fun minimumBackgroundContrast(color: Int): Double =
        min(contrastRatio(color, BACKGROUND_DARK), contrastRatio(color, BACKGROUND_LIGHT))

    private fun derivedColor(edge: Lab): Int {
        val hue = atan2(edge.b, edge.a)
        var best: Candidate? = null
        // Croma e matiz saem da cor da borda; L é ajustado para manter contraste nos dois temas.
        for (rotationDegrees in doubleArrayOf(150.0, 165.0, 180.0, 195.0)) {
            val targetHue = hue + rotationDegrees * PI / 180.0
            for (lightness in doubleArrayOf(.48, .51, .54, .57, .60, .63)) {
                for (chroma in doubleArrayOf(.24, .22, .20, .18, .16, .14, .12)) {
                    val color = fromOklab(Lab(lightness, chroma * cos(targetHue), chroma * sin(targetHue))) ?: continue
                    if (isSkin(color)) continue
                    val edgeDelta = deltaE(toOklab(color), edge)
                    val contrast = minimumBackgroundContrast(color)
                    if (edgeDelta < MIN_BORDER_DELTA_E || contrast < MIN_BACKGROUND_CONTRAST) continue
                    val lab = toOklab(color)
                    val candidate = Candidate(color, chroma, edgeDelta, min(
                        deltaE(lab, toOklab(BACKGROUND_DARK)),
                        deltaE(lab, toOklab(BACKGROUND_LIGHT)),
                    ), contrast)
                    if (best == null || candidate.score > best.score) best = candidate
                }
            }
        }
        best?.let { return it.color }

        // Reserva cores seguras se a cor de borda estiver fora da gama RGB representável.
        return intArrayOf(0xE748C8, 0x2385E8, 0x2DBF85, 0xD98D00)
            .filterNot(::isSkin)
            .filter { color ->
                deltaE(toOklab(color), edge) >= MIN_BORDER_DELTA_E &&
                    minimumBackgroundContrast(color) >= MIN_BACKGROUND_CONTRAST
            }
            .maxByOrNull { deltaE(toOklab(it), edge) }
            ?: 0xE748C8
    }

    private fun fallbackResult(): Result {
        val color = 0xE748C8
        return Result(color, 0, deltaE(color, 0))
    }

    private data class Candidate(
        val color: Int,
        val chroma: Double,
        val edgeDelta: Double,
        val backgroundDelta: Double,
        val contrast: Double,
    ) {
        val score: Double
            get() = chroma * (.52 * min(1.0, edgeDelta / 48.0) + .30 * min(1.0, backgroundDelta / 45.0) +
                .18 * min(1.0, (contrast - 1.0) / 5.0))
    }

    private data class Bucket(var count: Int = 0, var red: Int = 0, var green: Int = 0, var blue: Int = 0) {
        fun add(color: Int) {
            count++
            red += (color shr 16) and 0xFF
            green += (color shr 8) and 0xFF
            blue += color and 0xFF
        }

        fun color() = rgb(red / count, green / count, blue / count)
    }

    private data class Lab(val l: Double, val a: Double, val b: Double)

    private fun toOklab(color: Int): Lab {
        val r = linear(red(color) / 255.0)
        val g = linear(green(color) / 255.0)
        val b = linear(blue(color) / 255.0)
        val l = cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
        val m = cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
        val s = cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
        return Lab(
            .2104542553 * l + .7936177850 * m - .0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
            .0259040371 * l + .7827717662 * m - .8086757660 * s,
        )
    }

    private fun fromOklab(lab: Lab): Int? {
        val l0 = lab.l + .3963377774 * lab.a + .2158037573 * lab.b
        val m0 = lab.l - .1055613458 * lab.a - .0638541728 * lab.b
        val s0 = lab.l - .0894841775 * lab.a - 1.2914855480 * lab.b
        val l = l0 * l0 * l0
        val m = m0 * m0 * m0
        val s = s0 * s0 * s0
        val r = 4.0767416621 * l - 3.3077115913 * m + .2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - .3413193965 * s
        val b = -.0041960863 * l - .7034186147 * m + 1.7076147010 * s
        if (r !in 0.0..1.0 || g !in 0.0..1.0 || b !in 0.0..1.0) return null
        return rgb(gamma(r), gamma(g), gamma(b))
    }

    private fun deltaE(first: Lab, second: Lab): Double {
        val dl = first.l - second.l
        val da = first.a - second.a
        val db = first.b - second.b
        return 100.0 * sqrt(dl * dl + da * da + db * db)
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + .05) / (min(a, b) + .05)
    }

    private fun luminance(color: Int): Double =
        .2126 * linear(red(color) / 255.0) + .7152 * linear(green(color) / 255.0) + .0722 * linear(blue(color) / 255.0)

    private fun linear(value: Double): Double = if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    private fun gamma(value: Double): Int = ((if (value <= .0031308) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - .055) * 255.0).roundToInt()
    private fun hue(r: Double, g: Double, b: Double): Double {
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val d = high - low
        if (d == 0.0) return 0.0
        val value = when (high) {
            r -> 60.0 * (((g - b) / d) % 6.0)
            g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }
        return if (value < 0.0) value + 360.0 else value
    }

    private fun red(color: Int) = (color shr 16) and 0xFF
    private fun green(color: Int) = (color shr 8) and 0xFF
    private fun blue(color: Int) = color and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
