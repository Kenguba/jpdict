# 悬浮按钮 v18 移植与验证

本记录不是工程规范；根 README.md 是唯一规范来源。

基线：`main` / `3bd718c3aee5509f27bb934417f1452ebcae8d34`。参考原文件：`jpdict_style_v18.html`，926 行、92,821 bytes，SHA-256 `3df8e409286498f2d03a7fcfcb2f4393b7b87487a529b7dd52eab10b181b3235`，已完整读取 CSS、DOM、JavaScript。没有读取或复用 `work/native-bubble-html-v18-20261013` 的实现。

用户最后补充优先：布局、功能和交互遵循 v18；视觉圆角、间距与颜色风格沿用 JPDict。原生标题栏保持 48dp，卡片圆角 18dp、控件圆角 14dp，沿用现有蓝色、灰白底、系统 SeekBar 和字体。HTML 的虚拟状态栏不移植；不使用 WebView。

## 功能与职责对照

| HTML / 用户要求 | 原生职责 |
| --- | --- |
| 外观/手势、已保存样式吸顶 | SettingsView 的 stickyHeader；Activity 的固定标题/头部与原生纵向 ScrollView |
| 左侧六形状、右侧默认/文字/图片编辑 | SettingsView；shape sidebar 随滚动位置调整 |
| 24–100dp、透明度、Icon 50–150% | Config + 平台 SeekBar |
| 六个 SVG 搜索图标、SVG 导入 | 共用 AppearanceRenderer；SAF SvgPickerActivity；受限 BubbleSvg |
| Icon / 背景独立纯色、线性、自由渐变 | 不可变 ColorConfig；PaintEditorView；共享 PaintRenderer |
| HSV、HEX、六个纯色/渐变预设 | 原 ColorPickerView 与 PaintEditorView 的局部横向列表 |
| 圆形角度控制：0 上、90 右、颜色顺序 | AngleDialView；CSS 投影长度对应原生 LinearGradient |
| 自由渐变 2–8 点、半径 10–100 | FreeGradientEditorView；二维径向融合 ColorField |
| 图片 20–300%、XY ±100%、单指拖/中心捏合/重置 | 原 SAF ImagePickerActivity、本机副本；ImageEditorView 与共用 Renderer |
| 点按样式/保存覆盖/＋新增/560ms 删除确认 | HistoryView 持有稳定 ID；Facade 的原子持久化事务 |
| 草稿预览、短暂后台/图片返回保留、退出回滚 | 原 Activity 生命周期和同进程 Facade 观察通知 |
| 系统浮球、顶层代理、OCR、查词、返回 | 保留 FloatingService 和原触摸控制器，通过同一外观 Facade 更新 |

## 数据兼容设计

- 使用明确的 `data_version = 3` 和完整 `appearance_v3` JSON。仍保留旧字段，以便合理回退读取。
- Config 完整表达形状、内容、图标类别/比例/SVG、两个独立 ColorConfig、自由色点、图片引用与变换。Builder 产生不可变快照；编辑控件通过回调更新单一草稿。
- 旧预设缺 ID 时按原位置与内容分配确定 ID；不按视觉值去重。正常新建使用 UUID；覆盖只按 ID 更新原位置；只有 ＋ 新增至左侧，十个上限只限制新增。
- 旧 `あ`/角标、旧搜索图标、连续圆角与图片 center-crop 通过 legacy 标志保留。只改形状只更新轮廓，不顺带替换旧图形或裁切规则；主动选择新内容/图标或编辑图片变换后采用对应的 v18 规则。
- 模型兼容空图片引用；UI 按用户要求只保留上传/更换入口。历史样式和当前已提交/预览引用均参与清理保护。图片选择/取消不提交配置。
- 系统备份规则原本包含外观 preferences 和私有图片文件；保持现有备份规则。

## 算法与平台差异

自由颜色融合按 v18：`spread = .085 + .0035 × radius`，`weight = exp(-distance²/(2 × spread²)) + .012`；横向距离乘画布宽高比，通道按 ImageData 的最近偶数规则取整。生成在有界后台队列，缓存有界，完成后仅重绘。

HTML 的胶囊在正方形预览上使用 `border-radius:999px`，实际同圆形；原生保持该规则。六边形、菱形和星形使用实际 clip-path 比例，选择栏图标与裁切轮廓分别遵循 HTML 的来源。

SVG 为绘图蒙版，限制 120KiB、节点/深度/路径复杂度及数值，禁止 DTD、实体、脚本、事件和资源引用。支持的路径、几何图形、分组与变换由平台绘制；未实现的 SVG 特性必须明确拒绝，并在交付中列明，不能用其他图标替代。

