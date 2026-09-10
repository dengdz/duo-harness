# 02 — 远端工具同步进 tools 服务

## What to build

连接之上长出能力：把 MCP 服务器暴露的远端工具自动注册进工具域。`tools/list` 全量拉取 → 命名清洗与冲突校验 → `mcp__<server>__<tool>` 命名注册进 ToolsService（注册即连接作用域 effect）→ 调用映射（`execute` 转发远端 `tools/call`，isError 与传输失败收敛为 error 结果，text content 透传）。工具同步两阶段原子换新：同步失败保留旧一代继续服务；`tools/list_changed` 通知触发自动重同步。完成的判据：filesystem server 的全部工具出现在工具清单中、能经三段管线执行读到真实文件、杀掉 server 后工具保留（旧代继续）、重连后工具自动恢复（接缝 C）。

## Blocked by

01

## Status

done

## Checklist

- [x] 工具同步两阶段：全量拉取校验（重名/冲突失败）→ 原子换新（旧代注销、新代注册）
      阶段一（拉取 + 转换 + 命名校验）不动注册表，失败旧代原样可用；阶段二才注销旧的、注册新的
- [x] 命名：`mcp__<server>__<tool>` + 非法字符清洗（清洗冲突即失败点名）
- [x] 调用映射：execute → 远端 callTool（raw 名），isError/传输失败 → error 结果，text content 透传
      isError 经抛错交给三段管线收敛（直接 markError 会被管线终端的 setResult 覆盖）
- [x] `tools/list_changed` → 自动重同步（syncLock 串行防交错；断连期间抵达的通知丢弃）
- [x] outputSchema 的契约校验接线**不在本票**（宽松透传，04 接线）
- [x] 测试：工具出现/执行/变更同步/断连保留旧代/恢复、清洗冲突点名、停止注销

## 实施记录

用例落在接缝 A（内核级：真实 Context + tools 服务 + 真实 stdio 协议）而非票据原写的接缝 C——
工具同步的观测量是"工具在册且可经三段管线执行"，必须经真实 ToolsService 才成立，
接缝 C 单独测连接层反而覆盖不到。6 个用例：

| 用例 | 覆盖 |
|---|---|
| remoteToolsAppearAndExecuteThroughPipeline | 出现 + 回声执行 + isError 收敛为错误形态 |
| pluginStopUnregistersRemoteTools | 注册即作用域 effect：插件停止工具随之下线 |
| nameSanitizationCollisionFailsAndRegistersNothing | `a.b` / `a$b` 清洗后重名 → 点名失败且注册表零残留 |
| listChangedNotificationResyncsTools | 远端 addTool → list_changed → 自动重同步 |
| dropKeepsToolsUntilReconnectRefreshes | 断连不撤工具（仅预算耗尽才注销）+ 重连自动恢复 |
| budgetExhaustionUnregistersTools | 重连预算耗尽 → 全部工具注销 |

夹具 `MinimalStdioServer` 追加 `dup`（清洗冲突）、`once`（标记文件：首启常驻、其后启动即退，
用于构造"再也连不上"）、`boom`（返回 isError）三种形态。

## 连接生命周期的配套修正

1. **`firstAttempt.countDown()` 位置**：工具同步挂钩在放行点之前执行，
   放行点（`countDown`）需在同步完成后触发——否则 `runFirstAttempt()` 在
   同步阻塞期间不返回。
2. **`stop()` 后 `awaitForExit` 中断的 catch**：dispose 的 interrupt 让
   `awaitForExit` 抛错，catch 需判 `closed` 标志——非连接故障，
   不覆盖 STOPPED 也不记失败。
3. **`stop()` 的中断/关闭次序**：先中断循环线程（可能正阻塞在
   `awaitForExit`），再关连接（关连接可能等对端，不该挡住退出）。
4. **`McpClientPluginTest` 需挂 tools 服务**：插件 `inject` 声明 tools，
   缺它插件停 PENDING，`awaitStartup()` 无限等待。
