# 03: 03-高级·MCP 深入章交付

## What to build
MCP 深入章成章（300-600 行）：把散落 7+ 处的一句话级覆盖聚合升格为专文——连接生命周期（首连同步、两阶段换新、list_changed 重同步）、指数退避重连与预算耗尽放弃、带哈希工具命名规则（含 `McpToolNames.matches` 稳定匹配原语）、config 面全表（yml 行与编程形态对照）、connectorStatus 状态面消费、mcpfs 迷你 server 示例 walkthrough。读者定位：连接问题可自查的部署者与集成维护者。

## Blocked by
01（大纲确认）

## Status
ready-for-agent

## Checklist
- [ ] 成章，篇幅 300-600 行，风格对齐既有文档惯例
- [ ] 素材锚点全覆盖并对账：模块划分 §mcp 包边界与关键语义表、插件配置参考 §编程形态、工具目录 §MCP 远端机制、02-指南 §第四步、limitations M2 节、ADR-0006/0026
- [ ] 聚合升格非重复——与 02-指南操作路径分工（指南讲「怎么接」，本章讲「怎么运转」），互链不照抄
- [ ] 连接生命周期叙述以源码实测为准（探索结论进章前关键行抽查——经验档「探索代理汇报实测抽查」纪律）
- [ ] mcpfs walkthrough 可复跑（demo server 命令自带清场）
- [ ] 内链自查
- [ ] 提交前两 grep 自检
