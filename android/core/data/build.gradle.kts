plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)
    implementation(libs.play.billing.ktx)

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
    // The shipped catalog asset is read in place from :app, never copied.
    val catalogAsset = rootProject.projectDir.resolve("app/src/main/assets/exercises-v1.json")
    systemProperty("gymlog.catalogAsset", catalogAsset.canonicalPath)
    inputs.file(catalogAsset)
    // Domain parity fixtures (insight-cases.json) are read in place from <repo>/contracts/domain.
    val domainContractsDir = rootProject.projectDir.resolve("../contracts/domain")
    systemProperty("gymlog.domainContractsDir", domainContractsDir.canonicalPath)
    inputs.dir(domainContractsDir)
}
