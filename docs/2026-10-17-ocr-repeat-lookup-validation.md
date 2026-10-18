# 2026-10-17 连续 OCR 取词修复与验证

## 范围与证据边界

用户现象：第一次取词后关闭词典弹窗，第二次拖动仍有准星，但停留一秒也没有结果。此次先只读调查；用户随后明确授权“先找出原因后修复”。基线为已拉取的 `origin/main`：`00d2b8cd401e6a8e2bef02accacd9a428ab3d28f`，工作分支为 `codex/ocr-repeat-lookup-recovery`。没有提交、推送、合并、改版本或发布。

已核查历史“修复真机 OCR 连续取词故障”的设备记录：小米 Android 15 / API 35。记录中有成功的截图、模型与精读回调，也有系统录屏占用 MediaProjection 的状态；当时完整的连续操作验收为 0 次有效完成，不能视为本次问题已在真机定位或修复的证据。本次没有连接手机，以下确定结论来自 Android 回归与模拟器实际链路。

按用户要求采用对抗式审查：区分可复现缺陷和手机故障的推断，检查调用链、共享状态、取消/重授权与原球/代理球交接；未将“有准星”“模型单独能识别”或“构建成功”当作完整取词通过。

## 可复现缺陷与最小原位修复

调用链：`FloatingService` 原球/代理球 → `OcrPickupController.listener()` → 停留门控 → `ScreenCaptureService` 预览/精读 → 当前 session 的识别广播 → `Host.lookup()` → 实际词典窗口 → Back 恢复原球。

### 1. 排队的触摸事件丢失识别触发

原 `drag()` 用处理事件时的 `uptimeMillis()` 作为停留起点，再投递延迟任务；松手无条件删除该任务。如果主线程来不及运行停留任务，实际已完成的停留不会被承认。绘制准星并不依赖识别任务，因而可以出现“有准星、无识别请求”。

复现使用实际控制器和 Android `MotionEvent`，在同一主线程回合交付带原始时间戳的 DOWN/MOVE/UP，模拟积压事件；MOVE 至 UP 已经过 300ms。修复前失败：

```text
AssertionError: Batched touch events preserve the real 300ms dwell when the UI cannot run its timer before UP
```

修复在原 `drag()` 使用 `event.getEventTime()`，按相同 uptime 时钟投递绝对执行时间；在原松手分支按既有 `OcrDwellGate` 补做资格判断，并调用已有 `activate`。复用其 `ocrRequested`、gesture generation、closed 与 held 检查，不新增计时器、状态或识别入口。原先 150ms 的停留门槛不变。

反例测试：排队停留 300ms 和恰好 150ms 应识别；149ms 和 40ms 不应识别。区域移动重置停留、700ms 长按方向圆盘、取消及旧 session 隔离仍由现有测试覆盖。

### 2. 旧屏幕共享的停止广播误取消新取词

`ScreenCaptureService.stopCapture()` 在发出 `ACTION_STOPPED` 前将唯一共享就绪状态 `READY` 设为 false；广播可能晚于替代共享的 READY 和新手势抵达。原接收分支只要 `armed` 就无提示取消，未区分已经恢复的共享。

在新取词已发出预览请求、READY 为 true 时交付延迟的旧 STOPPED，修复前失败：

```text
AssertionError: A delayed stop from the old projection cannot silently cancel a fresh pickup
```

修复在同一分支检查现有 READY：共享已恢复时忽略旧停止通知；真实停止则复用 `fail()` 清理并提示“屏幕共享已结束，请重新取词并授权”。没有第二份共享状态。反向测试将 READY 设为 false，验证当前选区和等待状态清除、下一次重新申请授权、授权后沿原入口恢复预览。

两个缺陷均可独立复现；它们与用户现象吻合，但不能据此断言未连接的手机必定只受这两个因素影响。

## 实际验证结果

环境：Android API 37.2 arm64 模拟器、SwiftShader 软件渲染、独立 `com.yuen.jpdict.repeatqa` 与测试包；模拟器以只读且不保存快照方式启动。JDK 17、Gradle 8.11.1、Android SDK build-tools 36.0.0。正式应用和用户词典、SAF 授权、记录未用于测试。

| 验证 | 实际结果 |
|---|---|
| OCR 控制器和语言回归 | 197 + 175 条断言通过，包含两项失败复现及 150ms 边界 |
| 真实 WindowManager 交互 | 316 条断言通过，20 次模式提交与中心取消，包含长按、指针接管与窗口清理 |
| native 截图 worker 回归 | 51 条断言通过，包含取消、在途操作、最新排队请求与图像生命周期 |
| 选词回归 | 83 条断言通过，包含初次落点、折返、字框及旧结果隔离 |
| 纯 Java 手势规则 | 36 条断言通过 |
| 实际输入→截图→模型→词典→Back | 20/20 次完成，交替得到准确的 `first` / `second` |
| arm64 Debug 应用与测试 APK | 构建成功 |
| arm64 Debug Lint | 0 errors / 109 warnings，未扩大范围清理警告 |

