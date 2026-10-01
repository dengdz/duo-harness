# MCP 接入：从零接一个服务器

这篇讲把一个 MCP（Model Context Protocol）服务器接进 duo-harness 的完整操作路径——配一行、起一次、确认工具到位。机制深挖（隔离边界、连接生命周期、命名规则）见[MCP 深入](../03-高级/MCP深入.md)。

## 第一步：写装配行

在装配 yml（如 `agent-demo.yml`）加一行，一个连接一行：

```yaml
  - id: files
    name: dev.duo.harness.mcp.McpClientPlugin
    config:
      serverName: files                  # 必填：字母/数字/下划线/连字符（1~32 位）
      command: npx                       # 必填：服务器启动命令
      args: ["-y", "@modelcontextprotocol/server-filesystem", /path/to/dir]
      env:                               # 可选：传给子进程的环境变量
        SOME_FLAG: "1"
      requestTimeoutMs: 20000            # 可选：请求超时（缺省 20 秒）
      failOnStartupError: false          # 可选：首连失败是否拖垮整个 boot（缺省 false——连接失败该行 FAILED，其余照常）
      reconnect:                         # 可选：断连重连（缺省开启）
        initialDelayMs: 500              #   退避起始（缺省 500ms）
        maxDelayMs: 30000                #   退避上限（缺省 30 秒）
        maxAttempts: 10                  #   重连预算（缺省 10 次）
```

`serverName` 与 `command` 必填，其余字段可省——缺省值已标注在注释里。

## 第二步：启动并确认工具到位

启动后（配置错字段会**启动即 FAILED 并点名插件与字段**，不会静默跳过），三个地方确认：

1. **控制台**：装配审计里 `files` 行 ACTIVE，远端工具同步日志可见；
2. **Web 状态面**：连接器区出现 `files: CONNECTED`；多连接聚合同一块板，断连显示 `BACKOFF`（退避重试中）、预算耗尽显示 `GAVE_UP`（标红，工具已下线）；
3. **问模型**：「你现在有哪些 MCP 工具」——远端工具以 `mcp__files__<工具名>__<8位哈希>` 命名出现在清单里，模型直接可调。

## 第三步：日常排障五分钟

| 症状 | 先看什么 | 通常是什么 |
|---|---|---|
| 启动即 FAILED 点名 config | 报错信息里的字段路径 | 字段拼写/类型错（严格绑定不静默） |
| 连接器区 `BACKOFF` 反复 | command 是否可执行、env 是否齐 | 服务器起不来——修启动命令或环境 |
| 状态面 `GAVE_UP` | 重连预算耗尽（缺省 10 次） | 服务器持续不可达；修复后重开连接（重启或重挂行） |
| 工具在册但调用报错 | 连接状态 | BACKOFF 期工具仍在册但不可用——等自动重连 |
| 工具名对不上 | 尾部 `__<8位哈希>` | 命名规范化 + 防坍缩哈希——安全策略按名匹配用「去哈希精确匹配」，不要拿完整名做前缀匹配 |
| 升级 SDK 后子进程抛 `NoClassDefFoundError` | — | SDK 与 json-schema 校验器版本须成对升级（见[已知限制](../limitations.md)） |

## 深读

- 连接生命周期、命名规范与状态板语义：[MCP 深入](../03-高级/MCP深入.md)
- 自己写一个 MCP 服务器给 duo 挂（迷你 filesystem 治理范例）：[MCP 深入](../03-高级/MCP深入.md)
- 官方 SDK 选型决策：[ADR-0006](../adr/0006-MCP接入采用官方JavaSDK.md)
