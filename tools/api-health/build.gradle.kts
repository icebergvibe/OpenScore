plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "org.openscore"
version = "0.2.2-SNAPSHOT"

kotlin { jvmToolchain(21) }

application {
    mainClass.set("org.openscore.health.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// `./gradlew :tools:api-health:run --args="--league nhl"` — the tool locates apis/ from the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
