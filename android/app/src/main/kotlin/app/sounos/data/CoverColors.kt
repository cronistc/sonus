package app.sounos.data

import android.graphics.BitmapFactory
import android.graphics.Color
import app.sounos.color.AccentColorSelector
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Cores da capa: [0..2] = as 3 predominantes (fundo Gradiente); [3] = cor de destaque para a barra e o anel.
 * A última cor é selecionada pelo AccentColorSelector a partir das bordas e do centro da capa.
 */
object CoverColors {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    fun of(file: File): List<String> = cache.getOrPut(file.path) { fromDisk(file) ?: compute(file).also { toDisk(file, it) } }

    /** A capa desse arquivo foi trocada: esquece as cores calculadas. */
    fun forget(file: File) { cache.remove(file.path); runCatching { diskFile(file).delete() } }

    // guardadas em disco (files/cores): a mesma capa nunca é analisada duas vezes, nem depois de reabrir o app.
    // Se a capa for trocada (arquivo mais novo), recalcula.
    private fun diskFile(file: File) = File(file.parentFile?.parentFile ?: file.parentFile, "cores2/" + Integer.toHexString(file.path.hashCode()) + ".txt")

    private fun fromDisk(file: File): List<String>? = runCatching {
        val d = diskFile(file)
        if (!d.exists() || d.lastModified() < file.lastModified()) return null
        d.readText().split(",").takeIf { it.size == 4 && it.all { c -> c.startsWith("#") } }
    }.getOrNull()

    @Volatile private var oldDirGone = false
    private fun toDisk(file: File, colors: List<String>) {
        // "cores/" era da regra antiga (que podia inventar uma cor): apaga uma vez
        if (!oldDirGone) { oldDirGone = true; runCatching { File(diskFile(file).parentFile?.parentFile, "cores").deleteRecursively() } }
        runCatching { diskFile(file).apply { parentFile?.mkdirs() }.writeText(colors.joinToString(",")) }
    }

    private fun compute(file: File): List<String> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        opts.inSampleSize = maxOf(1, min(opts.outWidth, opts.outHeight) / 48)
        opts.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return FALLBACK
        val buckets = HashMap<Int, FloatArray>()     // chave → [peso, r, g, b, n]
        val hsv = FloatArray(3)
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val c = bmp.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            if (hsv[2] < .12f || (hsv[1] < .08f && hsv[2] > .92f)) continue
            val key = ((Color.red(c) shr 5) shl 6) or ((Color.green(c) shr 5) shl 3) or (Color.blue(c) shr 5)
            val b = buckets.getOrPut(key) { FloatArray(5) }
            b[0] += .25f + hsv[1] * hsv[2]; b[1] += Color.red(c).toFloat(); b[2] += Color.green(c).toFloat(); b[3] += Color.blue(c).toFloat(); b[4]++
        }
        val accent = try {
            AccentColorSelector.select(bmp.width, bmp.height) { x, y -> bmp.getPixel(x, y) and 0xFFFFFF }.color
        } finally {
            bmp.recycle()
        }
        val ranked = buckets.values.sortedByDescending { it[0] }
            .map { Color.rgb((it[1] / it[4]).toInt(), (it[2] / it[4]).toInt(), (it[3] / it[4]).toInt()) }
        val picked = mutableListOf<Int>()
        for (c in ranked) {
            if (picked.none { distance(it, c) < 70 }) picked += c
            if (picked.size == 3) break
        }
        while (picked.size < 3) picked += if (picked.isEmpty()) Color.parseColor("#2f6bff") else shade(picked.last())
        return (picked + accent).map { String.format("#%06X", 0xFFFFFF and it) }
    }

    private fun distance(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
    private fun shade(c: Int): Int { val h = FloatArray(3); Color.colorToHSV(c, h); h[0] = (h[0] + 35) % 360; h[2] = minOf(1f, h[2] * 1.15f); return Color.HSVToColor(h) }
    private val FALLBACK = listOf("#2F6BFF", "#1D4ED8", "#60A5FA", "#E748C8")
}
