plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

val fixturesDir = rootProject.projectDir.resolve("../contracts/v1/fixtures")
tasks.withType<Test>().configureEach {
    systemProperty("gymlog.fixturesDir", fixturesDir.canonicalPath)
    inputs.dir(fixturesDir)
}
