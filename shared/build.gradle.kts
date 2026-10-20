plugins {
    id("com.android.library")
}

android {
    namespace = "com.hanshin.healthtask.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("junit:junit:4.13.2")
}