HTML 手势页是占位；原生显示现有操作说明，保持真实轻点、长按 OCR、方向反馈及返回机制。Android 12+ 仍保留代理覆盖时本体窗口 alpha=0 / NOT_TOUCHABLE 的现有机制；参考 [Android 官方触摸限制](https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events)。

## 修改文件与职责

Java 文件均位于 `app/src/main/java/com/yuen/jpdict/`；新增组件只承担独立编辑或共用渲染职责。

| 文件 | 职责 |
| --- | --- |
| `FloatingBubbleAppearance.java` | 扩展现有外观 Facade、不可变配置/颜色点、版本化 JSON、稳定 ID 事务、兼容与图片引用清理 |
| `FloatingBubbleSettingsActivity.java` | 固定原生头部、滚动通知、原生命周期草稿预览、SVG 结果、手势页返回 |
| `FloatingBubbleSettingsView.java` | v18 双栏和模式、尺寸/文字/图标协调、单一草稿、保存/新增/删除确认；六形状采用固定图标列/文字列，整组居中 |
| `BubbleStyleHistoryView.java` | 按 ID 选中、完整共享缩略图、横滚取消 560ms 长按、几何居中的＋ |
| `BubbleColorPickerView.java` | 复用 HSV/HEX，调整紧凑布局、锁定触摸指针和无效输入恢复 |
| `BubbleImagePickerActivity.java` | 保留系统图片选择与 256px 私有副本；临时压缩文件完成后再发布 |
| `BubbleAppearanceRenderer.java`（新增） | 六形状、内置搜索图标、SVG 蒙版、文字/图片与旧视觉共用 Canvas 绘制 |
| `BubblePaintRenderer.java`（新增） | 纯色/线性 Shader、自由场后台队列和有界纹理缓存，供设置/历史/浮球复用 |
| `BubbleColorField.java`（新增） | 独立可测试的 HTML 自由颜色融合算法 |
| `BubblePaintEditorView.java`（新增） | 两个独立目标的颜色模式、六个预设、输入状态与对话框协调 |
| `BubbleFreeGradientEditorView.java`（新增） | 2–8 色点选中/拖动、HEX/XY/范围、增加/删除/重置与焦点处理 |
| `BubbleAngleDialView.java`（新增） | 外圈角度拖动、HTML 方向与颜色顺序 |
| `BubbleImageEditorView.java`（新增） | 同一图片变换状态的滑杆、拖动、手势中心捏合、形状裁切与重置 |
| `BubbleSvg.java`（新增） | 受限 SVG 解析、绘制与 16 项 LRU 缓存 |
| `BubbleSvgPickerActivity.java`（新增） | SAF、严格 UTF-8/120KiB、可取消的后台本机文件读取与明确失败提示 |
| `app/src/main/AndroidManifest.xml` | 注册不导出的 SVG 选择 Activity |
| `app/build.gradle` | 可选 `jpdictTestRunner` 参数，默认词典 Instrumentation 保持不变；没有新增依赖 |
| `app/src/test/java/.../FloatingBubbleAppearanceTest.java` | 项目实际编译类的纯 Java 配置、事务、边界和融合回归检查 |
| `app/src/androidTest/java/.../BubbleAppearanceInstrumentation.java`（新增） | 独立 QA 包中的持久化/渲染/生命周期、真实 SAF、进程重启与截图检查 |
| `app/src/androidTest/java/.../BubbleEditorValidation.java`（新增） | 编辑手势、布局、真实挂载的长按/横滚/焦点和居中检查 |
| `app/src/androidTest/java/.../BubbleTextRenderingValidation.java`（新增） | 中日英完整存储与前两个字符/旧文字的共用渲染夹具 |
| `README.md`、本文 | 更新当前功能描述和记录实际验证；工程规范仍只在根 README |

`FloatingService.java`、OCR、查询/代理触摸控制器、备份规则、版本与发布工作流没有修改。

## 验证环境与命令

2026-10-13：JDK 17.0.20.1、Gradle 8.11.1、compileSdk 35 / minSdk 23 / targetSdk 35；API 37.2 ARM64 模拟器。所有设备写入与选择测试使用临时 `com.yuen.jpdict.v18qa` 包，未修改原应用数据。QA 副本只改 applicationId 和测试 provider authority，并通过属性选择平台 Instrumentation。

