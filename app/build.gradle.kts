plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.breeze.ringctl"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.breeze.ringctl"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }
    // 本地是 arm64 环境，NDK 只有 x86_64 编译器，跑不起来；
    // 传 -PenableNative=true 时才启用 native 编译（GitHub Actions 用）
    if (project.hasProperty("enableNative")) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        ndkVersion = "27.0.12077973"
        defaultConfig {
            ndk {
                abiFilters += listOf("arm64-v8a")
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")

    configurations.all {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    }
}