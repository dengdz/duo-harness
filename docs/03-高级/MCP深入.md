# MCP 深入

> 本文对齐 0.25.0，机制描述锚定 `duo-harness-mcp` 模块源码。读者定位：接了（或准备接）MCP 服务器、连接问题想自查的部署者与集成维护者。「怎么接」的动手路径见[组装你的第一个 agent](../02-指南/组装你的第一个agent.md)第四步，字段速查见[插件配置参考](../05-参考/插件配置参考.md)；本章讲「怎么运转」——隔离边界、连接生命周期、工具同步与命名、状态面与治理范例。

## 2.1 MCP 在 duo 中的位置与隔离边界

**连接即接入。** yml 里一行 `McpClientPlugin` 配置就是一个 MCP 服务器连接：连接建立后远端工具自动同步进工具域，出现在工具清单里，模型直接调用——挂一个连接不需要写任何注册代码。对模型与工具域而言，MCP 工具与本地工具毫无区别（[设计主线](../04-架构/设计主线.md)）：同一份工具目录、同一条三段管线、同一套审批与 guard 挂点。

MCP 接入是工具管线的**第一个真实客户**（ADR-0006）：工具域的强化件——output 输出契约、审批声明、结果治理——需要一个真实的远端消费方来证明不是为本地工具量身定做的。MCP 先于 agent 循环落地：远端工具三段管线走通之日，就是"本地工具与远端工具同权"验证之时。

**SDK 隔离在实现层。** duo-harness 没有自写 MCP 协议栈。JSON-RPC 编解码、stdio 帧协议、初始化握手、能力协商、`tools/list_changed` 通知，全部交给官方 Java SDK（ADR-0006：协议胶水不是本项目的核心价值，核心是"远端工具 ↔ 本地工具域"的适配与生命周期管理）。SDK 被严格关在模块内部，体现在两包结构上（[模块划分 §mcp 的包边界](../04-架构/模块划分.md)）：

| 包 | 内容 | 对外承诺 |
|---|---|---|
| `dev.duo.harness.mcp` | `McpClientPlugin`（一行配置一个 stdio 连接）、`McpToolNames`（公开命名与匹配原语） | 契约包，仅此两个类型 |
| `dev.duo.harness.mcp.internal` | 连接生命周期（`ConnectionSupervisor`）、重连策略（`ReconnectPolicy`）、工具同步（`McpToolSync`）、装配支撑与配置归一化 | SDK 类型止步于本包，不对外暴露 |

也就是说：下游模块与你的插件能碰到的 MCP 面只有两样——**挂连接的插件行**与**工具名的命名/匹配规则**。SDK 升级、传输层改造、协议演进，都收敛在 internal 包内（升级成本 = 适配层改动，非协议重写，ADR-0006）。SDK 现随 `io.modelcontextprotocol.sdk:mcp:0.18.1`（0.18 起传输层要求显式 JSON mapper，已在 internal 内处理）。

模块依赖方向：`mcp → core + tools + MCP SDK`，单向无环——MCP 是工具域的消费者而非特权通道（[模块划分](../04-架构/模块划分.md)）。它给工具域的远端工具与其他插件注册的本地工具走完全相同的入口，也因此自动继承了工具域后来长出的一切治理能力（权限规则、状态板等）。

**远端工具在目录里的样子。** 同步进来的工具除了名字带 `mcp__` 前缀，还有两处可观察的痕迹：描述文本自动追加来源后缀 `（MCP: <serverName>）`——模型与状态面据此能分辨远端；Web 状态面的工具清单标题即"工具（本机 + MCP 远端）"，两源混排一表。除此之外无任何特殊通道：审批、guard、权限规则、输出契约校验对两类工具一视同仁。

一个已知耦合点提前交代：SDK 的 Jackson 绑定件 `mcp-json-jackson2` 硬依赖 networknt json-schema-validator 2.0.0，升级 SDK 时必须同步核对该版本（错配会在子进程抛 `NoClassDefFoundError`，见[已知限制](../limitations.md) M2 节）。

## 2.2 接一个服务器：config 面全表

