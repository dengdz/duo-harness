# 10: 文档与收口

## What to build

M21 用户可见面收口：参考文档、运行 Demo、CHANGELOG/limitations 记账全部就位，对账测试绿，里程碑验收件备好。全部行为工单（01-09）完成后执行。

## Blocked by

02, 03, 04, 05, 06, 07, 08, 09

## Status

done

- 2026-09-20（收口轮）：文档五件全部落位，工具目录对账 13 工具绿（attachment 行入 demo yml 后 read_image 进对账）。**收口核实发现**：工单 06 的 `imageDelivery` 配置与投递集成未实现（仅客户端类落地），已更正工单 06 状态为"部分完成"、文档按实态记账（只写 inline 投递、limitations M21 #3 记未接线）、验收件注明边界。

## Checklist

- [x] `工具目录.md` 增 read_image / session_search 条目（工具目录对账测试绿——13 个注册工具）
- [x] `插件配置参考.md` 增附件段 / 会话检索段 / `llm.vision` 字段（`imageDelivery` 未接线，按实态不写、指向 limitations）
- [x] `运行Demo.md` 增输入面与会话工具演示段 + demo yml 示例行（web-tools 外新增 attachment / session-query 行——attachment 行随本工单补入）
- [x] CHANGELOG 0.16.0 段 Added 记账（web 工具族之外的本四件用户可见变更——按实态只写 inline 投递）
- [x] limitations 新增三条：内存倒排索引量级边界（后期转 SQLite，接口已后端无关）/ 治理 token 估算不含图片（真实 usage 侧覆盖）/ Files API 未接线（仅 DeepSeek 形态端点客户端就位，inline 通用兜底）
- [x] 验收件准备（duo-acceptance）：`.scratch/m21-input-sessions/acceptance.md`——14 行汇总 Demo 表 + 自动化佐证 + 已知边界
