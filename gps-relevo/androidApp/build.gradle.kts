import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // The app code lives under .app, apart from the shared packages (geo, road, drive...).
    namespace = "io.github.brunovinicioslg.ladeira.app"
    // The latest AndroidX libraries need compileSdk 37; targetSdk stays at 36 (Play requirement).
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.brunovinicioslg.ladeira"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MapLibre ships native code for four CPU types (about 45 MB). For a quick install on one
        // device, `-Pabi=x86_64` (emulator) or `-Pabi=arm64-v8a` (phones) builds just that one.
        // Opt-in only: normal builds keep every CPU type, ChromeOS included.
        //noinspection ChromeOsAbiSupport
        providers.gradleProperty("abi").orNull?.let { ndk { abiFilters += it } }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Version-update checks would fail the build whenever a library ships a new release.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // targetSdk 36 is deliberate (the Play requirement); 37 comes after testing Android 17 behavior changes.
        disable += "OldTargetApi"
        // Brought in by MapLibre's Timber dependency; this app logs with android.util.Log on purpose.
        disable += "LogNotTimber"
    }

    // Lets Android 13+ users pick the app language (Portuguese or English) apart from the system's.
    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Keeps the APK free of the Google-encrypted dependency blob (required by F-Droid).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric's Android 16 runtime reaches into JDK internals that JDK 17+ no longer exports.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
