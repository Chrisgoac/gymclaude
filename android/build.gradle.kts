plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

val expectedModules = listOf(
    ":app",
    ":core:model", ":core:domain", ":core:database", ":core:network", ":core:data",
    ":core:designsystem", ":core:testing",
    ":feature:auth", ":feature:train", ":feature:routines", ":feature:exercises",
    ":feature:progress", ":feature:history", ":feature:body", ":feature:achievements",
    ":feature:coach", ":feature:settings",
)

// Allowed project dependencies per module (design spec 5.1). Anything else fails the build.
val allowedProjectDeps: Map<String, Set<String>> = mapOf(
    ":core:model" to emptySet(),
    ":core:domain" to setOf(":core:model"),
    ":core:database" to setOf(":core:model"),
    ":core:network" to setOf(":core:model"),
    ":core:data" to setOf(":core:model", ":core:domain", ":core:database", ":core:network"),
    ":core:designsystem" to emptySet(),
    ":core:testing" to setOf(":core:model", ":core:domain"),
) + expectedModules.filter { it.startsWith(":feature:") }.associateWith {
    setOf(":core:model", ":core:domain", ":core:designsystem")
}

// Run `./gradlew verifyModuleGraph` (also wired into `check`): fails if a declared module is
// missing from the build or if a module depends on a project it must not see.
tasks.register("verifyModuleGraph") {
    group = "verification"
    description = "Asserts every spec module is included and project dependencies follow the layering rules."
    doLast {
        val present = rootProject.allprojects.map { it.path }.toSet()
        val missing = expectedModules.filterNot { it in present }
        check(missing.isEmpty()) { "Modules missing from settings.gradle.kts: $missing" }

        val violations = mutableListOf<String>()
        allowedProjectDeps.forEach { (path, allowed) ->
            val dependencies = project(path).configurations
                .flatMap { it.dependencies.withType<ProjectDependency>() }
                .map { it.path }
                .toSet()
            val bad = dependencies - allowed - path
            if (bad.isNotEmpty()) violations += "$path must not depend on $bad"
        }
        check(violations.isEmpty()) { violations.joinToString("\n") }
    }
}

// Run `./gradlew verifyUniqueResourceNames` (also run by verifyModuleGraph): library resources merge
// into one namespace in the APK, so the same name in two modules silently shadows one of them.
val verifyUniqueResourceNames = tasks.register("verifyUniqueResourceNames") {
    group = "verification"
    description = "Fails if two modules declare a value resource with the same type, qualifier and name."
    val resDirs = rootProject.allprojects.associate { it.path to it.file("src/main/res") }
    doLast {
        val declaredIn = mutableMapOf<String, MutableSet<String>>()
        val tag = Regex("<(string|plurals|string-array|integer-array|array|color|dimen|bool|integer|style|attr|declare-styleable)\\s[^>]*name=\"([^\"]+)\"")
        resDirs.forEach { (path, res) ->
            res.listFiles { dir -> dir.isDirectory && dir.name.startsWith("values") }.orEmpty().forEach { dir ->
                dir.listFiles { file -> file.extension == "xml" }.orEmpty().forEach { file ->
                    tag.findAll(file.readText()).forEach { match ->
                        declaredIn.getOrPut("${dir.name}/${match.groupValues[1]}/${match.groupValues[2]}") { mutableSetOf() } += path
                    }
                }
            }
        }
        val duplicates = declaredIn.filterValues { it.size > 1 }
        check(duplicates.isEmpty()) { "Resource names declared by several modules: " + duplicates.entries.joinToString { "${it.key} in ${it.value}" } }
        logger.lifecycle("verifyUniqueResourceNames: ${declaredIn.size} value resources, no duplicates across modules")
    }
}
tasks.named("verifyModuleGraph") { dependsOn(verifyUniqueResourceNames) }

// ---- Shared build conventions (no buildSrc: keep the scaffold small) ----
val javaVersion = JavaVersion.VERSION_17

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            namespace = "com.chrisgoac.gymlog" + path.replace("-", "").replace(":", ".")
            compileSdk = 37
            defaultConfig.minSdk = 26
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            testOptions.unitTests.isIncludeAndroidResources = true
            // Library resources merge into ONE app namespace: a shared name silently resolves to whichever
            // module wins the merge. Every feature owns a prefix (lint `ResourceName` enforces it).
            if (path.startsWith(":feature:")) resourcePrefix = path.substringAfterLast(':') + "_"
        }
    }
    // Modules using the Compose compiler plugin need the runtime on their classpath.
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        dependencies {
            add("implementation", platform(rootProject.libs.androidx.compose.bom))
            add("implementation", rootProject.libs.androidx.compose.runtime)
        }
    }
}
