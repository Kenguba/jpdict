# jpdict

这是支持中文、英文、日文、韩文 OCR 和 Groq 多语种查询的原生 Android 应用。

## 工程规则唯一来源（Single Source of Truth）

**根目录 `README.md` 是本工程唯一工程规则源（Single Source of Truth, SSOT）。**

以后进行任何以下工作前，都必须先读取本文件：

- 开发
- 修复
- 重构
- 测试
- 构建
- 发布

如果其他 feature README、子目录 README、旧文档、历史说明、代码注释或其他规则与根目录 `README.md` 冲突，**一律以根目录 `README.md` 为准**。

工程规则发生变化时，应优先更新本文件，避免在多个文档中维护互相冲突的规则。

## 架构、代码质量与体积硬约束

以下规则属于工程级硬约束，适用于新增功能、Bug 修复、重构、性能优化、测试、构建与发布。

### 总体开发原则

- **轻量优先**：优先使用平台已有能力和项目现有能力，避免为很小的问题引入大依赖、大框架或重复实现。
- **高内聚、低耦合**：同一功能的状态、规则和行为尽量聚合在同一 feature / 模块内；跨模块通过清晰接口交互，避免互相直接操作内部实现。
- **多复用**：重复 UI、业务规则、网络/存储逻辑、格式化与平台能力应抽成可复用组件、工具或服务，禁止复制粘贴式扩展。
- **可扩展**：新增功能优先通过扩展点、接口、组合和配置完成，避免不断向单一超大类追加分支。
- **最小范围修改**：修复问题时优先定位根因，只修改完成目标所必须的代码；禁止顺手大面积改名、重排、重写无关模块。
- **兼容优先**：已有可用行为、公开入口、数据格式和用户习惯不得因为无关重构被破坏。
- **依赖克制**：新增第三方依赖前必须确认项目现有能力无法低成本解决；能用小依赖解决时不得引入重型框架。
- **避免过度设计**：设计模式只用于降低耦合、提高复用或建立稳定扩展点，不为“使用模式”而制造额外层级和样板代码。

### 架构设计要求

工程架构思路需要与 **Flutter 主流可维护架构思想**保持一致，但当前工程仍是原生 Android Java，**不得为了形式上的“符合 Flutter”而额外引入 Flutter Runtime 或无必要框架**。

默认采用以下等价原则：

- 优先 **feature-first** 组织：按功能聚合代码，而不是把所有页面、所有服务、所有工具无限堆进少数大文件。
- 复杂功能按需拆分为 `presentation / domain / data / platform` 等边界；简单功能不得为了分层而机械增加文件。
- UI / 展示层只负责展示、交互和轻量状态协调，不承载可复用的核心业务规则。
- 业务规则尽量独立于具体 Activity、Service、网络库、数据库和系统 API。
- 数据访问优先通过稳定边界封装；需要时使用 Repository / Adapter / Facade，避免上层直接依赖底层实现细节。
- 状态来源要清晰，避免同一状态在多个对象中重复保存、互相同步。
- 平台能力、网络、AI、存储、OCR 等外部能力需要可替换、可测试，避免业务代码和具体 SDK 强绑定。
- 优先组合而不是继承；优先小接口而不是“大而全”接口。
- 新增抽象必须能实际减少重复、降低耦合或提供明确扩展点，否则不新增抽象层。

允许并推荐按场景使用的设计模式包括但不限于：

- Repository：隔离数据来源与业务层。
- Strategy：替换搜索、解析、AI、OCR、构建策略等可变行为。
- Adapter：隔离第三方 SDK / 平台 API。
- Factory：统一创建具有多实现的对象。
- Facade：为复杂平台能力提供轻量统一入口。
- Observer / 状态驱动：处理 UI 与状态变化。
- Dependency Inversion：高层逻辑依赖抽象，不直接绑死具体实现。

任何模式都必须服务于实际问题，**禁止过度工程化**。

### 文件与类的控制

- 禁止继续制造职责混杂的超大 Activity、Service、Manager 或 Utils。
- 一个类出现多个独立变化原因时，应优先拆分职责。
- 公共工具必须有明确边界，禁止形成“万能 Utils”。
- 新功能优先复用已有组件；若需要新增公共组件，应保证命名、职责和 API 足够稳定。
- 重构优先小步进行，保持每一步可构建、可验证、可回退。

