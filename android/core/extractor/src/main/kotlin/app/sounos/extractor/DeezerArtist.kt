package app.sounos.extractor

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Foto de artista de reserva: a API pública do Deezer (sem chave), usada quando o YouTube Music
 * não tem foto (ou só tem o boneco cinza). Só vale um resultado cujo nome é exatamente o do artista.
 */
class DeezerArtist(private val client: OkHttpClient) {
    /** URL da foto do artista, ou null se o Deezer não tem uma com o mesmo nome. */
    fun photo(name: String): String? = runCatching {
        val q = URLEncoder.encode(name, "UTF-8")
        val req = Request.Builder().url("https://api.deezer.com/search/artist?q=$q&limit=5").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            pick(r.body?.string().orEmpty(), name)
        }
    }.getOrNull()

    companion object {
        /** Escolhe, no JSON da busca, a foto do artista de mesmo nome com mais fãs (picture_xl, senão picture_big). */
        fun pick(json: String, name: String): String? {
            val key = CoverStore.artistKey(name)
            if (key.isBlank()) return null
            val data = runCatching { JsonParser.`object`().from(json) }.getOrNull()?.getArray("data") ?: return null
            return data.filterIsInstance<JsonObject>()
                .filter { CoverStore.artistKey(it.getString("name", "")) == key }
                .sortedByDescending { it.getLong("nb_fan", 0) }   // homônimos: o mais ouvido
                .firstNotNullOfOrNull { o ->
                    listOf("picture_xl", "picture_big").map { o.getString(it, "") }
                        .firstOrNull { it.isNotBlank() && !isPlaceholder(it) && CoverStore.allowedUrl(it) }
                }
        }

        /** Foto vazia do Deezer: a URL fica sem o código da imagem ("/images/artist//"). */
        fun isPlaceholder(url: String) = "/images/artist//" in url
    }
}
