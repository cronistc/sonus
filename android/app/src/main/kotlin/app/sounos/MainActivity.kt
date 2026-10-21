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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

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
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)   // sai sem parar a música
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        SounosApp.instance.player.saveSession()
        SounosApp.instance.updater.run { foreground = false; installIfIdle() }     // atualização pronta: instala agora que o app saiu da frente
    }
    override fun onResume() {
        super.onResume()
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
            if (path.startsWith("cover/")) return cover(path.removePrefix("cover/"), url.getQueryParameter("u"), url.getQueryParameter("pin") == "1")
            return try {
                WebResourceResponse(mimeOf(path), "utf-8", assets.open(path))
            } catch (e: IOException) { notFound() }
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != HOST
    }

    /** Capa: do disco se já guardada; senão baixa na maior qualidade (e guarda). */
    private fun cover(id: String, hint: String?, pin: Boolean): WebResourceResponse {
        val app = SounosApp.instance
        if (!CoverStore.validId(id)) return notFound()
        // já guardada: serve na hora (fixada e pequena → passa pelo get, que troca pela grande e fixa)
        val file: File? = app.covers.local(id)?.takeUnless { pin && (app.covers.isSmall(it) || app.covers.pinnedFile(id).length() == 0L) } ?: run {
            val videoId = id.removePrefix("yt:").takeIf { id.startsWith("yt:") }
            val track = Track(id, videoId?.let { "https://www.youtube.com/watch?v=$it" } ?: "", "", "", null, 0,
                listOfNotNull(hint?.let { Thumb(it, 0, 0) }))
            runCatching { runBlocking { app.covers.get(track, pin) } }.getOrNull() ?: app.covers.local(id)
        }
        if (file == null || file.length() == 0L) return notFound()
        val head = file.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
        val mime = when {
            head[0] == 0xFF.toByte() -> "image/jpeg"
            head[0] == 0x89.toByte() -> "image/png"
            String(head, 8, 4) == "WEBP" -> "image/webp"
            else -> "image/*"
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "max-age=31536000"), file.inputStream())
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())

    private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val PICK_IMAGE = 7
    }
}
