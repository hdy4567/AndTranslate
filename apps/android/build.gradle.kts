plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.viitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.viitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // @ysig_audit(ticket_id="TASK-401", risk="ERR_MISSING_KEYSTORE")
            // Play Store 배포를 위한 KeyStore 정보 기입 (실제 키스토어 생성 후 사용 시 주석 수정 필수)
            val keystoreFile = file("keystore.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "password"
                keyAlias = "key0"
                keyPassword = "password"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            
            // keystore.jks가 없으면 디폴트로 테스트하게 signingConfig 무시
            if (file("keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        viewBinding = true
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
    implementation(project(":libs:stt-engine"))
    implementation(project(":libs:subtitle-renderer"))
    implementation(project(":libs:translation"))
    implementation(project(":libs:privacy"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.tensorflow.lite)
    implementation(libs.vosk.android)
    implementation(libs.kotlinx.coroutines.android)
}
