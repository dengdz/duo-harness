# 03: composer 四态档位选择器——计划/只读/区内写/完全访问

## What to build

composer 左下角四态档位选择器（ADR-0040 决策一/Q2，ZCode 同位同文案）：四态 = **计划模式 / 变更前确认 / 自动编辑 / 完全访问**，映射既有机制——计划模式 → `/plan`（入计划态）；变更前确认 → `/permission read-only`；自动编辑 → `/permission workspace-write`；完全访问 → `/permission danger-full-access`。点选经既有斜杠分发路径（`/api/message` 文本命令，`/permission` 为 ANY+busySafe 双面）触发；**当前态高亮跟随**事件流（`permission/mode` 事件同步选中项；计划态进入/退出的既有事件形态跟随）。纯前端 + 既有后端，零后端改动。

验收标准：点四态任一 → 档位/计划态真实切换（事件与行为可验）→ 当前态高亮正确；busy 中切档即时生效（busySafe）；回退 `git checkout` 无关文件零变化。

## Blocked by

None (can start immediately)

## Status

ready-for-agent

## Checklist

- [ ] composer 左下角四态选择器 UI（ZCode 同款四态文案；M36 主题 token 体系内样式）
- [ ] 点选触发斜杠分发（/plan 入计划态；/permission 档位三态；plan 态与其他三态互斥切换语义在选择器内消化）
- [ ] 当前态高亮跟随（permission/mode 事件 + 计划态事件同步；会话切换/重开后恢复正确态）
- [ ] node --check 绿 + 浏览器手验四态切换（截图留档）+ 回归：既有对话/审批流程零影响
