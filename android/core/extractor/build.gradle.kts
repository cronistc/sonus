plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.newpipe.extractor)
    api(libs.okhttp)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
