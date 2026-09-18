# M18 扩展机制：hooks 生态格式复用、插件可选依赖、awaitStartup 可配超时

Status: ready-for-agent

语义权威：ADR-0019（扩展机制）。本文是其面向实现的落地规格；术语以 [术语表](../../docs/05-参考/术语表.md) 为准（钩子、可选依赖两词条已入册）。

## Problem Statement

三类用户各被一道墙挡着：

1. **从 Claude Code/Codex 迁移或并用的用户**：手里已有一套 hooks 配置（拦危险命令、审计工具调用），duo-harness 无法承接——要么放弃钩子生态，要么等一份自研协议。
2. **插件开发者与部署者**："有则用之"的服务依赖只能声明硬依赖，服务缺席整个插件起不来——CLI 想要"纯对话装配"（不装 fs 工具）无法表达，`/permission` 这类可选增强与必需启动被绑死。
3. **编程挂载的开发者**：`awaitStartup()` 无限阻塞且零日志，插件缺依赖时调用方静默卡死，排查无从下手。

## Solution

- **hooks（新模块 `duo-harness-hooks`）**：把 Claude Code/Codex 的 hooks 配置拷进 `~/.duo/hooks.json`、boot yml 加一行插件，PreToolUse/PostToolUse 钩子即生效；行为跟随两家官方（fail-open），阻断走 exit 2 / stdout JSON 双通道，stderr 回给模型。duo 自身的硬闸门仍由 guard/审批承担。
- **插件可选依赖**：`optionalInject()` 声明"就绪则用、缺失不拦"；服务出现/消失自动重载，升级↔降级双向对称。
- **awaitStartup**：可传超时的重载（超时点名缺失服务），无参版保持原语义但进入等待打点名日志。

## User Stories

1. 作为 Claude Code 用户，我想把现有 settings.json 的 hooks 段原样粘贴到 `~/.duo/hooks.json` 即生效，所以迁移零学习成本。
2. 作为 hooks 作者，我想以 exit 2 阻断一次工具调用并让 stderr 回给模型，所以能拦截危险操作且模型知道被拒原因。
3. 作为 hooks 作者，我想在 stdout 输出 JSON 裁定（permissionDecision deny/allow + 理由）精确表达结论，所以不依赖退出码也能控制放行与否。
4. 作为 hooks 作者，我想用 matcher（精确名 / `|` 多选 / 正则 / 省略全匹配）圈定钩子作用于哪些工具，所以一份配置装下多条规则。
5. 作为 hooks 作者，我想用 PostToolUse 审计已完成的调用（阻断 = 结果改写为错误回给模型），所以能做事后告警与反馈而不假装撤销副作用。
6. 作为运维者，钩子脚本超时、崩溃或路径不存在时工具调用照常放行并留 WARN 日志，所以第三方脚本故障不会把 agent 变成残废。
7. 作为运维者，我想给每条钩子配 `timeout` 秒数（缺省 600s），所以慢脚本的上限由我定。
8. 作为运维者，hooks.json 写错时 boot 不连坐其他插件、hooks 插件本身 FAILED 并点名文件与位置，所以配置错误一眼定位。
9. 作为用户，boot yml 不装 hooks 插件行则一切零感知零开销，所以不用 hooks 时无负担。
10. 作为 hooks 作者，我想让钩子进程从 stdin 拿到 session_id、transcript_path、cwd、hook_event_name、tool_name、tool_input、tool_use_id 载荷并拥有 `DUO_HOME` 环境变量，所以脚本能基于调用上下文判断。
11. 作为模型，被钩子阻断时收到含原因的错误结果，所以能改道而不是反复撞墙。
12. 作为运维者，hooks.json 里 duo 尚不支持的事件或处理器类型被跳过并留 WARN 点名，所以粘贴两家的完整配置不会炸启动。
13. 作为部署者，我想在 boot yml 去掉 fs 插件行得到纯对话 CLI，所以精简装配不再整树起不来。
14. 作为 CLI 用户，workspace 服务缺席时 `/permission` 提示"未挂载"而非程序崩溃，所以降级明确且可诊断。
15. 作为插件开发者，我想用 `optionalInject()` 声明可选服务依赖，所以"有则用之"的增强不必写硬依赖或绕读取纪律。
16. 作为插件开发者，可选服务出现/消失时我的插件自动重载（升级↔降级双向对称），所以不用手写服务监听与状态机。
17. 作为插件开发者，可选依赖与硬依赖共用 `as()` / `hasService()` 读取方式，所以心智模型只有一套。
18. 作为运维者，可选服务缺席不进 boot 审计报错，所以审计只报真问题。
19. 作为插件开发者，我想用 `awaitStartup(Duration)` 在超时时拿到点名缺失服务的异常，所以测试与引导快速失败且知道在等谁。
20. 作为插件开发者，无参 `awaitStartup()` 保持无限等待（服务晚到会被唤醒），所以合法的动态就绪模式不被破坏。
21. 作为排查者，无参 `awaitStartup()` 进入等待时日志点名在等哪些服务，所以"静默卡死"从根上消失。
22. 作为 CLI+Web 双开的用户，管线超时监听器不再被两个呈现位叠挂，所以超时裁决只有一层、语义可预期。
23. 作为维护者，测试进程收割问题在本期拿到限时评估结论（顺手修或方案记档），所以债务不再无限漂。