一行 MCP 插件的最小形态（可在 `agent-demo.yml` 直接追加，见[组装你的第一个 agent](../02-指南/组装你的第一个agent.md)第四步）：

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

config 是**可选密集型**：必填的只有 `serverName` 与 `command`，其余字段省略即取文档化默认值（由本模块自绑定，不走内核的严格绑定——内核严格绑定服务于"必填契约"，MCP 的缺省是刻意行为）。逐字段行为如下（速查表见[插件配置参考](../05-参考/插件配置参考.md)「不在 yml 里的挂载」节，该表列了主干的五个字段；`env` / `failOnStartupError` / `reconnectEnabled` 三个可选项与稳定窗口行为只在本文交代）：

| 字段 | 缺省 | 行为 |
|---|---|---|
| `serverName` | 必填 | 全局唯一；仅允许字母/数字/下划线/连字符（1~32 位）。它是命名清洗与标记服务的来源，配错在装载即点名 |
| `command` | 必填 | 服务器启动命令（stdio 传输，子进程形态） |
| `args` | `[]` | 启动参数数组，逐项传给子进程 |
| `env` | `{}` | 额外环境变量（键值对象），叠加在继承的进程环境上 |
| `requestTimeoutMs` | 20000 | `initialize` 握手与 `callTool` 调用的单请求超时；超时后的走向见下文 |
| `reconnectEnabled` | `true` | 置 `false` 即禁用自动重连：断连直接进 `GAVE_UP` 终态 |
| `reconnect.initialDelayMs` | 500 | 指数退避起始延迟（毫秒） |
| `reconnect.maxDelayMs` | 30000 | 退避上限（毫秒） |
| `reconnect.maxAttempts` | 10 | 连续失败预算：达到即放弃重连 |
| `failOnStartupError` | `false` | 首连失败是否判插件启动失败（语义见 2.3） |
| 稳定窗口 | 30000（固定） | 存活满此时长的连接断开时，失败计数清零——未暴露配置项 |

**stdio 子进程形态与拉起时机。** `command + args` 描述一个子进程：每次连接尝试（装载时的首连、以及之后的每次重连）都会拉起一个全新的服务器进程，并完成 MCP 初始化握手——握手成功且工具同步完成，连接才算就绪。子进程的回收有三层兜底：

- 正常路径：插件停止（行拔除 / 树销毁 / `dispose`）即断连，当前连接关闭并级联销毁子进程；
- 宿主异常退出：连接器注册了 JVM 退出钩子（仅一次，重连不重复挂），进程正常退出但未 dispose 时关 client 级联杀掉 server 进程；
- 宿主被强杀（`kill -9`）：钩子不跑，由 server 侧 stdin EOF 自退兜底——stdio 服务器的惯例是父进程消失即退出（本章 2.5 的 `MiniFileSystemServer` 就是这么实现的）。

**serverName 冲突在装载即拒。** 每个连接以标记服务 `mcp-connection/<serverName>` 占坑，两行配了同一个 `serverName` 时后到者在装载阶段被服务注册表点名拒绝——不会出现两个连接共名互相覆盖。`serverName` 同时是公开工具名的前缀来源（`mcp__<server>__…`），起名时把它当"这台服务器的命名空间"对待：装了什么服务器、名字一眼可辨。

接一个真实的 stdio 服务器（形态示意——以社区常见的 filesystem server 为例）：

```yaml
  - id: workspace-fs
    name: dev.duo.harness.mcp.McpClientPlugin
    config:
      serverName: workspace
      command: npx
      args: [-y, "@modelcontextprotocol/server-filesystem", /srv/workspace]
      env:
        NODE_ENV: production
      requestTimeoutMs: 20000      # 省略即此缺省
      reconnect:
        initialDelayMs: 500        # 以下三行全部省略即 500/30000/10
        maxDelayMs: 30000
        maxAttempts: 10
```

`command + args + env` 原样交给 SDK 的 `ServerParameters` 拉起子进程；`-y` 之后的参数随目标服务器而定。命令本身可以是可执行文件、脚本解释器或包运行器——duo 只要求它是一个 stdio MCP 服务器。

