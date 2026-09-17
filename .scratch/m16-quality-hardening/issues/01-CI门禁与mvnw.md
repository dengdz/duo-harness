# 01: CI 门禁与 mvnw

## What to build

建成远端回归门禁：push 到 main 与 `0.*` 版本分支、以及全部 PR 时，CI 自动执行全量构建与测试（`mvn -B verify`）；同时 Maven wrapper 入库——新环境克隆后 `./mvnw` 直接构建，不再依赖本机 Maven 安装。457+ 用例从此每次 push 都有回归防线，CI 绿自此是每期里程碑的固定验收件。

## Status

ready-for-agent

## Checklist

- [ ] `.github/workflows/ci.yml`：push（main + `0.*`）与 pull_request 触发，单 JDK 21，步骤 `mvn -B verify`，带依赖缓存
- [ ] Maven wrapper（mvnw + 配套 wrapper 目录）入库，`./mvnw verify` 与本机 mvn 行为等效
- [ ] `0.11.0` 分支 push 后工作流跑通全绿
- [ ] 构建要求文档提及 mvnw 用法（如有 Maven 表述则同步）
