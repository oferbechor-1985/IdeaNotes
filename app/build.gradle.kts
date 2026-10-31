plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// מספר הגרסה נקבע אוטומטית בבנייה ב-GitHub (מספר הריצה). מקומית: 1.
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
// מפתח החתימה מגיע רק מ-GitHub Secrets (משתני סביבה), לעולם לא מהקוד.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.ofer.ideanotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ofer.ideanotes"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "ideanotes"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("androidx.core:core-ktx:1.13.1")
}
