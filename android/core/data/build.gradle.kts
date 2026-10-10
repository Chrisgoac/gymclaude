plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    implementation(project(":core:model"))
    api(project(":core:domain"))
    api(project(":core:database"))
    api(project(":core:network"))
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
}

// Robolectric's SDK 36 shadows reach into JDK internals on JDK 17+.
// Shared contract fixtures are read in place from <repo>/contracts/v1/fixtures, never copied.
val fixturesDir = rootProject.projectDir.resolve("../contracts/v1/fixtures")
tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("gymlog.fixturesDir", fixturesDir.canonicalPath)
    inputs.dir(fixturesDir)
}
