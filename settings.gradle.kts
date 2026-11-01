pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AdFree"

include(":core")

// The Android app needs the Android SDK. Without it (for example on a plain
// JVM machine) only the shared filtering core is built and tested.
val localProperties = file("local.properties")
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProperties.exists() && localProperties.readText().contains("sdk.dir"))
gradle.extra["hasAndroidSdk"] = hasAndroidSdk
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found: building :core only. Set ANDROID_HOME to build the app.")
}
