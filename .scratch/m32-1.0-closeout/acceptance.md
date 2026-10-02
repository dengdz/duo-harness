# M32 acceptance——1.0 判定四项证据核对表

> 形态（[ADR-0033](../../docs/adr/0033-M31文档官方化立项决策.md) 预裁）：四项各一节，三段式（判据 / 证据 / 结论）；盖章时择要并入 CHANGELOG 1.0.0 段（工单 05）。
> 判据原文：[ADR-0016](../../docs/adr/0016-M16至1.0里程碑规划.md)「具名候选全部 done 或明示出栈；CI 绿为常态且无 S 级质量债；文档三层齐备（含 03-高级）；安全基线闭合（Origin 防护 + 审批钉死 + 沙箱出栈决议）」；沙箱出栈决议见 [ADR-0023](../../docs/adr/0023-沙箱出栈与M23+里程碑规划.md)。

## 判定四项核对表（工单 04 填充）

### 一、候选全清

（待工单 01 / 02 终审表验收后出证据）

### 二、CI 绿常态

（待工单 04）

### 三、文档三层齐备

（待工单 04）

### 四、安全基线闭合

（待工单 04）

---

## 证据附件 A：具名候选终审（工单 01，2026-10-02）

**判据**：ADR-0007「具名候选期」清单八项全部 done（对应里程碑交付）或明示出栈（决议锚点）。ADR-0029 立项核查预核「已提前达成」，本表逐项 grep 实证（2026-10-02，CHANGELOG 版本段原文引quot，非转述）。

| # | 候选（ADR-0007 原文） | 归宿 | 凭证锚点（逐项 grep 实证） |
|---|---|---|---|
| 1 | 上下文治理（compaction + token 计量 + 工具结果修剪 + spill，四件一套） | ✅ 已做——M9（0.4.0） | CHANGELOG 0.4.0「上下文治理四件套（M9，agent 域）」（spill→修剪→计量→compaction 管线原文在册）；ADR-0016「上下文治理、subagent 两候选已分别于 M9/M15 完成」；`.scratch/m9-context-governance/` |
| 2 | subagent | ✅ 已做——M15（0.10.0） | CHANGELOG 0.10.0「子代理任务分解（M15，ADR-0015）」+ 控制面五件 + 模板制装配；ADR-0015 + ADR-0017（知识可见性模板 opt-in）；`.scratch/m15-subagent/` |
| 3 | 并发工具调度 | ✅ 已做——M17（0.12.0） | CHANGELOG 0.12.0「并发工具调度（工单 01，ADR-0018）」（并发安全分流 + model 序成对提交 + `maxParallelToolCalls`）；ADR-0018；`.scratch/m17-concurrent-tool-scheduling/` |
| 4 | hooks | ✅ 已做——M18（0.13.0） | CHANGELOG 0.13.0「hooks 生态兼容扩展（工单 03/04，ADR-0019）」（Claude Code/Codex 同形配置、两事件、fail-open）；ADR-0019；`.scratch/m18-extension-mechanism/` |
| 5 | workspace/fs 工具族 | ✅ 已做——M12（0.7.0） | CHANGELOG 0.7.0「本机 fs 工具族六件（M12，ADR-0012）」（read/write/edit/glob/grep/bash）；ADR-0012；`.scratch/m12-workspace-tools/` |
| 6 | 附件 | ✅ 已做——M21（0.16.0） | CHANGELOG 0.16.0「附件与视觉多模态（M21，ADR-0022）」（AttachmentPlugin、内容寻址图片库、read_image、vision 闸门）；ADR-0022；duo-harness-attachment 模块（README 模块表在册） |
| 7 | 会话查询/导出 | ✅ 已做——M26（0.21.0） | CHANGELOG 0.21.0「会话检索引擎迁移 SQLite FTS5（M26 工单 02，ADR-0028 决策一）」「交付声明（M26 工单 04）」「/export 增强（M26 工单 05）」；ADR-0028；`.scratch/m26-session-data-search/` |
| 8 | 权限预设（依赖沙箱概念） | ✅ 已做（依赖前提改道后照常成立）——M12 三档预设（0.7.0）→ M24 规则引擎深化（0.19.0）；依赖前提「沙箱」按 ADR-0023 明示出栈，权限走规则引擎路线 | CHANGELOG 0.7.0「三档权限预设（M12，ADR-0012）」+ 0.19.0「权限规则引擎地基（M24 工单 01，ADR-0026）」；ADR-0023（沙箱出栈决议）；ADR-0024「沙箱按『已出栈（ADR-0023）』闭合」 |

**结论**：八项全部 done，无出栈项；唯一依赖前提「沙箱」经 ADR-0023 明示出栈改道（权限改规则引擎路线），候选实质面照常交付。判定第一项「具名候选全部 done 或明示出栈」的**候选面**成立（全清结论待证据附件 B 移出项/菜单终审合流后由工单 04 落判）。

## Comments

- 2026-10-02 工单 01 产出：本文件建档 + 证据附件 A 八项逐项 grep 实证（CHANGELOG 版本段原文引quot）。
- 2026-10-02 **用户验收通过，证据附件 A 定稿**（工单 01 done）。
