# M10 验收对照表

> duo-acceptance 里程碑级验收件。含**预期日志原文**（实测快照）——跑完命令逐行对照，不用凭描述猜。
> M10「Web 可靠性与体验加固」：8 张功能工单（01-09，10 为本件）全部 done 后闭环。

## 运行命令

测试路径（281 用例）：

```bash
# 先杀 18080 监听进程（demo 装配的 WebPlugin 会占用该端口，测试内的等价装配会失败）
lsof -ti :18080 | xargs kill 2>/dev/null; mvn -o test
```

演示路径（Web + CLI 双入口，端口 18080；需 `~/.duo/config.yml` 的 llm 段）：

```bash
mvn -o -pl duo-harness-example -am package exec:java -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

浏览器打开 `http://127.0.0.1:18080/`。

IDEA 等价路径（输出与上述一致）：

- **测试**：测试类（或包/模块）右键 → Run Tests
- **Demo**：`AgentReplMain` main 方法旁绿色运行箭头 → Run；浏览器开 18080

## 预期日志 A：测试路径（mvn test）

输出中应出现与本里程碑相关的套件叙述（顺序随执行而定，内容与数字逐条核对）：

```
=== 套件：WebFaceTest —— Web 面：静态资源（拆分件/vendor 库/白名单 404）、状态 JSON（含上下文占用）、SSE 游标回放（快照/增量/越界兜底/帧序号）、安全（id 白名单/请求体上限/错误脱敏）、会话锁冲突与幂等切换、fail-closed 宽限（25 用例） ===
=== 套件：WebAnswererTest —— HITL Web answerer：POST 回答解除阻塞（审批/提问）、断连 fail-closed、超时兜底、重复完成幂等（5 用例） ===
=== 套件：WebPluginAssemblyTest —— 纯 Web 装配：HITL 交互工具随装配注册（1 用例） ===
=== 套件：SessionTest —— 事件溯源：append 落盘与回放、投影规则、可选字段往返（usage/reasoning）、独占锁语义（争用拒绝/释放重开/关闭守卫）、latest 选取（20 用例） ===
=== 套件：OpenAiCompatAdapterTest —— OpenAI 兼容适配器：流式聚合与请求形态、usage 统计捕获、错误呈现（14 用例） ===
=== 套件：ToolCallingAgentTest —— 工具循环：Function Calling 闭环、历史投影、usage 落事件、迭代上限（10 用例） ===
=== 套件：ContextGovernanceUsageTest —— 计量双路径：真实值优先、估算兜底（3 用例） ===
```

末尾汇总：合计 **281 用例，0 失败 0 错误**。全部 45 个测试类均有套件叙述行（M10 补齐 6 个）。

测试固有的预期现象（不是缺陷）：mcp 用例的 `TimeoutException` 栈（夹具启动即退，握手必然超时——正是被测行为）；`Process terminated with code 143`（夹具 cleanup kill）；exec:java 结束时的 reactor 线程 linger 警告。

## 预期日志 B：演示路径（实测快照）

### 验收点 1：会话独占（M10-03）

启动即见——WebPlugin 先持锁，CLI 续接同一会话被拒并改开新会话（进程内争用是**预期**形态）：

```
[提示] 会话已被占用：20260914-174800-f82e（/Users/zhangyl/.duo/agent-sessions/20260914-174800-f82e.jsonl）——同一会话同一时刻只允许一个进程使用，请先关闭占用它的程序
[提示] 改为新建会话继续；被占会话仍由占用方使用。
会话 20260914-194149-5dbf（工具循环上下文）。/exit 退出，/new 开新话题。
```

跨进程拒绝：新开终端跑同一命令，应看到（进程退出）：

```
[web] 启动失败（state=FAILED）: ...Web 面无法启动：会话已被占用：<id>（<路径>）——同一会话同一时刻只允许一个进程使用，请先关闭占用它的程序
```

### 验收点 2：Web 面启动与 SSE 连接（M10-01/05）

```
Web 面已启动: http://127.0.0.1:18080
[web] SSE 连接：模式=snapshot，游标=null，事件数=0
```

浏览器刷新页面后应再出现一条 `模式=snapshot，游标=null`（F5 为新实例，走快照）；真实断线重连（Chrome 非 IAB）带游标时显示 `模式=incremental，游标=N`。

### 验收点 3：连线对话（M10-06/07/08/09 的端到端形态）

浏览器操作与预期：

| 操作 | 预期 |
|---|---|
| 打开页面 | 三区布局；状态面顶部「上下文」行初始 `0 / 128,000 tokens（0.0%… · 估算）` |
| 发送消息 | 按钮转「思考中…」禁用 → 回复流式输出，完成后 Markdown 整段渲染（代码块/列表/标题）→ 按钮恢复「发送」 |
| 发送后看状态面 | 「上下文」行约 5 秒内刷新为 `N / 128,000 tokens（…% · 实测）`——口径从估算切到实测 |
| 刷新页面（F5） | 历史消息/工具卡/审批卡完整重建，无缺失无重复 |
| 触发写操作后刷新 | 审批卡仍在且可点「批准本次执行」（M10-04：不误杀） |
| 模型调 ask_user | 提问卡弹出（选项 + 自由输入）→ 点选后模型继续（M10-02：纯 Web 装配补全） |
| Ctrl-C 停服务后发消息 | 页面顶部弹出红色「消息发送失败：Failed to fetch」，5 秒后消失，按钮恢复可点 |

实测快照（发一轮 Markdown 请求后的状态面探针）：

```
上下文行: 3,321 / 128,000 tokens（2.6%，压缩阈值 102,400 · 实测）
渲染结构: h2 ✓ / 列表 2 项 ✓ / 代码块 1 个 ✓ / 行内码 ✓
按钮状态: 处理中「思考中…」禁用 → 完成后「发送」恢复
```

视觉留档：[screenshots/10-acceptance-final.png](screenshots/10-acceptance-final.png)（MD 渲染 + 占用实测口径 + 状态面三区）。