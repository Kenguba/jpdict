# 2026-10-14 输入、词库与 OCR 验证

环境：JDK 17、Gradle 8.11.1、compile/target SDK 35、min SDK 23。设备验证使用 API 37.2 ARM64 模拟器上的独立 `com.yuen.jpdict.featureqa`，未使用原应用的词库或真实 Token。

- 自研解析器 2,646 条断言、调度和缓存验证通过。100 MiB 单压缩块在 128 MiB Java 堆中完整流式写出，覆盖 Unicode、raw/zlib/LZO、大块、跨块、取消、坏校验、截断、高压缩小块、重复 MDD 资源。实际 100 MiB WebView 渲染未实测。
- 搜索状态、草稿与分词纯 Java 验证通过；OCR 300ms 停留、死区、四方向与 Latin 清洗 22 条断言通过。
- 原生输入设备验证 23 条断言通过：剪贴板完整替换、紫色草稿、真实触摸长按拖拽、准确中间光标插入、垃圾桶删除、完整词组点击、短词组可见性、焦点恢复去重。
- 词库操作设备验证通过：后台任务不因 Activity 销毁取消、全局球拖动与恢复、六轮反复漏斗动画、后台完成、窗口清理、扫描结果转交新宿主继续选择。
- OCR 设备逻辑 50 条断言、语言与输入回归 169 条断言通过。覆盖失效捕获资源重新授权与旧结果隔离；不等同于真实模型精度验收。
- 词库设备集成 61 条断言通过，包含原有本地资源与网络隔离、浏览、展开、缓存及导入行为。
- HTML 输入、Token 脚本存储与重新加载 20 条设备断言通过，使用原创 Token 字符串；第三方词库服务器授权未验证。
- 三 ABI Debug 构建与 Lint 通过，每项 0 errors / 113 warnings。浮球配置 126 条检查通过。Debug APK 各约 4.30 MiB；Git 项目含新增源码约 1.38 MiB。签名 Release 的大小、版本与证书由 GitHub Actions 再次验证。

发现并修复：旧 512 KB/2 MiB 门槛；旧小块压缩比门槛与新流式验证不一致；MDD 重复资源累计分配；Bitmap 提前回收导致原生崩溃；目录扫描绑定旧 Activity 丢失后续步骤；API 23 不支持的集合 API；短词组合并后误隐藏。测试坐标同步错误使用屏幕坐标修正，保持真实触摸注入。

界面已用原生截图核对文字大爆炸、输入卡片、语言/麦克风/暂存区域的排布。OriginOS 实机、旧 Android 实机、物理触觉反馈、真实模型小字精度、无悬浮权限通知备用路径与系统备份恢复未实测。系统终止进程后在途导入不自动续传；旧安装记录继续有效，未提交代次在启动时清理。

复现命令：`sh scripts/test-mdict.sh`、`sh scripts/test-floating-search-state.sh`、`sh scripts/test-ocr-gesture-rules.sh`、`sh scripts/test-floating-bubble-config.sh arm64Debug`。独立 QA Instrumentation 使用 `-e composer true`、`-e dictionary_operation true`、`-e ocr logic`、`-e html_editing true`；默认模式为词库集成。
