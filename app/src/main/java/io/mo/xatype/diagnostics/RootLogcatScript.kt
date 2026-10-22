package io.mo.xatype.diagnostics

/**
 * Root shell scripts for a logcat capture that outlives the module app, so collection needs no
 * foreground service. Requesting its permissions would make Android 10+ show a synthetic
 * app-info icon once the launcher icon is hidden.
 */
internal object RootLogcatScript {
    const val END_MARKER = "[collector] logcat finished"

    /** Prints the capture's process group id; `timeout` and `head -c` bound it even if the app dies. */
    fun start(
        file: String,
        uids: String,
        since: String,
        budget: Long,
        seconds: Long,
        previousGroup: Int = 0,
    ): String = buildString {
        if (previousGroup > 0) appendLine(kill(previousGroup))
        // setsid makes the inner shell a group leader so stop() reaches logcat and head as well.
        append("setsid sh -c '")
        append("echo \$\$; exec </dev/null >/dev/null 2>&1; ")
        append("timeout $seconds logcat -b main -b system -b crash -v threadtime ")
        append("--uid=$uids -T \"$since\" \"*:V\" | head -c $budget >> \"$file\"; ")
        append("echo >> \"$file\"; echo \"$END_MARKER\" >> \"$file\"")
        appendLine("' &")
    }

    fun stop(group: Int): String = "${kill(group)}; exit 0"

    private fun kill(group: Int) = "kill -TERM -$group 2>/dev/null"
}
