// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp.plugin) apply false
    kotlin("plugin.serialization") version libs.versions.kotlin apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt) apply false
}

buildscript {
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.buildkeys)
    }
}

// --- CI DEBUG (temporary, remove after diagnosis): on failure, push captured console tail to ci-debug-log branch
gradle.buildFinished { result ->
    if (result.failure != null) {
        try {
            val ws = System.getenv("GITHUB_WORKSPACE")
            if (ws != null) {
                val debugDir = File(ws, "ci-debug")
                val console = File(debugDir, "console.log")
                val tail = if (console.exists()) {
                    console.readLines().takeLast(300).joinToString("\n")
                } else {
                    "no console captured"
                }
                val summary = "BUILD FAILED: " + (result.failure.message ?: "unknown")
                File(debugDir, "error.txt").writeText(
                    summary + "\n\nSTACK:\n" + (result.failure.stackTraceToString() ?: "").take(20000) +
                        "\n\nCONSOLE TAIL (last 300 lines):\n" + tail
                )
                val firstLine = summary.replace("\n", " ").take(140).replace("\"", "'")
                val cmd = "cd " + ws +
                    " && git config user.email ci-debug@local && git config user.name 'CI Debug' " +
                    "&& git add -f ci-debug/error.txt && git commit -m 'CI-ERROR: " + firstLine + "' " +
                    "&& git push -f origin HEAD:refs/heads/ci-debug-log 2>&1 | tail -5"
                val p = ProcessBuilder("bash", "-lc", cmd).redirectErrorStream(true).start()
                p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                val out = p.inputStream.bufferedReader().readText()
                System.err.println("=== CI-DEBUG-PUSH ===\n" + out)
            }
        } catch (t: Throwable) {
            System.err.println("ci-debug hook failed: " + t)
        }
    }
}
// --- END CI DEBUG ---