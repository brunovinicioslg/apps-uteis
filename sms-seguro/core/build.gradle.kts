import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Protocol, message format and SMS transport logic, free of Android so it runs in plain JVM tests.
// Java 21: required by libsignal 0.103+.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Compiled against the desktop build; the Android app brings libsignal-android (same classes,
    // phone native libraries) so the 150 MB desktop jar never reaches the APK.
    compileOnly(libs.libsignal.client)
    testImplementation(libs.libsignal.client)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
