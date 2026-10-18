package io.mo.xatype.util

/**
 * Root shell script that force-stops the IME while keeping it as the default input method.
 *
 * AOSP InputMethodManagerService resets the selected IME and picks a new default (e.g. LatinIME)
 * when the current IME's package is force-stopped. HyperOS keeps the selection, so the restore
 * step only runs when the setting actually changed away from the saved Xiaomi IME.
 */
object ImeRestartScript {
    const val IME_PACKAGE = "com.xiaomi.type"

    /**
     * @param user target user; null keeps the shell defaults (current user, all users for force-stop).
     * @param extraPackages packages force-stopped before the IME.
     */
    fun build(user: Int? = null, extraPackages: List<String> = emptyList()): String {
        val settings = "settings" + (user?.let { " --user $it" } ?: "") + " get secure default_input_method"
        val am = "am force-stop" + (user?.let { " --user $it" } ?: "")
        // `ime --user` only exists on newer releases; the shell already targets user 0.
        val ime = "ime" + (user?.takeIf { it != 0 }?.let { " %s --user $it" } ?: " %s")
        return buildString {
            appendLine("saved=\$($settings)")
            (extraPackages + IME_PACKAGE).forEach { appendLine("$am $it") }
            appendLine("case \"\$saved\" in")
            appendLine("$IME_PACKAGE/*)")
            // The reset is normally synchronous, but allow the package monitor a moment to run.
            appendLine("  i=0")
            appendLine("  while [ \"\$i\" -lt 5 ] && [ \"\$($settings)\" = \"\$saved\" ]; do sleep 0.1; i=\$((i+1)); done")
            appendLine("  if [ \"\$($settings)\" != \"\$saved\" ]; then")
            appendLine("    ${ime.format("enable")} \"\$saved\" >/dev/null 2>&1")
            appendLine("    ${ime.format("set")} \"\$saved\" >/dev/null 2>&1")
            appendLine("    [ \"\$($settings)\" = \"\$saved\" ] || exit 3")
            appendLine("  fi")
            appendLine("  ;;")
            appendLine("esac")
            appendLine("exit 0")
        }
    }
}
