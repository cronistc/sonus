package app.sounos.extractor

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** Fonte reserva para a capa de um álbum quando todas as miniaturas do YouTube falham. */
class DeezerAlbum(private val client: OkHttpClient) {
    fun covers(title: String, artist: String): List<String> = runCatching {
        if (title.isBlank()) return emptyList()
        val query = listOf(title, artist).filter(String::isNotBlank).joinToString(" ")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder().url("https://api.deezer.com/search/album?q=$encoded&limit=20").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            pick(response.body?.string().orEmpty(), title, artist)
        }
    }.getOrDefault(emptyList())

    companion object {
        fun pick(json: String, title: String, artist: String): List<String> {
            val titleKey = CoverStore.artistKey(title)
            val artistKey = CoverStore.artistKey(artist)
            if (titleKey.isBlank()) return emptyList()
            val data = runCatching { JsonParser.`object`().from(json).getArray("data") }.getOrNull() ?: return emptyList()
            val albums = data.filterIsInstance<JsonObject>()
                .filter { CoverStore.artistKey(it.getString("title", "")) == titleKey }
                .filter { album ->
                    artistKey.isBlank() || CoverStore.artistKey(album.getObject("artist")?.getString("name", "").orEmpty()) == artistKey
                }
            return albums.flatMap { album ->
                listOf("cover_xl", "cover_big", "cover_medium").map { album.getString(it, "") }
            }.filter { it.isNotBlank() && CoverStore.allowedUrl(it) }
                .distinct()
        }
    }
}