### 代码体积上限

最终工程的 **Git 跟踪的项目自有代码、资源与配置总量不得超过 35 MB**。

统一按以下规则理解：

- 上限按 `35 × 1024 × 1024 bytes` 计算。
- 统计 Git 跟踪的工程自有文件。
- 不统计 `.git`、Gradle/Maven 缓存、Android SDK、构建缓存、临时文件和下载的外部依赖。
- 不允许通过把大量代码或资源改成压缩包、Base64、生成文件等方式规避限制。
- 接近上限前必须先做去重、资源压缩、依赖裁剪和结构优化，而不是直接放宽限制。

### APK 体积上限

**任何最终面向用户发布的单个 APK 都不得超过 30 MB。**

统一按以下规则理解：

- 上限按 `30 × 1024 × 1024 bytes` 计算。
- `arm64-v8a`、`armeabi-v7a`、`arm` 等所有正式发布 APK 都必须分别满足限制。
- `universal` 只有在自身也满足 30 MB 上限时才能作为正式发布产物。
- 如果 `universal` 因包含多个 ABI 超过 30 MB，必须停止发布该 universal APK，优先使用 ABI Split / 分架构 APK，而不是突破体积上限。
- 新依赖、新资源、新模型加入前必须评估对 APK 体积的影响。
- 发布前必须检查实际 APK 文件大小；超过上限视为发布阻断问题。

### 修改优先级

当“快速实现”和上述规则冲突时，优先级固定为：

1. 正确性与稳定性
2. 最小范围修改
3. 轻量 / 高内聚 / 低耦合
4. 复用与可扩展性
5. 性能与体积
6. 实现速度

除非用户明确要求修改本 README 的规则，否则不得为了赶进度临时绕过这些约束。

## 版本规则

对外版本号固定格式：

```text
YY.MM.DD-NNNNNX
```

示例：

```text
26.10.07-00001A
26.10.07-00002D
26.10.07-00003B
26.10.07-00004T
26.10.07-00005C
26.10.07-00006R
26.10.07-00007P
26.10.07-00008H
```

阶段字母固定为：

| 字母 | 阶段 | 含义 |
| --- | --- | --- |
| `A` | Alpha | 原型阶段，可无构建产物 |
| `D` | Development | 开发调试 |
| `B` | Build | 成功构建 |
| `T` | Test | 测试 |
| `C` | Candidate | 发布候选 |
| `R` | Release | 正式发行构建 |
| `P` | Production | 正式生产版本 |
| `H` | Hotfix | 生产环境紧急修复 |

### 流水号规则

`NNNNN` 为 5 位十进制流水号。

同一天：

- 所有阶段共用同一条流水号序列。
- 阶段字母变化时，流水号**不归零**。
- 每产生一个新版本，`NNNNN` 继续递增。

例如：

```text
26.10.07-00001A
26.10.07-00002D
26.10.07-00003B
26.10.07-00004T
```

换一天：

- 使用新的当天日期。
- 当天流水号重新从 `00001` 开始。

例如：

```text
26.10.07-00008H
26.10.08-00001D
```

### Android / iOS 内部构建号

对外版本号与平台内部构建号分离。

- Android `versionCode` 必须保持**纯数字、永久递增**。
- iOS build number 必须保持**纯数字、永久递增**。
- 不允许直接把带日期、连字符或阶段字母的 `YY.MM.DD-NNNNNX` 硬塞进 Android `versionCode` 或 iOS build number。
- 平台内部构建号跨日期、跨阶段都不能回退或重复。

`version.properties` 记录已分配的本地版本。GitHub Actions 将此记录与远端标签、已发布版本合并计算下一版本；Android 构建号同时考虑当前 Workflow 流水号，避免 Workflow 更名或重试造成回退。新的本地版本分配后也必须更新该记录。

## APK 命名规则

APK 文件名固定格式：

```text
名字-YY.MM.DD-NNNNNX-ABI.apk
```

示例：

```text
名字-26.10.07-00004T-arm64-v8a.apk
```

