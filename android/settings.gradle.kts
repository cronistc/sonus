pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")   // NewPipe Extractor é publicado no JitPack
    }
}

rootProject.name = "Sonus"
include(":app")              // app Android (0.1: a interface do protótipo rodando no celular)
include(":core:extractor")   // NewPipe + caches (Kotlin/JVM puro: roda e testa no PC)
include(":tools:smoke")      // teste de fumaça: busca e toca de verdade, medindo os tempos
