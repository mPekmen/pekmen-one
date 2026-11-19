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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "pekmen-one"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:database")
include(":core:datastore")
include(":core:platform")
include(":core:data")
include(":core:portability")
include(":core:cloud")
include(":feature:focus")
include(":feature:learning")
include(":feature:planner")
include(":feature:insights")
include(":feature:settings")
include(":feature:circles")
