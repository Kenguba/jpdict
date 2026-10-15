---
name: jpdict-release
description: JPDict Android APK 的版本分配、构建、打包、签名、Release 发布与验证；仅在用户明确要求构建 APK、准备发布或发布 Release 时调用，普通开发不触发。
---

# JPDict Release

## 路由与边界

- 涉及版本或 APK 命名时读 [references/versioning.md](references/versioning.md)。
- 涉及 Android ABI、签名、构建或 GitHub Release 时读 [references/android.md](references/android.md)。
- 纯功能开发和常规代码审查不加载此 Skill。
- 只执行用户授权的动作：构建不等于提交，提交不等于授权发布。未经明确授权不推送、部署或创建 Release。

## 执行

1. 确认目标分支、是否仅构建、发布阶段和成功标准；查看最新源码、工作流、版本记录与现有 Release，避免重用版本。
2. **警惕 `main` 的 push 会自动触发签名构建并可能发布正式 Release**，`workflow_dispatch` 亦然。仅构建时使用不会发布的已有本地命令或安全路径，不能靠猜测触发 CI。
3. 复用 `.github/workflows/build-jpdict.yml` 和 `scripts/allocate-build-version.py`；不新增第二套构建/版本逻辑。按真实 CI 记录校验源码和 APK 体积、离线 OCR 模型、ABI、固定签名、包名/版本、SHA-256。
4. 只有明确授权正式发布，才执行可能创建 Release 的提交/推送和工作流操作；发生权限、版本、签名或产物校验异常，不绕过检查、不尝试危险强推。
5. 完成时报告源提交、版本、CI 结果、产物/Release 链接、签名及哈希核验，未运行的验证明确说明。不能把 Workflow 开始运行等同于发布成功。

## Agent

简单核对和日志整理可以委托可用低成本子 Agent；实际发布由明确授权下的 Agent 顺序执行，复杂故障升级给主 Agent，不硬编码模型或多级 Agent。
