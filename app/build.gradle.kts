plugins {
    id("com.android.application")
}

android {
    namespace = "com.paradisemc.rokid.plugin.voicerelay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paradisemc.rokid.plugin.voicerelay"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.16.0")
}
