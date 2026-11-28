plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bosmuda.translatelayar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bosmuda.translatelayar"
        minSdk = 26
        targetSdk = 34
        // Nomor build dari GitHub Actions (GITHUB_RUN_NUMBER), dipakai untuk cek pembaruan.
        // Di luar GitHub tetap 1.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    // Kunci tanda tangan tetap, diambil dari GitHub Secrets saat build di GitHub.
    // Kalau tidak ada (misalnya build di HP), tetap pakai kunci debug bawaan.
    // Dengan kunci tetap, APK baru bisa menimpa (update) APK lama.
    signingConfigs {
        create("rilis") {
            val berkas = System.getenv("KEYSTORE_FILE")
            if (!berkas.isNullOrBlank()) {
                storeFile = file(berkas)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("rilis")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")

    // OCR di HP (tanpa internet). Modul tambahan untuk Mandarin, Jepang, dan Korea.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // Terjemahan offline di HP (gratis) dan deteksi bahasa otomatis
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
