# DEX 缓存与键盘自适配

本次在 `2.1.6.5` 补齐审查中的五类遗漏。缓存标识覆盖完整 ZIP 条目元数据；命中后检查必需键、描述符、接口归属与类型；临时扫描异常不写入负缓存。独立键盘选择、形码、18 键、快捷操作和排版改为读取运行时解析的接口映射。当前宿主 `0.2.1053` 与整体重命名样本均解析出 22 组结果，并成功重载缓存、初始化七个桥接对象。

完整变更日志、全部单元测试明细及设备探针原始输出已随代码保存，见 [提交日志](2026-10-16_DEX缓存适配-提交日志.md)。

## 范围与版本

范围、授权和设备操作边界见 [scope](dex-cache-adaptation-scope.md)。这是普通 APK 兼容性分析，`flavor = null`；不涉及安全漏洞利用或恶意软件指标。

正式发版记录为 `11-2.1.6`，保留正式版本三段并递增已有第四段，得到 `2.1.6.5`，`versionCode = 19`。本次未发布 Release。

## 实现

### 缓存

`ApkFingerprint` 对所有 ZIP 条目的名称、CRC、大小、压缩大小和压缩方式计算 SHA-256，不再截取 APK 最后 64 KiB。模块构建标识、表结构版本、APK 大小和时间戳继续参与缓存键；这不是对整个 APK 内容逐字节计算 SHA-256。

缓存必须完整覆盖本表的键，成功与失败集合不能重叠。普通描述符通过当前 ClassLoader 反射重载；列表长度、现代外观属性、键盘映射所需的键、类型类别、静态属性、方法参数/返回值和归属关系分别校验。无效缓存触发扫描，后续反射失败会使相关条目和磁盘缓存失效。

只有明确的 `FingerprintMiss` 作为稳定未命中保存。类加载、JNI、资源等异常保留失败原因，但不写入长期缓存，下一次启动重试。收集器先检查批量结果数量再发布，避免部分成功。写入使用同目录唯一临时文件与原子替换。

### 键盘接口

参考资料包含 178 个接口符号、129 个结构见证，分为七组：

| 键 | 覆盖范围 |
|---|---|
| `keyboard.engine` | 形码与18键共用的引擎接口、候选、状态、工厂与原生按键分发 |
| `keyboard.layout` | 按键、行、布局、布局工厂及模式变化 |
| `keyboard.actions` | 候选确认、原始输入保留、清理与编辑收尾 |
| `keyboard.picker` | 原生键盘选择面板及选择回调 |
| `keyboard.settings` | 原生设置入口、偏好项和引擎切换 |
| `keyboard.swipe` | Compose 按键绘制、坐标、状态、画布和手势回调 |
| `keyboard.letterAppearance` | 18键字母字号及对齐 |

扫描器按字符串、参数结构和操作码定位唯一方法，再沿指令顺序中的调用或字段引用取得成员。`const-string` 与其 jumbo 形式按同一种操作比较，避免字符串池重排导致失效。匹配不唯一时跳过对应组。

源码和参考文件中的 `z8.g`、`ka.c` 等名称是参考 ID。生产桥接通过 `KeyboardAbi` 将它们转换为缓存中的实际类型、方法和字段；不会把这些 ID 当作宿主混淆名回退。代理按解析后的 `Method` 身份分发，避免类名变化后仍按旧方法名执行。桌面替身测试显式注入参考映射，生产入口必须先解析符号。

### 动态剪贴板与状态

类加载监听路径从定义该类的 DexFile 找到实际 APK，缓存目录使用当前进程自己的设备保护存储。之后获得完整 Context 时可补充缓存信息，且不会重复安装 Hook。扫描期间的类加载重入通过单独的安装集合拦截。

新功能的安装异常进入适配结果。首页检查剪贴板报告的版本和模块构建标识；缺少报告时显示等待状态。文案区分适配完成与功能开关，不再用“全部功能已定位，可正常使用”概括所有功能。

## Evidence

| E-id | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-01 | `app/build/test-results/testDebugUnitTest/`、`testReleaseUnitTest/` | `./gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease --offline --console=plain` | n/a；可重新生成 |
| E-02 | `work/fingerprint-probe-54d21715d75944028e1fa69474052086/result.txt` | 下方当前宿主探针 | n/a；本地临时证据，可重新生成 |
| E-03 | `work/fingerprint-probe-54d21715d75944028e1fa69474052086/result.txt` | 下方重命名样本与探针 | n/a；本地临时证据，可重新生成 |
| E-04 | `work/fingerprint-probe-8b10e468a65b4f219e97d7b47d977bc5/result.txt` | `verify-host-fingerprints.ps1 -Table PhraseSymbols`，目标为 `work/apk` 中五份剪贴板 APK | n/a；本地临时证据，可重新生成 |
| E-05 | `work/fingerprint-probe-4ad573fff07346d89382ebe4607eb3f4/result.txt` | `verify-host-fingerprints.ps1`，目标为 `work/apk` 中四份较旧输入法 APK | n/a；本地临时证据，可重新生成 |

