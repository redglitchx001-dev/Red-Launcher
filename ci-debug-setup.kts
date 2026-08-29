import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream

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
