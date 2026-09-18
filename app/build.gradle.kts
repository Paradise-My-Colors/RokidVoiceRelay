plugins {
    id("com.android.application")
}

android {
    namespace = "com.paradisemc.rokid.plugin.voicerelay"
    compileSdk = 36

    defaultConfig {
        // Separate installation preserves v0.8: its published signer was not retained.
        applicationId = "com.paradisemc.rokid.aiui.voicerelay"
        minSdk = 30
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.0-link-beta"
    }

    // CI must use the restored Voice Relay key, not a runner-generated default.
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
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
}
