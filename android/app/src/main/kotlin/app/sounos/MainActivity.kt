package app.sounos

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import android.widget.FrameLayout
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
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
    private var coldOpening = false
    private var openingDy = 0f                   // deslocamento vertical da palavra (alinha com o splash do sistema)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE) return
        fileCallback?.onReceiveValue(data?.data?.takeIf { resultCode == RESULT_OK }?.let { arrayOf(it) })
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // onCreate só ocorre numa criação nova; voltar do segundo plano usa onResume e não repete a abertura.
        coldOpening = true
        val theme = SounosApp.instance.library.settings().optString("theme", "gradient")
        val windowColor = if (theme == "light") Color.rgb(244, 244, 246) else Color.rgb(14, 14, 16)
        window.statusBarColor = windowColor
        window.navigationBarColor = windowColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (theme == "light") {
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else 0
        }
        web = WebView(this).apply {
            setBackgroundColor(windowColor)
            alpha = 0f
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
        bridge = Bridge(web, ::batteryOptimizationIgnored, ::requestBatteryOptimization).also { it.attach() }
        web.addJavascriptInterface(bridge, "SounosNative")
        val shell = FrameLayout(this).apply {
            background = if (theme == "gradient" && !coldOpening) GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(32, 67, 152), Color.rgb(17, 38, 79), Color.rgb(14, 14, 16)),
            ) else android.graphics.drawable.ColorDrawable(windowColor)
            addView(web, FrameLayout.LayoutParams(-1, -1))
            if (coldOpening) {
                // Mantém a primeira palavra visível enquanto a interface e o desenho vetorial preparam o primeiro quadro.
                // mesmo desenho e mesma posição do splash do sistema: a palavra não pula de um para o outro
                addView(android.widget.ImageView(this@MainActivity).apply {
                    tag = "sonus-opening-word"
                    setImageResource(R.drawable.splash_sonus)
                    // o splash do sistema centra na TELA; esta área começa abaixo da barra de status: corrige a
                    // diferença para a palavra não "descer" na troca
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        val loc = IntArray(2); (v.parent as android.view.View).getLocationOnScreen(loc)
                        val parentH = (v.parent as android.view.View).height
                        openingDy = resources.displayMetrics.heightPixels.let { h ->
                            val real = runCatching { windowManager.currentWindowMetrics.bounds.height() }.getOrDefault(h)
                            real / 2f - (loc[1] + parentH / 2f)
                        }
                        v.translationY = openingDy
                    }
                    if (theme == "light") setColorFilter(Color.rgb(17, 17, 20))
                }, FrameLayout.LayoutParams((288 * resources.displayMetrics.density).toInt(), (288 * resources.displayMetrics.density).toInt(), Gravity.CENTER))
            }
        }
        setContentView(shell)
        if (coldOpening || savedInstanceState == null) web.loadUrl("https://$HOST/home.html") else web.restoreState(savedInstanceState)

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
        web.evaluateJavascript("window.dispatchEvent(new Event('sounos:resume'))", null)
        SounosApp.instance.updater.run { foreground = true; maybeCheck() }
    }

    override fun onDestroy() {
        bridge.detach()
        web.destroy()
        super.onDestroy()
    }

    private fun batteryOptimizationIgnored(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryOptimization(manual: Boolean) {
        if (batteryOptimizationIgnored()) return
        val prefs = getSharedPreferences("sounos_battery", MODE_PRIVATE)
        if (!manual && prefs.getBoolean("pedido", false)) return
        prefs.edit().putBoolean("pedido", true).apply()
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { android.util.Log.w("Sonus", "pedido para tocar sem interrupções falhou", it) }
    }

    private inner class AssetClient : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (android.net.Uri.parse(url).host != HOST) return
            val path = android.net.Uri.parse(url).path.orEmpty()
            if (path != "/home.html") {
                web.alpha = 1f
                return
            }
            if (!coldOpening) {
                web.alpha = 1f
                return
            }
            coldOpening = false
            web.evaluateJavascript("window.__sonusOpeningLaunch&&window.__sonusOpeningLaunch(true,${openingDy / resources.displayMetrics.density})", null)
        }

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

    // cada capa avisa a tela no máximo uma vez por sessão (avisar sempre virava um laço: a tela recarregava,
    // pedia de novo, a capa "mudava" de novo… e piscava sem parar)
    private val coverNotified = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun notifyCoverReady(id: String) {
        if (!::web.isInitialized || isFinishing || !coverNotified.add(id)) return
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
