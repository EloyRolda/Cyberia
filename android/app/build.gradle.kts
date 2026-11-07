plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Cada corrida de la CI es una versión más nueva: así el celular sabe cuándo actualizarse.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: findProperty("cyberia.buildNumber")?.toString())
    ?.toIntOrNull() ?: 1

android {
    namespace = "app.cyberia"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.cyberia"
        // Android 8.0 o superior (RNF-10).
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Una sola clave para todas las versiones: Android solo acepta una actualización firmada con
    // la misma clave, y así se conservan la vinculación y los audios. La clave no está en el
    // repositorio: la CI la toma de sus secretos (ver README, «Actualizaciones»). Sin clave se
    // firma con la de depuración de la máquina, que sirve para probar pero no para actualizar.
    val keystore = System.getenv("CYBERIA_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) {
            create("cyberia") {
                storeFile = keystore
                storePassword = System.getenv("CYBERIA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CYBERIA_KEY_ALIAS") ?: "cyberia"
                keyPassword = System.getenv("CYBERIA_KEY_PASSWORD") ?: System.getenv("CYBERIA_KEYSTORE_PASSWORD")
            }
        }
    }
    val signing = signingConfigs.findByName("cyberia") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    lint {
        // La API de la PC usa un certificado propio que se valida por huella (RNF-06).
        disable += setOf("CustomX509TrustManager")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("app.cyberia:core:0.1.0")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.guava)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.zxing.android.embedded)
}
