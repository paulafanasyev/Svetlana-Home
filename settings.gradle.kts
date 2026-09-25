pluginManagement {
    repositories {
        maven { url = uri("/root/maven/localMvnRepository") }
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
        maven { url = uri("/root/maven/localMvnRepository") }
        google()
        mavenCentral()
    }
}

rootProject.name = "SvetlanaHome"
include(":app")
