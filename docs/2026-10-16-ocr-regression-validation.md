# 2026-10-16 OCR 紧急修复验证

## OCR 命中与模型

环境：Android 17 / API 37、arm64-v8a 模拟器，独立 `com.yuen.jpdict.p0qa` 包；正式包和词典数据未用于测试。模型来自项目正式的四脚本内置依赖。

PR #7 的英文分支同时采用 `regionAt(..., 0)` 和 `Rect.contains()`。在 Android Instrumentation 中，以带真实 `Rect` 的 `the first place` 字框为输入，首次准星 `(145, 38)` 距单词上边界仅 2 px，传入容差 12 px，旧代码仍返回空选区；新增回归的第一条断言在旧版失败。修复后按单词真实边界执行有界容差命中；等距间隙保留近处已选词，实际命中相邻词则切换，远离则清空。无字符框或部分字符框的完整单词可使用模型元素边界；无真实词框的多词整行不推测横向偏移，允许同帧词元素或无 seed 局部精读恢复。

`OcrSelectionRegressionValidation.run()` 覆盖首次偏移、20 次移动和折返、空格和两行、远离清空、半开边界、缺失和部分字符坐标、跨空格的聚合 symbol、空候选回退、CJK / 混合原文、不将 `girst` 猜改为 `first`。另外使用可阻塞的 SDK 入口验证主线程可响应、替换识别器不被旧 worker 占用、最终回调在主线程执行及在途 Bitmap / 模型生命周期。

`-e ocr models` 直接识别原创 Bitmap：1000×260，白底、48 px sans-serif；英文包含 `the first place` 和 `another second line` 两行。已实际测得：

| 样本 | 实际原文结果 | 一次测量耗时 |
|---|---|---:|
| 英文两行、按词输出 | `the / first / place / another / second / line` | 179 ms |
| 中文 | `中文识别` | 85 ms |
| 日文 | `日本語の辞書` | 18 ms |
| 韩文 | `한국어 단어` | 6 ms |

英文真实模型字框执行 20 次初始上偏 2 px、移动及折返，全部返回单个 `first`；英文句子模式保留 `the first place`。以无 seed、准星上偏 2 px 调用有界放大局部识别，也返回 `first`，覆盖截图裁剪和坐标逆映射。耗时包含模型调度，仅代表本次模拟器和样本，不能代表真机帧率或通用识别率。

## 准星覆盖字形造成的真实识别错误

首次完整 MediaProjection 链路返回了 `fist`，预览阶段已经错误，不能归因于精读选词过滤。进一步使用实际内置 Latin 模型，在 1000×260 和真实屏幕大小 1080×2424 的白底 Bitmap 上绘制相同 48 px 文字；除了准星外所有像素相同。设备 density=2.625，旧准星半径 4dp、白色描边 3dp，其覆盖直径约 28.875 px，足以擦掉邻近字形。两种尺寸的中央落点均得到以下完整原文：

| 叠加图形 | 实际模型候选 |
|---|---|
| 无准星 | `the / first / place / another / second / line` |
| 原白色 3dp 描边 + 蓝色 1.5dp 圈 | `the / fist / place / another / second / line` |
| 仅保留不透明蓝色 1.5dp 圈，中央落点 | `the / first / place / another / second / line` |
| 1dp、alpha=0x66 的蓝色圈，中央落点 | `the / first / place / another / second / line` |

无圈对照重复执行仍为 `first`。旧白圈的预览、带 seed 精读和无 seed 精读都为 `fist`；单纯精读无法恢复已被 UI 改写的截图像素。这是识别输入受准星污染的实证，没有使用词典或根据拼写猜词。

仅去掉白描边也不充分：继续扫描五个字母中心和四个字间边界，两尺寸共 18 点，不透明蓝圈在 `f`、`i` 和部分边界位置会得到 `irst` / `rst` / `frst`。改用无白底的 1dp、alpha=0x66 彩色圈后，同样 18 点的预览、带 seed 精读、无 seed 精读共 54 个结果全部为 `first`；约 2.4 秒完成读取（单点包含三个阶段为 84–401ms）。细半透明圈保留下面的字形明暗，同时保留准星位置、蓝/绿状态和原 140ms 平滑显隐，不采用截图时反复隐藏/恢复窗口。

永久模型回归通过反射构造实际 `OcrPickupController.SelectionView`，不挂载窗口，设置真实 aim 坐标后调用 `View.draw()` 绘到 Bitmap，避免复制新版绘图实现。它在两尺寸下逐字母中心及字间边界检查 `first`；历史白描边仅作为输出原文的对照图。原始 `girst` 保留测试继续独立执行，不能通过文本改写掩盖图像问题。此处的像素探针仍需与完整屏幕共享 20 次选择共同验收。

## 混合整句精度的既有限制

对单行 `中文 father 日本語 한국어`，首次严格探针要求整句同时保留各脚本文字，实际失败：

