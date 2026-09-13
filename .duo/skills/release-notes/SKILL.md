---
name: release-notes
description: 按 duo-harness 的 CHANGELOG 规范生成版本发布说明草稿
---
# 发布说明撰写技能

为 duo-harness 撰写版本发布说明时遵循以下规范：

1. **先读 CHANGELOG.md**：发布说明的唯一事实来源是 CHANGELOG.md 的对应版本段（`## Added/Changed/Removed` 分类）。
2. **输出结构**：
   - 一句话主题（本版本最重要的能力）
   - 按 Added / Changed / Fixed 分组的条目（每条一行，面向使用者措辞，不带内部工单号）
3. **口径**：模块名用反引号；新能力说明"使用者能做什么"而非"我们实现了什么"。
4. **不发明内容**：CHANGELOG 里没有的变更不写入；有疑问先向用户确认。
