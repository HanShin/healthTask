import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val userHomeDirectory = System.getProperty("user.home")
val externalSigningFile = file(
    System.getenv("HEALTHTASK_SIGNING_PROPERTIES")
        ?: "$userHomeDirectory/.gradle/healthtask-signing.properties"
)
val repositorySigningFile = rootProject.file("keystore.properties")
val activeSigningFile = if (externalSigningFile.exists()) externalSigningFile else repositorySigningFile

android {
    namespace = "com.hanshin.healthtask.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hanshin.healthtask"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (activeSigningFile.exists()) {
            create("release") {
                val properties = Properties().apply { activeSigningFile.inputStream().use { load(it) } }
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.guava:guava:33.4.8-android")

    testImplementation("junit:junit:4.13.2")
}
