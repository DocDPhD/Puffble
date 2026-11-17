import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing reads companion/keystore.properties (git-ignored), with:
//   storeFile=/absolute/path/to/puffble-release.jks
//   storePassword=...
//   keyAlias=puffble
//   keyPassword=...
// Without that file, release builds are left unsigned and debug builds are unaffected.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // Kotlin source package; the installed app id is applicationId below.
    namespace = "dev.proxywatch.companion"
    // PebbleKit Android 2 (1.3.2) requires compiling against API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.docdphd.puffble"
        // 33 keeps the BLE code on the non-deprecated GATT API only.
        minSdk = 33
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.rebble.pebblekit2:client:1.3.2")
    testImplementation("junit:junit:4.13.2")
}
