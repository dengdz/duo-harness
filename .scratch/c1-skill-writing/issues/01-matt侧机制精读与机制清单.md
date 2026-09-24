# 01: Matt 侧机制精读与机制清单

## What to build
全量一手重读 mattpocock-skills（37 个 SKILL.md + CONTEXT.md + invocation.md + writing-docs.md + writing-for-agents/SKILL-MECHANICS.md，约 2900 行），产出机制级精读清单，落 `docs/research/mattpocock-skills/` 底稿。每条机制带原文锚点（文件:行号）与一句话机理：调用权分流（user-invoked 硬隔离）、依赖=显式 Skill-tool 调用、完成判据（Done when / 出示证据）、用户检查点（阻塞步骤 + Wait）、领先词、否定句治理、信息层级与剪枝（sediment/no-ops）、词汇治理（CONTEXT.md Avoid 清单）、失败路径 fail-fast。既有通读笔记（2026-09-24 会话）可作起点，但结论须在执行会话中独立推导核实。

验收标准：清单覆盖全部机制主题且每条可回溯到原文；新会话读者只看清单即可理解"Matt 技能为何严格"。

## Blocked by
无（可立即开工）

## Status
done（2026-09-24；验收标准自查通过——15 机制主题覆盖工单九主题并新增 6 个增量主题，每条带行号锚点；产出 [docs/research/mattpocock-skills/机制清单.md](../../../docs/research/mattpocock-skills/机制清单.md)）

## Checklist
- [x] 37 SKILL.md + 4 元文档全量重读（锚点 = 插件版本 1.2.3 核对无变化；实测 37 SKILL.md 共 2465 行 + 元文档 174 行 = 2639 行）
- [x] 机制清单落 docs/research/mattpocock-skills/ 底稿（每条带 文件:行号；15 机制主题 + 覆盖对账表；docs:build 通过）
- [x] 与背景线索（Downloads 两份轻量分析）逐条比对，修正处标注（机制清单 §16：核心结论印证成立 + 8 处事实修正——含 user-invoked 实为 22 个而非 8 个、23 版锚点系 marketplace 旧缓存不采行号）

## 审查轮（2026-09-24）

**覆盖**：4 个文件 = 已审 4 + 跳过 0（覆盖率 100%）——机制清单.md（新增）、index.md（+1 登记）、CHANGELOG.md（+1 记账）、本工单 Status/Checklist。工作树内 C1 立项改动（docs/05-参考/术语表.md +6 词条、spec.md、其余 11 张工单）已随立项提交先行入库，不属本工单 diff。

**四轴**（Standards / Spec 两轴新上下文子代理并行；行级轴按轴定义"测试与文档不入行级名单"——本次 4 文件全为文档，入册名单为空集，轴执行完毕非豁免；Java 规范轴 Java diff = 0 同为空集执行）：

- **阻断**：无。
- **事实错误（已修 3）**：① §17.5 断言 hitl-loop.template.sh"插件内无该文件"不成立（实测存在于 skills/engineering/diagnosing-bugs/scripts/，两轴独立抓到）——该张力点撤销，§17.5 重写为 ask-matt "two on-ramps" 列三项的自相矛盾；② §3 引 loop-me:45-47 为持久化输出行号，实位 :27；③ §9 "七词条每条带 Avoid"实为八词条、仅 Module/Interface/Seam 三条带 _Avoid_。
- **文字缺陷（已修 3）**：韩文字符混入（:123）、"core down"中英粘连（:112）、setup 简称损可搜索性（:41 改全名）。
- **一致性（已修 2）**：§12 on-ramp 数量矛盾标注并挂 §17.5；CHANGELOG 条目"插入计划不发版"短语与 CHANGELOG 定位冲突，删除。
- **核对结论（Spec 轴）**：九主题全覆盖；40+ 锚点抽验除上述 2 处外全中；机械清点（37/2465/174/2639/22/4）全对；冷读自足；spec 决策 3 落实；无范围越界。
- **测试覆盖**：文档工单无自动化测试；最窄验证 = `npm run docs:build`（修复后重跑通过）。零 Java 触碰，不触发全量回归。

**处置汇总**：发现 8 处（合并去重后）→ 修复 8 / 记档 3（模式化问题见 review-log 同日条目）。
