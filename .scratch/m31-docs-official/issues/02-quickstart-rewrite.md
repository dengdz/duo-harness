# 02: 《快速开始》改写（01-入门）

## What to build

新用户五分钟跑起来：01-入门《运行Demo》改写为《快速开始》——`java -jar` 一条命令前置（JDK 21 + `~/.duo/config.yml` `llm` 段样例 + 双呈现位即起），mvn 源码形态与 headless 降为次要小节（现状相反）；M1/M2 机制演示叙事剥除，演示入口（DemoMain 等）仅留一句话指针。产品文档口吻（规范见工单 01 产出）。

## Blocked by

01（大纲确认）。

## Status

ready-for-agent

## Checklist

- [ ] 成稿：jar 路径为主干，mvn 与 headless 为次要小节，全文无里程碑叙事
- [ ] 命令块从既有文档整块复制并逐条实测可复跑（活体引用；多行折行命令逐行核对）
- [ ] config.yml 样例含 provider/baseUrl/apiKey/model（key 占位符），与 README 快速开始同源
- [ ] 内链自查（`ignoreDeadLinks: true` 无构建期死链闸门）
- [ ] vitepress build 通过
- [ ] 用户验收通过；CHANGELOG 0.27.0 段同 diff 记账
