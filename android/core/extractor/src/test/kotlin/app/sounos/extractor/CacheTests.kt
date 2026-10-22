package app.sounos.extractor

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheTests {
    private fun tempDir(prefix: String) = java.nio.file.Files.createTempDirectory(prefix).toFile()

    @Test fun `LRU compartilhado remove o arquivo mais antigo entre imagens e metadados`() {
        val root = tempDir("sonus-lru-")
        try {
            val images = File(root, "covers").apply { mkdirs() }
            val metadata = File(root, "metadata").apply { mkdirs() }
            val old = File(images, "old.img").apply { writeBytes(ByteArray(4)); setLastModified(1_000) }
            val recent = File(metadata, "recent.json").apply { writeBytes(ByteArray(4)); setLastModified(2_000) }
            val pinned = File(root, "pinned").apply { mkdirs() }.resolve("kept.img").apply { writeBytes(ByteArray(8)); setLastModified(1) }
            val budget = DiskCacheBudget(listOf(images, metadata), maxBytes = 5)

            val trim = budget.trim()

            assertEquals(4, trim.removedBytes)
            assertFalse(old.exists())
            assertTrue(recent.exists())
            assertTrue(pinned.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `metadados persistem e leitura atualiza o uso LRU`() {
        val root = tempDir("sonus-meta-")
        try {
            val images = File(root, "covers")
            val dir = File(root, "metadata")
            val cache = DiskMetadataCache(dir, DiskCacheBudget(listOf(images, dir), 1024))

            val writeStarted = System.nanoTime()
            cache.write("album:https://exemplo/album", "{\"items\":[]}")
            val writeMs = (System.nanoTime() - writeStarted) / 1_000_000
            val file = dir.listFiles()!!.single()
            file.setLastModified(1_000)
            val readStarted = System.nanoTime()
            assertEquals("{\"items\":[]}", cache.read("album:https://exemplo/album"))
            val readMs = (System.nanoTime() - readStarted) / 1_000_000
            assertTrue(file.lastModified() > 1_000)
            assertNull(cache.read("album:ausente"))
            println("MEDICAO_TESTE cache_metadados gravar_ms=$writeMs ler_ms=$readMs bytes=${file.length()}")
        } finally { root.deleteRecursively() }
    }

    @Test fun `listas pedem miniatura pequena e cache de URL ignora tamanho`() {
        val track = Track("yt:dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "x", "y", null, 1,
            listOf(Thumb("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj", 1200, 1200)))
        val small = CoverStore.candidates(track, browse = true)

        assertEquals("https://lh3.googleusercontent.com/abc=w400-h400-l90-rj", small.first())
        assertFalse(small.any { "w1200" in it || "maxresdefault" in it })
        assertEquals(
            CoverStore.cacheUrlKey("https://lh3.googleusercontent.com/abc=w400-h400-l90-rj"),
            CoverStore.cacheUrlKey("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj"),
        )
        assertEquals(
            CoverStore.cacheUrlKey("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"),
            CoverStore.cacheUrlKey("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"),
        )
    }

    @Test fun `audio cache tem limite de tres GB e capa local e servida sem rede`() {
        val root = tempDir("sonus-cover-")
        try {
            val pinned = File(root, "pinned")
            val browse = File(root, "browse")
            val store = CoverStore(pinned, browse, OkHttpClient())
            val cached = store.browseFile("yt:abc123").apply { writeBytes(ByteArray(64)) }
            val started = System.nanoTime()
            val local = store.local("yt:abc123")
            val localMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(3L * 1024 * 1024 * 1024, AudioCache(File(root, "audio"), OkHttpClient()).maxTotalBytes)
            assertNotNull(local)
            assertEquals(cached, local)
            println("MEDICAO_TESTE capa_cache_local_ms=$localMs bytes=${local!!.length()} rede=0")
        } finally { root.deleteRecursively() }
    }

    @Test fun `migracao mantem downloads fixados e move imagens antigas para o LRU`() {
        val root = tempDir("sonus-migracao-")
        try {
            val store = CoverStore(File(root, "pinned"), File(root, "browse"), OkHttpClient())
            val kept = store.pinnedFile("yt:download").apply { writeBytes(ByteArray(8)) }
            val old = store.pinnedFile("yt:heard").apply { writeBytes(ByteArray(12)) }

            val result = store.migrateLegacyPinned(setOf("yt:download"))

            assertEquals(1, result.first)
            assertTrue(kept.exists())
            assertFalse(old.exists())
            assertTrue(store.browseFile("yt:heard").exists())
        } finally { root.deleteRecursively() }
    }
}
