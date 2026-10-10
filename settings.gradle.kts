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

// На GitHub Actions ошибки Kotlin и lint показываются аннотациями прямо в PR.
if (System.getenv("GITHUB_ACTIONS") == "true") {
    println("::add-matcher::" + rootDir.resolve("tools/kotlin-problem-matcher.json").absolutePath)
    @Suppress("DEPRECATION")
    gradle.buildFinished {
        var t: Throwable? = failure
        var n = 0
        while (t != null && n < 8) {
            val msg = (t.message ?: t.javaClass.name).replace("\r", "").replace("\n", " | ").take(3500)
            println("::error title=Gradle failure $n::$msg")
            t = t.cause
            n++
        }
    }
}
