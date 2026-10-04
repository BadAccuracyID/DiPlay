package com.shilapi.xcertplay.gt6switch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Drain both pipes while the process runs, including output we cannot retain. */
internal class RootCommands(private val executable: String = "/debug_ramdisk/su") : CommandRunner {
    override fun run(command: String, timeoutSeconds: Long): String {
        val process = ProcessBuilder(executable, "-c", command).start()
        val output = Drain(process.inputStream)
        val error = Drain(process.errorStream)
        output.start(); error.start()
        try {
            process.outputStream.close()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("Root command timed out. Check Magisk access and retry.")
            }
            output.join(2_000); error.join(2_000)
            if (output.isAlive || error.isAlive) throw IOException("Root command output did not close")
            if (process.exitValue() != 0) throw IOException("The car rejected a switch step (exit ${process.exitValue()})")
            if (output.failure != null || error.failure != null) throw IOException("Could not read root command output")
            if (output.overflow || error.overflow) throw IOException("Root command output exceeded its limit")
            return output.text()
        } finally {
            process.destroy()
            process.inputStream.close(); process.errorStream.close()
        }
    }

    private class Drain(private val stream: InputStream) : Thread("gt6-command-output") {
        private val bytes = ByteArrayOutputStream()
        @Volatile var overflow = false
            private set
        @Volatile var failure: IOException? = null
            private set
        init { isDaemon = true }
        override fun run() {
            try {
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val retained = minOf(count, MAX_OUTPUT - bytes.size())
                    if (retained > 0) bytes.write(buffer, 0, retained)
                    if (retained < count) overflow = true
                }
            } catch (error: IOException) { failure = error }
        }
        fun text(): String = bytes.toString(Charsets.UTF_8.name())
    }

    private companion object { const val MAX_OUTPUT = 1_048_576 }
}
