import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Release version, from the git tag in CI (-PversionName=1.2.3). */
val appVersion = (findProperty("versionName") as String?)?.removePrefix("v") ?: "0.1.0"

/** "1.2.3" -> 10203. Same formula as Updater.versionCode in the app: keep them in sync. */
fun versionCodeOf(version: String): Int {
    val parts = version.split('.').map { it.toInt() } + listOf(0, 0)
    require(parts.take(3).all { it in 0..99 }) { "Version parts must be 0..99: $version" }
    return parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

/** Release signing key, from the environment (GitHub secrets in CI). Without it, releases use the debug key. */
val releaseKeystore = System.getenv("NOTIFIER_KEYSTORE")?.let(::file)?.takeIf { it.exists() }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.notifier"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.notifier"
        minSdk = 29
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("NOTIFIER_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("NOTIFIER_KEY_ALIAS")
            keyPassword = System.getenv("NOTIFIER_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Updates only install over an app signed with the same key: every release must use one key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Local JVM tests only: the app itself has no dependencies. Android's org.json is a stub off-device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
