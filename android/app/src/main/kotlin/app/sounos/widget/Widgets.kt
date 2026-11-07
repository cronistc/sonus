package app.sounos.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.widget.RemoteViews
import app.sounos.MainActivity
import app.sounos.R
import app.sounos.SounosApp
import app.sounos.player.MediaSessionHolder

/**
 * Widgets da tela inicial — três desenhos, cada um aparece separado na lista de widgets do celular:
 *  · Barra (4×1): capa, nome, artista, voltar / tocar / passar
 *  · Capa (2×2): a capa ocupando tudo, nome por cima e o botão de tocar
 *  · Completo (4×2): capa grande, nome, artista e controles grandes
 * O fundo é a capa desfocada com a cor da música por cima (como o fundo do player); sem capa, só a cor. Atualizados pelo PlayerController a cada
 * mudança de estado ([atualizar]); só redesenham quando algo visível mudou. Os botões chegam aqui ([Base.onReceive])
 * e vão direto para o player.
 */
object Widgets {
    private const val TOCAR = "app.sounos.widget.TOCAR"
    private const val VOLTAR = "app.sounos.widget.VOLTAR"
    private const val PASSAR = "app.sounos.widget.PASSAR"
    private const val LETRA = "app.sounos.widget.LETRA"
    private val tipos = listOf(Barra::class.java to R.layout.widget_barra, Capa::class.java to R.layout.widget_capa,
        Completo::class.java to R.layout.widget_completo)
    /** A linha da letra que está tocando (a lista dos widgets pinta essa de branco — LetraService). */
    @Volatile var linhaAtual = -1
    /** As linhas que a lista mostra (publicadas aqui, na linha principal; o LetraService lê só isto, em outra linha) */
    @Volatile var linhasWidget: List<String> = emptyList()
    // como no app: trecho sem letra = "♪", inclusive a introdução antes da primeira linha cantada
    private fun comIntro(s: List<Pair<Double, String>>): List<Pair<Double, String>> =
        if ((s.firstOrNull()?.first ?: 0.0) > 2.0) listOf(0.0 to "♪") + s else s
    private fun letraDoWidget(): List<Pair<Double, String>>? = SounosApp.instance.player.letraSincronizada()?.let(::comIntro)
    // reconferências de "centralizar" por widget: a nova substitui a anterior (não se acumulam)
    private val fichas = HashMap<Int, Any>()
    private fun agenda(id: Int, atraso: Long, r: () -> Unit) {
        val f = fichas.getOrPut(id) { Any() }
        relogio.removeCallbacksAndMessages(f)
        relogio.postAtTime(r, f, android.os.SystemClock.uptimeMillis() + atraso)
    }
    private fun telaLigada(ctx: Context) = (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    private var ouvindoTela = false
    // sem linhas vazias: o espaço em cima e embaixo da lista (padding calculado pela altura do widget) já deixa a
    // primeira e a última linha no meio
    const val SOBRA = 0
    const val TOPO = 0
    private var letraDe = ""                               // de qual música é a lista que está no widget
    // até onde rolar para a linha atual ficar no meio (a rolagem suave para com o alvo na borda de baixo)
    // a lista tem um espaço em cima e embaixo do tamanho certo para caber UMA linha no meio: rolar até a linha atual
    // (a rolagem suave a deixa encostada na borda desse espaço) a põe no meio do widget, em qualquer tamanho
    private fun alvo(@Suppress("UNUSED_PARAMETER") layout: Int, i: Int, n: Int) = maxOf(i, 0).coerceAtMost(maxOf(n - 1, 0))
    private class Medida(val larguraDp: Int, val alturaDp: Int, val tamanhoSp: Float)
    private fun medida(ctx: Context, layout: Int, id: Int): Medida {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
        // a letra acompanha o tamanho do widget (a Capa muda de tamanho): proporcional ao lado menor, com limites
        // Capa e Completo com a mesma regra: do mesmo tamanho, a letra fica igual
        val sp = if (layout == R.layout.widget_barra) 18f else minOf(h * 0.13f, w * 0.09f).coerceIn(15f, 30f)
        return Medida(w, h, sp)
    }
    // a largura da letra em pixels (a largura do widget menos as margens da lista): as linhas são desenhadas nela
    private fun listaIntent(ctx: Context, layout: Int, m: Medida): Intent {
        val nome = when (layout) { R.layout.widget_barra -> "barra"; R.layout.widget_capa -> "capa"; else -> "completo" }
        val px = ((m.larguraDp - 64) * ctx.resources.displayMetrics.density).toInt()
        return Intent(ctx, LetraService::class.java).putExtra("tipo", nome).putExtra("largura", px).putExtra("tamanho", m.tamanhoSp)
            .setData(android.net.Uri.parse("sonus://letra/$nome/$px/${m.tamanhoSp}"))
    }
    // o fundo da letra do app: degradê da cor da música (misturada 40% com o fundo #0e0e10) até o fundo, aos 65%
    private fun fundoDaLetra(c1: Int): Bitmap {
        val b = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        val base = Color.rgb(14, 14, 16)
        val topo = Color.rgb((Color.red(c1) * .4f + 14 * .6f).toInt(), (Color.green(c1) * .4f + 14 * .6f).toInt(), (Color.blue(c1) * .4f + 16 * .6f).toInt())
        Canvas(b).drawRect(0f, 0f, 120f, 240f, Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, 240f, intArrayOf(topo, base, base), floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
        })
        return b
    }

