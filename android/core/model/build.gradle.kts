plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

// Shared contract fixtures live at <repo>/contracts/v1/fixtures and are read in place, never copied.
tasks.test {
    useJUnit()
    systemProperty(
        "gymlog.fixturesDir",
        rootProject.projectDir.resolve("../contracts/v1/fixtures").canonicalPath,
    )
    inputs.dir(rootProject.projectDir.resolve("../contracts/v1/fixtures"))
}
