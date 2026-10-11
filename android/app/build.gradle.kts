import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Local, untracked configuration (android/local.properties). Never commit secrets.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun buildConfigString(key: String, default: String): String = quoted(localProps.getProperty(key, default).trim())

// Release signing: android/signing.properties (gitignored, see signing.properties.example) or, in CI, the
// GYMLOG_KEYSTORE_* environment variables. Without all four values the release is built unsigned.
val signingProps = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun signingValue(property: String, env: String): String? =
    (signingProps.getProperty(property) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }

val releaseKeystore = signingValue("storeFile", "GYMLOG_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "GYMLOG_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "GYMLOG_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "GYMLOG_KEY_PASSWORD")
val canSignRelease = listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

// Overridable from CI: ./gradlew -Pgymlog.versionCode=42 -Pgymlog.versionName=1.2.0 bundleRelease
// Play accepts 1..2100000000 and every upload needs a higher code than the last one.
val appVersionCode: Int = providers.gradleProperty("gymlog.versionCode").orElse("1").get().let { raw ->
    raw.trim().toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: error("gymlog.versionCode must be an integer in 1..2100000000, got '$raw'")
}
val appVersionName: String = providers.gradleProperty("gymlog.versionName").orElse("1.0.0").get()

// Clerk publishable key (public, not a secret): local.properties, else -Pclerk.publishableKey, else the
// GYMLOG_CLERK_PUBLISHABLE_KEY env var (CI: repository variable). Empty is allowed only for unsigned builds.
val clerkPublishableKey: String = (
    localProps.getProperty("clerk.publishableKey")
        ?: providers.gradleProperty("clerk.publishableKey").orNull
        ?: System.getenv("GYMLOG_CLERK_PUBLISHABLE_KEY")
        ?: ""
    ).trim()
val liveClerkKey = Regex("^pk_live_[A-Za-z0-9+/=_-]+$")

// Public web pages the app links to (privacy policy, account deletion): the web app, same host as the API by default.
val webBaseUrl: String = localProps.getProperty("gymlog.webBaseUrl", localProps.getProperty("gymlog.apiBaseUrl", "https://gym.chrisgoac.dev"))
    .trim().trimEnd('/')

android {
    namespace = "com.chrisgoac.gymlog"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chrisgoac.gymlog"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", buildConfigString("gymlog.apiBaseUrl", "https://gym.chrisgoac.dev"))
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", quoted(clerkPublishableKey))
        buildConfigField("boolean", "SIGNED_RELEASE", "false")
        buildConfigField("String", "PRIVACY_POLICY_URL", quoted("$webBaseUrl/privacy"))
        buildConfigField("String", "ACCOUNT_DELETION_URL", quoted("$webBaseUrl/delete-account"))
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
                buildConfigField("boolean", "SIGNED_RELEASE", "true")
            }
        }
    }

    // kotlinx-coroutines' debug-agent stub; nothing loads it in the app.
    packaging.resources.excludes += "DebugProbesKt.bin"

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    // Same Compose assertions run on the JVM (Robolectric) and on a device (androidTest).
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }
}

// AGP only creates unit tests for the tested (debug) build type. The app adds `testReleaseUnitTest` for
// ReleaseConfigTest (release BuildConfig, merged manifest, packaged resources). The behaviour suite stays on
// debug: Robolectric resolves Compose test activities from the app manifest, and ui-test-manifest's
// ComponentActivity must never ship in the release one.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as HasHostTestsBuilder).hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

// A signed bundle is upload-ready: refuse to sign one whose sign-in cannot work (empty or development key).
// A separate task without outputs, so it runs even when the packaging tasks come from the build cache.
val checkReleaseClerkKey by tasks.registering {
    val signed = canSignRelease
    val keyIsLive = liveClerkKey.matches(clerkPublishableKey)
    doLast {
        if (signed && !keyIsLive) {
            throw GradleException(
                "Signed release builds need a production Clerk publishable key (pk_live_…): set clerk.publishableKey " +
                    "in local.properties, -Pclerk.publishableKey or GYMLOG_CLERK_PUBLISHABLE_KEY.",
            )
        }
    }
}
tasks.matching { it.name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle") }.configureEach {
    dependsOn(checkReleaseClerkKey)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:train"))
    implementation(project(":feature:routines"))
    implementation(project(":feature:exercises"))
    implementation(project(":feature:progress"))
    implementation(project(":feature:history"))
    implementation(project(":feature:body"))
    implementation(project(":feature:achievements"))
    implementation(project(":feature:coach"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.clerk.android.api)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Robolectric's SDK 36 shadows reach into JDK internals on JDK 17+.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    if (name == "testReleaseUnitTest") filter.includeTestsMatching("com.chrisgoac.gymlog.ReleaseConfigTest")
}
