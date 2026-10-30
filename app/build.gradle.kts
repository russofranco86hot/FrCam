import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.frcam.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.frcam.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.2.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    val frcamKeystoreFile: File? = run {
        val props = Properties()
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { props.load(it) }
        val storeProp = props.getProperty("storeFile", "../keystore/frcam.keystore")
        val directFile = file(storeProp)
        val rootFile = rootProject.file(storeProp.removePrefix("../"))
        when {
            directFile.exists() -> directFile
            rootFile.exists() -> rootFile
            else -> null
        }
    }

    signingConfigs {
        if (frcamKeystoreFile != null) {
            create("frcam") {
                val props = Properties()
                val f = rootProject.file("keystore.properties")
                if (f.exists()) f.inputStream().use { props.load(it) }
                storeFile = frcamKeystoreFile
                storePassword = props.getProperty("storePassword", "android")
                keyAlias = props.getProperty("keyAlias", "androiddebugkey")
                keyPassword = props.getProperty("keyPassword", "android")
            }
        }
    }

    buildTypes {
        release {
            if (frcamKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("frcam")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            if (frcamKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("frcam")
            }
            isDebuggable = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.datastore)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.animation)
    implementation(libs.compose.icons)

    implementation(libs.viewmodel.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.material3)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.rtsp)
    implementation(libs.media3.hls)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring(libs.desugaring)
    testImplementation(libs.junit)
}