**`requestTimeoutMs` 超时后发生什么。** 它是请求级超时，作用在两处、走向不同：

- **握手期超时**（`initialize`）：本次连接尝试判失败——计数进预算、按退避排下次重连（生命周期裁决见 2.3）；`failOnStartupError: true` 且发生在首连时，插件直接 FAILED 点名。服务器启动慢（如首次拉取依赖包）是最常见的误配场景，把超时调得比冷启动时长更短，得到的不是"等它起来"而是"反复拉起反复掐"；
- **调用期超时**（`callTool`）：该次工具调用收敛为 error 结果（工具错误是业务结果不是系统故障，见 2.4 双轨制），**连接不受影响**——断连只有一个判据：server 进程退出（2.3 的 `awaitForExit`）。所以慢工具的正确对策是调大 `requestTimeoutMs` 或治理侧加 advisory，而不是等重连。

**编程形态对照。** 不走 yml 时以等价 config 编程挂载（仓库的机制演示入口即此形态——它需要注入临时目录与 classpath，且拔除演示要求持有句柄）：

```java
ObjectNode config = JsonNodeFactory.instance.objectNode();
config.put("serverName", "files");
config.put("command", javaCommand());
config.put("requestTimeoutMs", 5_000);
var reconnect = config.putObject("reconnect");
reconnect.put("initialDelayMs", 100);
reconnect.put("maxDelayMs", 500);
reconnect.put("maxAttempts", 3);
var args = config.putArray("args");
args.add("-cp").add(subprocessClasspath())
        .add("dev.duo.harness.example.mcpfs.MiniFileSystemServer")
        .add(rootDir.toString());

PluginHandle files = root.plugin(new McpClientPlugin(), config);
files.awaitStartup();   // 阻塞至首连落定（含工具同步）
```

字段语义与 yml 完全一致——yml 行在 boot 时就是这段代码的声明式形态。

## 2.3 连接生命周期

单个连接的状态机由 `ConnectionSupervisor` 驱动（[模块划分 §关键语义表](../04-架构/模块划分.md)"MCP 连接生命周期"行的权威即它的 JavaDoc）：

| 状态 | 含义 | 如何到达 |
|---|---|---|
| `CONNECTING` | 连接尝试进行中（拉进程 + 握手 + 工具同步） | 装载首连、每次重连尝试开始 |
| `CONNECTED` | 已连接，工具在册 | 握手与工具同步都成功 |
| `BACKOFF` | 断连后退避等待，下次重连排期中 | 连接断开/尝试失败，预算未尽 |
| `GAVE_UP` | 重连预算耗尽（或重连被禁用）——终态，工具已注销 | 连续失败达 `maxAttempts`，或断连时 `reconnectEnabled: false` |
| `STOPPED` | 插件停止（行拔除 / 树销毁）——终态 | `stop()` 被触发，全链幂等 |

**首连是同步语义。** 插件装载线程阻塞至首连落定（成功或失败），这承袭内核"同步加载"的语义（ADR-0002）：`awaitStartup` 返回时，你要么得到一个已连接、工具已同步的连接，要么得到一个明确的首连失败。工具同步算"连接就绪"的一部分——同步抛错即本次连接尝试失败，交给重连预算裁决，不会出现"已连接但工具没影"的中间态。

首连失败的两种收场，由 `failOnStartupError` 裁决：

- `false`（缺省）：插件照常 ACTIVE，失败进退避循环，后台继续重试——服务器暂时不在场（如晚启动的依赖）不阻塞整个 boot；
- `true`：首连失败判插件 FAILED，boot 审计点名原因（配置错误、可执行文件不存在这类"重试也不会好"的问题建议用它）——此时继续重试只会拉起僵尸进程，循环会主动放弃。

**断连与指数退避。** 断连检测依赖 SDK 传输层的 `awaitForExit()`：server 进程退出即返回（stdio 形态下进程死 = 连接死，没有更含糊的中间态）。断连后按 `ReconnectPolicy` 计算退避：

```
退避延迟 = initialDelayMs × 2^(连续失败次数 − 1)，封顶 maxDelayMs
```