完整往返由新增 `OcrLookupRoundTripValidation` 通过系统输入注入驱动真实浮球监听器，使用真实 MediaProjection、项目内置 Latin 模型、真实精读和实际词典浮层。每轮断言查询词等于本轮新词、代理球出现，Back 后词典与代理球均移除并恢复原球；同一 FloatingService、ScreenCaptureService 和 projection 连续复用，关闭后无在途或排队请求。第二轮额外发送延迟的旧 STOPPED，仍得到 `second`。没有每轮重启服务、重授权或直接塞入识别结果。

测试页使用实际屏幕原点计算字形与准星位置；此模拟器的原点为 `[0,142]`。早期测试未计入该偏移而精读失败，属于测试坐标错误，未计入生产缺陷。仅修复停止广播的一轮真实往返在第 16 轮未发出请求，未算通过；最终版本完成全部 20 轮，之后又完成全部上述控制器和交互回归。20 轮不能等价为无故障保证，也不能代表手机性能或通用识别准确率。

同时校正既有测试中过时的球中心横坐标预期：当前 main 的准星按触点 `finalX` 定位，而旧断言仍要求 `finalLeft+22`；没有为满足测试改变生产坐标。

## 改动预算与兼容性

唯一生产文件为 `app/src/main/java/com/yuen/jpdict/OcrPickupController.java`：UTF-8 文件从 26,347 B 变为 26,691 B，**合计净增 344 B**（停止通知 193 B，事件计时 151 B），低于单 Bug 全部生产代码净增 500 B 上限；没有通过删无关代码或压缩排版抵扣。

测试和本验收记录单独统计，不进入应用生产逻辑。未增依赖、权限、持久化字段、数据迁移或模型；不改词典/SAF/索引/缓存及离线识别契约。QA Debug APK 实测 19,047,056 B，低于 30 MiB；未构建或发布正式 APK。项目自有跟踪文件含本次新增文件约 1.8 MiB，低于 35 MiB。

回退只需撤销该控制器的本次差异，不涉及用户数据。手机 Android 15 / OEM 调度、真实使用页面及其他录屏应用打断共享尚未做本次实机验收。

## 复跑方式

只在可丢弃模拟器上使用临时 Gradle init 配置，将 applicationId 改为 `com.yuen.jpdict.repeatqa`；测试 manifest 的 fixture provider authority 同步改为 `com.yuen.jpdict.repeatqa.test.fixtures`。`roundtrip` 明确拒绝非 `.repeatqa` 包，避免影响正式设置。

```sh
gradle -I /tmp/jpdict-repeat-qa.gradle :app:assembleArm64Debug :app:assembleArm64DebugAndroidTest :app:lintArm64Debug
# 安装独立 QA 应用和测试 APK，再只给该包开启悬浮和通知权限。
adb -s emulator-5554 shell appops set com.yuen.jpdict.repeatqa SYSTEM_ALERT_WINDOW allow
adb -s emulator-5554 shell pm grant com.yuen.jpdict.repeatqa android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell am instrument -w -e ocr logic com.yuen.jpdict.repeatqa.test/com.yuen.jpdict.DictionaryInstrumentation
adb -s emulator-5554 shell am instrument -w -e ocr p0 com.yuen.jpdict.repeatqa.test/com.yuen.jpdict.DictionaryInstrumentation
# 在模拟器上批准此 QA 包的系统共享框；20 轮内不重启/重授权。
adb -s emulator-5554 shell am instrument -w -e ocr roundtrip com.yuen.jpdict.repeatqa.test/com.yuen.jpdict.DictionaryInstrumentation
sh scripts/test-ocr-gesture-rules.sh
```

## 工程规范与分支只读核对

JPDict 与 JPCARD 的简单优先、原位修改、复用、状态单一、兼容和按需加载原则一致。对比的 JPCARD main 为 `6aaf1bd2988ec0b8a6d1c3fdf154c831114cd966`，已核对与远端 main 一致。JPCARD 的根规范额外永久写入“Bug 先获批”和“500 B”，JPDict 当前根规范没有同样的永久条目；本次按用户明确要求执行这两项边界。JPDict 为原生 Java 项目，没有 `h5/README.md` 或仓库内 `DEVELOPMENT_ADVERSARIAL_REVIEW.md`；本次读取 JPCARD 对应文件作为指定审查方法，未复制 H5 的缓存/发布约束或修改项目规则。

远端三条 OCR 修复分支的提交已在 main；剩余未合并的是 `origin/work/native-bubble-html-v18-20261013` 的 5 个历史浮球外观提交。本次没有把它们并入 OCR 修复。main 推送可能自动发布，未执行相关操作。