## Implementation Decisions

**模块与承载**

- 新 Maven 模块 `duo-harness-hooks`（pom 与 README 模块表同 diff 增补）；hooks 插件 `inject()` 硬依赖 `ToolsService`；opt-in = boot yml 插件行，不装行零感知。
- core/tools 域对 hooks 无感知：钩子是管线租户，监听器经既有瀑布挂点注册，tools 模块零改动（`updatedInput` 不做是前提）。

**hooks 配置与保真基线**

- 配置文件 `~/.duo/hooks.json`（DuoHome 解析），顶层 `{"hooks": {...}}` 与 Claude Code/Codex 同形；未知键宽容忽略；解析失败 → hooks 插件 FAILED、点名文件与位置，不连坐 boot 树。
- 一期只做 `PreToolUse` + `PostToolUse` 两事件（映射 tools/pre-execute 与 tools/post-execute）、只做 `"type": "command"`；其余事件/类型跳过 + WARN 点名。
- 阻断双通道：exit 2（stderr 回给模型）与 exit 0 时 stdout JSON `permissionDecision`（reason 优先呈现；allow 不带改写等价放行）；其余非零退出 = 非阻断 + WARN。
- PostToolUse 的"阻断" = 结果改写为错误形态（走管线 post 段既有 `markError` 能力），不假装撤销副作用。
- matcher 两档：纯字母数字 `_ - | ,` = 精确名/多选；含其他字符 = 正则；`*`/省略 = 全匹配。
- 条目级 `timeout`（秒）缺省 600s；超时 = 取消进程、丢弃输出、放行（fail-open）。
- 进程形态：`args` 数组在场 = exec 直启，缺省 = `sh -c`；stdin 载荷最小七字段（session_id、transcript_path、cwd、hook_event_name、tool_name、tool_input、tool_use_id）+ `DUO_HOME` 环境变量；字段与 duo 对应物的映射关系写进配置参考文档。

**顺序与并发**

- PreToolUse 钩子与审批策略解析者同段（pre-execute），按注册序先到先决；钩子 deny 占先即省掉审批交互；钩子只收不放——没有"跳过审批放行"的能力（与 guard 单调否决同哲学）。
- 并发零改动：钩子在每次调用的管线内部执行，不参与 M17 分组判定与屏障；成对有序提交不受影响（ADR-0019 决策 6）。

**可选依赖（core）**

- `Plugin` 新 default 方法 `optionalInject(): Set<String>`（空集缺省）；`as()` 读取许可放宽为 `inject() ∪ optionalInject()`；不新增读取 API（`hasService()` 判存 + `as()` 惰性视图）。
- epoch 语义唯一规则改动：缺失不阻塞 PENDING；依赖指纹缺失项跳过（不置 null），在场项照常参与；服务出现/消失经既有 recheck 自动重载。
- yml 路径 BootLoader 审计对"仅可选缺失"的插件不报 PENDING 等待（其本就不会 PENDING，审计文案复用缺失清单逻辑，与 awaitStartup 共用抽取）。

