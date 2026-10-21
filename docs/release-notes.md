## 本次更新

- 修复通过欧路默认 Intent 打开查词小窗时，JPDict 后台主页变白的问题；搜索主页、草稿和窗口重建状态会保留。
- 修正悬浮取词指针位置：横向固定对齐球心，上侧距球心 48dp、下侧距球心 48dp，并保留屏幕边界约束。

## 兼容与验证范围

Release 工作流负责运行项目测试、三种 ARM ABI 的 Lint 与 Release 构建，并校验 APK 体积、离线 OCR 模型、固定签名、包名/版本及 SHA-256。此次修复另在 Android API 37.2 arm64 模拟器验证窗口重建、欧路 Intent 协议、键盘返回及 OCR 指针回归；第三方窗口使用透明 Activity 替身，未在欧路或 OriginOS 真机上验证。

## 下载

- `arm64-v8a`：推荐现代 ARM 手机。
- `armeabi-v7a`：32 位 ARM 手机。
- `arm`：同时包含以上两种 ARM 架构。

三个 APK 均须低于 30 MiB，使用固定 Release 签名。附件 `SHA256SUMS.txt` 用于完整性校验。
