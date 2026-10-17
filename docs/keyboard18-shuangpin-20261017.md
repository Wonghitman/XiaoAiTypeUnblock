# 使用 18 键双拼

版本：2.1.8.2（versionCode 23）。正式 Release 基准仍为 2.1.8，上一小修订为本会话已生成的 2.1.8.1。

## 开启与输入

1. 打开模块「键盘 → 输入方案 → 18 键键盘」。
2. 开启「18键双拼」，通过「双拼方案」选择小鹤、自然码、微软、搜狗、智能 ABC、拼音加加或紫光，默认小鹤。
3. 用同页预览确认输入；点击「重启输入法并应用」，在小爱的「键盘选择」中选择「18键」。

双拼编码中的字母按既有 7＋6＋5 分组输入。例如小鹤双拼的「为什么」为 `ww uf me`，依次点 `WE · WE · U · FG · M · WE`。长按按键选择准确字母，可收窄分组重码。候选显示原词及全拼，空格选首项，选择词首候选后剩余编码继续组词；分隔符只在音节边界生效。

微软、搜狗和紫光双拼用分号表示 `ing` 韵母。18 键的 L 分组同时接受该韵母；中文长按 L 菜单额外提供 `;`，用于精确输入。英文键盘和关闭双拼后的 L 菜单维持原设置。独立成音的 `m`、`n`、`hm`、`ng` 按上游方案保留字面拼写。

关闭开关后使用原全拼/首字母简拼。双拼和全拼使用各自索引；18键与九键的开关及方案分别保存。切换模式/方案时，预览先提交当前输入，再重建候选索引；宿主在重启输入法后应用设置。

## 实现与来源

`ShuangpinScheme` 共享七种方案的 ID，九键仍使用宿主原生解码。18键在读取原拼音词库时，将每个音节转换为双拼编码建立分组索引，词面、原始拼音与频率保持。未扩展宿主引擎枚举或增加解码库。

键位依据 [Rime 双拼方案](https://github.com/rime/rime-double-pinyin/tree/01a13287cbd27819be1c34fa1ddc1b3643d5001b) 核对；搜狗与紫光采用 [Rime Ice 的对应方案](https://github.com/iDvel/rime-ice/tree/da1fbe602e38f26db846fa10120ee64c2b0324c0)。固定版本、文件链接和 SHA-256 记录于 [SOURCE-shuangpin.txt](../app/src/main/assets/keyboard18/SOURCE-shuangpin.txt)。

`scripts/update-shuangpin-reference.py` 独立运行上游方案的拼写变换，为词库全部 415 个音节生成七套测试向量（2,905 行）。测试将实际 Kotlin 编码逐项与上游结果比较，覆盖声母、韵母、零声母和特殊音节。向量只用于测试，不打包进 APK。

设置通过 `Keyboard18Store` 的设备保护存储与已有 Provider 同步；每次更改递增 revision 并清除旧状态。宿主词库读取前后核验 revision。预览使用递增加载序号丢弃过期结果，避免快速更改方案后旧词库覆盖新词库。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:testReleaseUnitTest :app:assembleRelease --console=plain
.\gradlew.bat :app:lintDebug --console=plain
python scripts/verify-shape-apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

新增回归覆盖七种方案、精确字母与分组输入、零声母、上游音节向量、短语选词后续输、退格、恢复、音节分隔符、分号韵母、全拼标点以及中文/英文 L 长按菜单隔离。七套模式同时使用完整内置词库验证「你好」「为什么」「中国」。

Debug / Release 各 99 项单元测试通过，两种 APK 均已构建，Release 为未签名产物；Debug 的 APK 签名校验通过。Release 的 lintVital 和词库打包检查通过，测试向量未打包进 APK。`lintDebug` 仍因 34 项已有错误失败，另有 195 项警告；已逐个核对错误对应源码行均存在于本次修改前的 HEAD，涉及旧有外观 API、隐藏接口和布局设置。

界面沿用 ShapeUiStyle 的颜色、圆角、间距和触控尺寸。浅色正文/卡片对比度约 16.7:1、副文本 5.3:1；深色约 13.9:1 / 8.0:1。实际动态配色、字体放大、手势和宿主输入效果仍需真机验证。

本次 ADB 列表无在线真机，未启动任何模拟器。运行验收需确认：方案切换与重启、18键分组与准确字母、退格/空格/回车/选词、中英文切换、分号韵母、下滑快捷操作及切回其他键盘。
