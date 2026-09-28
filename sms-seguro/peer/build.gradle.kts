import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// A second "phone" on the computer: talks to the app in the emulator through injected SMS, to
// test the encrypted conversation end to end without two real phones.
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

application {
    mainClass.set("io.github.brunovinicioslg.sigilo.peer.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.libsignal.client)
}