其中版本部分必须严格使用本 README 定义的 `YY.MM.DD-NNNNNX` 规则。

当前 Android 常规发布使用的架构 / 变体后缀包括：

- `arm64-v8a`
- `armeabi-v7a`
- `arm`

`universal` 仅在最终 APK 本身不超过 30 MB 时允许发布。

因此构建产物命名应遵循同一规则，例如：

```text
名字-26.10.07-00004T-arm64-v8a.apk
名字-26.10.07-00004T-armeabi-v7a.apk
名字-26.10.07-00004T-arm.apk
```

如果未来 universal APK 优化到 30 MB 以内，则可使用：

```text
名字-26.10.07-00004T-universal.apk
```

## 当前实现

- 原生 Android Java
- 单桌面图标
- 可拖拽悬浮取词按钮
- 参考录屏风格的白色原生悬浮搜索页：固定顶栏、淡灰水印、横向推荐、双行输入器、抽屉、私密态、锚定菜单
- MediaProjection 屏幕捕获
- ML Kit 中 / 英 / 日 / 韩多语种 OCR（Latin / Chinese / Japanese / Korean，Play services 动态模型）
- 区域 OCR 取词
- Intent 快速词条弹窗
- PROCESS_TEXT / SEND
- 桌面搜索 Widget
- 桌面 Widget 和系统 `ACTION_SEARCH` 共用 `SearchActivity` 路由：有查询时直接进入透明 `LookupLinkActivity` 承载的查词小窗，没有查询时进入同一宿主承载的悬浮搜索。禁止从此路由直接发送无宿主的 `mode=float` 查词请求，否则会漏掉全屏 Mask 和系统返回宿主；路由自身也必须透明、无预览、无动画、空 taskAffinity、排除最近任务，避免拉起应用主页。桌面直接查词关闭后回到调用方，不恢复搜索层。
- 交互固定：轻点悬浮球进入桌面全局搜索；拖拽悬浮球进行区域 OCR，识别成功后必须直接打开查词弹窗，不得先进入或回落到桌面全局搜索层
- 全局悬浮搜索由 `TYPE_APPLICATION_OVERLAY` 显示独立 `FloatingSearchView` 白色原生页面，复用透明 `LookupLinkActivity` 承载系统栏背景和系统返回。返回优先关闭附件/模型菜单，再收起键盘、关闭抽屉/设置，最后关闭搜索；完整关闭后恢复调用方原页面和系统栏。搜索主页面的白色留白属于页面，不能用作查词 Mask 点击区。查词结果继续使用原有全屏黑色 Mask。OCR 查词结果必须通过显式 Intent 触发 `FloatingService.ACTION_SHOW_LOOKUP_OVERLAY`，由 `WindowManager.TYPE_APPLICATION_OVERLAY` 直接显示在当前桌面或前台 App 上方，OCR 结果统一由 Service 接入透明 `LookupLinkActivity` 承载全屏 Mask 和系统返回，再由同一 Service 绘制查词浮层；禁止启动 `QuickLookupActivity`、应用主页或全局搜索页。透明宿主不得渲染词典页面，不得使用应用主页的任务归属、预览或过渡动画
- 首次开启 OCR 仅通过透明、无预览、无动画、空 taskAffinity、排除最近任务的 `OcrActivity` 请求系统屏幕共享授权，不得把应用主页拉到前台。授权或取消后立即结束授权宿主并恢复之前的应用；系统屏幕共享授权框必须保留。
- 有悬浮窗权限时，OCR、Intent（Deep Link / PROCESS_TEXT / SEND）与悬浮搜索选词复用 `FloatingService.ACTION_SHOW_LOOKUP_OVERLAY` 的同一查词卡片。这些入口统一默认大小、位置、标题拖动、底部版权栏缩放和持久化窗口设置，并都带全屏 Mask；入口差异只影响关闭后是否恢复搜索。没有悬浮窗权限时，PROCESS_TEXT / SEND 可回退到独立 Activity。
- OCR 查词 WindowManager 浮层以用户提供的视频1词典页为 UI 基准：紧凑白色圆角卡片、词头 + 收藏图标、独立发音行、40dp 左右的浅灰词典分区头、白色词条内容区、底部工具栏和版权栏；工具图标使用黑色描边资源，禁止使用 Unicode 字符冒充主要工具栏图标。参考视频中的灰色圆点属于触摸指示器，不得做成可见抓手。顶部标题区可拖动窗口；底部版权栏整条区域作为隐形缩放热区，横向拖动改变宽度、纵向拖动改变高度，左上角保持锚定，并持久化窗口 x/y/宽/高
- OCR、Intent 与搜索查词小窗必须自己消费左右返回手势、系统返回和实体返回键，统一调用 `handleLookupBack()`，一次返回直接关闭整个小窗；编辑态和查询历史都不得拦截为“退出编辑”或“上一词”。收起键盘并移除 Mask 后，底层应用保持原页面；搜索入口可恢复原搜索查询。蓝色悬浮球始终位于查词浮层最上层。
- 对外查询 Deep Link 固定为 `jpdict://lookup?q=<URL编码后的查询内容>`（旧链接 `jp-native://lookup` 仍兼容接收）；由无界面的 `LookupLinkActivity` 接收后转发到 `FloatingService.ACTION_SHOW_LOOKUP_OVERLAY`，Deep Link 本身不得渲染或切换到查词 Activity 页面
- Deep Link 继续接受 `mode=fullscreen` 与 `mode=float`，两者在 Intent 入口都采用同一可拖动、可缩放小窗和全屏 Mask；不再因入口或 mode 改变卡片大小和交互。词典卡片仍是 `TYPE_APPLICATION_OVERLAY`；透明、无预览、无动画、空 taskAffinity 的 `LookupLinkActivity` 只承载 Mask 和系统返回，不渲染词典页面，并在小窗关闭时 finish。OCR 入口仍通过显式 Intent 将识别原文直接交给 Service，由 Service 复用该透明宿主；返回关闭后保持原桌面或应用，不恢复搜索层。
- OCR、Intent 与搜索的 WindowManager 根层覆盖整个显示区域并允许布局进入刘海区域。卡片外点击通过独立 Mask 点击层调用关闭方法；系统返回键、Android 13+ 返回回调和左右边缘返回手势统一调用 `handleLookupBack()`。
- 全屏黑色 Mask 使用约 42% 不透明度，由透明 `LookupLinkActivity` 的独立全屏 View 绘制，覆盖底部、状态栏和导航栏；系统栏透明且关闭系统额外 contrast scrim，关闭宿主后恢复调用方系统栏。`TYPE_APPLICATION_OVERLAY` 根层保留独立的卡片外点击层，宿主已绘制 Mask 时此层不重复着色，避免双重变暗；没有宿主时由 Overlay 自己绘制 Mask。不能仅依赖悬浮窗口扩大范围或半透明 Root background 保证系统栏遮罩。
- OCR、Intent 与搜索小窗顶部词头支持编辑态：点词头切换为输入框 + 蓝色“确认”并弹出键盘；点击与标题拖动通过 touch slop 区分。编辑态返回也直接关闭整个小窗并收起键盘。OCR 触发期间蓝色悬浮球不得临时设为 INVISIBLE。
- OCR、Intent 与搜索小窗的系统返回由透明、无动画、可触摸的 `LookupLinkActivity` 在小窗生命周期内接收。Android 13+ 通过 Activity 的 `OnBackInvokedDispatcher`、旧版本通过 `onBackPressed()` 把返回发送给 `FloatingService.ACTION_LOOKUP_BACK`；小窗关闭后 Service 通知宿主 finish，保持调用方页面不变。
- 普通浏览/全屏 Mask 状态下已接入透明宿主的 OCR、Intent 与搜索查词 Overlay 必须保持 `FLAG_NOT_FOCUSABLE`，确保透明 Back 宿主是真正的系统返回目标；只有点顶部词头进入编辑时临时去掉 `FLAG_NOT_FOCUSABLE` 以获取 IME，退出编辑或确认后立即恢复。禁止让常态 Overlay 抢走 Activity Back 焦点
- OCR 成功后的查词请求必须与 Intent、搜索选词共用透明 Mask / Back 宿主，不得继续渲染无宿主的 `mode=float` 结果。普通浏览时由宿主接收返回，Overlay 保持 `FLAG_NOT_FOCUSABLE`；词头编辑时临时聚焦 Overlay 并注册 `PRIORITY_OVERLAY` 返回回调，一次返回同时关闭键盘、查词和 Mask，底层应用不得收到该次返回。
- 搜索浮层的 Android 13+ 返回回调使用 `PRIORITY_OVERLAY`，与宿主共用 `handleSearchBack()` 的分层收起规则。抽屉横滑避开系统返回边缘；取消手势只恢复抽屉位置，不能关闭窗口或把同一手势传给底层应用。旧版输入框通过 `onKeyPreIme()` 消费返回。键盘位移读取实际 Insets，Android 11+ 跟随 IME 动画，禁止使用固定键盘高度；只在 IME 退场结束或明确收起时释放编辑焦点，避免进场动画中途失焦。
- 搜索页在密度、字号和方向变化后重新布局，保留查询草稿与私密状态；查词返回搜索后的宿主重建不能再次打开旧结果。横屏输入禁止输入法全屏提取，空间不足时收起顶部和推荐项并缩紧输入区；收起键盘后恢复布局。
- 搜索的私密态不写本机查询历史，也不读取或写入共享 AI 缓存；退出整个搜索或宿主进入后台时清除私密草稿。支持的输入法收到无个性化学习标志。AI 请求仍由 Groq 处理，不承诺第三方模型训练政策。界面不冒充 Grok 官方模型或会员；摄像头、图库、文件、技能、连接器、Imagine、构建和语音当前明确标为未接入。
- 搜索选词、查词返回搜索共用同一透明返回宿主。搜索输入框未编辑时 Overlay 保持 `FLAG_NOT_FOCUSABLE`，由宿主接收返回；编辑时临时聚焦搜索 Overlay 并注册返回回调。宿主退到后台时必须移除搜索、查词和 Mask，不得在后台恢复搜索。
- vivo/OriginOS 的透明 Back 宿主不得设置 `FLAG_NOT_TOUCHABLE`，两种 mode 都保持可触摸、可聚焦，并在 `onPostResume()` 注册高优先级返回回调；上方 WindowManager Overlay 继续接收卡片和卡片外点击。
- 编辑态临时聚焦 Overlay 时，Android 13+ 在编辑期间注册 `PRIORITY_OVERLAY` 的 `OnBackInvokedCallback`，返回时关闭整个小窗；旧版本通过输入框 `onKeyPreIme()` 处理键盘之前的实体返回。恢复 `FLAG_NOT_FOCUSABLE` 时立即注销回调。
- 蓝色 `あ` 悬浮球本体禁止为了 Z 顺序执行 `removeView/addView`；查询过程中本体持续挂载，结果层上方使用同步代理窗口保持视觉与触控连续，避免“先消失再出现”的闪烁。搜索键盘打开且原位置与输入器重叠时，仅临时将代理移到输入器上方；收起键盘后恢复原位置，不改持久化坐标
- OCR 层仅负责识别文字和返回尽量完整的原始识别文本，禁止只提取首个词、提前截取 40 字、强制判定语种或改写原文；OCR 四种文字脚本的结果选取属于识别任务，不属于语言语义判断
- OCR 的 Latin / Chinese / Japanese / Korean `TextRecognizer` 必须跟随 `ScreenCaptureService` 生命周期复用，禁止每次框选识别都重新创建并立即销毁；Service 退出时统一释放，仍在执行的识别完成后再安全关闭
- Groq 接收原始 OCR 文本作为用户消息，独立判断输入属于中文、日文、韩文、英文、混合文本或不确定语种；按实际语种解释，不得预设为日语或强制翻译成日语
- 简短汉字、模糊词语及语言重叠情形允许报告不确定性，不能假装能准确区别中文和日文

