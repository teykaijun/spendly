import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Optional release signing.
 *
 * In-app updates only work if every build is signed with the *same* key —
 * Android refuses to install an update signed by a different one. The debug
 * keystore is generated per machine, so it cannot be that key. Create
 * `keystore.properties` (git-ignored, see keystore.properties.example) to sign
 * releases properly; without it the build still works and falls back to debug
 * signing, but those APKs cannot update each other across machines.
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

/**
 * The single source of truth for the app's version.
 *
 * versionCode is derived from the name rather than maintained separately,
 * because the updater reads the version out of the GitHub tag and computes the
 * code the same way. Keeping two numbers in sync by hand guarantees they drift,
 * and when they do the app offers an update it then refuses to install.
 *
 * Two digits per component, so every part must stay below 100. "1.10" therefore
 * outranks "1.9", which a plain string comparison would get backwards.
 */
val appVersionName = "1.1"

val appVersionCode = appVersionName.split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    require(minor < 100 && patch < 100) {
        "versionName '$appVersionName' has a component of 100 or more, which the " +
            "updater's two-digits-per-part scheme cannot represent."
    }
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "com.spendly"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.spendly"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // Still produces an installable APK for local use; just not one
                // that can be updated from a build made elsewhere.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The updater compares BuildConfig.VERSION_CODE against the release feed.
        buildConfig = true
    }

    lint {
        // Library-scoped suppressions live in app/lint.xml so they cannot
        // accidentally silence the same issue in our own code.
        lintConfig = file("lint.xml")
        // Adaptive icons genuinely need the -v26 qualifier; AAPT rejects the
        // plain "anydpi" folder that ObsoleteSdkInt suggests merging into.
        disable += "ObsoleteSdkInt"
        // We pin versions to what this machine's SDK and Gradle cache hold.
        disable += "AndroidGradlePluginVersion"
        // Test libraries are pinned to match their runtime counterparts —
        // kotlinx-coroutines-test must equal the kotlinx-coroutines-core the app
        // resolves (1.9.0), so "a newer version exists" is not actionable.
        disable += "NewerVersionAvailable"
        warningsAsErrors = false
        abortOnError = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.pdfbox.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
