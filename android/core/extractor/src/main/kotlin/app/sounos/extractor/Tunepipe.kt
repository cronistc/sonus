package app.sounos.extractor

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Inicialização do NewPipe. Chamar uma vez no início do app (Application.onCreate).
 * País e idioma vêm das configurações (padrão: Brasil / português) e mudam os resultados e as tendências.
 */
object Sounos {
    @Volatile lateinit var http: OkHttpClient
        private set

    fun init(
        client: OkHttpClient = OkHttpDownloader.newClient(),
        language: String = "pt",
        country: String = "BR",
    ) {
        http = client
        NewPipe.init(OkHttpDownloader(client), Localization(language, country), ContentCountry(country))
    }

    /** Troca de país/idioma em tempo real (tela de configurações). */
    fun setRegion(language: String, country: String) {
        NewPipe.setupLocalization(Localization(language, country), ContentCountry(country))
    }
}