以缺省 500 / 30000 为例：500ms → 1s → 2s → 4s → 8s → 16s → 30s → 30s …。重连循环跑在专用虚拟线程上（`mcp-conn-<serverName>`），退避睡眠可被 dispose 中断。

**稳定窗口清零——预算按"连续失败段"计。** 连接存活满稳定窗口（30 秒，固定值）后断开，失败计数**清零后计 1**：一条跑了几小时后偶发抖动的连接，拿到的永远是全新的完整预算。反之，存活不足窗口的连环抖动（起不来、起来即死）逐次累计同一预算。所以 `maxAttempts` 的准确含义是**一段连续失败里的尝试上限**，不是进程生命周期累计——长期运行的服务器不会"攒满"预算。

把 demo 配置（100 / 500 / 3）代入一条崩溃循环，时间线长这样：

| 时刻 | 事件 | 失败计数 | 之后 |
|---|---|---|---|
| t0 | 首连尝试，进程秒退 | 1 | 退避 100ms |
| t0+0.1s | 第 2 次尝试失败 | 2 | 退避 200ms |
| t0+0.3s | 第 3 次尝试失败 | 3 = maxAttempts | **GAVE_UP**，工具注销、状态面标红、通知发出 |

部署里更常见的重档配置（2s / 30s / 5，给慢启动服务器留余地）的完整时刻序列——注意每次重连都是**拉全新进程 + 握手**：

| 第 n 次尝试 | 时刻（累计） | 失败后退避 |
|---|---|---|
| 1（首连） | t0 | 2s |
| 2 | t0 + 2s | 4s |
| 3 | t0 + 6s | 8s |
| 4 | t0 + 14s | 16s |
| 5 | t0 + 30s | —（5 = maxAttempts，**GAVE_UP**） |

即从首连失败到放弃约 30 秒，共 5 次尝试、4 次退避；退避序列 2→4→8→16 被 30s 上限截住前就已用完预算。

若其中某次连接成功并存活超过 30 秒后才断，计数清零重计——预算是给"连续故障段"的，不是给整段运行期的。

**断连期间模型看到什么。** 退避期工具仍在册，模型调用会得到明确的 error 结果（底层连接已死）而非挂死——模型可以改述、等待或换路；重连成功后同批名字原位换新，无增删扰动。这条语义由测试 `dropKeepsToolsUntilReconnectRefreshes` 锁定（详见 2.4 两阶段换新）。

**预算耗尽即放弃（GAVE_UP）。** 连续失败达 `maxAttempts`，循环退出、状态定格 `GAVE_UP`，并触发三件事（ADR-0026 决策四"重连耗尽终态"——消灭静默消失）：

1. 该服务器的全部远端工具注销下线（模型不再看到它们）；
2. 连接器状态板更新为 `GAVE_UP`，Web 状态面标红（见 2.5）；
3. 会话事件通知发出（"重连预算耗尽，相关工具已下线"），CLI 呈现位将其注入收件箱——模型与用户都可见。

`GAVE_UP` 不自动恢复。运行时无行级热重载（[已知限制](../limitations.md)遗留节），恢复手段就是重新挂载该连接——部署上等价于修好服务器后重启进程。

**插件停止即断连。** 行拔除或树销毁触发 `stop()`：置停止标志、中断循环线程（可能正阻塞在退避睡眠或 `awaitForExit`）、关闭当前连接、注销退出钩子、移除状态板条目——全链幂等。停止与重连存在竞态时的裁决是确定的：连接虽成但插件已要求停止，则不置 `CONNECTED`、直接关闭新连接——不会留下脱离管理的连接。

两条与部署直接相关的已知限制（[已知限制](../limitations.md) M2 节）：SDK 与 networknt 校验器版本耦合（升级须成对核对）；demo 的子进程 classpath 依赖运行环境探测（`java.class.path` 或 ClassRealm 枚举），非标准 classloader 下会失败——该探测只在示例模块，核心无此依赖。

## 2.4 工具同步与命名

**公开名格式：`mcp__<server>__<名>__<8hex>`。** 每个远端工具经 `McpToolNames.publicName` 两步变换后注册进工具域（ADR-0026 决策四；术语表词条[「工具名规范化」](../05-参考/术语表.md)）：

