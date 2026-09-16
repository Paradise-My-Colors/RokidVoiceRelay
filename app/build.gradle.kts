plugins {
    id("com.android.application")
}

android {
    namespace = "com.paradisemc.rokidcamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paradisemc.rokidcamera"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.1"
    }

    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.exclude("com/paradisemc/rokid/plugin/voicerelay/**")
    packaging {
        jniLibs {
            excludes += "**/libtdjson.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-video:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
}
