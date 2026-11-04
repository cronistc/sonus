package app.sounos.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.sounos.R
import app.sounos.SounosApp

/**
 * As linhas da letra para a lista rolável dos widgets (modo letra). A linha atual ([Widgets.linhaAtual]) fica branca,
 * as outras apagadas, como na tela de letra do app; o widget rola suave até ela (setScrollPosition).
 * No fim, algumas linhas vazias para a última linha também poder subir até o meio.
 */
class LetraService : RemoteViewsService() {
    companion object {
        fun fonte(ctx: Context): Typeface = runCatching {
            val f = ctx.resources.getFont(R.font.urbanist)
            if (Build.VERSION.SDK_INT >= 28) Typeface.create(f, 650, false) else f
        }.getOrDefault(Typeface.DEFAULT_BOLD)
        // o texto de uma linha (até 3 linhas de texto, "…" se passar), na altura dele mesmo — como no app
        fun layout(ctx: Context, texto: String, tamanhoSp: Float, largura: Int, agora: Boolean, tf: Typeface = fonte(ctx), linhas: Int = 3): StaticLayout {
            val dm = ctx.resources.displayMetrics
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf; textSize = tamanhoSp * dm.scaledDensity * (if (agora) 1.15f else 1f)
                color = if (agora) Color.WHITE else Color.argb(100, 255, 255, 255)
                if (!agora) maskFilter = BlurMaskFilter(1.4f * dm.density, BlurMaskFilter.Blur.NORMAL)
            }
            return StaticLayout.Builder.obtain(texto, 0, texto.length, p, largura.coerceAtLeast(100)).setLineSpacing(0f, 1.05f)
                .setIncludePad(false).setMaxLines(linhas).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        }
        fun respiro(ctx: Context) = (6 * ctx.resources.displayMetrics.density).toInt()
        /** altura (px) da linha da letra como ela aparece na lista */
        /** a Barra é baixa: uma linha de texto por verso ("…" se passar) */
        fun linhasDe(tipo: String) = if (tipo == "barra") 1 else 3
        fun altura(ctx: Context, texto: String, tamanhoSp: Float, largura: Int, agora: Boolean, tipo: String) =
            layout(ctx, texto, tamanhoSp, largura, agora, linhas = linhasDe(tipo)).height + 2 * respiro(ctx)
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Linhas(applicationContext, intent.getStringExtra("tipo") ?: "completo", intent.getIntExtra("largura", 600), intent.getFloatExtra("tamanho", 20f))

    private class Linhas(private val ctx: Context, private val tipo: String, private val largura: Int, tamanhoSp: Float) : RemoteViewsFactory {
        private val layout = when (tipo) { "barra" -> R.layout.widget_linha_barra; "capa" -> R.layout.widget_linha_capa; else -> R.layout.widget_linha_completo }
        private var linhas: List<String> = emptyList()
        private var atual = -1
        private val cache = HashMap<String, Bitmap>()
        private val dm = ctx.resources.displayMetrics
        private val tamanho = tamanhoSp * dm.scaledDensity                  // calculado pelo tamanho do widget (Widgets.medida)
        // a fonte do app (Urbanist, peso 650 como a letra do app); o launcher não carrega fontes de outros apps,
        // por isso cada linha vai como imagem
        private val fonte: Typeface = fonte(ctx)
        override fun onCreate() {}
        override fun onDestroy() {}
        override fun onDataSetChanged() {
            // roda numa linha de fundo do sistema: só lê o que o player já tem
            // a cópia publicada pelo widget (na linha principal): sem disputar o player com outra linha
            val novas = Widgets.linhasWidget
            if (novas != linhas) cache.clear()
            linhas = novas
            atual = Widgets.linhaAtual
        }
        // linhas vazias antes (a primeira linha também fica no meio) e depois (a última também sobe até o meio)
        override fun getCount() = if (linhas.isEmpty()) 0 else Widgets.TOPO + linhas.size + Widgets.SOBRA
        override fun getViewAt(pos: Int): RemoteViews = RemoteViews(ctx.packageName, layout).apply {
            val i = pos - Widgets.TOPO
            // cada linha desenhada uma vez (acesa e apagada): na troca de linha só duas mudam
            val txt = linhas.getOrNull(i).orEmpty(); val agora = i == atual
            setImageViewBitmap(R.id.w_li, cache.getOrPut("$agora|$i") { desenha(txt, agora) })
            setOnClickFillInIntent(R.id.w_li, Intent())        // tocar na letra também abre o app
        }
        // como no app: a atual branca e nítida (um pouco maior); as outras translúcidas e levemente desfocadas
        private fun desenha(texto: String, agora: Boolean): Bitmap {
            val lay = layout(ctx, texto, tamanho / dm.scaledDensity, largura, agora, fonte, linhasDe(tipo))
            val pad = respiro(ctx)
            val b = Bitmap.createBitmap(largura.coerceAtLeast(100), lay.height + 2 * pad, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { translate(0f, pad.toFloat()); lay.draw(this) }
            return b
        }
        override fun getLoadingView(): RemoteViews = RemoteViews(ctx.packageName, layout)   // vazio (o padrão escreve "Carregando")
        override fun getViewTypeCount() = 1
        override fun getItemId(pos: Int) = pos.toLong()
        override fun hasStableIds() = true
    }
}