**awaitStartup（core）**

- 新增重载 `awaitStartup(Duration timeout)`：超时抛点名异常（"缺失服务： a, b"，复用 BootLoader 缺失清单文案逻辑）；无参版语义不变，进入等待打一条 INFO 日志点名在等谁。
- 超时来源 = 调用方传参，不加全局配置；超时后插件保持 PENDING（后台就绪照常激活），异常沿调用方路径走（apply 内抛 = 只杀本实例）。

**搭车与文档义务**

- PipelineTimeout 挂载查重先到先得（与 registerTodoWriteTool 同模式）+ 测试。
- 测试进程收割：限时评估，结论为"顺手修"或"方案记档继续挂账"。
- 文档同 diff：插件配置参考增 hooks.json 章节与载荷字段映射；用户文档明示"钩子不是执法边界"（fail-open 立场）；README 模块表；CHANGELOG 0.13.0 记账（收官工单）；backlog 对账（销"可选依赖""awaitStartup 超时""PipelineTimeout 双挂"三条，新增"项目级 hooks 配置""UserPromptSubmit/Stop 事件""updatedInput 改写"三条）。

## Testing Decisions

**接缝（已与用户确认，三缝两旧）**

1. **Boot 双接缝（core 既有，LifecycleTest/BootTest 叙事）**：optionalInject 的缺失照常 ACTIVE、provide/unprovide 触发重载双向、yml 审计不误报；awaitStartup 重载超时点名异常、无参版语义不变。
2. **ToolsService.execute 三段管线（tools 既有）**：hooks 拦截语义单测——假工具 + 直接注册钩子监听器，断言 deny/结果改写/放行/fail-open/matcher 命中。
3. **Boot 全链路端到端（复用缝 1，新用例）**：yml 挂 hooks 插件行 + `@TempDir` 注入 DUO_HOME + 真 hooks.json + 真外部进程（`sh -c` 调 echo/exit 类系统命令），经 execute 断言全链路；CI（Linux）可跑。

**好测试标准**：只断言外部行为（工具结果形态、插件状态、异常消息点名、日志可见性），不测内部实现；钩子进程用真实系统命令，不 mock 进程 API。

**先例**：LifecycleTest/BootTest 的接缝 A/B 叙事；tools 管线测试的假工具模式；M17 并发测试的行为断言风格。格式解析（matcher、宽容跳过、坏 JSON 点名）在 hooks 模块内做纯单元测试，不上缝。

**验收演示**：CLI 无 fs 行启动 + `/permission` 降级"未挂载"——装配级自动断言 + duo-acceptance 真机验收件（M17 同款：隔离端口与 DUO_HOME，用户亲手跑）。

## Out of Scope

- 项目级/仓库级 hooks 配置（`.duo/` 双层合并——"项目根"概念未立）。
- UserPromptSubmit / Stop / SessionStart 等非工具事件（需 agent 循环新挂点）。
- `updatedInput` 入参改写（`ToolExecution.args` final 的管线 API 变更 + 日志同构牵连）。
- `http` / `prompt` / `agent` / `mcp_tool` 处理器类型；`async` 异步钩子；`if` 权限规则语法；`statusMessage`；`${CLAUDE_PROJECT_DIR}` 类占位符替换；`permission_mode` 载荷字段。
- hooks 配置热重载（改配置重启生效，与 boot 单文件惯例一致）。
- MCP 工具并发白名单、skill 并发放开（M17 遗留，各自挂账）。
- subagent 域（ADR-0017 维持挂账独立挑期）；goal 域。

## Further Notes

- fail-open 是定位声明不是疏忽：外部脚本不承担执法边界，duo 硬闸门 = guard + 审批（进程内、不依赖第三方存活）。ADR 拒绝项有完整论证。
- Codex `hooks.json` 与 Claude Code settings.json 的 hooks 段结构同族，一期基线按 Claude Code 形状实现即可天然兼容两家；语义差异处（超时缺省、PostToolUse 审计性）已按超集对齐。
- MCP 首连已有 20s requestTimeout + `failOnStartupError` 缺省 false，不是 awaitStartup 卡死源，本期不动 MCP 域。
- 版本 0.13.0，版本分支 `0.13.0` 已切。
