plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.viitor.stt"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":libs:audio-capture"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.tensorflow.lite)
    implementation(libs.vosk.android)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation("junit:junit:4.13.2")
}
