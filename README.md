# jpdict

原生 Android Java 应用，提供中文、英文、日文离线 OCR、MDX/MDD 词典、悬浮查词和 Groq 多语种查询。

## 按任务分流（仅按需读取命中项）

| 需要了解 / 正在处理 | 按需入口 | 说明与边界 |
| --- | --- | --- |
| **长期开发原则、500 B / 两轮熔断、权限** | [`AGENTS.md`](AGENTS.md) | 编码执行与 Agent 协作核心规则 |
| **架构设计、35 MiB 源码 / 30 MiB APK 预算** | [`docs/engineering-guidelines.md`](docs/engineering-guidelines.md) | 原生 Java 等价分层与体积硬约束 |
| **当前功能、OCR / MDX / 词库导入 / 缓存** | [`docs/implementation-reference.md`](docs/implementation-reference.md) | 实现细节与兼容行为（按模块查段落，不全文读） |
| **界面视觉、颜色、手势与交互** | [`style.md`](style.md) | 改 UI 时必读；专项功能继续按需进 `docs/style/` |
| **历史测试与已验证记录** | [`docs/verification-history.md`](docs/verification-history.md) | 仅查历史基线或回归口径时按需读取 |
| **对抗式开发审查** | [`DEVELOPMENT_ADVERSARIAL_REVIEW.md`](DEVELOPMENT_ADVERSARIAL_REVIEW.md) | **仅当用户当次明确要求“对抗式审查”等时启用** |
| **构建、版本、ABI、签名与 Release** | [`.agents/skills/jpdict-release/SKILL.md`](.agents/skills/jpdict-release/SKILL.md) | **仅明确构建或发布任务时启用** |
| **Codex 指令与配置审计（只读）** | [`.agents/skills/jpdict-instruction-audit/SKILL.md`](.agents/skills/jpdict-instruction-audit/SKILL.md) | **仅明确要求审计指令或配置时启用** |

## 本仓不可违背的硬红线

1. **原生 Android Java**：不引入 Flutter Runtime 或形式化无收益抽象；具体代码克制与 Bug 审批（先只读定位根因、≤500 B 净增、两轮熔断）见 [`AGENTS.md`](AGENTS.md)。
2. **体积上限**：Git 跟踪自有文件总量 **≤ 35 MiB**（`35 × 1024 × 1024 B`）；每个正式发布 APK **≤ 30 MiB**（`30 × 1024 × 1024 B`）。
3. **自动发布高危警告**：[构建工作流](.github/workflows/build-jpdict.yml)在**每次推送 `main` 时触发**，签名 Secrets 满足后会直接发布正式 Release（阶段默认 `R`）。普通提交或改文档绝不擅自 `git push`；当前版本记录在 [version.properties](version.properties)。
4. **数据与离线兼容优先**：保留词典文件、SAF 授权、原子导入备份、离线 OCR、悬浮状态与版本签名兼容，不得损坏用户数据或泄漏密钥。
