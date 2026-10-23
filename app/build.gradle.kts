plugins {
    alias(libs.plugins.android.application)
    // AGP 9 內建 Kotlin 支援，所以不再套用 org.jetbrains.kotlin.android。
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.dsh.overlaytranslator"

    // Compose 1.12+（BOM 2026.09.00）要求 compileSdk 37。
    // targetSdk 保持 35，因為測試機是 Android 15；這樣測到的行為就是使用者實際會遇到的。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dsh.overlaytranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation("junit:junit:4.13.2")
}