```sh
gradle :app:assembleArm64Debug :app:assembleArmv7Debug :app:assembleArmDebug \
  :app:lintArm64Debug :app:lintArmv7Debug :app:lintArmDebug
sh scripts/test-floating-bubble-config.sh
sh scripts/test-floating-search-state.sh
sh scripts/test-mdict.sh

# 以下只在可丢弃 QA 副本中运行；默认真实包会被 runner 的后缀守卫拒绝。
gradle -PjpdictTestRunner=com.yuen.jpdict.BubbleAppearanceInstrumentation \
  :app:assembleArm64Debug :app:assembleArm64DebugAndroidTest
adb install -r app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
adb install -r app/build/outputs/apk/androidTest/arm64/debug/app-arm64-debug-androidTest.apk
adb shell am instrument -w com.yuen.jpdict.v18qa.test/com.yuen.jpdict.BubbleAppearanceInstrumentation
adb shell am instrument -w -e mode ocr com.yuen.jpdict.v18qa.test/com.yuen.jpdict.BubbleAppearanceInstrumentation
adb shell am instrument -w -e mode restart-save com.yuen.jpdict.v18qa.test/com.yuen.jpdict.BubbleAppearanceInstrumentation
adb shell am force-stop com.yuen.jpdict.v18qa
adb shell am instrument -w -e mode restart-check com.yuen.jpdict.v18qa.test/com.yuen.jpdict.BubbleAppearanceInstrumentation
adb shell am instrument -w -r -e mode picker -e kind image com.yuen.jpdict.v18qa.test/com.yuen.jpdict.BubbleAppearanceInstrumentation
adb shell am instrument -w -r -e mode picker -e kind svg com.yuen.jpdict.v18qa.test/com.yuen.jpdict.BubbleAppearanceInstrumentation
adb shell am instrument -w -e mode capture -e label 393 com.yuen.jpdict.v18qa.test/com.yuen.jpdict.BubbleAppearanceInstrumentation
```

`picker` 模式需在系统 SAF 中实际选择文档，60 秒内返回；取消另由主设备检查覆盖。截图模式使用原创色块图，测试数据的预设数量与真实用户数据无关。屏幕以 `wm size` / `wm density` 临时设置，结束恢复默认。

## 实际结果与边界

- 三 ABI Debug 构建成功，三个 Lint 均为 **0 errors、103 warnings**；不宣称零警告。没有构建 Release。
- 配置纯 Java **126 条**检查、搜索状态检查通过；MDX/MDD **2,623 条**及调度检查通过。
- 最后一轮 **474 条设备断言**在 density 160 与 420 各通过一次，包括 320/393/600dp 量测、六项图标中心和名称起点一致、两列组中心误差≤1px、最长名称完整显示、自由点有焦点的 HEX/XY × 新增/删除/重置、无效值保留、真实 portrait→landscape 配置重建后的完整草稿/ID/无效输入与双栏无溢出。
- OCR 逻辑 **28 条**通过；`restart-save` 3 条、真实 force-stop 后 `restart-check` 5 条通过，保留两个完整有序预设与当前 ID。
- 原系统 SAF 实际选取 PNG、SVG 成功，私有 PNG ≤256px、变换重置、安全 SVG 内容/名称、已提交配置与整个历史未改、退出回滚均核对通过；第一次手工图片协调超过 60 秒被测试判失败，重跑在期限内完成。
- 编辑/布局的大部分设备断言使用真实 View 的合成事件；历史长按/横滚与自由点输入焦点在实际挂载 Activity 中执行。长按只抽样验证 180ms 未触发、640ms 已触发确认，不宣称精确毫秒边界实测。
- 实际浮球点击打开原搜索层；静止长按出现 MediaProjection 授权页，授权后长按拖动保存新坐标（88,658），同时挂载的本体窗口 alpha=0 / NOT_TOUCHABLE 与可触摸顶层代理核对通过。没有关闭系统遮挡触摸保护。退出已停止临时 QA 共享与服务。
- 本次未测真实 OCR 文本识别精度/四脚本模型下载、物理振动、OriginOS 或其他实体手机、Android 23–36；不能把逻辑检查当作这些实机验证。备份 JSON/图片引用兼容已检查，未实际执行云端备份/恢复。
- 最终构建的 Debug APK：arm64 4,459,752 bytes、armv7 4,459,751 bytes、arm 4,459,731 bytes；基线 arm64 为 4,112,885 bytes，增加 346,867 bytes（约 8.43%）。最终 Git 跟踪文件合计 1,184,296 bytes，低于 README 的 35MiB；所有 Debug APK 均低于 5MiB。未据此宣称签名 Release 体积已经实测。

## 视觉对照与明确差异

参考原 HTML 通过本机 Chrome 在 320/393/480 CSS px 生成顶部、全页、线性、自由、图片截图；三个宽度的 document scrollWidth 等于 viewport。原生相同 dp 宽度截图包含顶部/双栏、线性、自由与点详情、图片详情、键盘和退出回滚；600dp 另有 View 量测。393 对照使用一个相同初始样式的临时 HTML localStorage 夹具，没有修改原文件。

