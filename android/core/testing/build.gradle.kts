plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
}
