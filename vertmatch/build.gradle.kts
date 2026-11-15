// The VertMatch partner API client (https://vertmatch.run/developers): sign-in,
// upload, delete, the training summary and the documented error handling.
// Plain Kotlin/JVM, so it builds and tests without the Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Public API surface stays deliberate: nothing is exported by accident.
        explicitApi()
    }
}

dependencies {
    // Only the JSON tree API is used: no compiler plugin, no generated serializers.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
