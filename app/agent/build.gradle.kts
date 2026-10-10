import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// AOSP platform test key (public). TrebleDroid is signed with it, so signing the agent with it
// grants signature-level permissions. Kept outside the repo in ~/.config/or1/.
val platformKeystore = File(System.getProperty("user.home"), ".config/or1/platform.p12")

android {
    namespace = "com.or1.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.or1.agent"
        minSdk = 31
        targetSdk = 33
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("platform") {
            storeFile = platformKeystore
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("platform")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.anthropic:anthropic-java:2.71.0")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}