1. **有损规范化**：展示段 = `mcp__<serverName>__<原始工具名>`，原始名中字母/数字/下划线/连字符以外的字符全部替换为下划线（`a.b` 与 `a$b` 同为 `a_b`）；
2. **短哈希后缀**：尾缀 `__` + SHA-256 前 4 字节的 8 位 hex，**哈希对 server 名与原始工具名计算**（不是清洗后形态）。

这样裁定的理由：清洗同形的异名工具（`a.b` 与 `a$b`）哈希必不同——防坍缩的承诺由哈希本身兑现；同一输入恒得同一名字——跨重启稳定（工具名会被审批卡、权限规则、会话历史引用，稳定优先于美观）。此裁定同时废弃了旧语义「清洗后重名即抛错」——一台服务器的工具清单换了写法（`write.file` 改名 `write_file`）不再导致连接起不来。32 位截断存在理论碰撞（约 1e-5 量级 @500 工具），转换时以序号兜底（`_2`、`_3`…），正常部署永不触发。

几组变换实例（`serverName: files`，哈希值随输入而定，此处示意）：

| 远端原始名 | 清洗后展示段 | 公开名（带哈希尾） |
|---|---|---|
| `read_file` | `mcp__files__read_file` | `mcp__files__read_file__1a2b3c4d` |
| `write.file` | `mcp__files__write_file` | `mcp__files__write_file__9f8e7d6c`（与上一行展示段同形、哈希不同） |
| `a.b` 与 `a$b` | 同为 `mcp__files__a_b` | 两个不同哈希尾——同形共存不坍缩 |

`stripHash` 只剥"恰为 `__` + 8 位 hex"的尾部：本地工具名（不含该形态尾缀）原样参与 `matches` 比较且恒不误配——同一套匹配原语可安全地混用于混合工具清单。

**配置侧引用公开名的姿势**：在权限规则、审批白名单等配置里引用 MCP 工具时，写**完整公开名**（含哈希段，从 Web 状态面工具清单或工具目录里复制）；在插件代码里做**运行时判定**时，用 `matches`（原始名）。两个层面各有正路，混用（配置里写前缀、代码里写 `startsWith`）都会回到误伤老路。

**安全策略按名匹配的正路是 `McpToolNames.matches`。** 哈希后缀使 `equals` 不再成立——0.24.0 之前治理插件被迫退化成前缀匹配，而前缀匹配已被实证误伤：`write_file` 的规则同样命中 `write_file_x`。`matches` 是"去哈希后的精确匹配"的单一出口（自 0.24.0 起）：给定执行时的公开工具名与「服务器名 + 远端原始工具名」，剥去尾部 `__<8hex>` 段后与展示形态精确比较；非哈希尾形态的名字（本地工具名）原样参与比较、恒不误配。写保护插件是它的首个消费方与用法范例：

```java
// duo-harness-example: dev.duo.harness.example.mcpfs.WriteProtectorPlugin
Disposable ask = ctx.on(ToolsService.PRE_EXECUTE,
        (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
            if (McpToolNames.matches(exec.toolName(), "files", "write_file")) {
                exec.requestApproval();          // 精确命中 write_file，不波及 write_file_x
            }
            return next.invoke(exec);
        });
```

写自己的治理/权限逻辑时，凡要按名匹配 MCP 工具，用 `matches(公开名, serverName, 原始工具名)`，不要手写前缀或 `startsWith`——后者正是被实证误伤过的写法。

**同步的两个触发点，一套两阶段换新。** 远端工具清单进工具域走 `McpToolSync`，两个来源串行过同一把锁：连接建立后的全量同步，与远端 `tools/list_changed` 通知触发的重同步。两者都走**两阶段换新**：

- 阶段一（不动注册表）：全量拉取 → 转换 → 命名校验。此阶段失败，旧一代工具**原样保留**——远端坏了不拖累本地目录；
- 阶段二（切换）：注销旧代 → 注册新代。注册中途失败（如外部工具占了同一名）则丢弃半新代、best-effort 恢复旧代，残留由下次同步覆盖。

