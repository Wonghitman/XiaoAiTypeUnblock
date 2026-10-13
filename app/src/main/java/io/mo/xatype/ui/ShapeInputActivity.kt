package io.mo.xatype.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.mo.xatype.shape.ShapeStore
import java.util.concurrent.Executors

class ShapeInputActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private var busy = false
    private val pendingImportName get() = ShapeStore.prefs(this).getString("pending_import_name", "自定义形码").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "形码输入 · 虎码 / 五笔"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(androidx.appcompat.widget.Toolbar(this).apply {
            title = "形码输入 · 虎码 / 五笔"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }, 0)
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(14)) }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "实验功能：适配小爱 0.2.1053，启用后请在小爱中选择「拼音26键」。\n" +
                "复用原有键盘与候选栏。所有修改需重启输入法生效；关闭本开关并重启可恢复拼音。"
            textSize = 14f
        })
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(controls)
        root.addView(Button(this).apply {
            text = "重启输入法使设置生效"
            setOnClickListener { ImeRestarter.restart(this@ShapeInputActivity) }
        })
        runTask("准备内置码表…") { ShapeStore.ensureBuiltin(this); "" }
    }

    override fun onResume() {
        super.onResume()
        if (::controls.isInitialized && !busy) render()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun render() {
        val snapshot = runCatching { ShapeStore.snapshot(this) }.getOrElse {
            status.text = "码表读取失败：${it.message}"
            return
        }
        status.text = "${snapshot.getString(ShapeStore.NAME, "尚未导入码表")}\n" +
            "${snapshot.getInt(ShapeStore.COUNT)} 条 · 最长 ${snapshot.getInt(ShapeStore.DICT_MAX)} 码\n" +
            snapshot.getString(ShapeStore.STATUS)
        controls.removeAllViews()
        toggle("启用形码输入", ShapeStore.ENABLED, false)
        button("导入码表…") { importDialog() }
        button("恢复内置虎码字词码表") {
            AlertDialog.Builder(this).setMessage("用内置虎码替换当前码表？完成后需重启输入法。")
                .setPositiveButton("替换") { _, _ -> runTask("正在恢复…") {
                    val result = ShapeStore.restoreBuiltin(this)
                    "已恢复 ${result.dictionary.entries.size} 条"
                } }.setNegativeButton("取消", null).show()
        }
        button("最大码长：${snapshot.getInt(ShapeStore.MAX_CODE, 4)}") {
            val min = snapshot.getInt(ShapeStore.DICT_MAX, 1).coerceIn(1, 8)
            val values = (min..8).toList()
            AlertDialog.Builder(this).setTitle("最大码长")
                .setItems(values.map { "$it 码" }.toTypedArray()) { _, index ->
                    val sp = ShapeStore.prefs(this)
                    sp.edit().putInt(ShapeStore.MAX_CODE, values[index])
                        .putLong(ShapeStore.REVISION, sp.getLong(ShapeStore.REVISION, 0) + 1)
                        .remove(ShapeStore.STATUS).remove(ShapeStore.STATUS_TIME).commit()
                    render()
                }.show()
        }
        toggle("达到最大码长且候选唯一时自动上屏", ShapeStore.AUTO_COMMIT, true)
        toggle("满码后继续输入时顶屏首选", ShapeStore.OVERFLOW, true)
        toggle("编码中按 1–9 选择候选", ShapeStore.DIGITS, true)
        toggle("分号选择第二候选", ShapeStore.SEMICOLON, true)
        toggle("单引号选择第三候选", ShapeStore.QUOTE, true)
        toggle("按码表权重排序（默认保留原始顺序）", ShapeStore.SORT_WEIGHT, false)
        controls.addView(TextView(this).apply {
            text = "码表格式：UTF-8 文本，每行「文字 TAB 编码」或「编码 TAB 文字」，可带第三列权重；" +
                "支持 Rime .dict.yaml 的实际码表和 Fcitx5 [Data] 段。编码限小写 a–z、1–8 码。\n\n" +
                "首版不执行 Rime 配置或 Lua，不提供拼音反查、自造词和学习调频。" +
                "空码按空格会保留并上屏原编码。\n\n" +
                "内置数据来源：lvyww/tiger-code（CC0-1.0，固定版本 3e1bff2）。"
            textSize = 13f
            setPadding(0, dp(16), 0, 0)
        })
    }

    private fun toggle(label: String, key: String, default: Boolean) {
        controls.addView(SwitchCompat(this).apply {
            text = label
            setPadding(0, dp(12), 0, dp(12))
            isChecked = ShapeStore.prefs(this@ShapeInputActivity).getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                ShapeStore.saveBoolean(this@ShapeInputActivity, key, checked)
                status.text = status.text.toString().substringBeforeLast('\n') + "\n设置已保存，重启输入法后生效"
            }
        })
    }

    private fun button(label: String, action: () -> Unit) {
        controls.addView(Button(this).apply { text = label; setOnClickListener { if (!busy) action() } })
    }

    private fun importDialog() {
        val name = EditText(this).apply { hint = "例如：我的虎码"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("导入形码码表").setView(name)
            .setMessage("选择 UTF-8 码表文件。导入成功会替换当前码表，失败则保留原码表。")
            .setPositiveButton("选择文件") { _, _ ->
                ShapeStore.prefs(this).edit().putString("pending_import_name", name.text.toString().trim().ifBlank { "自定义形码" }.take(128)).commit()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, IMPORT_CODE)
            }.setNegativeButton("取消", null).show()
    }

    @Deprecated("Legacy result API supported on API 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_CODE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = pendingImportName
        runTask("正在校验并导入码表…") {
            val result = checkNotNull(contentResolver.openInputStream(uri)) { "无法读取所选文件" }.use {
                ShapeStore.import(this, it, name)
            }
            "已导入 ${result.dictionary.entries.size} 条，跳过 ${result.skipped} 行、去重 ${result.duplicates} 条。请重启输入法。"
        }
    }

    private fun runTask(message: String, operation: () -> String) {
        if (busy) return
        busy = true
        status.text = message
        controls.visibility = View.GONE
        worker.execute {
            val result = runCatching(operation)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                controls.visibility = View.VISIBLE
                render()
                result.onSuccess { if (it.isNotEmpty()) Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                    .onFailure { AlertDialog.Builder(this).setTitle("操作未完成")
                        .setMessage(it.message ?: "操作失败").setPositiveButton("知道了", null).show() }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val IMPORT_CODE = 310 }
}
