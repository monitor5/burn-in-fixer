plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.burnin.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.burnin.scanner"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        debug {
            // 디버그 가능 빌드는 ART가 다 쓴 지역 변수도 GC 루트로 붙잡고 최적화도 덜 한다.
            // 512MB 힙에서 측정을 끝까지 돌리려면 실측용 빌드도 디버그 불가로 둔다(로그는 그대로).
            isDebuggable = false
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
