# 02: 《快速开始》改写（01-入门）

## What to build

新用户五分钟跑起来：01-入门《运行Demo》改写为《快速开始》——`java -jar` 一条命令前置（JDK 21 + `~/.duo/config.yml` `llm` 段样例 + 双呈现位即起），mvn 源码形态与 headless 降为次要小节（现状相反）；M1/M2 机制演示叙事剥除，演示入口（DemoMain 等）仅留一句话指针。产品文档口吻（规范见工单 01 产出）。

## Blocked by

01（大纲确认）。

## Status

done（2026-10-01 用户验收通过）

## Checklist

- [x] 成稿：jar 路径为主干，mvn 与 headless 为次要小节，全文无里程碑叙事（口吻自查零命中：无 M 期号/状态注记）
- [x] 命令块从既有文档整块复制并逐条实测可复跑（活体引用；`java -jar` 冒烟 = Main-Class DuoMain + `--json` 缺任务文本 usage 退出码 2，零 LLM 调用）
- [x] config.yml 样例含 provider/baseUrl/apiKey/model（key 占位符），与 README 快速开始同源
- [x] 内链自查（`ignoreDeadLinks: true` 无构建期死链闸门）——旧篇《运行Demo》移除，站内 11 处引用同步改链（config.mts nav+sidebar、index、设计主线×2、模块划分、组装指南、插件配置参考、MCP深入、技能编写×2、README×2）；ADR 内引用按落卷不改原则保留
- [x] vitepress build 通过（2026-10-01，1.26s）
- [x] 用户验收通过（2026-10-01）；CHANGELOG 0.27.0 段同 diff 记账

## Comments

- 2026-10-01 产出：`docs/01-入门/快速开始.md`（六节：前提/配置 LLM/拿到 jar/跑起来/试试第一句/headless/从源码跑，约 90 行产品口吻）替代 `运行Demo.md`（151 行开发叙事）；原工具循环/Web 双面/输入面的详细场景表不搬迁——由指南批（05-08）与《Web 界面使用说明》（11）按任务视角重新组织。**前向链接四枚**（config全量字段参考/权限与审批指南/会话管理与恢复指南/CLI参考）指向工单 09/05/06/10 产物，工单 12 收口核对兑现。
