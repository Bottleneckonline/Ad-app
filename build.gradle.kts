buildscript {
    repositories {
        google()
        mavenCentral()
    }
    // The Android Gradle plugin is only needed (and only downloadable) where the
    // Android SDK is installed; see settings.gradle.kts.
    if (gradle.extra["hasAndroidSdk"] == true) {
        dependencies { classpath("com.android.tools.build:gradle:8.7.3") }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}
