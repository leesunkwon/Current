plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kotlinsun.current.engine.webview"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:browser-engine"))
    implementation(libs.androidx.webkit)
}
