package io.mo.xatype.diagnostics

import org.junit.Assert.*
import org.junit.Test

class RootLogcatScriptTest {
    private val file = "/data/user/0/io.mo.xatype/files/input-diagnostic.txt"

    @Test fun captureIsDetachedBoundedAndReportsItsGroup() {
        val script = RootLogcatScript.start(file, "10123,10456", "10-22 12:00:00.000", 4096, 600)
        assertTrue(script.startsWith("setsid sh -c 'echo \$\$; exec </dev/null >/dev/null 2>&1; "))
        assertTrue(script.contains("timeout 600 logcat "))
        assertTrue(script.contains("--uid=10123,10456 -T \"10-22 12:00:00.000\" \"*:V\""))
        assertTrue(script.contains("| head -c 4096 >> \"$file\"; "))
        assertTrue(script.contains("echo \"${RootLogcatScript.END_MARKER}\" >> \"$file\""))
        assertTrue(script.trimEnd().endsWith("' &"))
        // The inner script is single-quoted, so it must not contain a single quote itself.
        assertEquals(2, script.count { it == '\'' })
    }

    @Test fun staleCaptureIsKilledWithoutEndingTheScript() {
        val script = RootLogcatScript.start(file, "1,2", "10-22 12:00:00.000", 1, 600, previousGroup = 321)
        assertTrue(script.startsWith("kill -TERM -321 2>/dev/null\nsetsid "))
        assertFalse(script.contains("exit"))
    }

    @Test fun stopKillsTheWholeGroupAndAlwaysSucceeds() {
        assertEquals("kill -TERM -321 2>/dev/null; exit 0", RootLogcatScript.stop(321))
    }
}
