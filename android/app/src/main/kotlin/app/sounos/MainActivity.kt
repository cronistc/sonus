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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.sounos.extractor.CoverStore
import app.sounos.player.PlaybackService
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
    private var ultimaJanela = ""                  // última aparência aplicada às barras/janela (applyWindowTheme)
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
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        // onCreate só ocorre numa criação nova; voltar do segundo plano usa onResume e não repete a abertura.
        // A abertura animada é para o app fechado ou muito tempo sem abrir: com o processo vivo e saída recente
        // (ex.: voltou pelo widget depois de sair com o "voltar"), entra direto.
        coldOpening = ultimaSaida == 0L || System.currentTimeMillis() - ultimaSaida > 30 * 60_000L
        val theme = SounosApp.instance.library.settings().optString("theme", "gradient")
        val windowColor = windowColor(theme)
        // De ponta a ponta (pedido do dono, 27/10/2026): o app desenha atrás da barra de status e da barra de navegação,
        // que ficam transparentes — o fundo do Sonus (gradiente, capa, foto) vai até a borda. O conteúdo fica longe das
        // barras pelas medidas que a interface recebe (--sb-topo / --sb-base, ver aplicarMedidas).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowTheme(theme, opening = true)
        web = WebView(this).apply {
            setBackgroundColor(windowColor)
            alpha = 0f
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // desenho fixo na largura do A54 (meta viewport width=384 em home.html): o WebView amplia/reduz a tela inteira
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.textZoom = 100                          // tamanho da fonte do sistema não deforma o desenho
            settings.allowFileAccess = false
            // Assets locais mudam junto com o APK; o cache do WebView sobrevive à atualização do app.
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
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
        bridge = Bridge(
            web,
            ::batteryOptimizationIgnored,
            ::requestBatteryOptimization,
            ::notificationPermissionGranted,
            ::requestNotificationPermission,
        ).also { it.attach() }
        web.addJavascriptInterface(bridge, "SounosNative")
        val shell = FrameLayout(this).apply {
            background = windowBackground(theme, opening = coldOpening)
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
        // medidas das barras e do teclado: o teclado encolhe a área do app (como o adjustResize fazia); as barras viram
        // espaço reservado dentro da interface
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, ins ->
            val barras = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val teclado = ins.isVisible(WindowInsetsCompat.Type.ime())
            v.setPadding(0, 0, 0, if (teclado) ins.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0)
            aplicarMedidas(barras.top, if (teclado) 0 else barras.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(shell)
        if (coldOpening || savedInstanceState == null) web.loadUrl("https://$HOST/home.html") else web.restoreState(savedInstanceState)
        // aberto por um link de música (a página pega quando estiver pronta); recriada ou reaberta pelos recentes: o link já foi tratado
        if (savedInstanceState == null) { receberLink(intent); abrirDoWidget(intent, if (coldOpening) 3200 else 1400) }

    }

    private fun windowColor(theme: String) = if (theme == "light") Color.rgb(244, 244, 246) else Color.rgb(14, 14, 16)

    private fun windowBackground(
        theme: String,
        opening: Boolean,
        top: Int = if (theme == "gradient" && !opening) Color.rgb(32, 67, 152) else windowColor(theme),
        bottom: Int = windowColor(theme),
    ): android.graphics.drawable.Drawable =
        if (theme == "gradient" && !opening) GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, Color.rgb(17, 38, 79), bottom),
        ) else android.graphics.drawable.ColorDrawable(windowColor(theme))

    /** Barras do sistema (status e navegação) na cor do tema; chamado ao abrir e sempre que o tema muda. */
    fun applyWindowTheme(
        theme: String,
        opening: Boolean = false,
        statusColor: String? = null,
        navigationColor: String? = null,
    ) {
        val color = windowColor(theme)
        // Durante o splash vale o fundo noturno compartilhado por Escuro e Gradiente. Depois da
        // abertura, o Gradiente usa a cor do primeiro ponto do fundo da tela; a navegação acompanha a base.
        val defaultStatus = if (theme == "gradient" && !opening) Color.rgb(32, 67, 152) else color
        val status = statusColor?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: defaultStatus
        val navigation = navigationColor?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: color
        // nada mudou: não mexe na janela de novo. De ponta a ponta as barras são transparentes, então a cor pedida pela
        // tela (que muda a cada música) não muda nada — reaplicar cor/flags/fundo fazia o Android reorganizar a janela
        // inteira (relayoutWindow) a cada troca de música, travando quadros. Só o tema e a abertura contam.
        val chave = "$theme|$opening|${::web.isInitialized}"
        if (chave == ultimaJanela) return
        ultimaJanela = chave
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
        )
        // transparentes: o fundo do app aparece atrás delas (status/navigation continuam valendo para o fundo da janela no splash)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setBackgroundDrawable(windowBackground(theme, opening, status, navigation))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // Materialize o DecorView antes de pedir o InsetsController. No splash, onCreate ainda
        // não passou por setContentView e PhoneWindow.insetsController pode falhar com DecorView nulo.
        val decor = window.decorView
        WindowInsetsControllerCompat(window, decor).apply {
            isAppearanceLightStatusBars = theme == "light"
            isAppearanceLightNavigationBars = theme == "light"
        }
        if (::web.isInitialized) {
            web.setBackgroundColor(color)
            (web.parent as? android.view.View)?.background = windowBackground(theme, opening, status, navigation)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) web.evaluateJavascript("window.dispatchEvent(new Event('sounos:resume'))", null)
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
        ultimaSaida = System.currentTimeMillis()
        bridge.setPageActive(false)
        web.evaluateJavascript("window.__sounosPageActive?.(false)", null)
        web.onPause()
        super.onPause()
        SounosApp.instance.player.saveSession()
        SounosApp.instance.updater.foreground = false
        SounosApp.instance.motor.fechouApp()                       // diário: quanto tempo o app ficou aberto na tela
    }
    override fun onResume() {
        super.onResume()
        bridge.setPageActive(true)
        web.onResume()
        web.evaluateJavascript("window.__sounosPageActive?.(true)", null)
        web.evaluateJavascript("window.dispatchEvent(new Event('sounos:resume'))", null)
        SounosApp.instance.updater.run { foreground = true; maybeCheck() }
        SounosApp.instance.motor.abriuApp()
    }

    override fun onDestroy() {
        bridge.detach()
        web.destroy()
        super.onDestroy()
    }

    private fun batteryOptimizationIgnored(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

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
            if (path.startsWith("cover/") && url.getQueryParameter("mini") == "1") return coverMini(path.removePrefix("cover/"), url.getQueryParameter("u"))
            if (path.startsWith("cover/")) return cover(
                path.removePrefix("cover/"), url.getQueryParameter("u"),
                url.getQueryParameter("pin") == "1", url.getQueryParameter("large") == "1", url.getQueryParameter("n"), url.getQueryParameter("a"),
            )
            return try {
                WebResourceResponse(mimeOf(path), "utf-8", assets.open(path))
            } catch (e: IOException) { notFound() }
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != HOST
    }

    /** Capinha (linhas da busca): qualquer capa guardada que baste; senão baixa só a pequena e avisa a tela. */
    private fun coverMini(id: String, hint: String?): WebResourceResponse {
        if (!CoverStore.validId(id) || id.startsWith("artist:")) return notFound()
        val app = SounosApp.instance
        app.covers.localMini(id, hint)?.let { return imageResponse(it) }
        val videoId = id.removePrefix("yt:").takeIf { id.startsWith("yt:") }
        val track = Track(id, videoId?.let { "https://www.youtube.com/watch?v=$it" } ?: "", "", "", null, 0, listOfNotNull(hint?.let { Thumb(it, 0, 0) }))
        umaBusca("$id|mini") { if (runCatching { app.covers.getMini(track) }.getOrNull() != null) notifyCoverReady(id, false) }
        return notFound()
    }

    /** A resposta da rota nunca espera rede: usa disco ou agenda o preenchimento em segundo plano. */
    private fun cover(id: String, hint: String?, pin: Boolean, large: Boolean, name: String?, albumArtist: String?): WebResourceResponse {
        val app = SounosApp.instance
        if (!CoverStore.validId(id)) return notFound()
        val started = System.nanoTime()
        if (id.startsWith("artist:")) return artistCover(id, hint, name)
        val diskStarted = System.nanoTime()
        val file = app.covers.local(id, hint, large)
        android.util.Log.i("Sonus", "cover rota etapa=disco_ms=${(System.nanoTime() - diskStarted) / 1_000_000} imagem=${file != null} pin=$pin grande=$large")
        val videoId = id.removePrefix("yt:").takeIf { id.startsWith("yt:") }
        val album = id.startsWith("album:")
        val track = Track(id, videoId?.let { "https://www.youtube.com/watch?v=$it" } ?: "", if (album) name.orEmpty() else "", if (album) albumArtist.orEmpty() else "", null, 0,
            listOfNotNull(hint?.let { Thumb(it, 0, 0) }))
        // capinha de lista: aparece já com a imagem que veio; "clipe ou música?" é conferido DEPOIS (conferirDepois)
        val esperar = large || pin
        if (file == null) {
            umaBusca("$id|$pin|$large") {
                val fetchStarted = System.nanoTime()
                val result = runCatching {
                    app.covers.get(track, pin, large, conferirAntes = esperar) { android.util.Log.i("Sonus", "cover $it") }
                }.onFailure { android.util.Log.w("Sonus", "cover falhou id=$id", it) }.getOrNull()
                android.util.Log.i("Sonus", "cover rota etapa=preload_ms=${(System.nanoTime() - fetchStarted) / 1_000_000} id=$id cache=${result != null}")
                if (result != null) notifyCoverReady(id, large)
                if (!esperar) conferirDepois(track, pin, large)
            }
            android.util.Log.i("Sonus", "cover rota etapa=total_ms=${(System.nanoTime() - started) / 1_000_000} origem=ausente_agendada")
            return notFound()
        }
        val beforeResolution = app.covers.resolution(file)
        // precisaConferir: clipe cuja capa oficial (versão em música) ainda não foi conferida/instalada
        val conferia = app.covers.precisaConferir(id)
        if (pin || large || !app.covers.meetsMinimum(file, large) || conferia) umaBusca("$id|$pin|$large") {
            val beforeLength = file.length()
            val beforeModified = file.lastModified()
            val fillStarted = System.nanoTime()
            val result = runCatching {
                app.covers.get(track, pin, large, conferirAntes = true) { android.util.Log.i("Sonus", "cover $it") }
            }.onFailure { android.util.Log.w("Sonus", "cover atualizacao falhou id=$id", it) }.getOrNull()
            android.util.Log.i("Sonus", "cover rota etapa=upgrade_ms=${(System.nanoTime() - fillStarted) / 1_000_000} id=$id cache=${result != null}")
            // melhorou de resolução OU virou outra imagem (capa oficial no lugar da que veio com o clipe)
            // (não compara a data: ler a capa do cache já atualiza a data do arquivo — daria aviso sem fim)
            val improved = result != null && (app.covers.resolution(result) > beforeResolution ||
                (result == file && result.length() != beforeLength) || (conferia && !app.covers.precisaConferir(id)))
            if (improved) android.util.Log.i("Sonus", "cover rota qualidade id=$id antes=$beforeResolution depois=${app.covers.resolution(result!!)} bytes_antes=$beforeLength bytes_depois=${result.length()} alterado=${result.lastModified() != beforeModified}")
            if (improved) notifyCoverReady(id, large)
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
        val beforeResolution = local?.let(app.covers::resolution) ?: 0
        val beforeLength = local?.length() ?: 0
        val beforeModified = local?.lastModified() ?: 0
        android.util.Log.i("Sonus", "cover artista etapa=disco_ms=${(System.nanoTime() - lookupStarted) / 1_000_000} imagem=${local != null}")
        // foto marcada para conferir (regras novas): mostra a atual e confere em segundo plano
        val recheck = local != null && app.covers.artistNeedsRecheck(key)
        val needsUpgrade = local != null && beforeResolution < 226
        if (local == null || recheck || needsUpgrade) {
            umaBusca(id) {
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
                val updated = photo?.file
                val changed = updated != null && app.covers.resolution(updated) >= 226 &&
                    (app.covers.resolution(updated) > beforeResolution || updated.length() != beforeLength || updated.lastModified() != beforeModified)
                if (changed) notifyCoverReady(id)
            }
            if (local == null) {
                android.util.Log.i("Sonus", "cover artista etapa=total_ms=${(System.nanoTime() - started) / 1_000_000} origem=ausente_agendada")
                return notFound()
            }
        }
        android.util.Log.i("Sonus", "cover artista etapa=resposta_ms=${(System.nanoTime() - started) / 1_000_000} bytes=${local.length()} origem=disco")
        return imageResponse(local)
    }

    /**
     * Uma busca de capa/foto por vez para cada imagem. A tela pede de novo a mesma imagem enquanto ela não existe
     * (resposta 404 não fica no cache do WebView): sem isto cada pedido abria outra busca, todas na fila do mesmo
     * cadeado — minutos de buscas repetidas e CPU alta com o app parado (visto com a foto de Tribalistas).
     */
    private fun umaBusca(chave: String, trabalho: suspend () -> Unit) {
        if (!emBusca.add(chave)) return
        SounosApp.instance.io.launch { try { trabalho() } finally { emBusca.remove(chave) } }
    }

    /** Confere "clipe ou música?" em segundo plano; se a capa oficial for outra, troca e avisa a tela. */
    private suspend fun conferirDepois(track: Track, pin: Boolean, large: Boolean) {
        val app = SounosApp.instance
        if (!app.covers.precisaConferir(track.id)) return
        val antes = app.covers.local(track.id, track.thumbs.firstOrNull()?.url, large)?.let { it.length() to it.lastModified() }
        val depois = runCatching { app.covers.get(track, pin, large, conferirAntes = true) }.getOrNull()
        if (depois != null && (depois.length() to depois.lastModified()) != antes) notifyCoverReady(track.id, large)
    }

    private fun notifyCoverReady(id: String, large: Boolean = false) {
        // a capa da música atual chegou: notificação, tela de bloqueio e widgets também a recebem (sem esperar outra ação)
        val sonus = SounosApp.instance
        if (id == sonus.player.currentTrack?.id) runOnUiThread { PlaybackService.atualizarTudo(sonus) }
        if (!::web.isInitialized || isFinishing) return
        web.post { web.evaluateJavascript("window.__sounos&&window.__sounos.emit('coverReady',{id:${JSONObject.quote(id)},large:$large})", null) }
    }

    private fun imageResponse(file: File): WebResourceResponse {
        val head = file.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
        val mime = when {
            head[0] == 0xFF.toByte() -> "image/jpeg"
            head[0] == 0x89.toByte() -> "image/png"
            String(head, 8, 4) == "WEBP" -> "image/webp"
            else -> "image/*"
        }
        // A rota serve arquivos que podem ser atualizados em segundo plano. Não deixa o WebView
        // manter uma miniatura antiga sob a mesma URL por um ano.
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-cache, must-revalidate"), file.inputStream())
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

    /** Altura (px do aparelho) da barra de status e da de navegação; a interface converte para px dela. */
    private fun aplicarMedidas(topo: Int, base: Int) {
        if (topo == barraTopo && base == barraBase) return
        barraTopo = topo; barraBase = base
        if (::web.isInitialized) web.post { web.evaluateJavascript("window.__sonusMedidas&&window.__sonusMedidas($topo,$base)", null) }
    }

    // ---------- link de música (tocado ou compartilhado de outro app) ----------
    // Guarda o link; a interface o pega (getPendingLink) quando a página está pronta ou na hora, se já estiver aberta.
    private fun receberLink(i: Intent?) {
        if (i == null || i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val texto = when (i?.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.takeIf { it.contains("http") } ?: return
        linkPendente = texto
        if (::web.isInitialized) web.post { web.evaluateJavascript("window.__sonusLinkChegou&&window.__sonusLinkChegou()", null) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receberLink(intent)
        abrirDoWidget(intent, 0)
    }

    // toque no widget: o player na hora (e a letra, se o modo letra do widget está ligado). Recém-criada, espera a página.
    private fun abrirDoWidget(i: Intent?, espera: Long) {
        val modo = i?.getStringExtra("sonus.widget") ?: return
        i.removeExtra("sonus.widget")
        val js = "(function(){var s=window.__playerShell;if(!s)return;s.open&&s.open();" +
            (if (modo == "letra") "setTimeout(function(){try{var w=document.querySelector('iframe').contentWindow,d=w.document;" +
                "if(!d.getElementById('phone').classList.contains('lyr-mode'))d.getElementById('lyricsBtn').click()}catch(e){}},450);" else "") + "})()"
        web.postDelayed({ if (!isFinishing) web.evaluateJavascript(js, null) }, espera)
    }

    companion object {
        /** quando o app saiu da tela pela última vez (neste processo): decide se a abertura animada roda */
        @Volatile var ultimaSaida = 0L
        /** link de música recebido e ainda não tratado pela interface */
        @Volatile var linkPendente: String? = null
        /** barras do sistema em px do aparelho (de ponta a ponta); a interface lê com getMedidas */
        @Volatile var barraTopo = 0
        @Volatile var barraBase = 0
        /** imagens com busca em andamento (ver umaBusca) */
        private val emBusca: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        private const val HOST = "appassets.androidplatform.net"
        private const val PICK_IMAGE = 7
        private const val REQUEST_NOTIFICATIONS = 42
    }
}
