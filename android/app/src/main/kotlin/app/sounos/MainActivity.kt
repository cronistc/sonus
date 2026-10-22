package app.sounos

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.sounos.extractor.CoverStore
import app.sounos.extractor.Thumb
import app.sounos.extractor.Track
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import org.json.JSONObject

/**
 * Sonus — a interface (HTML/CSS/JS feitos do zero) roda dentro do app num endereço próprio
 * (https://appassets.androidplatform.net), e conversa com o player e o NewPipe pela [Bridge].
 * As capas chegam à interface por /cover/<id>: sempre do disco quando já guardadas (offline, sem sumir).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var fileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE) return
        fileCallback?.onReceiveValue(data?.data?.takeIf { resultCode == RESULT_OK }?.let { arrayOf(it) })
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0E0E10"))
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = AssetClient()
            webChromeClient = object : android.webkit.WebChromeClient() {
                // "trocar capa" da playlist: abre a galeria do celular
                override fun onShowFileChooser(v: WebView, cb: android.webkit.ValueCallback<Array<android.net.Uri>>, p: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = cb
                    val pick = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    return runCatching { startActivityForResult(pick, PICK_IMAGE); true }.getOrElse { fileCallback = null; false }
                }
            }
        }
        bridge = Bridge(web).also { it.attach() }
        web.addJavascriptInterface(bridge, "SounosNative")
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl("https://$HOST/home.html") else web.restoreState(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)   // controles da música na notificação
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // primeiro a interface fecha o que estiver aberto (página, player, busca); senão volta/sai sem parar a música
        web.evaluateJavascript("(window.__sonusBack && window.__sonusBack()) || false") { handled ->
            if (handled != "true") { if (web.canGoBack()) web.goBack() else moveTaskToBack(true) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        bridge.setPageActive(false)
        web.evaluateJavascript("window.__sounosPageActive?.(false)", null)
        web.onPause()
        super.onPause()
        SounosApp.instance.player.saveSession()
        SounosApp.instance.updater.run { foreground = false; installIfIdle() }     // atualização pronta: instala agora que o app saiu da frente
    }
    override fun onResume() {
        super.onResume()
        bridge.setPageActive(true)
        web.onResume()
        web.evaluateJavascript("window.__sounosPageActive?.(true)", null)
        SounosApp.instance.updater.run { foreground = true; maybeCheck() }
    }

    override fun onDestroy() {
        bridge.detach()
        web.destroy()
        super.onDestroy()
    }

    private inner class AssetClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return null
            val path = url.path.orEmpty().trimStart('/').ifEmpty { "home.html" }
            if (path.startsWith("cover/")) return cover(
                path.removePrefix("cover/"), url.getQueryParameter("u"),
                url.getQueryParameter("pin") == "1", url.getQueryParameter("large") == "1", url.getQueryParameter("n"),
            )
            return try {
                WebResourceResponse(mimeOf(path), "utf-8", assets.open(path))
            } catch (e: IOException) { notFound() }
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != HOST
    }

    /** A resposta da rota nunca espera rede: usa disco ou agenda o preenchimento em segundo plano. */
    private fun cover(id: String, hint: String?, pin: Boolean, large: Boolean, artistName: String?): WebResourceResponse {
        val app = SounosApp.instance
        if (!CoverStore.validId(id)) return notFound()
        val started = System.nanoTime()
        if (id.startsWith("artist:")) return artistCover(id, hint, artistName)
        val diskStarted = System.nanoTime()
        val file = app.covers.local(id, hint, large)
        android.util.Log.i("Sonus", "cover rota etapa=disco_ms=${(System.nanoTime() - diskStarted) / 1_000_000} imagem=${file != null} pin=$pin grande=$large")
        val videoId = id.removePrefix("yt:").takeIf { id.startsWith("yt:") }
        val track = Track(id, videoId?.let { "https://www.youtube.com/watch?v=$it" } ?: "", "", "", null, 0,
            listOfNotNull(hint?.let { Thumb(it, 0, 0) }))
        if (file == null) {
            app.io.launch {
                val fetchStarted = System.nanoTime()
                val result = runCatching {
                    app.covers.get(track, pin, large) { android.util.Log.i("Sonus", "cover $it") }
                }.onFailure { android.util.Log.w("Sonus", "cover falhou id=$id", it) }.getOrNull()
                android.util.Log.i("Sonus", "cover rota etapa=preload_ms=${(System.nanoTime() - fetchStarted) / 1_000_000} id=$id cache=${result != null}")
                if (result != null) notifyCoverReady(id)
            }
            android.util.Log.i("Sonus", "cover rota etapa=total_ms=${(System.nanoTime() - started) / 1_000_000} origem=ausente_agendada")
            return notFound()
        }
        if (pin || large || !app.covers.isSmall(file)) app.io.launch {
            val beforeLength = file.length()
            val beforeModified = file.lastModified()
            val beforeSmall = app.covers.isSmall(file)
            val fillStarted = System.nanoTime()
            val result = runCatching {
                app.covers.get(track, pin, large) { android.util.Log.i("Sonus", "cover $it") }
            }.onFailure { android.util.Log.w("Sonus", "cover atualizacao falhou id=$id", it) }.getOrNull()
            android.util.Log.i("Sonus", "cover rota etapa=upgrade_ms=${(System.nanoTime() - fillStarted) / 1_000_000} id=$id cache=${result != null}")
            val replaced = result != null && (result.absolutePath != file.absolutePath || result.length() != beforeLength || result.lastModified() != beforeModified)
            if (replaced && (large || (!beforeSmall && result?.let(app.covers::isSmall) == true))) notifyCoverReady(id)
        }
        val responseStarted = System.nanoTime()
        val response = imageResponse(file)
        android.util.Log.i("Sonus", "cover rota etapa=resposta_ms=${(System.nanoTime() - responseStarted) / 1_000_000} bytes=${file.length()} total_ms=${(System.nanoTime() - started) / 1_000_000} origem=disco")
        return response
    }

    private fun artistCover(id: String, hint: String?, suppliedName: String?): WebResourceResponse {
        val started = System.nanoTime()
        val key = id.removePrefix("artist:")
        val name = suppliedName?.takeIf { CoverStore.artistKey(it) == key } ?: key
        val app = SounosApp.instance
        val lookupStarted = System.nanoTime()
        val local = app.covers.local(id, hint, large = false)
        android.util.Log.i("Sonus", "cover artista etapa=disco_ms=${(System.nanoTime() - lookupStarted) / 1_000_000} imagem=${local != null}")
        if (local == null) {
            app.io.launch {
                val fetchStarted = System.nanoTime()
                val photo = runCatching {
                app.covers.getArtistPhoto(
                    name,
                    hint?.takeIf { it.isNotBlank() },
                    lookup = { query -> app.source.searchArtist(query) { android.util.Log.i("Sonus", it) } },
                    trace = { android.util.Log.i("Sonus", "foto artista $it") },
                )
                }.onFailure { android.util.Log.w("Sonus", "foto artista falhou nome=$key", it) }.getOrNull()
                android.util.Log.i("Sonus", "cover artista etapa=preload_ms=${(System.nanoTime() - fetchStarted) / 1_000_000} cache=${photo?.file != null}")
                if (photo?.file != null) notifyCoverReady(id)
            }
            android.util.Log.i("Sonus", "cover artista etapa=total_ms=${(System.nanoTime() - started) / 1_000_000} origem=ausente_agendada")
            return notFound()
        }
        android.util.Log.i("Sonus", "cover artista etapa=resposta_ms=${(System.nanoTime() - started) / 1_000_000} bytes=${local.length()} origem=disco")
        return imageResponse(local)
    }

    private fun notifyCoverReady(id: String) {
        if (!::web.isInitialized || isFinishing) return
        web.post { web.evaluateJavascript("window.__sounos&&window.__sounos.emit('coverReady',{id:${JSONObject.quote(id)}})", null) }
    }

    private fun imageResponse(file: File): WebResourceResponse {
        val head = file.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
        val mime = when {
            head[0] == 0xFF.toByte() -> "image/jpeg"
            head[0] == 0x89.toByte() -> "image/png"
            String(head, 8, 4) == "WEBP" -> "image/webp"
            else -> "image/*"
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "public, max-age=31536000, immutable"), file.inputStream())
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())

    private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "png" -> "image/png"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val PICK_IMAGE = 7
    }
}
