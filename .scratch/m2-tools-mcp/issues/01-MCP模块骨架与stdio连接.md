# 01 — MCP 模块骨架与 stdio 连接

## What to build

新模块 duo-harness-mcp（功能模块，依赖 tools）：引入官方 MCP Java SDK（隔离在 internal），实现与单个 stdio MCP 服务器的完整连接生命周期——配置行装载（serverName/command/args/env/failOnStartupError/reconnect 严格绑定）、stdio 启动外部进程、首连、断连指数退避重连（500ms 起 30s 封顶、稳定窗口 30s 清零计数）、预算耗尽（10 次）停止重连、全程可读日志。完成的判据：对真实 filesystem server 连接成功且有连接日志；杀掉 server 进程后能看到退避重连序列；重启 server 后自动恢复（接缝 C 首批用例）。

## Blocked by

None — can start immediately

## Status

ready-for-agent

## Checklist

- [ ] duo-harness-mcp 模块注册（根 pom + 模块 pom，依赖 tools）+ 包骨架与 package-info
- [ ] 官方 SDK（mcp:0.10.0）接入 internal 层；核实传递依赖树，重依赖评估 exclude 或记录
- [ ] 配置行：serverName 唯一性校验 / command 必填 / failOnStartupError（默认 false）/ reconnect 参数
- [ ] 连接生命周期：首连、指数退避重连、稳定窗口清零、预算耗尽停止
- [ ] failOnStartupError 两态：true 时首连失败 → 插件启动失败（awaitStartup 可见）；false → 容忍降级只记日志
- [ ] 接缝 C 测试：真实 filesystem server 连接 + 假 stdio server 测退避与预算语义
- [ ] 文档同步（模块角色入 docs/04-架构/模块划分.md，同一 diff）
