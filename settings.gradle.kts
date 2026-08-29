import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream

// --- CI DEBUG (temporary, remove after diagnosis): tee full build console to ci-debug/console.log ---
val ciDebugWs = System.getenv("GITHUB_WORKSPACE")
if (ciDebugWs != null) {
    try {
        val origOut = System.out
        val origErr = System.err
        val debugDir = File(ciDebugWs, "ci-debug")
        debugDir.mkdirs()
        val outStream = PrintStream(BufferedWriter(FileWriter(File(debugDir, "console.log")), Charsets.UTF_8), true)
        val errStream = PrintStream(BufferedWriter(FileWriter(File(debugDir, "console-err.log")), Charsets.UTF_8), true)
        System.setOut(PrintStream(object : OutputStream() {
            override fun write(b: Int) { origOut.write(b); outStream.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { origOut.write(b, off, len); outStream.write(b, off, len) }
        }, true, Charsets.UTF_8))
        System.setErr(PrintStream(object : OutputStream() {
            override fun write(b: Int) { origErr.write(b); errStream.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { origErr.write(b, off, len); errStream.write(b, off, len) }
        }, true, Charsets.UTF_8))
    } catch (_: Throwable) {
    }
}
// --- END CI DEBUG ---

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
