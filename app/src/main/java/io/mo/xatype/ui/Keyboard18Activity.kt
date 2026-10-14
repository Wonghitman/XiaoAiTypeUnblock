package io.mo.xatype.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import io.mo.xatype.keyboard18.Keyboard18Dictionary
import io.mo.xatype.keyboard18.Keyboard18Engine
import io.mo.xatype.keyboard18.Keyboard18Keys
import io.mo.xatype.keyboard18.Keyboard18Store
import java.util.concurrent.Executors

/** Interactive local preview uses the same dictionary and decoder as the host hook. */
class Keyboard18Activity : AppCompatActivity() {
    private lateinit var style: ShapeUiStyle
    private lateinit var enabled: SwitchCompat
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var composing: TextView
    private lateinit var output: EditText
    private lateinit var candidateRow: LinearLayout
    private lateinit var languageButton: AppCompatButton
    private lateinit var shiftButton: AppCompatButton
    private val letterButtons = LinkedHashMap<String, AppCompatButton>()
    private val previewControls = ArrayList<View>()
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: Keyboard18Engine? = null
    private var english = false
    private var uppercase = false
    private var pendingRestart = false
    private var loading = false
    private var restoreRaw = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        style = ShapeUiStyle(this)
        english = savedInstanceState?.getBoolean("english") ?: false
        uppercase = savedInstanceState?.getBoolean("uppercase") ?: false
        pendingRestart = savedInstanceState?.getBoolean("pending_restart") ?: false
        restoreRaw = savedInstanceState?.getString("raw").orEmpty()
        val root = column().apply { setBackgroundColor(style.background) }
        val toolbar = Toolbar(this).apply {
            title = "18键键盘"
            setTitleTextColor(style.primary)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(style.primary)
            setNavigationContentDescription("返回")
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, style.dp(ShapeUiStyle.BUTTON)))
        val body = column().apply { setPadding(style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.SM),
            style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.XL)) }
        val scroll = NestedScrollView(this).apply { isFillViewport = true
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(text("更宽的按键，更轻松地输入", ShapeUiStyle.TITLE, strong = true))
        body.addView(text("按图片的 7 · 6 · 5 布局。中文点按分组键；长按可选择准确字母、数字及快捷操作。",
            ShapeUiStyle.BODY, secondary = true), spaced())
        val settings = card()
        enabled = SwitchCompat(this).apply {
            text = "在小爱中启用18键"
            textSize = ShapeUiStyle.HEADING
            setTextColor(style.primary)
            minHeight = style.dp(ShapeUiStyle.BUTTON)
            isChecked = Keyboard18Store.prefs(this@Keyboard18Activity).getBoolean(Keyboard18Store.ENABLED, false)
            setOnCheckedChangeListener { _, checked ->
                runCatching { Keyboard18Store.setEnabled(this@Keyboard18Activity, checked) }
                    .onSuccess { pendingRestart = true; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); updateStatus() }
                    .onFailure { Toast.makeText(this@Keyboard18Activity, it.message, Toast.LENGTH_LONG).show()
                        setOnCheckedChangeListener(null); isChecked = !checked; recreate() }
            }
        }
        settings.addView(enabled, LinearLayout.LayoutParams(-1, -2))
        settings.addView(text("启用后重启输入法，并在小爱中选择「拼音26键」。18键与虎码/五笔互斥；词库和输入均在本地。",
            ShapeUiStyle.CAPTION, secondary = true), spaced())
        status = text("", ShapeUiStyle.CAPTION, secondary = true)
        settings.addView(status, spaced())
        body.addView(settings, spaced())

        body.addView(text("试试18键", ShapeUiStyle.HEADING, strong = true), spaced())
        body.addView(text("下方预览可以直接输入，无需启用模块。它使用与宿主相同的离线解码器。",
            ShapeUiStyle.CAPTION, secondary = true), spaced())
        val preview = card()
        output = EditText(this).apply {
            hint = "输入的文字显示在这里"
            textSize = ShapeUiStyle.BODY
            setTextColor(style.primary); setHintTextColor(style.secondary)
            minHeight = style.dp(PREVIEW_OUTPUT_HEIGHT)
            maxLines = 6
            showSoftInputOnFocus = false
            setText(savedInstanceState?.getString("text").orEmpty())
            setSelection(text.length)
        }
        preview.addView(output, LinearLayout.LayoutParams(-1, -2))
        composing = text("", ShapeUiStyle.BODY)
        preview.addView(composing, spaced())
        candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false
            addView(candidateRow, android.widget.FrameLayout.LayoutParams(-2, -2)) }, LinearLayout.LayoutParams(-1, -2))
        Keyboard18Keys.rows.forEachIndexed { rowIndex, groups ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
            if (rowIndex == 2) {
                shiftButton = keyButton("⇧") { flush(); uppercase = !uppercase; labels() }
                row.addView(shiftButton, keyParams(1.3f))
            }
            groups.forEach { group ->
                val button = keyButton(group) {
                    val decoder = engine ?: return@keyButton
                    if (english || uppercase) { flush(); insert(if (uppercase) group.first().uppercaseChar().toString() else group.first().toString()) }
                    else showResult(decoder.input(Keyboard18Keys.code(group).toChar()))
                }
                button.contentDescription = "${group.uppercase()}键，长按选择字母或快捷操作"
                button.setOnLongClickListener { popup(group); true }
                letterButtons[group] = button
                row.addView(button, keyParams(if (group.length == 2) 1.7f else 1f))
            }
            if (rowIndex == 2) {
                val delete = keyButton("⌫") {
                    val decoder = engine ?: return@keyButton
                    if (decoder.raw.isNotEmpty()) showResult(decoder.backspace()) else {
                        val start = output.selectionStart.coerceAtLeast(0)
                        val end = output.selectionEnd.coerceAtLeast(start)
                        if (end > start) output.text.delete(start, end)
                        else if (start > 0) output.text.delete(Character.offsetByCodePoints(output.text, start, -1), start)
                    }
                }
                delete.contentDescription = "退格，长按清空预览"
                delete.setOnLongClickListener { engine?.clear(); output.text.clear(); showCandidates(); true }
                row.addView(delete, keyParams(1.3f))
            }
            preview.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(keyButton("符") { symbols() }, keyParams(1f))
        bottom.addView(keyButton("123") { choices("数字", (0..9).map(Int::toString)) { literal(it) } }, keyParams(1f))
        bottom.addView(keyButton("，") { literal(if (english) "," else "，") }, keyParams(0.7f))
        bottom.addView(keyButton("空格") { val decoder = engine ?: return@keyButton
            if (decoder.raw.isNotEmpty()) showResult(decoder.input(' ')) else insert(" ") }, keyParams(2.5f))
        bottom.addView(keyButton("。") { literal(if (english) "." else "。") }, keyParams(0.7f))
        languageButton = keyButton("中") { flush(); english = !english; labels(); showCandidates() }
        bottom.addView(languageButton, keyParams(1f))
        bottom.addView(keyButton("↵", accent = true) { flush(); insert("\n") }, keyParams(1f))
        preview.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        progress = text("正在准备离线词库…", ShapeUiStyle.CAPTION, secondary = true)
        preview.addView(progress, spaced())
        preview.addView(button("清空预览") { engine?.clear(); output.text.clear(); showCandidates() }, spaced())
        body.addView(preview, spaced())
        body.addView(text("此原型限定小爱0.2.1053，尚未运行测试。使用独立离线候选，不沿用讯飞云候选；平板分离布局暂保留原布局。",
            ShapeUiStyle.CAPTION, secondary = true), spaced())
        body.addView(button("重新读取词库") { prepareDictionary() }, spaced())

        val footer = column().apply { setPadding(style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.SM),
            style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.SM)); setBackgroundColor(style.surface) }
        footer.addView(button("重启输入法并应用", accent = true) {
            ImeRestarter.restart(this) { if (!isDestroyed && !isFinishing) updateStatus() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(footer)
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        window.statusBarColor = style.background; window.navigationBarColor = style.surface
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !style.dark; isAppearanceLightNavigationBars = !style.dark }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        labels(); updateStatus(); prepareDictionary()
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("text", output.text.toString()); outState.putString("raw", engine?.raw ?: restoreRaw)
        outState.putBoolean("english", english); outState.putBoolean("uppercase", uppercase)
        outState.putBoolean("pending_restart", pendingRestart); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    private fun prepareDictionary() {
        if (loading) return
        loading = true; previewControls.forEach { it.isEnabled = false }; progress.text = "正在准备离线词库…"
        val raw = engine?.raw ?: restoreRaw
        worker.execute {
            val loaded = runCatching { assets.open("keyboard18/pinyin.table").use(Keyboard18Dictionary::read) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loading = false
                loaded.onSuccess { dictionary ->
                    engine = Keyboard18Engine(dictionary).apply { restore(raw) }
                    restoreRaw = ""; previewControls.forEach { it.isEnabled = true }
                    progress.text = "离线词库 · ${dictionary.entries.size} 条字词"
                    showCandidates()
                }.onFailure { progress.text = "词库读取失败：${it.message}。可重新读取。" }
            }
        }
    }
    private fun updateStatus() {
        val snapshot = Keyboard18Store.snapshot(this)
        if (snapshot.getLong(Keyboard18Store.STATUS_TIME) > 0) pendingRestart = false
        status.text = if (pendingRestart) "设置已保存，请重启输入法应用。" else snapshot.getString(Keyboard18Store.STATUS)
    }
    private fun labels() {
        letterButtons.forEach { (group, view) ->
            val index = Keyboard18Keys.groups.indexOf(group)
            view.text = (if (uppercase) group.uppercase() else group) + "\n" + Keyboard18Keys.hints[index]
        }
        languageButton.text = if (english) "英" else "中"
        shiftButton.text = if (uppercase) "⇪" else "⇧"
    }
    private fun showResult(result: io.mo.xatype.keyboard18.Pinyin18Result) { result.commit?.let(::insert); showCandidates() }
    private fun showCandidates() {
        val decoder = engine
        composing.text = decoder?.display?.ifEmpty { if (english) "英文：点按首字母，长按选择另一字母" else "点按分组输入，长按选择准确字母" }
        candidateRow.removeAllViews()
        decoder?.candidates?.take(40)?.forEachIndexed { index, candidate ->
            candidateRow.addView(button(candidate.text) { decoder.select(index)?.let(::insert); showCandidates() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(ShapeUiStyle.XS) })
        }
    }
    private fun insert(text: String) {
        val start = output.selectionStart.coerceAtLeast(0)
        val end = output.selectionEnd.coerceAtLeast(start)
        output.text.replace(start, end, text); output.setSelection(start + text.length)
    }
    private fun flush() { engine?.commitAll()?.let(::insert); showCandidates() }
    private fun literal(value: String) { flush(); insert(value) }
    private fun popup(group: String) {
        val hint = Keyboard18Keys.hints[Keyboard18Keys.groups.indexOf(group)]
        val values = group.map { if (uppercase) it.uppercaseChar().toString() else it.toString() } +
            listOf(hint).filter { it.length == 1 } + listOfNotNull(Keyboard18Keys.shortcuts[group])
        choices("${group.uppercase()}键", values) { selected ->
            when (selected) {
                "全选" -> { flush(); output.selectAll() }
                "复制" -> output.onTextContextMenuItem(android.R.id.copy)
                "粘贴" -> { flush(); output.onTextContextMenuItem(android.R.id.paste) }
                "段首", "段尾" -> {
                    flush(); val text = output.text.toString(); val caret = output.selectionStart.coerceAtLeast(0)
                    output.setSelection(if (selected == "段首") text.lastIndexOf('\n', caret - 1) + 1
                        else text.indexOf('\n', caret).let { if (it < 0) text.length else it })
                }
                else -> if (!english && !uppercase && selected.length == 1 && selected[0] in 'a'..'z')
                    engine?.input(selected.single())?.let(::showResult) else literal(selected)
            }
        }
    }
    private fun symbols() = choices("符号", listOf("，", "。", "！", "？", "、", "：", "；", "'", "(", ")", "@", "#", "/", "-", "_", "=", "+", "%", "￥")) {
        if (it == "'" && !english) engine?.input('\'')?.let(::showResult) else literal(it)
    }
    private fun choices(title: String, values: List<String>, selected: (String) -> Unit) {
        if (engine == null) return
        AlertDialog.Builder(this).setTitle(title).setItems(values.toTypedArray()) { _, index -> selected(values[index]) }
            .setNegativeButton("取消", null).show()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply { background = style.surface(); setPadding(style.dp(ShapeUiStyle.MD),
        style.dp(ShapeUiStyle.LG), style.dp(ShapeUiStyle.MD), style.dp(ShapeUiStyle.LG)) }
    private fun text(value: String, size: Float, secondary: Boolean = false, strong: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (secondary) style.secondary else style.primary)
        if (strong) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(label: String, accent: Boolean = false, action: () -> Unit) = AppCompatButton(this).apply {
        text = label; isAllCaps = false; textSize = ShapeUiStyle.BODY
        setTextColor(if (accent) style.onAccent else style.primary)
        background = style.ripple(if (accent) style.accent else style.surface, ShapeUiStyle.SMALL_RADIUS)
        backgroundTintList = null; minHeight = style.dp(ShapeUiStyle.TOUCH)
        setPadding(style.dp(ShapeUiStyle.SM), style.dp(ShapeUiStyle.SM), style.dp(ShapeUiStyle.SM), style.dp(ShapeUiStyle.SM))
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); action() }
    }
    private fun keyButton(label: String, accent: Boolean = false, action: () -> Unit) = button(label, accent, action).apply {
        minWidth = 0; minimumWidth = 0; textSize = KEY_TEXT_SIZE
        setPadding(0, style.dp(ShapeUiStyle.XS), 0, style.dp(ShapeUiStyle.XS))
        minHeight = style.dp(ShapeUiStyle.BUTTON); previewControls.add(this)
    }
    private fun keyParams(weight: Float) = LinearLayout.LayoutParams(0, -2, weight).apply {
        setMargins(style.dp(KEY_GAP), style.dp(KEY_GAP), style.dp(KEY_GAP), style.dp(KEY_GAP)) }
    private fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(ShapeUiStyle.MD) }
    companion object {
        private const val PREVIEW_OUTPUT_HEIGHT = 80
        private const val KEY_TEXT_SIZE = 17f
        private const val KEY_GAP = 2
    }
}
