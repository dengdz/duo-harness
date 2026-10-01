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
done（2026-10-01 用户目测验收通过；全景截图项用户裁定暂不补图，验收期动作保留未勾——后续补图随下一 diff 同步）

## Checklist
- [x] 五段结构落地（定位 / 快速开始 / 能力概览 / 模块表 / 文档站入口）
- [x] config.yml 最小配置样例（占位符形态，无真实 key——红线 2）
- [x] 演进史压缩 + demo 叙事段替换 +「运行 Demo」页链接核实（docs/01-入门/运行Demo.md 存在）
- [x] 模块表微调（13 行与根 pom 一一对账，补 attachment/session-query/stats 三行、llm 行更新四 provider、example 行 DuoMain 入口口径）
- [x] 用户目测验收通过（2026-10-01）
- [ ] （验收期）web 面全景截图一张补入门面（用户裁定暂不补图，留后续）

## Comments

### 实现记录（2026-10-01）

- 整文件重写 60 行：一句话定位（演进史压成一句指向 CHANGELOG，0.15.0 陈账清）→ 三行快速开始（JDK 21 前提 / config.yml `llm` 段样例【字段名 provider/baseUrl/apiKey/model 经 LlmConfig.java:216-218 核实，provider 四值 openai-compat 缺省 + anthropic/deepseek/glm；占位符 key + 红线 2 行内警示】/ `java -jar duo-harness-0.26.0.jar`（GitHub Releases 下载或自建）+ 双面即起（令牌访问形态 `http://127.0.0.1:18080/?token=...` 经 WebPlugin.java:294 核实）+ headless 一句）→ 能力概览八条（不按版本史）→ 模块表 13 行（缺表三模块补齐）→ 文档站入口（Pages 地址 + 运行 Demo 降级 + 构建说明）
- 「一条命令看它做什么」M1/M2 demo 叙事段删除，Demo 降级文档站（工单处置口径）；docs.yml 触发面含 README.md，合入 main 自动同步 Pages

### 审查轮（2026-10-01·四轴）

**覆盖**：README.md 单文件整重写（+41/-11），已审 1 + 跳过 0；行级轴 OCR reviewable 空集（md 不在其覆盖面，零发现）；Java 规范轴核实 Java diff 为空 0 违规

**阻断**：无

**建议（已修复 1 项）**：
- 令牌 URL 示例 localhost → 127.0.0.1（Standards P3：提高逐字可复现性，实打地址即 127.0.0.1）

**记档（不修，附理由）**：
- 版本前瞻：pom 当前 0.25.0，README 写 `duo-harness-0.26.0.jar`——ADR-0032 发版时序的按计划前置（验收后 14 处 pom 对齐 → tag），合入 main 后自动成真；finalName 模板一致
- Releases 链接在 tag v0.26.0 打出前是空页——同属发版时序，非投机承诺
- 能力概览与模块表内容部分重叠（Standards 判断题）——两节均 ADR-0032 五段结构明定，措辞已分化（概览 vs 职责），受制裁的冗余

**事实核查**：Standards 轴抽查 7 组全对上（config 字段/provider 四值/令牌 URL/FTS5/思考等级/--json 契约//model 与 /export/附件寻址）；红线 2/6/9 与术语表 _Avoid_ 全合规

**测试覆盖**：纯文档 diff 不触代码路径；全量 verify 兜跑一次绿（exit 0，BUILD SUCCESS，2026-10-01），CI push 门禁亦有 verify
