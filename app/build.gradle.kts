import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Reads a setting from a Gradle property (-Padfree.keyAlias=…) or the matching environment
 * variable (ADFREE_KEY_ALIAS).
 */
fun setting(name: String): String? =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(
            name.replace(Regex("([a-z])([A-Z])"), "$1_$2").replace('.', '_').uppercase(),
        )?.takeIf { it.isNotBlank() }

android {
    namespace = "online.bottleneck.adfree"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.bottleneck.adfree"
        minSdk = 24
        targetSdk = 35
        // CI passes the build number so every release has a higher versionCode.
        versionCode = setting("adfree.versionCode")?.toInt() ?: 1
        versionName = "1.0.0"

        // Lemon Squeezy store/product that sells AdFree for Android (see docs/LAUNCH_CHECKLIST.md).
        // 0 means "not set up": the app then shows the trial but cannot activate keys.
        buildConfigField("long", "LS_STORE_ID", "${setting("adfree.lsStoreId") ?: "0"}L")
        buildConfigField("long", "LS_PRODUCT_ID", "${setting("adfree.lsProductId") ?: "0"}L")
        buildConfigField(
            "String",
            "BUY_URL",
            "\"${setting("adfree.buyUrl").orEmpty()}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing. The keystore never goes into git; CI decodes it from a secret.
    // Set ADFREE_KEYSTORE_FILE, ADFREE_KEYSTORE_PASSWORD, ADFREE_KEY_ALIAS, ADFREE_KEY_PASSWORD
    // (or the matching adfree.* Gradle properties). Without them the release APK is unsigned.
    val keystore = setting("adfree.keystoreFile")
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = setting("adfree.keystorePassword")
                keyAlias = setting("adfree.keyAlias")
                keyPassword = setting("adfree.keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
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