**换新期间正在执行的工具调用怎么办。** 换新在同步锁内串行，而工具执行不持这把锁（执行走对当前客户端的 volatile 读）——两相不阻塞：

- **已在执行中的调用**：持有的是旧一代工具定义的执行闭包，换新不触碰它，照常完成；即使换新由断连重连触发、旧客户端已死，该调用也只是收敛为 error 结果，不会被换新"腰斩"成半截状态；
- **换新瞬间新到的调用**：阶段二存在一个"旧代已注销、新代未注册完"的缝隙（纯内存操作，毫秒级），落进缝隙的调用得到"未注册"类错误，新代注册完成即消失。正常运维里这个缝隙不可感知——`list_changed` 重同步频率极低，全量同步只发生在连接建立时。

这与连接层的语义对齐：**断连退避期间工具仍在册**（调用会得到 error 结果——底层连接已死），重连成功后同批名字原位换新，模型视角无增删扰动；只有预算耗尽才真正注销下线。测试 `dropKeepsToolsUntilReconnectRefreshes` 锁定的就是这条语义。

**`list_changed` 的增量重同步细节**（SDK 0.18 的通知处理器已代为完成 `listTools`，回调收到的就是全量清单）：

- 工具名集合未变则跳过——MCP 的 `list_changed` 语义是清单变更（增/删），同名集合意味着无实质变更；
- 断连期间抵达的通知直接忽略（连接已死，重连后的全量同步自然带上最新清单）；
- 插件停止期间抵达的通知按 `ScopeDestroyedException` **类型判定**静默跳过——0.24.0 前靠异常文案 `contains("作用域已销毁")` 匹配，core 文案演进即静默失效，类型化后消费方按类型识别"停止期间的失败可忽略"，不再被文案锁死；
- 重同步中命名冲突等应点名的失败走 warn 日志，不静默。

**远端工具的输出契约双轨制**与本地工具同标准：远端声明了 `outputSchema` 则带入本地契约校验（违约由工具域点名转 error 结果）；未声明则宽松透传。调用映射优先 `structuredContent`，无则回退拼接的 text content；远端返回 `isError` 时抛类型化异常，由三段管线收敛为 error 结果——工具错误是业务结果，不是系统故障。机制总览见[工具目录 §MCP 远端工具](../05-参考/工具目录.md)。

## 2.5 状态面、排障与治理范例

### 连接器状态板（connectorStatus）

多连接的生命周期状态聚合同一块板（ADR-0026 决策四）：`ConnectorStatusBoard` 以服务名 `connectorStatus` 发布（首个 MCP 连接行发布，后续行复用同一 JVM 共享实例），每行连接对应一个条目，快照按 server 名稳定排序。条目状态随连接状态机覆盖式更新——`CONNECTING`（连接中）→ `CONNECTED`（已连接）→ `GAVE_UP`（重连预算耗尽，工具已下线）；连接行停止或拔线时条目同步移除，状态面不留幽灵条目。

消费两端：

- **Web 状态面**：`/status` 响应携带 `connector` 数组，前端逐行渲染 `server：STATE（detail）`，`GAVE_UP` 标红（危险色）——连接器区与插件六态表、工具清单同板呈现；
- **CLI 呈现位**：订阅 `onGaveUp` 把耗尽通知注入收件箱（模型与用户可见）。订阅返回**注销句柄**（自 0.24.0 起）：状态板是 JVM 共享实例，订阅若不摘除，长驻进程多轮装配（Web 换绑、CLI 重启）会累积引用已销毁呈现位的监听——泄漏加重复通知。订阅方随自身装配销毁执行注销即可。

### 排障三分：可选服务缺席/故障/重复的判别

连接器状态板是"可选服务"的典型消费场景，围绕它的失败判别自 0.24.0 收紧为三分法（此前 `catch (RuntimeException)` 吞掉一切，状态板可能实际未发布而无人知晓）：

