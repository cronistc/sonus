import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.sounos"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.sounos"
        minSdk = 26
        targetSdk = 34
        // nome da versão: quem decide é o Guilherme. versionCode só sobe (o Android exige).
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 4
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.2"
    }

    // Chave definitiva do Sounos (fica fora do projeto, em ~/Sonus-chave). Toda versão precisa sair com ela:
    // é o que permite atualizar por cima (e sozinho) nos celulares.
    val keyProps = Properties().apply {
        val f = File(System.getProperty("user.home"), "Sonus-chave/sonus.properties")
        if (f.exists()) f.inputStream().use(::load)
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("sounos") {
            storeFile = File(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        val key = signingConfigs.findByName("sounos")
        debug { if (key != null) signingConfig = key }        // testes no celular: mesma chave da versão final
        release { isMinifyEnabled = false; if (key != null) signingConfig = key }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true      // o NewPipe Extractor usa APIs do Java 11+
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

    // as telas (HTML/CSS/JS do Sounos) ficam na pasta prototype/ e entram no app como estão
    sourceSets["main"].assets.srcDirs("../../prototype")

    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation(project(":core:extractor"))
    implementation(libs.coroutines.android)
    coreLibraryDesugaring(libs.desugar)
}