## Android CPU 架构 / ABI 说明

这里的 v7、v8 指的是 Android 设备的 CPU 指令集架构（ABI），不是 Android 系统版本。

| ABI | 简称 | 位数 | 主要设备 / 用途 |
| --- | --- | --- | --- |
| `armeabi-v7a` | v7 | 32 位 | 老 Android 手机、旧平板 |
| `arm64-v8a` | v8 | 64 位 | 现在绝大多数 Android 手机 |
| `x86` | x86 | 32 位 | 老模拟器、少量旧设备 |
| `x86_64` | x86_64 | 64 位 | 模拟器、部分 Chromebook / 特殊设备 |

## APK 下载建议

正式发布 APK 必须同时满足上面的版本化命名规则和 **单 APK ≤ 30 MB** 的体积硬限制。

| 架构 / 变体 | 包含架构 | 建议 |
| --- | --- | --- |
| `arm64-v8a` | `arm64-v8a` | 推荐给现在绝大多数 Android 手机，体积最小 |
| `armeabi-v7a` | `armeabi-v7a` | 老旧 32 位 ARM 手机 / 平板 |
| `arm` | `arm64-v8a + armeabi-v7a` | 手机通用集合版，兼容新旧 ARM 手机 |
| `universal` | `arm64-v8a + armeabi-v7a + x86 + x86_64` | 仅在最终文件 ≤ 30 MB 时允许发布 |

