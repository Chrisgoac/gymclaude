pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gymlog"

include(
    ":app",
    ":core:model",
    ":core:domain",
    ":core:testing",
    ":core:database",
    ":core:network",
    ":core:data",
    ":core:designsystem",
    ":feature:auth",
    ":feature:train",
    ":feature:routines",
    ":feature:exercises",
    ":feature:progress",
    ":feature:history",
    ":feature:body",
    ":feature:achievements",
    ":feature:coach",
    ":feature:settings",
)
