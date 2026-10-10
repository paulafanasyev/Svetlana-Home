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

// На GitHub Actions ошибки Kotlin, lint и упавшие тесты (unit и на эмуляторе)
// показываются аннотациями прямо в PR.
if (System.getenv("GITHUB_ACTIONS") == "true") {
    println("::add-matcher::" + rootDir.resolve("tools/kotlin-problem-matcher.json").absolutePath)
    val resultDirs = listOf(
        rootDir.resolve("app/build/test-results/testDebugUnitTest"),
        rootDir.resolve("app/build/outputs/androidTest-results/connected")
    )
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
            val re = Regex("<testcase name=\"([^\"]+)\" classname=\"([^\"]+)\"[^>]*>\\s*<(?:failure|error)[^>]*?>([^<]{0,1800})", RegexOption.DOT_MATCHES_ALL)
            var shown = 0
            resultDirs.forEach { dir ->
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".xml") }.forEach { f ->
                    re.findAll(f.readText()).forEach { m ->
                        if (shown < 15) {
                            val cls = m.groupValues[2].substringAfterLast('.')
                            val text = m.groupValues[3].replace("&#10;", " | ").replace("\n", " | ").replace("\t", " ").take(1800)
                            println("::error title=Test $cls.${m.groupValues[1]}::$text")
                            shown++
                        }
                    }
                }
            }
        }
    }
}
