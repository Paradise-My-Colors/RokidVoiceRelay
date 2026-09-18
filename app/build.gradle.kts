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
        versionCode = 10
        versionName = "1.0.0-nexus"
    }

    providers.environmentVariable("VOICE_RELAY_SIGNING_FILE").orNull?.let { signingFile ->
        signingConfigs.getByName("debug") {
            storeFile = file(signingFile)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.16.0")
}
