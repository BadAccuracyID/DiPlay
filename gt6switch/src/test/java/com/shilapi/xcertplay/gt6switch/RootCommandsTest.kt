package com.shilapi.xcertplay.gt6switch

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RootCommandsTest {
    private val commands = RootCommands("/bin/sh")

    @Test fun drainsLargeStdoutAndStderrWithoutWaitingOnFullPipes() {
        val script = "i=0; while [ \"\$i\" -lt 10000 ]; do printf 0123456789abcdef; printf fedcba9876543210 >&2; i=\$((i+1)); done"
        val output = commands.run(script, 10)
        assertEquals(160_000, output.length)
        assertTrue(output.startsWith("0123456789abcdef"))
    }

    @Test fun excessiveOutputIsBoundedAndRejectedAfterDraining() {
        val script = "i=0; while [ \"\$i\" -lt 70000 ]; do printf 0123456789abcdef; i=\$((i+1)); done"
        val failure = assertThrows(IOException::class.java) { commands.run(script, 10) }
        assertTrue(failure.message!!.contains("exceeded its limit"))
    }

    @Test fun timeoutTerminatesCommandPromptly() {
        val start = System.nanoTime()
        val failure = assertThrows(IOException::class.java) { commands.run("exec sleep 30", 1) }
        assertTrue(failure.message!!.contains("timed out"))
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
    }

    @Test fun nonzeroExitDoesNotExposeCommandStderr() {
        val failure = assertThrows(IOException::class.java) { commands.run("echo private-value >&2; exit 7", 2) }
        assertTrue(failure.message!!.contains("exit 7"))
        assertFalse(failure.message!!.contains("private-value"))
    }
}
