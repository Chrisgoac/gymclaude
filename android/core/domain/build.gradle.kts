plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}

// Domain fixtures shared with the PWA are read in place from <repo>/contracts/domain, never copied.
val domainContractsDir = rootProject.projectDir.resolve("../contracts/domain")
tasks.withType<Test>().configureEach {
    systemProperty("gymlog.domainContractsDir", domainContractsDir.canonicalPath)
    inputs.dir(domainContractsDir)
}
