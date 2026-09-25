# 10: duo-skill-evolution 瘦身 + duo-java-review 入口适配

## What to build
duo-skill-evolution 328 → ≤120 行：删第六节使用示例（与三、五节重复的 sediment）；第四节操作步骤并为一段（模板格式指向 experience.md）；输出模板不写死"达，"人格前缀（全局规则由模型响应时套用，模板去除分层耦合）；三条"不要过度 X"合并为正向原则（"进化以『下次少犯一个已记录的错误』为唯一收益标准"）。duo-java-review：description 重写、L0/L1/L2 渐进加载结构保留、与 duo-code-review 的衔接改显式调用。

验收标准（重放 seam）：下一次真实复盘（任一工单收口）按瘦身后的新模板走通；java-review 入口在四轴派发 brief 中被显式调用。

## Blocked by
04

## Status
done（2026-09-25；瘦身 328→57 行远优于 ≤120 目标且语义零丢失（审查轴逐分句对照）；java-review 适配零越界；重放 seam——本单收尾复盘即新版首跑）

## Checklist
- [x] duo-skill-evolution 瘦身至 ≤120 行（实测 **57 行**：删第六节示例、4.1/4.2 并"选择进化方式"节、"达，"5 处清零 + 分层解耦声明、三连否定并正向原则、红线 8 复述改指针；description 92→73 字；复盘六维表语义回补；自身 experience.md 增独立"复盘维度"段）
- [x] duo-java-review 入口适配（description 153→68 字、概述节衔接段=Java 规范轴显式调用 + 三约束同源；L0/L1/L2 与规则文件零触碰——spec Out of Scope 遵守）
- [x] 重放：一次真实复盘按新模板走通——本单收尾的红线 8 复盘即首跑（快速判断→六维提炼→方式 A 落盘→无前缀输出，全程按新版执行；进化点=自身 experience.md 复盘维度规范位置缺失，已修）

## 审查轮（2026-09-25）

**覆盖**：3 个文件 = 已审 3——两技能 SKILL.md、规范 4 处锚点；修复阶段扩展 evolution 自身 experience.md（复盘维度段）。行级/Java 轴空集执行（java-review/SKILL.md 属文档）；第 0 步预检：SHA 01e73d3 + stat 3 文件出示。

**四轴**（Standards / Spec 双子代理）：保命题逐分句对照（328→57 大删除）全过——授权删除四项全落地、未授权义务分句全部有承载；java-review diff 仅 2 hunk（description + 衔接段），L0/L1/L2 与规则文件零触碰、三约束与 code-review:24 同源一致；规范 4 处锚点快照指针核真；末尾换行 od 检查两文件通过。

- **阻断**：无。
- **规范位置（已修 1）**：evolution 自身 experience.md 的"复盘维度"埋在初始条目正文——按新接入约定移至头部独立段（红线 8 首跑触碰点）。
- **语义残损（已修 1）**：复盘六维表瘦心中"错误处理"维丢失——回补为六维清单（维度表→清单合法变形，丢维即残损）。
- **预算（已修 2）**：evolution description 92→82→73 字、java-review 122→90→68 字（两轮压缩终值达标）。
- **建档指导（已修 1）**：方式 A 补建档文件头部格式（标题 + 引言 + ---）。
- **测试覆盖**：两 description wc 终值 + "达，" grep 清零 + 行数实测 + od 换行检查 + docs:build。零 Java 触碰。

**处置汇总**：发现 5 项（合并去重）→ 修复 5。终值：evolution 73 字·57 行·达=0 / java-review 68 字·136 行。
