package app.sounos.data

import app.sounos.extractor.Thumb
import app.sounos.extractor.Track
import org.json.JSONArray
import org.json.JSONObject

/** Conversão das músicas entre o app (Kotlin) e a interface (JavaScript). */
object Json {
    fun track(t: Track): JSONObject = JSONObject()
        .put("id", t.id).put("url", t.url).put("title", t.title).put("artist", t.artist)
        .put("artistUrl", t.artistUrl ?: JSONObject.NULL).put("dur", t.durationSec)
        .put("thumb", bestThumb(t) ?: JSONObject.NULL)
        .put("thumbs", JSONArray().apply { t.thumbs.forEach { put(JSONObject().put("url", it.url).put("w", it.width).put("h", it.height)) } })

    fun tracks(list: List<Track>) = JSONArray().apply { list.forEach { put(track(it)) } }

    fun track(o: JSONObject): Track {
        val thumbs = mutableListOf<Thumb>()
        o.optJSONArray("thumbs")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { thumbs += Thumb(it.getString("url"), it.optInt("w"), it.optInt("h")) } }
        if (thumbs.isEmpty()) o.optString("thumb").takeIf { it.isNotBlank() && it != "null" }?.let { thumbs += Thumb(it, 0, 0) }
        val url = o.getString("url")
        return Track(
            id = o.optString("id").ifBlank { Track.idFor(url) },
            url = url,
            title = o.optString("title"),
            artist = o.optString("artist"),
            artistUrl = o.optString("artistUrl").takeIf { it.isNotBlank() && it != "null" },
            durationSec = o.optLong("dur"),
            thumbs = thumbs,
        )
    }

    fun tracks(a: JSONArray?): List<Track> = if (a == null) emptyList() else (0 until a.length()).map { track(a.getJSONObject(it)) }

    /** Capa quadrada maior primeiro (a interface usa só como "pista"; quem baixa e guarda é o CoverStore). */
    fun bestThumb(t: Track): String? =
        t.thumbs.sortedWith(compareByDescending<Thumb> { it.width > 0 && kotlin.math.abs(it.width - it.height) <= 2 }.thenByDescending { it.width * it.height })
            .firstOrNull()?.url
}
