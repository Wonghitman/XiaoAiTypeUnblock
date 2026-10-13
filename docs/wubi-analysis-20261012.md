# 超级小爱输入法五笔接入迹象分析

分析日期：2026-10-12。主样本：`com.xiaomi.type`，`0.2.1053.06f65b7c`（versionCode 21053）。

## 结论

**当前安装包没有显示小米正在接入五笔的明确证据。发现的五笔标记是讯飞 SDK 长期保留的常量，尚未形成小米侧的设置、引擎创建、输入模式和资源加载链路。**

这能支持“本版尚未接入可识别的五笔功能”，不能证明“小米内部没有五笔开发计划”，也不能据此预测上线时间。底层 SDK 是否含无名称的五笔实现，本次未作肯定或否定结论。

## 样本及最新版核对

- 主样本：`work/apk/超级小爱输入法_0.2.1053.06f65b7c.apk`，47,203,344 字节。
- SHA-256：`c7e1e9d73fc515bb087e3677288b4de64110b5191d913d1d6c9757b3c1b5563b`。
- 版本交叉核对：`work/xiaoai-21053/apktool/apktool.yml:11`，另用 Androguard 直接解析原始 APK 的二进制 Manifest，确认包名、versionCode 和 versionName 一致。
- 同时直接读取本地 0.2.701、0.2.790、0.2.910、0.2.974 的 APK 作对照。
- 当日访问的[公开分发频道搜索页](https://t.me/s/VoyagerMIUIUpdate?q=%23Xiaomi_Hyper_XiaoAi_Keyboard)列出的最高版本为 0.2.1053.06f65b7c；[对应消息](https://t.me/VoyagerMIUIUpdate/17351)时间为 2026-09-29 12:38:28 UTC。第三方分发页只能用于版本线索，不能代替小米官方发布承诺。
- 小米商店网页未取得该包的有效版本详情；包查询接口返回 HTTP 400。没有连接的 ADB 设备可核对机型定向推送版本。因此本报告的“最新”限定为本地及本次公开渠道可核对的最高版本，不能覆盖未公开内测渠道。
- 网页快照保存于 `work/wubi-analysis-20261012/telegram-channel.html` 和 `xiaomi-store.html`。未以商店首页中的其他应用字段作为本包版本证据。

## 基础概况与依赖证据

### E-manifest

复核 `work/xiaoai-21053/apktool/AndroidManifest.xml`：

- 包名 `com.xiaomi.type`；Application 为 `com.mi.ime.MiImeApplication`（第 72 行）。
- 输入法服务为 `com.mi.ime.MiInputMethodService`，受 `android.permission.BIND_INPUT_METHOD` 保护（第 88 行）。
- 未用本项目模块 APK 替代宿主样本。

### E-imports / E-exports

DEX 等价依赖视图：文字输入使用 `com.iflytek.depend.common.base.SmartEngineManager`、`IPinYinEngine`、`DecoderManager`；小米在 `r9` 包包装具体输入引擎，在 `ab.x0` 管理切换。云控通过 `t8.c` 调用 `MiuiSettings.SettingsCloudData.getCloudDataList`。

APK 的原生库只有 `libandroidx.graphics.path.so` 和 `libgeneralcore-jni-v1001.so`，均为 ARM64。对后一库实际读取 ELF 动态依赖、导入、导出：

- DT_NEEDED：`libz.so`、`liblog.so`、`libandroid.so`、`libm.so`、`libdl.so`、`libc.so`。
- 226 个未定义动态符号、2351 个已定义动态符号；完整列表见 `inventory.json`。
- 资源/文件读写导入包含 `AAssetManager_open`、`AAsset_read`、`fopen`、`fread`、`fwrite`、`open`、`read`、`write`。
- 定义符号包含 `JNI_OnLoad`、`Phn_UtilWordToPinyin` 和 `CloudPinyinResult_*`。
- 对动态导入表的检查未发现直接 socket/connect/send/recv、dlopen/dlsym 或常见 AES/EVP/SSL 名称；这不排除 Java/JNI 侧网络、静态链接或其他实现。

本机 IDA 技能启动脚本返回 `ERR:IDADIR not set and IDA Pro not found`。本次 native 部分改用 pyelftools 解析 ELF、字符串检查和跨版本哈希比较，没有执行 native 函数反编译。

## 具体发现

### E-wubi-constant：存在五笔常量，但属于旧有 SDK 定义

`com/iflytek/inputmethod/smart/api/constants/SmartConstants.java:158`：

```java
public static final int SMART_INPUT_METHOD_WUBI = 4;
```

原始 smali 同步确认：`smali/com/iflytek/inputmethod/smart/api/constants/SmartConstants.smali:312`。本次另从主样本重新用 JADX 单类导出，结果相同，产物位于 `work/wubi-analysis-20261012/fresh-jadx/`。

直接解析五份原始 APK 的所有 `classes*.dex` 字符串表，匹配 `wubi|五笔|五筆|cangjie|zhengma`：每一版都只命中 `SMART_INPUT_METHOD_WUBI`。它至少从 0.2.701 已经存在，没有出现新的五笔命名痕迹。

常量可能在编译时内联，因此“没有其他名称命中”不能单独证明无调用。本次继续检查了实际模式映射和引擎工厂。

### E-product-engine：小米自身没有五笔引擎类型和工厂分支

`work/xiaoai-21053/jadx/sources/z8/g.java:34` 的完整引擎类型为：

```text
PINYIN, SHUANGPIN, T9, HANDWRITING, STROKE, ENGLISH, VOICE
```

其中 `SHUANGPIN` 是双拼，`STROKE` 是笔画，均不能视为五笔。

- `r9/h.java:13`：讯飞引擎工厂为拼音、九键、手写、笔画、英文创建包装对象，没有五笔分支。
- `ab/x0.java:52`：切换首先通过上述工厂创建引擎，否则查询 `z8.f` 注册表。
- `z8/f.java` 的注册表初始化为空，`MiImeApplication.java:432` 的 `registerEngines()` 也是空实现；未发现这里补注册五笔的迹象。
- `ab/x0.java:166`：配置中的 `engine_type` 通过 `z8.g.valueOf` 转换，不是任意字符串模式；写入 `WUBI` 不能凭空获得对应枚举和实现。

因此没有证据表明五笔仅靠一个现成的隐藏偏好或云控开关即可启用。存在云控 API 本身不能作为五笔接入证据。

### E-settings：设置页和资源没有五笔入口

- `apktool/res/xml/settings_keyboard_type.xml:6`：只有 `kbd_type_pinyin`、`kbd_type_t9`、`kbd_type_handwriting`、`kbd_type_stroke` 四个选项。
- `jadx/sources/db/l.java:15`：设置控制器把这四项分别映射为 `PINYIN`、`T9`、`HANDWRITING`、`STROKE`。
- 对全部解包 smali、XML、JSON、TXT 的检索只发现上述 SDK 常量；未发现五笔标题、键盘资源名、配置键或五笔版本选择项。

### E-input-mode：正常布局转换没有选择 WUBI 模式

- `com/iflytek/depend/common/pinyin/LayoutType.java:4`：只有中文九键、中文 26 键、英文九键、英文 26 键、笔画布局。
- `com/iflytek/inputmethod/smartengine/j.java:283` 的 `a(int,int)`：输入方法低位选择 1（拼音）、2（笔画）或 8（英文），再合并布局位；没有选择 `SMART_INPUT_METHOD_WUBI = 4` 的分支。
- `apktool/smali/com/iflytek/inputmethod/smartengine/j.smali:265` 附近交叉核对上述位运算，避免依赖同方法中不完整的 JADX 控制流恢复。
- 小米笔画包装 `r9/l.java:468` 明确调用 `INPUT_TYPE_BI_HUA`，不是把五笔隐藏在“笔画”标签下面。

注意：小米枚举中 `STROKE` 的序号 4、SDK 五笔模式位 4 属于不同编号体系，不能把两者直接等同。

### E-native-diff / E-dicts：近期原生引擎及内置核心词库未变化

| 宿主版本 | generalcore 原生库 SHA-256 | DEX 五笔相关字符串 |
| --- | --- | --- |
| 0.2.701 | `d2cc7065780e148a9d572a17d89736b84b624eceb6a42eb0867d2be9e527abf1` | 仅旧常量 |
| 0.2.790 | `e4996723df68fed4550b8393ec1972883be455a90645f1c38fc8ddcdfe6020de` | 仅旧常量 |
| 0.2.910 | `722a42c4094cb3f0cec85086cbf207c9bfaa78d0f1de7b2f8e8620da5b546215` | 仅旧常量 |
| 0.2.974 | 同 0.2.910 | 仅旧常量 |
| 0.2.1053 | 同 0.2.910 | 仅旧常量 |

0.2.790、0.2.910、0.2.974、0.2.1053 的 `assets/internalDicts/` 文件集合及各文件内容哈希完全一致。没有近期新增的核心内置词库文件证据。

`EngineConstants.java` 提供拼音映射、符号、笔画映射、主词库、手写等资源别名，未见五笔专用映射或字典常量。各版本原生库的 ASCII 字符串及导出符号未匹配 Wubi 相关名称；也检查了简繁中文 `五笔` / `五筆` 在 UTF-8、UTF-16 LE/BE 中的字节表示，均未命中。

这些是辅助证据：哈希一致说明近期没有改动这些文件，不能证明旧引擎绝不具备五笔能力；混合、压缩或不透明格式词库未做完整解码，不能把“未见五笔专用文件”扩大为“确定没有任何五笔数据”。

## 复现与边界

本轮仅做本地静态分析和公开版本查询，没有修改宿主、安装 APK、注入进程或调整设备设置；未评估登录、风控或语音等无关功能。

复现原始 APK 对照：

```powershell
python -X utf8 work/wubi-analysis-20261012/audit.py
```

辅助 ELF 依赖 pyelftools 已局部安装于任务目录 `python-deps/`。脚本直接读取 ZIP、DEX 字符串表和 ELF，输出 `inventory.json`；首次运行的控制台结果保存为 `inventory-summary.txt`。业务链路分析使用现有 JADX/apktool 产物，并对五笔常量重新导出核对。

已完成：样本哈希/版本、Manifest/入口、DEX 依赖视图、native 导入/导出、全包命名检索、设置/引擎/布局调用链、smali 交叉核对、五版本 DEX/native/词库比较及证据落盘。未完成也未宣称完成：在线设备验证、远端下发内容检查、native 函数反编译、私有词库格式完整解码及内部产品计划验证。
