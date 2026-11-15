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

// The version. scripts/release.sh rewrites these two lines, and the release pipeline reads
// them (scripts/ci-release-context.sh). A nightly overrides both from the environment: its
// versionCode is the next free one of the counter (scripts/version-code.sh).
val baseVersionCode = 1
val baseVersionName = "0.1.0"
val versionCodeOverride = providers.environmentVariable("OPENBRIDGE_VERSION_CODE")
    .map { it.toInt() }
val versionNameOverride = providers.environmentVariable("OPENBRIDGE_VERSION_NAME")
val nightlyVersionNameSuffix = versionNameOverride
    .map { "" }
    .orElse("-nightly")

// Release signing comes from the environment, so no key is ever in the repository. Without
// it, release and nightly builds are unsigned and a debug build takes the debug key.
val releaseStoreFilePath = System.getenv("OPENBRIDGE_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("OPENBRIDGE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("OPENBRIDGE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OPENBRIDGE_RELEASE_KEY_PASSWORD")
// A PKCS12 store has one password for the store and its keys.
val isPkcs12ReleaseStore = releaseStoreFilePath
    ?.lowercase()
    ?.let { it.endsWith(".p12") || it.endsWith(".pfx") || it.endsWith(".pkcs12") }
    ?: false
val effectiveReleaseKeyPassword = if (isPkcs12ReleaseStore) releaseStorePassword else releaseKeyPassword
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    effectiveReleaseKeyPassword,
).all { !it.isNullOrBlank() }

// The release pipeline publishes a debug APK beside the release one, signed with the release
// key and not debuggable, since it is public.
val signDebugWithReleaseKey = System.getenv("OPENBRIDGE_SIGN_DEBUG_WITH_RELEASE_KEY") == "true"
val publishedDebugApk = System.getenv("OPENBRIDGE_PUBLISHED_DEBUG_APK") == "true"

android {
    namespace = "tech.mmarca.openvitals.bridge"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "tech.mmarca.openvitals.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOverride.orElse(baseVersionCode).get()
        versionName = versionNameOverride.orElse(baseVersionName).get()
        buildConfigField("String", "VERTMATCH_CLIENT_ID", "\"$vertMatchClientId\"")
        buildConfigField("boolean", "VERTMATCH_REFRESH_TOKENS", vertMatchRefreshTokens.toString())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseStoreFilePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(effectiveReleaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (signDebugWithReleaseKey && hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            if (publishedDebugApk) {
                isDebuggable = false
            }
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
        // The nightly: the release build under a version name that says so. It has the
        // release's application id, so it updates in place and shares its VertMatch redirect.
        create("nightly") {
            initWith(getByName("release"))
            versionNameSuffix = nightlyVersionNameSuffix.get()
            matchingFallbacks += listOf("release")
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
