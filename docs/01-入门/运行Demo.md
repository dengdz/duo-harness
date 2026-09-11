# 运行 Demo

> 状态：M1 + M2 全链路可用。一条命令演示插件化全部机制（M1）与 MCP 接入及治理链（M2）。

## 一条命令

```bash
mvn -pl duo-harness-example -am package exec:java
```

输出按时间顺序叙述两段：

**M1 段**：boot 状态迁移（`plugin/status` 事件）→ 服务注入（消费者经视图获得问候）→ 工具三段管线（正常执行 / 准入否决 / 结果治理）→ 运行时拔服务级联停止（消费者 UNLOADING 回 PENDING）→ 整树回滚。

**M2 段**：临时目录写入真实文件 → boot 治理配置（审批 always-deny + 写保护插件）→ 挂载 MCP 连接（迷你 filesystem server，真实 stdio 子进程）→ 远端工具自动同步 → `read_file` 经三段管线读真实文件 → guard 拦截涉密文件（署名 guard）→ `write_file` 被声明需审批遭策略拒绝 → dispose 连接后工具消失。

## Demo 演示什么

| 机制 | 叙述中的体现 |
|---|---|
| 配置驱动 boot | `demo.yml` 一行一插件；`demo-disabled` 行在场而实例未装载 |
| 行序无加载语义 | `greeting-client` 行排在 `greeting` 前，先 PENDING 后被唤醒 |
| 服务注入 | GreetingClientPlugin `inject` 声明 + `ctx.as(视图)` 调用 |
| 工具三段管线 | echo 工具的 pre-execute 否决（敏感词）与 post-execute 结果后缀 |
| 依赖驱动生命周期 | 拔掉临时提供者 → 消费者 UNLOADING → PENDING（等待回归） |
| 整树回滚 | 收尾 root.dispose，每个插件 DISPOSED 逐一叙述 |
| MCP 连接与工具同步 | `mcp__files__*` 工具自动注册，`read_file` 读到真实文件内容（M2） |
| 审批策略（ask 三态） | `write_file` 被治理插件声明 ask → always-deny 拒绝并署名策略（M2） |
| guard 单调否决 | 读 `secret.txt` 被拒并署名 guard，拒绝无法翻回（M2） |
| 连接即生命周期 | dispose MCP 连接 → 远端工具随作用域注销（`未注册` 点名，M2） |

## 想改着玩

- 编辑 `duo-harness-example/src/main/resources/demo.yml`：翻转 `disabled`、改 config 前缀、增删行，重跑命令即见形态变化
- 编辑 `demo-m2.yml`：把审批 policy 换成 `auto-approve` 并白名单 `mcp__files__write_file`，重跑可见写操作放行、临时目录出现新文件
- 示例插件源码在 `duo-harness-example` 的 `dev.duo.harness.example` 包树——`greeting`（服务对）、`tools`（工具与治理）、`approval`（审批策略）、`contract`（输出契约与 guard）、`mcpfs`（迷你 filesystem server 与写保护）五个功能子包

M2 逐工单验收对照表见 `.scratch/m2-tools-mcp/acceptance.md`（含预期日志原文快照）。