    // ---- modo letra ("A" nos widgets) ----
    // Ligado, fica até a pessoa tocar no "A" de novo. Música sem letra: continua ligado mas mostra o player; a próxima
    // com letra volta a mostrar a letra sozinha. Pausar numa música sem letra desliga (apertar play depois não religa).
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sonus.widget", Context.MODE_PRIVATE)
    private fun letraLigada(ctx: Context, id: Int) = prefs(ctx).getBoolean("letra_$id", false)
    private fun ligaLetra(ctx: Context, id: Int, v: Boolean) = prefs(ctx).edit().putBoolean("letra_$id", v).apply()
    private val relogio = android.os.Handler(android.os.Looper.getMainLooper())
    private var proximaLinha: Runnable? = null

    /** Algum widget do Sonus na tela inicial? (boas-vindas e novidades só oferecem quando não há) */
    fun existe(ctx: Context): Boolean = runCatching {
        val am = AppWidgetManager.getInstance(ctx)
        tipos.any { (cls, _) -> am.getAppWidgetIds(ComponentName(ctx, cls)).isNotEmpty() }
    }.getOrDefault(false)

    /** Quantos widgets de cada tipo (barra, capa, completo) já estão na tela inicial (Configurações mostra "Na tela"). */
    fun contagem(ctx: Context): Map<String, Int> = runCatching {
        val am = AppWidgetManager.getInstance(ctx)
        mapOf("barra" to Barra::class.java, "capa" to Capa::class.java, "completo" to Completo::class.java)
            .mapValues { (_, cls) -> am.getAppWidgetIds(ComponentName(ctx, cls)).size }
    }.getOrDefault(emptyMap())