## Findings

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-01 完整目录标识与缓存校验 | n/a_re | E-01、E-02 | high | `compat/ApkFingerprint.kt`、`compat/SymbolTable.kt` | validated |
| F-02 临时异常重试及原子发布 | n/a_re | E-01、E-02、E-04 | high | `compat/SymbolTable.kt` | validated |
| F-03 新键盘功能使用解析映射 | n/a_re | E-01、E-02、E-03 | high | `compat/KeyboardAbi*.kt`、键盘桥接与 Hook | validated |
| F-04 动态加载来源与缓存目录补全 | n/a_re | E-04 | high | `compat/LoadedApkSource.kt`、`hooks/ClipboardPermanentHook.kt` | validated；完整 Hook 生命周期仍需实际输入验收 |
| F-05 状态纳入新功能失败与报告有效期 | n/a_re | E-01、源码检查 | high | `AdaptationStatus.kt`、`MainActivity.kt` | validated；未做交互界面验收 |

## Path

P-01，`path_type=callflow`：包加载 → ZIP 元数据标识 → 缓存完整性与反射校验 → 命中后复用；否则运行独立指纹 → 校验结果 → 原子发布稳定结果 → 键盘桥接解析成员 → 安装结果上报。临时异常保留重试路径（F-01、F-02、F-03）。

P-02，`path_type=callflow`：动态加载剪贴板管理类 → 找到定义类的 DexFile/APK → 使用当前进程缓存目录 → 防止扫描重入和重复安装 → 上报剪贴板结果（F-04、F-05）。

## 复现

以下 PowerShell 命令在项目根目录执行，设备需已授权 ADB。脚本选择唯一在线设备，也可显式传 `-DeviceSerial`。

```powershell
./gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease --offline --console=plain
$keys = @('keyboard.engine','keyboard.layout','keyboard.actions','keyboard.picker','keyboard.settings','keyboard.swipe','keyboard.letterAppearance')
./scripts/verify-host-fingerprints.ps1 -TargetApk 'work/apk/超级小爱输入法_0.2.1053.06f65b7c.apk' -RequireKey $keys
python scripts/verify-keyboard-abi-renaming.py work/xiaoai-21053/apktool work/keyboard-abi-renaming-check
./scripts/verify-host-fingerprints.ps1 -TargetApk work/keyboard-abi-renaming-check/renamed-host.apk -RequireKey $keys
$phrases = @(Get-ChildItem work/apk -Filter '*.apk' | Where-Object Name -Like '*常用语*' | Select-Object -ExpandProperty FullName)
./scripts/verify-host-fingerprints.ps1 -Table PhraseSymbols -TargetApk $phrases
python scripts/verify-shape-apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

重命名脚本要求一个尚不存在的输出目录、已有 apktool 解包资料和本地 apktool 3.0.2 jar；`--tool-jar` 可指定路径。它改变 56 个类名、38 个短方法名和20个字段名，保留字符串标签和指令结构；测试 APK 不安装到设备。

更新当前参考资料使用 `./scripts/update-keyboard-abi-reference.ps1 -TargetApk 'work/apk/超级小爱输入法_0.2.1053.06f65b7c.apk'`；换用其他版本前应审查接口语义，不能仅因脚本生成成功就宣称新版本日常输入可用。

## 验证结果与边界

- Debug、Release 各 84 项桌面测试通过，构建及 Release 必需的 lint 检查通过，内置虎码表打包检查通过。
- 当前宿主静态兼容探针额外通过109项接口检查（`work/compat-probe-377ae5d71fc54b1b8c27d141afa35cb7/result.txt`）。
- 当前 `0.2.1053` 与重命名样本：22组解析并命中缓存，七个桥接对象初始化成功。参考宿主首扫约1秒；数据来自独立探针，不是输入体验性能测试。
- 剪贴板 `5.6.9/5.7.0/5.7.2/5.7.3/5.7.4`：六组符号解析、缓存重载通过；实际动态 APK 来源定位通过。
- 输入法 `0.2.701/0.2.790/0.2.910/0.2.974`：原有符号按各版本可用范围加载，新增七组没有全部满足结构见证，因此保留原生键盘、显示缺失状态；本次不宣称这些旧版已经支持新增实验键盘。
- 未替换设备上的模块或宿主，尚未在真实输入会话中验收键盘切换、手势绘制、剪贴板完整生命周期及长期稳定性。

## Timeline

2026-10-15：读取正式 Release 及本地修订记录；复现缓存键覆盖、命中校验和临时异常缓存问题；实现缓存与七组接口映射；完成桌面测试、独立设备探针、整体改名测试及构建；整理交付记录。

## 构建产物

- `app/build/outputs/apk/debug/app-debug.apk`：7106441 字节；SHA-256 `8353d899b9bb7c66de015f4f72787fa230621be518dea81ab10a40474a4f34a4`。
- `app/build/outputs/apk/release/app-release-unsigned.apk`：5914472 字节；SHA-256 `b20589690d6d41e2ffd43a12d758993af64dc923ca1a725afa638377f9842e03`。

Debug APK 已通过签名校验，可用于手动安装测试；Release APK 为未签名构建产物，未作为正式发版发布。