截图存放在当前任务产物目录 `bubble-v18/`：`compare-393-top.png`、`compare-393-linear.png`、`compare-393-free-detail.png`、`compare-393-image-detail.png`，原始截图为 `html-*` 和 `v18-qa/native-*`。实际浮球入口/授权/代理拖动截图为 `native-service-search-320.png`、`native-service-ocr-permission.png`、`native-service-held-drag.png`。固定列对齐的末轮截图已刷新，320/393/480dp 截图模式各通过 11 条检查；已逐区查看顶部、线性、自由点详情与图片详情。

原 HTML 240×180 自由场五个独立像素样本与原生比较，每通道误差不超过 1。六形状、六个内置图标均在设备位图中绘制，文字另核对中日英。逐区检查保留布局与交互，**不宣称浏览器和 Canvas 逐像素完全相同**：

- 按用户最后补充保留 JPDict 48dp 固定顶栏、18dp 卡片/14dp 控件圆角、间距、字号、平台 SeekBar 与字体；HTML 顶栏可随页面滚出。系统状态/导航栏和 Gboard 是实际 Android 界面，HTML 的虚拟系统栏与演示浮球不移植。
- 六项固定图标列与文字列整体居中、形状选中无左竖杠、渐变前三张完整显示、无「移除图片」、文字无重复标签，是用户要求的改动，相应区域有意不同于原 HTML。
- 宽屏原生页面使用可用宽度，HTML 演示手机最大 450 CSS px；局部列表仍横滚，不造成全页横向溢出。原生删除弹窗使用平台 dim，未复刻 CSS 背景 blur/入场动画。
- 平台字体、字重、抗锯齿与基线和浏览器有差异。文字完整保存最多八个 Unicode 码点，新文字按 v18 预览前两个，旧文字保留旧规则；不会按 UTF-16 切断 emoji。HTML 文字缩略图/预览中的固定白色或纯色缺陷没有沿用，原生文字与 Icon 使用同一独立颜色/渐变规则。
- 自由场使用 HTML 的固定采样与融合，在首次缓存未命中时短暂显示纯色占位，再由后台计算重绘；拖动尽量保留上一帧，不在 UI 线程计算整幅像素。没有实测帧率/长期内存曲线，不能宣称所有设备恒定 60fps。
- 旧浮球 12dp elevation 保留；凹星形不能使用 API23 的凸轮廓阴影，系统阴影不是 CSS shadow 的像素复制。
- SVG 支持 path、circle/ellipse、rect、line、polyline/polygon、g、基础 transform、fill/stroke 与蒙版透明度。style/CSS、defs/use、内部渐变、文字/图片、clip/filter、虚线描边和分组 opacity 明确拒绝；脚本、事件、外链、DTD/实体和样式处理指令禁止；正规 XML 声明允许。导入显示失败原因并保留原图标，损坏的已存 SVG 明示错误图形，不静默替换为搜索图标。

## 对抗式审查与修复

- 将按值去重改为稳定 ID 事务，覆盖不移动位置，相同视觉可以显式新增；测试连续覆盖、十个上限与删除当前项。
- SharedPreferences 的失败 commit 会先修改内存 map；恢复原完整 typed map，失败不通知/不清理图片，纯 Java 注入失败检查通过。
- 图片改为 cache 临时文件完整压缩/fsync 后发布到私有目录，避免清理器误删半写入文件；每次导入唯一名，历史引用保留。
- 自由场缓存/队列各 24，上一帧弱引用按 32 个 owner / 512K 像素上限裁剪；SVG LRU 16 可容纳十历史加草稿，命中先于 UTF-8 分配，避免宽屏反复解析。
- 拒绝非有限数、极小/过大 SVG viewBox、过深/过多节点和过长路径；限制 120KiB/500 节点/32 深度/4,000 数值与路径操作。使用 token 事件明确拒绝处理指令、非空 CDATA/实体文字，避免跳过外部样式而无提示。Canvas 保存/恢复采用 finally，失败不污染后续画布。
- 触摸按 active pointer ID 处理，横滚/页面滚动取消长按；弹窗取消恢复自身颜色与未完成输入，detach 清理弹窗。新增/删除/重置色点前结束输入焦点，阻止显示旧点、输入写到新点的状态错配。
- 测试中的 SAF 返回需等实际窗口重新获焦；Activity.finish() 需等真实销毁再断言清理。早期等待错误曾失败，修复的是测试时机，没有在生产代码追加生命周期覆盖。

末轮构建、两个密度的设备检查、三个宽度的截图与只读对抗式审查已完成，没有已知阻塞实现缺陷；上述 SVG 子集与平台差异保留。main 提交使用 `[skip ci]` 避免触发仓库自动发布工作流，不创建 Release 或上传 APK。
