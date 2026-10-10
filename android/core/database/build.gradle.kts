plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Same isolation assertions run on the JVM (Robolectric) and on a device (androidTest).
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        // MigrationTestHelper reads the exported schema JSON files as assets.
        getByName("test").assets.directories.add("$projectDir/schemas")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }
}

// Exported schemas are committed: they are the baseline for future migration tests.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Robolectric's SDK 36 shadows reach into JDK internals on JDK 17+.
// Shared contract fixtures are read in place from <repo>/contracts/v1/fixtures, never copied.
val fixturesDir = rootProject.projectDir.resolve("../contracts/v1/fixtures")
tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("gymlog.fixturesDir", fixturesDir.canonicalPath)
    inputs.dir(fixturesDir)
}
