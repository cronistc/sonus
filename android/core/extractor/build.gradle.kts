plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.newpipe.extractor)
    api(libs.okhttp)
    api(libs.coroutines.core)
    api("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")       // JSON (o mesmo que o NewPipe usa): rádio do YouTube Music
    testImplementation(libs.junit)
}
