# 04: README 重写——门面与三行快速开始

## What to build
README 从 0.15.0 陈账叙事重写为当前版本面的「拿得出手」门面，五段结构：

1. **一句话定位**（插件化 agent harness，内核 + 插件组装）
2. **三行快速开始**：JDK 21 前提 → `java -jar duo-harness-<version>.jar` → `~/.duo/config.yml` 最小配置样例（**占位符形态，API key 永不入库——红线 2**）+ 双呈现位第一眼体验描述
3. **能力概览**：当前版本面（工具/MCP/会话治理/技能/计划模式/HITL/子代理/hooks/web+cli 双呈现位/headless），不按版本史写
4. **模块表微调保留**（11 模块职责 + DuoMain 入口口径对齐现状）
5. **文档站入口**（Pages 地址）

处置口径：0.1.0-0.15.0 演进史压缩成一句（CHANGELOG 已是完整账本）；「一条命令看它做什么」demo 叙事段替换为 `java -jar` 快速开始，Demo 降级到文档站「运行 Demo」页（链接可达性核实）。

验收标准：用户目测通过；合入 main 后 docs.yml 自动同步 Pages（触发路径已含 README.md，零额外配置）；全景截图为验收期动作（随验收裁定补入门面）。

## Blocked by
02（jar 产物名与用法写准才落笔——红线 3 文档与代码同 diff 或紧邻）。

## Status
ready-for-agent

## Checklist
- [ ] 五段结构落地（定位 / 快速开始 / 能力概览 / 模块表 / 文档站入口）
- [ ] config.yml 最小配置样例（占位符形态，无真实 key）
- [ ] 演进史压缩 + demo 叙事段替换 +「运行 Demo」页链接核实
- [ ] 模块表微调（职责与 DuoMain 入口口径对齐现状）
- [ ] 用户目测验收通过
- [ ] （验收期）web 面全景截图一张补入门面