    /** Redesenha os widgets que existem na tela (nada a fazer se a pessoa não pôs nenhum). */
    fun atualizar(contexto: Context, forcar: Boolean = false) {
        val ctx = contexto.applicationContext ?: contexto        // o relógio da letra não segura o contexto de um receptor
        runCatching {
            val am = AppWidgetManager.getInstance(ctx)
            val todos = tipos.flatMap { tipo -> am.getAppWidgetIds(ComponentName(ctx, tipo.first)).map { it to tipo } }
            proximaLinha?.let(relogio::removeCallbacks); proximaLinha = null
            if (todos.isEmpty()) return
            val player = SounosApp.instance.player
            val t = player.currentTrack
            val tocando = player.isPlaying
            val synced = letraDoWidget()
            // tela acesa de novo: o widget alcança a música (com a tela apagada ele não acompanha a letra)
            if (!ouvindoTela) { ouvindoTela = true
                ctx.registerReceiver(object : android.content.BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = atualizar(ctx) },
                    android.content.IntentFilter(Intent.ACTION_SCREEN_ON)) }
            val conhecida = player.letraConhecida()
            if (t != null && !conhecida && !player.letraChegando() && todos.any { letraLigada(ctx, it.first) }) player.lyricsJson()   // recém-aberto: busca a letra
            val pos = player.positionMs() / 1000.0
            val i = synced?.indexOfLast { it.first <= pos + 0.15 } ?: -1
            val n = synced?.size ?: 0
            // a cor mais viva da capa (capa acinzentada dava widget cinza)
            val cor = player.colors.maxByOrNull { c -> runCatching { FloatArray(3).also { Color.colorToHSV(Color.parseColor(c), it) }.let { it[1] * (0.4f + it[2]) } }.getOrDefault(0f) }
            val capa = t?.let { MediaSessionHolder.coverFor(it.id) }
            val fundo = escurece(runCatching { Color.parseColor(cor) }.getOrDefault(Color.parseColor("#2F6BFF")))
            val pr = prefs(ctx)
            val c1 = runCatching { Color.parseColor(player.colors.firstOrNull()) }.getOrDefault(Color.parseColor("#2F6BFF"))
            var letraFundo: Bitmap? = null
            var redonda: Bitmap? = null; var redondaCapa: Bitmap? = null; var desfocada: Bitmap? = null; var algumaLetra = false
            // a lista da letra pinta a linha atual (LetraService lê linhaAtual)
            val letraNova = "${t?.id}|$n"
            val linhaMudou = i != linhaAtual || letraNova != letraDe
            linhaAtual = i; letraDe = letraNova
            linhasWidget = synced?.map { it.second.ifBlank { "♪" } }.orEmpty()
            for ((id, tipo) in todos) {
                val layout = tipo.second
                // modo letra de CADA widget (o "A" de um não liga os outros). Pausar numa música sem letra desliga.
                if (letraLigada(ctx, id) && synced == null && !tocando && t != null && conhecida) ligaLetra(ctx, id, false)
                val modoLetra = letraLigada(ctx, id)
                val mostraLetra = modoLetra && synced != null
                if (mostraLetra) algumaLetra = true
                val o = am.getAppWidgetOptions(id)
                val base = "${t?.id}|$tocando|$cor|${capa != null}|$modoLetra|$mostraLetra|${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)}"
                val antes = pr.getString("base_$id", "")
                if (!forcar && antes == base) {
                    // só a linha mudou: a lista rola suave até ela, e nada mais é reenviado
                    if (mostraLetra && linhaMudou) {
                        am.notifyAppWidgetViewDataChanged(id, R.id.w_lista)
                        am.partiallyUpdateAppWidget(id, RemoteViews(ctx.packageName, layout).apply { centra(ctx, this, layout, id, synced!!, i) })
                        // a rolagem suave às vezes para antes (o espaço mudou junto): confere de novo logo depois
                        agenda(id, 700) { recentra(ctx, id, layout) }
                    }
                    continue
                }
                // imagens só quando a música/capa/cor/modo mudam (trocar imagem pisca)
                val visual = "${t?.id}|$cor|${capa != null}|$mostraLetra"
                val mandaImagens = forcar || pr.getString("visual_$id", "") != visual
                if (mandaImagens && desfocada == null) {
                    desfocada = desfoca(capa, fundo)
                }
                val v = RemoteViews(ctx.packageName, layout)
                if (mandaImagens) {
                    if (mostraLetra && letraFundo == null) letraFundo = fundoDaLetra(c1)
                    v.setImageViewBitmap(R.id.w_fundo, if (mostraLetra) letraFundo else desfocada)
                    if (layout == R.layout.widget_capa) { if (redondaCapa == null) redondaCapa = capa?.let { arredonda(it, 0.1f) } }
                    else if (redonda == null) redonda = capa?.let { arredonda(it, 0.12f) }
                    val c = if (layout == R.layout.widget_capa) redondaCapa else redonda
                    if (c != null) v.setImageViewBitmap(R.id.w_capa, c) else v.setImageViewResource(R.id.w_capa, R.drawable.widget_capa_vazia)   // a capa ainda está chegando
                }
                v.setTextViewText(R.id.w_titulo, t?.title ?: "Sonus")
                v.setTextViewText(R.id.w_artista, t?.let { it.artists.ifEmpty { listOf(it.artist) }.joinToString(", ") } ?: "Toque para abrir")
                v.setImageViewResource(R.id.w_tocar, if (tocando) R.drawable.ic_n_pause else R.drawable.ic_n_play)
                v.setInt(R.id.w_tocar, "setColorFilter", fundo)          // ícone na cor do fundo, sobre o círculo branco
                v.setContentDescription(R.id.w_tocar, if (tocando) "Pausar" else "Tocar")
                // player ⇄ letra por visibilidade: o launcher reaplica tudo a cada atualização, e o mesmo estado não pisca
                v.setViewVisibility(R.id.w_vplayer, if (mostraLetra) android.view.View.GONE else android.view.View.VISIBLE)
                v.setViewVisibility(R.id.w_vletra, if (mostraLetra) android.view.View.VISIBLE else android.view.View.GONE)
                val m = medida(ctx, layout, id)
                @Suppress("DEPRECATION") v.setRemoteAdapter(R.id.w_lista, listaIntent(ctx, layout, m))

                v.setPendingIntentTemplate(R.id.w_lista, PendingIntent.getActivity(ctx, 10_000 + id,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sonus.widget", "letra"),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                if (mostraLetra) { am.notifyAppWidgetViewDataChanged(id, R.id.w_lista); centra(ctx, v, layout, id, synced!!, i) }
                val botoesA = if (layout == R.layout.widget_barra) listOf(R.id.w_letra_btn, R.id.w_letra_btn2) else listOf(R.id.w_letra_btn)
                botoesA.forEach { b ->
                    v.setInt(b, "setBackgroundResource", if (modoLetra) R.drawable.widget_a_off else 0)
                    v.setTextColor(b, Color.WHITE)          // ícone sólido nos dois estados (ligado = círculo atrás)
                    v.setContentDescription(b, if (modoLetra) "Desligar letra" else "Mostrar letra")
                    v.setOnClickPendingIntent(b, acao(ctx, LETRA, id))
                }
                // abre o app no player (com a letra, se este widget está no modo letra) — a mesma tela se ela já existe
                v.setOnClickPendingIntent(R.id.w_raiz, PendingIntent.getActivity(ctx, 20_000 + id,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sonus.widget", if (mostraLetra) "letra" else "player"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                v.setOnClickPendingIntent(R.id.w_tocar, acao(ctx, TOCAR))
                if (layout != R.layout.widget_capa) {
                    v.setOnClickPendingIntent(R.id.w_voltar, acao(ctx, VOLTAR))
                    v.setOnClickPendingIntent(R.id.w_passar, acao(ctx, PASSAR))
                }
                // atualização parcial: o launcher só aplica o que mudou (a completa refazia o widget e "recarregava" a cada play)
                if (forcar) am.updateAppWidget(id, v) else am.partiallyUpdateAppWidget(id, v)
                // lista nova (mudou de tamanho, entrou no modo letra): o pedido de rolar chega antes de ela carregar e é
                // ignorado; centraliza de novo quando ela fica pronta
                if (mostraLetra) agenda(id, 600) { recentra(ctx, id, layout); agenda(id, 1400) { recentra(ctx, id, layout); agenda(id, 2000) { recentra(ctx, id, layout) } } }
                pr.edit().putString("base_$id", base).putString("visual_$id", visual).apply()
            }
            // a letra acompanha a música: o próximo redesenho fica marcado para a troca de linha
            if (algumaLetra && tocando && telaLigada(ctx)) synced?.getOrNull(i + 1)?.let { prox ->
                val espera = ((prox.first - pos) * 1000).toLong().coerceIn(250, 15_000)
                proximaLinha = Runnable { atualizar(ctx) }.also { relogio.postDelayed(it, espera) }
            }
        }.onFailure { android.util.Log.w("Sonus", "widget não atualizou", it) }
    }

    // os botões vão para um receptor só do Sonus (não exportado): outro app não consegue pausar ou passar a música
    private fun acao(ctx: Context, a: String, id: Int = 0) =
        PendingIntent.getBroadcast(ctx, a.hashCode() xor id, Intent(ctx, Botoes::class.java).setAction(a).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE)

    // a linha atual no meio: o espaço em cima e embaixo da lista é (altura do widget − altura DESSA linha) / 2, e a lista
    // rola até ela (a rolagem suave a encosta na borda desse espaço). Linhas com a altura do próprio texto, como no app.
    private fun centra(ctx: Context, v: RemoteViews, layout: Int, id: Int, s: List<Pair<Double, String>>, i: Int) {
        val m = medida(ctx, layout, id)
        val d = ctx.resources.displayMetrics.density
        val texto = s.getOrNull(maxOf(i, 0))?.second?.ifBlank { "♪" } ?: "♪"
        val h = LetraService.altura(ctx, texto, m.tamanhoSp, ((m.larguraDp - 64) * d).toInt(), i >= 0, if (layout == R.layout.widget_barra) "barra" else "")
        val vert = ((m.alturaDp * d - h) / 2).toInt().coerceAtLeast(0)
        val (ini, fim) = if (layout == R.layout.widget_barra) 6 to 14 else 18 to 46
        v.setViewPadding(R.id.w_lista, (ini * d).toInt(), vert, (fim * d).toInt(), vert)
        v.setScrollPosition(R.id.w_lista, alvo(layout, i, s.size))
    }
    private fun recentra(ctx: Context, id: Int, layout: Int) = runCatching {
        val p = SounosApp.instance.player
        if (!telaLigada(ctx)) return@runCatching
        val s = letraDoWidget() ?: return@runCatching
        val i = s.indexOfLast { it.first <= p.positionMs() / 1000.0 + 0.15 }
        AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(id, RemoteViews(ctx.packageName, layout).apply { centra(ctx, this, layout, id, s, i) })
    }

    // a cor da capa, escurecida para o texto branco aparecer (igual ao fundo do player)
    private fun escurece(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[2] = (hsv[2] * 0.55f).coerceAtMost(0.42f); hsv[1] = (hsv[1] * 1.05f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }

    // a capa reduzida a poucos pontos e ampliada em dois passos (fica desfocada, sem custo), a cor da música por cima
    // e uma sombra embaixo para o texto branco aparecer sempre
    private fun desfoca(capa: Bitmap?, cor: Int): Bitmap {
        val lado = 240
        val out = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (capa != null) {
            val mini = Bitmap.createScaledBitmap(capa, 6, 6, true)
            val meio = Bitmap.createScaledBitmap(mini, 30, 30, true)
            val grande = Bitmap.createScaledBitmap(meio, lado, lado, true)
            c.drawBitmap(grande, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            mini.recycle(); meio.recycle(); grande.recycle()
            c.drawColor(Color.argb(165, Color.red(cor), Color.green(cor), Color.blue(cor)))
        } else c.drawColor(cor)
        c.drawRect(0f, 0f, lado.toFloat(), lado.toFloat(), Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, lado.toFloat(), Color.argb(20, 0, 0, 0), Color.argb(110, 0, 0, 0), Shader.TileMode.CLAMP)
        })
        return out
    }

    private fun arredonda(b: Bitmap, raio: Float): Bitmap {
        val lado = minOf(b.width, b.height, 300)
        val out = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val m = android.graphics.Matrix().apply {
            val s = lado.toFloat() / minOf(b.width, b.height)
            setScale(s, s); postTranslate((lado - b.width * s) / 2, (lado - b.height * s) / 2)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) } }
        Canvas(out).drawRoundRect(RectF(0f, 0f, lado.toFloat(), lado.toFloat()), lado * raio, lado * raio, p)
        return out
    }

    open class Base : AppWidgetProvider() {
        override fun onUpdate(ctx: Context, am: AppWidgetManager, ids: IntArray) = atualizar(ctx, forcar = true)
        // mudou de tamanho: a letra se ajusta (tamanho da fonte e o meio)
        override fun onAppWidgetOptionsChanged(ctx: Context, am: AppWidgetManager, id: Int, o: android.os.Bundle) = atualizar(ctx)   // o tamanho entra na "base": só ele é refeito
        // widget tirado da tela: o estado guardado dele vai junto
        override fun onDeleted(ctx: Context, ids: IntArray) {
            prefs(ctx).edit().apply { ids.forEach { remove("letra_$it"); remove("base_$it"); remove("visual_$it") } }.apply()
        }
    }
    class Botoes : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val p = SounosApp.instance.player
            when (intent.action) {
                TOCAR -> p.toggle(); VOLTAR -> p.prev(); PASSAR -> p.next()
                LETRA -> { val id = intent.getIntExtra("id", 0); ligaLetra(ctx, id, !letraLigada(ctx, id)); p.lyricsJson(); atualizar(ctx) }   // lyricsJson: busca a letra se faltar
            }
        }
    }
    class Barra : Base()
    class Capa : Base()
    class Completo : Base()
}