普通用户建议：

- 新款 Android 手机：下载 `arm64-v8a`
- 不确定新旧 ARM 架构：下载 `arm`
- 老旧 32 位设备：下载 `armeabi-v7a`
- 模拟器 / Chromebook / 特殊设备：优先使用对应 ABI 构建；不得为了兼容而发布超过 30 MB 的 universal APK。

> `arm64-v8a` 和 `armeabi-v7a` 是当前手机端最重要的两种 ARM ABI。x86 / x86_64 如需支持，应继续采用分架构产物或其他不突破 30 MB 上限的方案。

## 固定 Release 签名

正式 APK 使用固定 Release 证书。公开证书保存在：

`signing/jpdict-release-cert.pem`

证书 SHA-256：

`CB:8B:97:98:9B:FE:78:94:E7:96:84:2C:E5:B2:E3:E3:B2:AA:B4:8A:A7:F0:F7:A9:7C:1E:CE:04:AC:18:42:52`

私钥 / JKS 不提交到仓库。GitHub Actions 读取以下 Repository Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

如果这 4 个 Secrets 尚未配置，Workflow 只生成临时 Debug Artifact，不发布正式 Release。Secrets 配好后，重新运行 Workflow 即可生成固定签名的正式 APK。

> 注意：GitHub Actions 的具体实现必须服从本 README 的版本与 APK 命名规则。Android `versionCode` 仍需使用独立的纯数字永久递增序列。

