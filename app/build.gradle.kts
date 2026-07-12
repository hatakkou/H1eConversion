plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.h1econversion"
    compileSdk = 34

    // ── CI/CD 用の署名設定 ─────────────────────────────────
    //    ワークフローから -P プロパティでキーストア情報を受け取る
    //    ローカルビルド時はプロパティ未設定のため無視される
    signingConfigs {
        create("release") {
            storeFile = (project.findProperty("RELEASE_STORE_FILE") as? String)?.let { rootProject.file(it) }
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as? String ?: ""
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as? String ?: ""
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as? String ?: ""
        }
    }

    defaultConfig {
        applicationId = "com.example.h1econversion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // arm64-v8a (64bit ARM) のみに限定
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // CI ビルド時のみ署名を適用（プロパティが設定されている場合）
            if (project.findProperty("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel + Compose integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // DocumentFile for SAF operations
    implementation("androidx.documentfile:documentfile:1.0.1")
}