| 情形 | 表现 | 定性 |
|---|---|---|
| 服务未挂载（装配里没有 mcp 行） | 消费侧静默降级：Web `/status` 不带 `connector` 字段（前端显示"—"）、CLI 跳过订阅零感——mcp 行后置时服务出现还触发 epoch 重载补订阅，功能自愈 | 正常态，不是故障 |
| 服务在场但解析/注册失败 | 点名：装配侧真注册故障上抛（boot FAILED 点名插件）；消费侧解析失败留 warn 日志排障——缺席与故障两态不混同 | 故障，必须可见 |
| 重复发布（多连接行都到） | `hasService` 预探测已发布场景按复用处理；探测与发布窗口内被另一连接行抢先注册（类型化异常）同样按复用/忽略 | 竞态，幂等吸收 |

自查连接问题时先定位属于哪一态：状态面"—"未必是故障（可能根本没挂连接行）；有行但 `GAVE_UP` 才是连接问题；插件表里该行不是 ACTIVE 则回装配与 boot 审计找原因。

### 排障速查

| 症状 | 先看哪里 | 常见根因 |
|---|---|---|
| 工具清单里没有 `mcp__<server>__*` | 插件六态表该行状态；boot 审计输出 | 行缺失 / `serverName` 重复被拒 / `command` 路径不存在（`failOnStartupError: true` 时 FAILED 点名） |
| 状态面该行 `CONNECTING` 久不迁移 | 服务器进程能否手工拉起；`requestTimeoutMs` 是否过短 | 服务器启动慢或握手挂起——首连包含握手与工具同步，超时即本次尝试失败 |
| 状态面 `GAVE_UP` 标红 | CLI 会话应已收到耗尽通知；服务器日志 | 连续失败达 `maxAttempts`：服务器崩溃循环、依赖缺失；修好后重启进程重挂 |
| 工具在册但调用报错 | 连接状态（`BACKOFF` 期工具仍在册） | 断连退避中——等待自动重连；持续报错则走向 `GAVE_UP` |
| 升级 SDK 后子进程抛 `NoClassDefFoundError` | [已知限制](../limitations.md) M2 节 | networknt 校验器版本未随 SDK 成对升级 |

### mcpfs 治理范例：全程可复跑

仓库自带的迷你 filesystem 服务器与两个治理插件构成一条完整可复跑的链路（真实 stdio 协议 + 真实文件，装配与步骤见[组装你的第一个 agent](../02-指南/组装你的第一个agent.md) 的 MCP 步骤）：

```bash
mvn -pl duo-harness-example -am package exec:java
```

三个角色（源码都在 `duo-harness-example` 的 `dev.duo.harness.example.mcpfs` 包）：

| 角色 | 类 | 干什么 |
|---|---|---|
| 远端服务器 | `MiniFileSystemServer` | SDK server 侧构建的 stdio 服务器，`read_file` / `write_file` 两工具，根目录锁定（越界即拒）；stdin 关闭即自退 |
| 连接 | `McpClientPlugin`（DemoMain 编程挂载，等价 yml 见 `demo-m2.yml` 注释） | `serverName: files`——远端工具以 `mcp__files__*` 进目录 |
| 治理 | `WriteProtectorPlugin` + `ApprovalPlugin(always-deny)` | 对 `write_file` 声明 ask、对涉密文件读取 guard 拒绝 |

运行叙述按序走七步，每步都对应本章讲过的机制：

1. 建临时目录写入真实文件（`notes.txt` / `secret.txt`）；
2. boot `demo-m2.yml`（tools + 审批 always-deny + 写保护插件）；
3. 挂载 MCP 连接——子进程拉起、握手、工具自动同步（2.2 / 2.3）；
4. `read_file(notes.txt)` 经三段管线读到真实内容（远端工具与本地同权，2.1）；
5. `read_file(secret.txt)` 被 guard 单调拒绝并署名 guard；
6. `write_file` 因被声明 ask、always-deny 策略拒绝（2.4 的 `matches` 命中）；
7. dispose 连接——工具随作用域注销，再调即"未注册"（连接即生命周期）。

每步之后可观察面（控制台叙述 + Web 状态面 `http://127.0.0.1:18080` 实时对照；demo 主流程是终端叙述，状态面行为以本章 2.5 讲过的状态板语义为准）：