```text
Mixed-script model merging retains Latin, Japanese and Korean text on one line
raw=+ỳ father ĝf04
```

为区分本次回归与原有精度限制，另将 `ebbba7d` 的原始 `MultilingualOcrRecognizer` 和 `OcrSweepSelection` 复制到临时测试源，仅修改类名以与新版共存，在同一设备、同一位图和同一模型依赖下运行。旧算法和新版的混合整句结果逐字相同：`+ỳ father ĝf04`（旧 50 ms、新 30 ms）。旧算法的纯中文、日文、韩文样本均返回上述完整原文。此次未修改脚本评分或候选合并逻辑，也未使用字典纠正 OCR。

混合按词模式的实际候选为 `+ỳ / father / 한국어 / 中文father / 日本語子 / 주 / E#`，保留了中文、Latin、日文和韩文片段，也存在误识别及不正确分词。因此最终回归明确检查四脚本文字片段保留，并持续输出混合整句的完整原文和 exact-match 结果；**不能据此声称混合整句准确率通过**。该现象已由真实模型对照确认是既有限制，不以仅检查非空掩盖。

无 GMS 的目标 OriginOS 真机、其他字体和字号、真实页面语料精度仍需设备验证。纯 Java 规则和上述 Bitmap 探针不能代替悬浮窗口、MediaProjection 与拖动帧率实测。

## 圆盘、截图线程与生命周期

- `captureMain` 原先在主线程同步执行整屏 Bitmap 分配、像素复制、去 padding 和裁剪；`refineVariant` 也在调用线程执行放大/增强，Task 默认主线程回调还承担了候选映射。这些工作现在分别在截图专用 HandlerThread 与有界 OCR worker 中执行。原生 Image 的获取、复制和关闭在同一线程串行，Bitmap 独立交给 OCR，回调切回主线程后核验截图源、session 和 operation。
- 重复 `hideOverlay()` 会取消并重启同一 140ms 退场动画。Android 基线测试以间隔 90ms 连续取消，原定退场后仍保留指针窗口，明确失败。现在重复隐藏幂等，新手势取消旧 end action 后接管冻结画面；旧 session 不能改新界面。
- 圆盘原先在 attach 回调自动启动入场，快速释放时可能覆盖控制器已安排的退出；入退场现在统一由控制器发起。移除窗口也不再用 `isAttachedToWindow()` 判断是否已加入 WindowManager，避免首次 attach 前关闭时遗漏已注册窗口；OEM 已移除窗口仍以捕获 IllegalArgumentException 安全结束。
- 不变的位置/方向不重复移动旋钮、更新窗口或重绘圆盘；绘图 Path、位置数组和旋钮 Rect 复用。圆盘窗口缩到实际绘制范围，真实浮球未因 Z 顺序卸载/重挂。原 700ms 长按、150ms 停留、死区/行程、方向和中心取消、140ms 指针显隐保留。
- 新的精读阶段标志只用于拒绝重复预览/松手请求；显示生命周期仍由现有窗口引用、`overlayHiding`、session 与 gesture generation 管理。空精读、精读错误和超时消息可使用当前会话已有的有效预览，真正授权撤销仍取消。

一次 Android 实测：20 轮真实 WindowManager 圆盘，包含慢/快移动、四向切换、中心取消、退出时新按下、20 次指针接管、重复预览、精读失败、旧结果和清理，316 条断言通过。独立 native ImageReader / ImageWriter 测试 51 条断言通过，覆盖阻塞截图 worker 时主线程取消/重启、只保留最新请求、20 次静态帧独立复制与关闭/线程退出。连续 20 次方向模式提交后立刻新按下，也验证旧的延迟截图不进入新会话；另含中心释放取消。基础选词/线程回归 83 条，真实模型回归 97 条通过。

该模拟器 API 37 强制使用 SwiftShader 软件 GLES（启动日志明确说明），不能代表目标手机 GPU。一次圆盘首次绘制距 DOWN 为 707–807ms（包含原有 700ms；最终一轮为 20 次模式提交加一次中心取消），Choreographer 间隔 p95 / max 为 83 / 266ms。gfxinfo 快照在窗口缩小前后的帧耗时 p50 / p95 分别为 69 / 150ms 与 53 / 117ms；软件渲染仍有大量超期帧。它们证实实际窗口/动画已运行，也暴露当前测量环境的绘制瓶颈，**不能据此宣称真机流畅或所有帧达标**。OriginOS 的长按延迟、持续拖动帧率、屏幕边缘及 20 次后性能仍需实机验收。

## 构建与命令

本地使用 JDK 17、Gradle 8.14.3、Android SDK 35；仅构建，不触发发布工作流，不变更版本与签名。

