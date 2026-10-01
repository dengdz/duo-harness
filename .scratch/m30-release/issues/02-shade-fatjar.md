# 02: shade fat-jar 打包——`java -jar` 即跑成立

## What to build
`mvn package` 产出对外命名 `duo-harness-<version>.jar`（去 example 后缀），`Main-Class` = DuoMain，`java -jar` 一条命令跑起完整双呈现位（终端 REPL + 浏览器）——ADR-0029「拿得出手」的核心承诺在真实 jar 形态下成立：

1. **shade 配置**（挂 duo-harness-example 模块）：`Main-Class` 指定、`finalName` 定制、签名文件 exclude、`META-INF/services` merge（MCP SDK 依赖声明）。
2. **jar 形态真跑验收**（非 mock classloader，01 的资源装载通道在真实 jar 下的端到端检验）：
   - `java -jar duo-harness-0.26.0.jar` 启动：终端 REPL 可交互、浏览器面可用（对话/工具卡）
   - Ctrl-C 级联停止、会话锁释放
   - `--json` headless 冒烟（NDJSON 事件流 + 退出码契约）

## Blocked by
01（Boot 资源装载通道——缺省 yml 不能从 jar 装载，打出来的 jar 缺省启动即炸）。

## Status
ready-for-agent

## Checklist
- [ ] example 模块 shade 配置（Main-Class / finalName / 签名 exclude / services merge）
- [ ] 产物名核实 `duo-harness-<version>.jar`
- [ ] `java -jar` 真跑：终端 REPL + 浏览器双面
- [ ] `java -jar` 真跑：Ctrl-C 级联停止与会话锁释放
- [ ] `java -jar --json` headless 冒烟
- [ ] 全量 ./mvnw verify 绿
