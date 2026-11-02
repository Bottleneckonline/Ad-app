import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "online.bottleneck.adfree"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.bottleneck.adfree"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing key, given to CI as secrets (see .github/workflows/release.yml).
    // Without it the release APK is signed with the build machine's debug key, which
    // installs fine but cannot be installed over a version signed with another key.
    val keystore = System.getenv("ADFREE_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("ADFREE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ADFREE_KEY_ALIAS") ?: "adfree"
                keyPassword = System.getenv("ADFREE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Ships blocklists/blocklist.txt as the asset "blocklist.txt".
    sourceSets["main"].assets.srcDir(rootProject.file("blocklists"))

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}
