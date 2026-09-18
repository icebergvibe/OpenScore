plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

group = "org.openscore"
version = "0.2.2-SNAPSHOT"

kotlin {
    jvmToolchain(21)

    jvm()

    androidLibrary {
        namespace = "org.openscore.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// Pass -Dopenscore.live=true through to the JVM tests to enable the live smoke tests.
tasks.withType<Test>().configureEach {
    System.getProperty("openscore.live")?.let { systemProperty("openscore.live", it) }
    testLogging.showStandardStreams = System.getProperty("openscore.live") == "true"
}