| 步骤 | 工具目录（清单） | 连接器状态板 |
|---|---|---|
| 3 挂载后 | 出现 `mcp__files__read_file__<hash>` 与 `mcp__files__write_file__<hash>`，描述带 `（MCP: files）` 后缀 | `files：CONNECTED（已连接）` |
| 5 / 6 治理命中 | 清单不变——治理不增删工具 | 不变 |
| 7 dispose 后 | 两个 `mcp__files__*` 工具消失 | 条目整行移除（不留"已下线"幽灵行） |

控制台摘录（节选，实际运行输出以此为准）：

```text
=== duo-harness M2 Demo：MCP 连接与治理链 ===
[M2] 临时目录就绪: duo-m2-demo…（notes.txt / secret.txt 已写入磁盘）
[M2] 挂载 MCP 连接（等价 yml 行: serverName=files, command=java, …）:
  [M2] 连接状态: ACTIVE（远端工具已自动同步进工具域）
[M2] read_file(notes.txt)——经三段管线调用远端 filesystem server:
[M2] read_file(secret.txt)——guard 治理（涉密拦截）:
[M2] write_file——写操作被声明需审批，always-deny 策略拒绝:
[M2] dispose MCP 连接:
  -> [消失] …
=== M2 Demo 结束（整树已回滚） ===
```

### 远端工具零改动施治理的两板斧

mcpfs 范例展示的治理形态对任何 MCP 服务器通用——工具本体（远端进程）零改动，治理经工具域挂点施加（[插件扩展点清单](../05-参考/插件扩展点清单.md)工具域节）：

- **事前拦**：pre-execute 瀑布声明 ask（交审批策略裁决）+ guard 单调拒绝（理由即拒、无法翻回）。适合"这个远端写操作必须过人"、"这个路径不许碰"的硬边界。最小骨架（`WriteProtectorPlugin` 同款 API）：

  ```java
  // 事前两件套：声明 ask + guard 单调否决（工具本体零改动）
  Disposable ask = ctx.on(ToolsService.PRE_EXECUTE,
          (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
              if (McpToolNames.matches(exec.toolName(), SERVER, RAW_NAME)) {
                  exec.requestApproval();                    // 交审批策略裁决
              }
              return next.invoke(exec);
          });
  Disposable guard = tools.guard(ctx, exec ->
          McpToolNames.matches(exec.toolName(), SERVER, READ_NAME)
                  && exec.args().path("path").asText("").contains("secret")
                  ? "禁止读取涉密文件" : null);              // 理由即拒，null 放行
  ```

- **事后改**：post-execute 结果改写。适合提醒类 advisory——`RepeatReminderPlugin` 对同一工具相同参数的连续重复调用，在结果尾部逐级加码提醒（3/5/8 阈值，yml `thresholds` 可配且须升序），**不改错误形态、不否决执行**：模型看到提醒自行换方法，硬性兜底仍是迭代上限。advisory 与 guard 语义不兼容——guard 是单调否决，别用 post-execute 实现"拦"。

  ```yaml
  - id: repeat-reminder
    name: dev.duo.harness.example.mcpfs.RepeatReminderPlugin
    config:
      thresholds: [3, 5, 8]   # 省略即默认 3/5/8
  ```

两板斧与本地工具用的是同一组挂点——这正是 2.1 说的"MCP 工具与本地工具毫无区别"在治理侧的兑现。

## 延伸阅读

- 动手接一个服务器：[组装你的第一个 agent](../02-指南/组装你的第一个agent.md)第四步
- 字段速查与编程挂载对照：[插件配置参考](../05-参考/插件配置参考.md)
- 工具目录与本目录对账约定：[工具目录](../05-参考/工具目录.md)
- 为什么这样设计：ADR-0006（[SDK 隔离](../adr/0006-MCP接入采用官方JavaSDK.md)）、ADR-0026（[命名哈希与耗尽终态](../adr/0026-M24权限与安全深化六裁定.md)决策四）
- 已知边界与版本耦合：[已知限制](../limitations.md) M2 节
