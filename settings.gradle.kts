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

// На GitHub Actions ошибки Kotlin, lint и упавшие unit-тесты показываются
// аннотациями прямо в PR.
if (System.getenv("GITHUB_ACTIONS") == "true") {
    println("::add-matcher::" + rootDir.resolve("tools/kotlin-problem-matcher.json").absolutePath)
    val testResults = rootDir.resolve("app/build/test-results/testDebugUnitTest")
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
        if (failure != null) {
            val re = Regex("<testcase name=\"([^\"]+)\" classname=\"([^\"]+)\"[^>]*>\\s*<failure message=\"([^\"]*)\"")
            var shown = 0
            testResults.listFiles()?.filter { it.name.endsWith(".xml") }?.forEach { f ->
                re.findAll(f.readText()).forEach { m ->
                    if (shown < 15) {
                        val cls = m.groupValues[2].substringAfterLast('.')
                        val text = m.groupValues[3].replace("&#10;", " | ").replace("\n", " | ").take(1500)
                        println("::error title=Test $cls.${m.groupValues[1]}::$text")
                        shown++
                    }
                }
            }
        }
    }
}
