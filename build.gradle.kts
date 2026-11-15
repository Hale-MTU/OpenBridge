plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// The gate, as CI runs it (.github/workflows/verify.yml). Locally: ./gradlew verifyCi.
tasks.register("verifyCiUnitTest") {
    group = "verification"
    description = "Runs the VertMatch client's tests and the app's unit tests."
    dependsOn(":vertmatch:test", ":app:testDebugUnitTest")
}

tasks.register("verifyCiPreflight") {
    group = "verification"
    description = "Assembles the debug app, so the manifest merges and the app packages."
    dependsOn(":app:assembleDebug")
}

tasks.register("verifyCiLint") {
    group = "verification"
    description = "Runs Android lint on the debug app."
    dependsOn(":app:lintDebug")
}

tasks.register("verifyCi") {
    group = "verification"
    description = "The whole gate: unit tests, preflight and lint."
    dependsOn("verifyCiUnitTest", "verifyCiPreflight", "verifyCiLint")
}
