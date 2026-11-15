import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The VertMatch client id, issued by VertMatch when it approves the app. Not a secret, but it
// names the approved app, so it comes from the build and stays out of the repository: a fork then
// cannot send traffic as OpenBridge. From -Popenbridge.vertmatch.clientId, else local.properties,
// else the environment. Empty: VertMatch shows as "not set up" and nothing can connect.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun buildSetting(property: String, environment: String): String =
    providers.gradleProperty(property).orNull
        ?: localProperties.getProperty(property)
        ?: providers.environmentVariable(environment).orNull
        ?: ""

val vertMatchClientId = buildSetting("openbridge.vertmatch.clientId", "OPENBRIDGE_VERTMATCH_CLIENT_ID").also { id ->
    require(id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        "openbridge.vertmatch.clientId may hold letters, digits, - and _ only"
    }
}

// Only once VertMatch agreed refresh tokens for the app: asks Auth0 for offline_access.
val vertMatchRefreshTokens =
    buildSetting("openbridge.vertmatch.refreshTokens", "OPENBRIDGE_VERTMATCH_REFRESH_TOKENS").toBoolean()

android {
    namespace = "tech.mmarca.openvitals.bridge"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "tech.mmarca.openvitals.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "VERTMATCH_CLIENT_ID", "\"$vertMatchClientId\"")
        buildConfigField("boolean", "VERTMATCH_REFRESH_TOKENS", vertMatchRefreshTokens.toString())
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(project(":vertmatch"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