## 本地构建

为遵守单 APK ≤ 30 MB 的硬限制，默认只构建当前可发布的 3 个变体；universal 不作为默认发布产物。

Debug：

```bash
gradle --no-daemon \
  :app:assembleArmDebug \
  :app:assembleArm64Debug \
  :app:assembleArmv7Debug
```

正式 Release 构建需要先提供固定签名环境变量，然后运行：

```bash
gradle --no-daemon \
  :app:assembleArmRelease \
  :app:assembleArm64Release \
  :app:assembleArmv7Release
```

GitHub Actions 会自动重命名 APK、验证签名、计算 SHA-256、上传 Artifact，并创建 GitHub Release。所有产物名称和版本号必须遵守本 README 的规则。

## 搜索交互状态验证

轻量纯 Java 状态测试已接入 GitHub Actions，覆盖菜单互斥、所有返回层级组合、私密切换及重置：

```bash
scripts/test-floating-search-state.sh
```

需要 JDK；支持 `JAVA_HOME` 或 `PATH`。完整界面验收仍需 Android 设备/模拟器，包含桌面和第三方 App 悬浮球入口、键盘、抽屉拖动、菜单、查词往返与后台关闭。

GitHub Actions 在打包前运行三个常规架构的 Android Lint，错误会阻止构建。通知在 Android 8.0+ 使用频道构造器，Android 6.0–7.1 使用兼容构造器；应用内部广播统一通过现有 AndroidX Core 注册为不对外导出。

Android 6.0–10 的搜索页按实际可见窗口区域布局，并在绘制前补偿系统对编辑焦点的平移；Android 11+ 使用原生 IME Insets 动画。键盘显隐后菜单和抽屉遮罩仍须对齐可见区域，输入器的发送按钮不能被键盘遮住。
