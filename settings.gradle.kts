// --- CI DEBUG (temporary, REMOVE AFTER DIAGNOSIS): on any build failure, push error details to ci-debug-log branch
gradle.buildFinished { result ->
    val ciFailure = result.failure
    if (ciFailure != null) {
        try {
            val ciWs = System.getenv("GITHUB_WORKSPACE") ?: ""
            if (ciWs.isNotEmpty()) {
                val ciConsole = File(ciWs, "ci-debug/console.log")
                val ciTail = if (ciConsole.exists()) {
                    ciConsole.readLines().takeLast(300).joinToString("\n")
                } else {
                    "no console captured"
                }
                val ciChain = buildString {
                    var ciT: Throwable? = ciFailure
                    var ciD = 0
                    while (ciT != null && ciD < 6) {
                        val ciCur = ciT
                        append(ciCur).append("\n")
                        ciCur.stackTrace.take(25).forEach { append("  at ").append(it).append("\n") }
                        ciT = ciCur.cause
                        ciD++
                    }
                }.take(20000)
                File(ciWs, "ci-debug/error.txt").writeText(
                    "FAILURE MESSAGE: " + (ciFailure.message ?: "unknown") + "\n\n" + ciChain +
                        "\n\nCONSOLE TAIL (last 300 lines):\n" + ciTail
                )
                val ciSafeMsg = (ciFailure.message ?: "unknown").replace(Regex("[^a-zA-Z0-9 :.]"), "_").take(100)
                val ciP = ProcessBuilder(
                    "bash", "-lc",
                    "cd '" + ciWs + "' && git config user.email ci-debug@local && git config user.name 'CI Debug' && git add -f ci-debug/error.txt && git commit -m 'CI-ERROR: " + ciSafeMsg + "' && git push -f origin HEAD:refs/heads/ci-debug-log 2>&1 | tail -5"
                ).redirectErrorStream(true).start()
                ciP.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                val ciOut = ciP.inputStream.bufferedReader().readText()
                System.err.println("=== CI-DEBUG-PUSH ===\n" + ciOut)
            }
        } catch (ciT: Throwable) {
            System.err.println("ci-debug hook failed: " + ciT)
        }
    }
}
// --- END CI DEBUG (hook) ---
// --- CI DEBUG (temporary): capture full build console; removed after diagnosis
apply(from = "ci-debug-setup.kts")
// --- END CI DEBUG (console capture) ---

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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.8.0")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RedLauncherBeta"
include(":RedLauncherBeta")
include(":LWJGL")
include(":LWJGL:lwjgl-3.3.3")
project(":LWJGL:lwjgl-3.3.3").projectDir = file("LWJGL/3.3.3")
include(":LWJGL:lwjgl-3.4.1")
project(":LWJGL:lwjgl-3.4.1").projectDir = file("LWJGL/3.4.1")
include(":LayerController")
include(":ColorPicker")
include(":Terracotta")
include(":InputMap")
