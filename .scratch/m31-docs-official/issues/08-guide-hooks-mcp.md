# 08: 指南新增——hooks + MCP 接入

## What to build

两篇新指南：①hooks（用户级 hooks.json 配置、PreToolUse/PostToolUse 两事件、载荷字段与退出码语义）；②MCP 接入（config `mcp` 段写法、启动连接与重连行为、状态面确认工具到位——配置与使用层；机制深入链接《MCP 深入》，不重复）。与《MCP 深入》分工：指南讲配置使用，高级讲机制。

## Blocked by

01（大纲确认）。

## Status

ready-for-agent

## Checklist

- [ ] hooks 篇成稿：配置文件位置与格式、两事件触发时机、载荷字段表、常用脚本示例可复跑
- [ ] MCP 接入篇成稿：从零接一个 stdio MCP server 的完整操作路径，状态面排障入口
- [ ] 与《MCP 深入》互链不重复（机制段一句话 + 链接）
- [ ] 内链自查 + vitepress build 通过
- [ ] 用户验收通过；CHANGELOG 0.27.0 段同 diff 记账
