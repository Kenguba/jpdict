# jpdict

原生 Android Java 应用，提供中文、英文、日文离线 OCR、MDX/MDD 词典、悬浮查词和 Groq 多语种查询。

## 文档与规则入口

| 需要了解 | 按需读取 |
|---|---|
| Codex 长期开发原则、权限与 Agent 协作 | [AGENTS.md](AGENTS.md) |
| 架构、代码质量、**35 MiB 源码 / 30 MiB APK** 限制 | [工程约束](docs/engineering-guidelines.md) |
| 当前功能、OCR、MDX/MDD、词库导入、离线/缓存及行为兼容 | [当前实现](docs/implementation-reference.md) |
| UI、手势和弹窗规则 | [style.md](style.md) |
| 相关测试及历史已验证/未验证项目 | [测试与验收记录](docs/verification-history.md) |
| 按需启用的对抗式开发审查 | [对抗式审查规范](DEVELOPMENT_ADVERSARIAL_REVIEW.md) |
| 构建、版本、ABI、签名和 GitHub Release | [发布 Skill](.agents/skills/jpdict-release/SKILL.md)（仅发布相关任务） |
| Codex 指令和配置只读审计 | [审计 Skill](.agents/skills/jpdict-instruction-audit/SKILL.md)（仅审计任务） |

**可选的对抗式审查：** 仅当用户在当前开发任务中明确要求“对抗式审查”“对抗性审查”或同义方式时，读取并执行 [DEVELOPMENT_ADVERSARIAL_REVIEW.md](DEVELOPMENT_ADVERSARIAL_REVIEW.md)。普通开发、修 Bug、常规代码审查不自动启用。启用后按风险分级审查，不替代本 README、AGENTS.md 或受影响功能的技术约束。

## 跨功能工程化约定

- **AI 工程化规范**：统一遵循根目录 [AGENTS.md](AGENTS.md)。Bug 须先只读复现、定位根因、提交最小原位修复方案、预计字节变化和回归计划，经用户明确批准后修改；单 Bug 全部生产代码合计净增不超过 **500 B**（测试单独统计），超额须事先批准。
- **最小范围修改与根因分析**：核对调用方与共享状态的读写，在原位置修复生效条件、函数或配置；禁止补丁堆叠、重复实现、双状态源、无关重构和猜测式修改。
- **兼容和数据优先**：保留词典文件/记录、SAF 授权、原子导入与备份、索引/缓存、离线 OCR、悬浮交互、用户习惯及版本/签名兼容；不得以修复无关功能为由清理用户数据。
- **职责边界清晰**：UI、业务、OCR/平台能力、网络和持久化使用现有清晰接口与单一状态源，不为了套用 Flutter 架构或设计模式增加无收益抽象。
- **本地与离线优先**：已有本地词典、索引和 OCR 能力保持可用；缓存、后台任务和数据更新按现有事务与失败恢复契约处理。
- **性能与依赖克制**：优先复用 Android 原生能力及现有实现，避免在高频交互中重复重计算、创建模型或添加重型依赖；不得牺牲 OCR 准确性与用户数据安全来换取短期指标。
- **配置与秘密保护**：网络地址、授权与服务端配置遵循既有集中管理，不向源码、日志或提交中泄漏密钥。
- **回归验证与失败回退**：检查实际 diff、功能回归、异步与异常分支、体积限制；无法实测必须说明。出现新 Bug 优先撤销错误修改并重新定位根因，不叠加补偿逻辑。
- **按需维护文档**：通用开发边界以 AGENTS.md 为准，Android 专有约束在 [工程约束](docs/engineering-guidelines.md) 和 [当前实现](docs/implementation-reference.md) 中维护；避免跨文件复制整套详细实现。

## 日常任务边界

- 当前项目是 **原生 Android Java**，不引入 Flutter Runtime；按任务读取必要章节和文件，不要求全文阅读所有旧验收材料。
- 工程自有 Git 跟踪文件总量上限 **35 × 1024 × 1024 字节**；正式发布的每个 APK 上限 **30 × 1024 × 1024 字节**。
- 本仓库的 [构建工作流](.github/workflows/build-jpdict.yml) 在推送 `main` 时启动，**签名条件满足后会自动发布 Release**。普通代码或文档提交不可当作无副作用操作；未经明确授权不得触发正式发布。

当前版本记录保存在 [version.properties](version.properties)；完整规则与操作详见发布 Skill。原有技术细节和验收记录已按主题迁移，未作为日常必读指令。
