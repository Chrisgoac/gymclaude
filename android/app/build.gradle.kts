import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Local, untracked configuration (android/local.properties). Never commit secrets.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun buildConfigString(key: String, default: String): String {
    val value = localProps.getProperty(key, default).trim()
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.chrisgoac.gymlog"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chrisgoac.gymlog"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "API_BASE_URL", buildConfigString("gymlog.apiBaseUrl", "https://gym.chrisgoac.dev"))
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", buildConfigString("clerk.publishableKey", ""))
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
    testImplementation(project(":core:testing"))
}