```sh
# 对临时独立 QA applicationId 构建并安装，不覆盖正式包。
gradle -I /tmp/jpdict-p0-qa.gradle :app:assembleArm64Debug :app:assembleArm64DebugAndroidTest
adb shell appops set com.yuen.jpdict.p0qa SYSTEM_ALERT_WINDOW allow
adb shell am instrument -w -e ocr logic com.yuen.jpdict.p0qa.test/com.yuen.jpdict.DictionaryInstrumentation
adb shell am instrument -w -e ocr p0 com.yuen.jpdict.p0qa.test/com.yuen.jpdict.DictionaryInstrumentation
adb shell am instrument -w -e ocr models com.yuen.jpdict.p0qa.test/com.yuen.jpdict.DictionaryInstrumentation
# 实际屏幕共享，需通过模拟器系统授权框。
adb shell am instrument -w -e ocr live com.yuen.jpdict.p0qa.test/com.yuen.jpdict.DictionaryInstrumentation

gradle --no-daemon :app:assembleArmDebug :app:assembleArm64Debug :app:assembleArmv7Debug \
    :app:lintArmDebug :app:lintArm64Debug :app:lintArmv7Debug :app:testArm64DebugUnitTest
sh scripts/test-ocr-gesture-rules.sh
sh scripts/test-floating-search-state.sh
sh scripts/test-floating-bubble-config.sh
sh scripts/test-mdict.sh
```

三 ABI Debug 构建、三 ABI Lint（各 0 errors / 109 warnings）和 Gradle unit task 通过。脚本验证 OCR 手势 33 条、浮球配置 126 条、MDX/MDD 2646 条以及调度/缓存/远程策略、搜索状态与输入组合通过。旧 OCR 平台回归 190 条、语言路由 175 条通过。未执行正式签名 Release、升级覆盖或发布；Debug 三 ABI 包体均低于 30 MiB，四脚本离线模型资产仍在包内。

## 完整屏幕链路

通过系统授权框启动真实 MediaProjection，在独立 QA 包中绘制 `the first place` 和第二行；使用真实悬浮窗口、控制器、截图与内置模型，松手后记录 lookup 回调（未使用任何字典猜词）。改指针之前，实际 preview=`the / fist / place ...`，最终也为 `fist`，严格断言失败。修复后连续 20 次取词覆盖五个字母中心、上下 2 px 变化、小幅移动和折返，**20/20 返回单个 `first`**；每次查询回调后立即再开始下一次操作。

本次软件 GPU 模拟器的 DOWN→lookup 为 1025–2163ms，UP 回调自身为 0–1ms；截图帧回调距 DOWN 为 558–1232ms，预览回调 841–1742ms，精读结果回调 1010–2148ms。数值包含停留阈值、系统捕获、SDK 工作和 UI 回调排队；这次测量期间宿主还执行了三 ABI 构建，不能用作独立性能基准。它验证完整链路能反复完成及松手不再同步处理 Bitmap，并不证明 OriginOS 的绝对延迟或帧率。

`OcrLivePipelineValidation` 保留严格的 20 次 `first` 检查，并记录三个阶段回调、UP 自身耗时和首轮 QA 屏幕截图；失败也输出 preview 原文，不能用“非空”替代成功。悬浮系统触摸分发、真正欧路跳转、真实词库查询和无 GMS 目标机仍需要实机验证。

## QA 包准备与体积

上述 `/tmp/jpdict-p0-qa.gradle` 是临时构建配置，内容如下。另创建 `/tmp/jpdict-p0-test-manifest.xml`，复制项目 `app/src/androidTest/AndroidManifest.xml` 并将 provider authority 改为 `com.yuen.jpdict.p0qa.test.fixtures`，避免与已安装的正式测试包冲突。

```groovy
allprojects {
    afterEvaluate { p ->
        if (p.plugins.hasPlugin('com.android.application')) {
            p.android.defaultConfig.applicationId = 'com.yuen.jpdict.p0qa'
            p.android.sourceSets.androidTest.manifest.srcFile('/tmp/jpdict-p0-test-manifest.xml')
        }
    }
}
```

安装 QA APK 后授予其悬浮权限；live 额外需要 `POST_NOTIFICATIONS` 和模拟器的系统屏幕共享授权。没有覆盖安装/清空正式应用，也没有读取或修改用户词库。正式包名、签名、版本文件及发布工作流均未修改。

最终 Debug APK：arm 25,936,506 bytes，arm64 18,684,053 bytes，armv7 14,860,820 bytes，分别核对 ABI 与 Latin/Chinese/Japanese/Korean 模型资产，均低于 30 MiB。加入本次测试与文档后，Git 跟踪项目仍不足 2 MiB，低于 35 MiB；最终提交前再核对。

修改范围：`OcrSweepSelection`、`OcrPickupController`、`OcrJoystickView`、`MultilingualOcrRecognizer`、`ScreenCaptureFrames`、`MainActivity.ScreenCaptureService`、`FloatingService`；新加四个 OCR 平台回归类，接入现有 `DictionaryInstrumentation`，仅调整旧 `OcrValidation` 的异步入口等待与同帧快速手势构造。没有加入新依赖、替换模型、改变词典或执行发布。
