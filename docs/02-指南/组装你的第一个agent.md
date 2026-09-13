# 组装你的第一个 agent

> 状态：对齐 0.3.0（教程体裁，可照抄运行；步骤 3 的装配变更已实测）。前置：JDK 21、Maven、`~/.duo/config.yml` 配置 llm 段（baseUrl / apiKey / model）。跑现成 demo 见[运行Demo](../01-入门/运行Demo.md)；本文教你**自己组装**——每一步都动真格的装配文件，看得见效果。逐行细节随时查[插件配置参考](../05-参考/插件配置参考.md)。

## 第一步：跑起来

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

启动后控制台会打印 `Web 面已启动: http://127.0.0.1:18080` 与会话提示行。浏览器打开这个地址：右侧状态面的插件表就是逐行装配的实时核对（每个装配行 → ACTIVE），三区界面即你的 agent 门户。终端输入 `/exit` 退出。

**你刚刚组装了什么**：这一条命令背后是 `duo-harness-example/src/main/resources/agent-demo.yml`——里面的每一行 `plugins:` 条目就是一个被挂载的能力。下一步打开它。

## 第二步：读懂装配清单

`agent-demo.yml` 的 `plugins:` 列表就是 agent 的全部能力来源：

| 装配行 | 给了 agent 什么 |
|---|---|
| `tools` | 工具域（三段执行管线 + 审批/guard 挂点）——MCP 与 agent 循环的前置 |
| `prompts` | system 提示（最前用户指令片段） |
| `agents-md` | 自动注入 AGENTS.md 项目约定 |
| `skills` | 技能清单 + `skill` 工具（模型自主加载技能） |
| `answers` | 交互服务（审批/提问的回答者注册表） |
| `approval` | 写操作交互审批（y/n 卡片） |
| `write-protector` | 示例治理：write_file 需审批、涉密文件拒读 |
| `repeat-reminder` | 重复调用逐级提醒 |
| `web` | 浏览器界面（端口 18080） |

除了这些行，还有三件挂载发生在代码里（`AgentReplMain`）：`ask_user` 工具、MCP files 连接（`MiniFileSystemServer` 指向临时沙箱）、console/web 回答者。逐行语义与 config 字段见[插件配置参考](../05-参考/插件配置参考.md)。

## 第三步：动手改装配（可观察）

**实验**：编辑 `agent-demo.yml`，把 `approval` 一行整体注释掉，重启：

```yaml
  # 交互审批（注释掉试试：写操作不再询问，直接执行）
  # - id: approval
  #   name: dev.duo.harness.tools.InteractiveApprovalPlugin
```

重启后让它写文件（比如"在 notes.txt 里追加一行"）——**写操作直接执行，不再弹出审批**。这就是"能力以插件组装"：拆掉审批插件，治理链少一环，其余机制原样工作。

> 改完把注释恢复。写操作不加审批属于裸奔——示例里 `write-protector` 的 ask 声明还要靠它兑现。

**同场加映**：把 `skills` 行注释掉，重启后问它"你能看到哪些工具"——技能工具消失；`web` 行注释掉则纯终端运行。每个装配行都是独立开关。

## 第四步：加一个 MCP 服务器

demo 的 MCP files 连接是编程挂载的（`AgentReplMain` 内），等价的 yml 形态长这样——想挂自己的 MCP 服务器，照此结构写：

```yaml
  - id: files
    name: dev.duo.harness.mcp.McpClientPlugin
    config:
      serverName: files
      command: /path/to/your/mcp-server   # 任意 stdio MCP 服务器可执行文件
      requestTimeoutMs: 5000
      reconnect:
        initialDelayMs: 100
        maxDelayMs: 500
        maxAttempts: 3
```

重启后它的工具自动出现在工具清单（`mcp__<serverName>__<工具名>`），模型直接可用——断连自动重连，`list_changed` 自动重同步。

## 第五步：会话与多入口

- 会话自动续接：重启后接着上次聊（JSONL 落 `~/.duo/agent-sessions`，文件名即会话 id）
- `/new` 开新话题；Web 侧栏可列出、切换、新建会话
- 终端 CLI 与浏览器操作**同一份会话**——约定单入口使用（同一时刻只用一个，见[已知限制](../limitations.md)）

## 下一步

- 逐行理解装配：[插件配置参考](../05-参考/插件配置参考.md)
- agent 现在能干什么：[工具目录](../05-参考/工具目录.md)
- 为什么这样设计：[设计主线](../04-架构/设计主线.md)
