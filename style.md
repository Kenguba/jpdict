# JPDict 界面样式

本文件仅保留跨页面通用视觉与交互约定。长期开发规则见 [AGENTS.md](AGENTS.md)，工程与体积约束见 [工程约束](docs/engineering-guidelines.md)，发布规则由 [发布 Skill](.agents/skills/jpdict-release/SKILL.md) 管理。

## 全局外观与通用规则

- **等宽数字（原生 Android）**：界面中展示数值的数字统一使用等宽数字（OpenType `tnum`），覆盖金额、数量、计数、时间、日期、百分比及动态变化值；原生 `TextView` 通过 `setFontFeatureSettings("tnum")` 或 XML `android:fontFeatureSettings="tnum"` 启用，已有绘制文本在对应 `Paint` 上配置 `tnum`。优先复用原有样式和 `AppTheme`，不把整个字体家族改为等宽字体；确认所用字体支持该特性，并检查数字跳宽与对齐效果。
- **共享主题语义色**：原生界面共用 `AppTheme` 的语义颜色与同一主题状态，不在每个页面复制浅色/深色状态。浅色保留蓝白灰（各页面保持原有色值）；深色页面为 `#171E2B`、卡片 `#252E3D`、次级表面 `#303C4E`、正文 `#EDF2FA`、辅助文字 `#ACB9CD`，操作强调蓝色；原生按钮保留浅色系统外观。
- **外观设置入口**：设置分组名称为“外观”。顶部直接显示太阳/月牙/时钟线条图标及“浅色 / 深色 / 自动”，不再显示“深色模式”标题；仅自动时显示开启/关闭时间。图标复用原生描边体系，侧边栏设置入口使用圆角窗口与左侧分隔线。词库管理和悬浮按钮设置仅在深色隐藏标签栏下的贯穿细分隔线，保留选中标签短下划线与布局高度。
- **主题隔离**：主题只改变界面颜色；悬浮按钮的用户纯色、渐变、图片、SVG、预设和色值编辑不做主题转换。词典 HTML 固定使用原始浅色环境和作者配色，不注入颜色、不重载正文。
- **点击热区与安全三角**：
  - 点击热区：能点的范围须比看到的大一圈；可点元素热区大于可见区域，点在整块热区内即执行，不只点中图标或文字才算。
  - 相邻无缝：相邻热区紧挨着、不留缝隙；列表行热区向外扩并填满行间空隙，点在两行中间不落空。
  - 安全三角：有子菜单的，从父项到子菜单连成的三角形区域内，指针斜向移向子菜单时同级项暂不响应、子菜单保持打开，移出三角形才切换。

## 按需细则（处理对应模块/功能时才读）

- 抽屉设置页与缩略图：[docs/style/drawer-preview.md](docs/style/drawer-preview.md)
- 词库操作弹窗：[docs/style/library-dialog.md](docs/style/library-dialog.md)
- 加载悬浮球：[docs/style/loading-bubble.md](docs/style/loading-bubble.md)
- 文字大爆炸与草稿：[docs/style/text-explosion-draft.md](docs/style/text-explosion-draft.md)
- OCR 手势：[docs/style/ocr-gestures.md](docs/style/ocr-gestures.md)
- 动效：[docs/style/motion.md](docs/style/motion.md)
