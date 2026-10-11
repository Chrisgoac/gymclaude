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
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", buildConfigString("gymlog.apiBaseUrl", "https://gym.chrisgoac.dev"))
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", buildConfigString("clerk.publishableKey", ""))
        buildConfigField("String", "PRIVACY_POLICY_URL", quoted("$webBaseUrl/privacy"))
        buildConfigField("String", "ACCOUNT_DELETION_URL", quoted("$webBaseUrl/delete-account"))
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

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
}
