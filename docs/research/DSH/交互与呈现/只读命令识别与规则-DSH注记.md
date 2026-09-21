# 只读命令识别与规则（DSH 注记）

> 源锚点：`ddefc45fbc7f8e46dd73185e68295696d1297887`（2026-09-21 本地核验）。M22 探测里程碑工单 08（T-12）产物——本项为 **DSH 侧注记**：DSH 无此实现，深析见 ZCode 侧 [../../../ZCode/交互与呈现/只读命令识别与规则.md](../../ZCode/交互与呈现/只读命令识别与规则.md)。

## 事实

DSH 的 read-only 档下 Bash **不做只读命令识别**：bash 工具声明 `requiresApproval()`，非 danger 档一律经审批 seam ask（ADR-0012 决策 4 与 `FsBashTool` javadoc 明示；docs/research/DSH/沙箱与权限.md 记录了沙箱在场时的免审批联动）。全仓无 shell 命令解析器、只读策略表或 `Bash(prefix:*)` 类前缀规则；`tool-bash/src/index.ts:6` 有一条 `TODO(permissions): deployment policy belongs in tools/pre-execute` 注记，即路径/命令规则类权限是其已知缺口。

## 对 duo 的含义

duo 的 M23「只读 bash 免审批」没有 DSH 侧可抄的实现，**唯一蓝本是 ZCode**（见上链文档）；DSH 的价值仅在证词：连实现最重的参考项目也把这块列为 TODO 而未做，说明它不是 agent harness 的标配，duo 按自身节奏取舍即可。
