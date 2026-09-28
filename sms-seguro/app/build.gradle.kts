import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.brunovinicioslg.sigilo.app"
    // The latest AndroidX libraries need compileSdk 37; targetSdk stays at 36 (Play requirement).
    compileSdk {
        version = release(37)
    }
    // Only for stripping debug symbols: libsignal's native libraries arrive unstripped (~120 MB each).
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.brunovinicioslg.sigilo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // libsignal and SQLCipher ship native code for several CPU types. For a quick install on one
        // device, `-Pabi=x86_64` (emulator) or `-Pabi=arm64-v8a` (phones) builds just that one.
        // Opt-in only: normal builds keep every CPU type, ChromeOS included.
        //noinspection ChromeOsAbiSupport
        providers.gradleProperty("abi").orNull?.let { ndk { abiFilters += it } }
        // Screenshots are blocked in the app; `-PallowScreenshots` lifts that for UI testing only.
        buildConfigField("boolean", "SECURE_WINDOW", (!providers.gradleProperty("allowScreenshots").isPresent).toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // libsignal 0.103+ is built for Java 21, and so is the core module.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // libsignal uses Java APIs newer than Android 8.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Version-update checks would fail the build whenever a library ships a new release.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // targetSdk 36 is deliberate (the Play requirement); 37 comes after testing Android 17 behavior changes.
        disable += "OldTargetApi"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        // A test-only build of libsignal (145 MB per CPU type) that the AAR carries along.
        jniLibs.excludes += "**/libsignal_jni_testing.so"
        resources.excludes += setOf(
            // libsignal-android depends on the desktop libsignal-client jar, which carries ~330 MB of
            // native code for macOS, Linux and Windows; the phone code comes from the AAR instead.
            "libsignal_jni*.so",
            "libsignal_jni*.dylib",
            "signal_jni*.dll",
            // BouncyCastle's multi-release jar metadata; not needed on Android.
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    // Keeps the APK free of the Google-encrypted dependency blob (required by F-Droid).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric's Android 16 runtime reaches into JDK internals that JDK 17+ no longer exports.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))
    // Same classes as the desktop libsignal-client the core compiles against, with phone native code.
    implementation(libs.libsignal.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    // Only for Argon2id (password hashing); R8 drops the rest of the library.
    implementation(libs.bouncycastle)

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
    // The desktop build runs libsignal on the test machine (the Android one has only phone libraries).
    testImplementation(libs.libsignal.client)
    // Tests use Android's own SQLite (no SQLCipher native code on the test machine).
    testImplementation(libs.androidx.sqlite.framework)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
