plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies { implementation(project(":core:extractor")) }

application { mainClass.set("app.sounos.smoke.SmokeKt") }

tasks.named<JavaExec>("run") {
    // argumentos: ./gradlew :tools:smoke:run --args="sua busca"
    workingDir = rootProject.layout.buildDirectory.dir("smoke").get().asFile.apply { mkdirs() }
}


tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.sounos.smoke.ProbeKt")
}
