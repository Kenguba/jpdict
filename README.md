# jpdict

原生 Android Java 应用，提供中文、英文、日文、韩文离线 OCR、MDX/MDD 词典、悬浮查词和 Groq 多语种查询。

## 文档与规则入口

| 需要了解 | 按需读取 |
|---|---|
| Codex 长期开发原则、权限与 Agent 协作 | [AGENTS.md](AGENTS.md) |
| 架构、代码质量、**35 MiB 源码 / 30 MiB APK** 限制 | [工程约束](docs/engineering-guidelines.md) |
| 当前功能、OCR、MDX/MDD、词库导入、离线/缓存及行为兼容 | [当前实现](docs/implementation-reference.md) |
| UI、手势和弹窗规则 | [style.md](style.md) |
| 相关测试及历史已验证/未验证项目 | [测试与验收记录](docs/verification-history.md) |
| 构建、版本、ABI、签名和 GitHub Release | [发布 Skill](.agents/skills/jpdict-release/SKILL.md)（仅发布相关任务） |
| Codex 指令和配置只读审计 | [审计 Skill](.agents/skills/jpdict-instruction-audit/SKILL.md)（仅审计任务） |

## 日常任务边界

- 当前项目是 **原生 Android Java**，不引入 Flutter Runtime；优先复用现有实现，定位根因并最小修改。
- 词典文件、SAF 外部关联、用户数据、导入事务、索引/缓存和离线 OCR 必须维持现有兼容性。具体契约按受影响功能查看当前实现及相关验证资料。
- 工程自有 Git 跟踪文件总量上限 **35 × 1024 × 1024 字节**；正式发布的每个 APK 上限 **30 × 1024 × 1024 字节**。
- 本仓库的 [构建工作流](.github/workflows/build-jpdict.yml) 在推送 `main` 时启动，**签名条件满足后会自动发布 Release**。普通代码或文档提交不可当作无副作用操作；未经授权不得发布。
- 按任务读取必要章节和文件，不要求每次全文阅读所有文档。完成修改后执行针对性验证，不省略与风险直接相关的检查。

当前版本记录保存在 [version.properties](version.properties)；完整规则与操作详见发布 Skill。原有技术细节和验收记录已按主题迁移，未作为日常必读指令。
