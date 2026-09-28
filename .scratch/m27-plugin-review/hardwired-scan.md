# M27 硬连点扫描册——绕过插件机制的写死接线台账

> 工单 02 产物。双重用途：**M28 立项对账底册**（丑条目的挑单池）+ **05 小步修候选池**（病条目的圈定来源）。
> 病丑判据（M27 grill Q3 裁定）：**病** = 阻塞或实质拖累"未来功能挂得上"，且须能指着预演（03）/demo（04）的具体卡点说"不修挂不上"才够 05 触发；**丑** = 能跑难看（归宿 M28 / backlog / 后续域）。
> 核实基准：2026-09-27，0.22.0 分支，全部条目实测（file:line 为当时行号，漂移以符号名为准）。10 处立项线索全部核实，另有补充扫描新发现 2 处、交叉引用 1 处。

## 台账（H-01 ~ H-15）

### H-01 类型下探改他者内部状态（×2）｜丑（M28 预判）
- 位置：PresenterAssembly.java:410（`instanceof ExitPlanModeTool` → `bindSession`）、:643（`instanceof ReadImageTool` → `setVisionGate`）——全仓 instanceof 具体工具类仅此两处（全扫核实）。
- 机制：装配层按具体类向下探到工具实例、直接改其内部状态；非该实现时仅 warn 兜底（"会话供给与批准回调未记账——计划状态可能串位"）。
- 影响面：工具换实现/第三方替换同名词工具即静默失效。
- 定性：丑——预判 M28（修法：工具声明式 capability 接口）；**升级触发**：若 03 预演证明它阻塞"替换实现挂载"场景则升病。

### H-02 前端工具名字面量分发渲染｜丑（backlog「工具名协议化渲染」域）
- 位置：web/app.js 实测 8 处（:378/:383/:429/:539/:562/:719/:720/:721——ask_user、exit_plan_mode、todo_write、export 四个名字面量分支）。
- 机制：新工具插件在 Web 无呈现路径（未知工具名静默忽略）。
- 影响面：工具"挂得上"（注册成功、模型可用）但"看不见"（Web 隐身）。
- 定性：丑——呈现层缺口；归宿 backlog（M29 视觉期顺带评估），非 M28 Java 重构域。

### H-03 呈现位常量钉死于 agent 契约｜丑（M28 预判）
- 位置：ChatAgent.java:15,18（`PRESENTER_CLI="cli"` / `PRESENTER_WEB="web"`）；亲和路由、后台任务归属按常量判（CliPlugin.java:293,1001 等）。agent 模块内无字面量散布（补充扫描核实，仅常量定义与 javadoc）。
- 机制：第三呈现位无合法身份（headless 只能在 example 自造 `HeadlessAnswerer.PRESENTER_ID` 绕行）。
- 定性：丑——改名/注册表化是破坏性接口变更，M28 裁；与独占面 C1（boundary.md）同域。

### H-04 agent 执行链直引功能域具体类｜丑（M28）
- 位置：ToolCallingAgent.java:82-92（字段直持 attachment.RequestVariants / ImageFileDelivery、tools.fs.ReadOnlyBashDetector、agent.memory.MemoryBook、agent.prompt.AgentsMdChain，均为具体类 import）。
- 机制：功能不装即 null 参数（14 参构造），而非"插件在场自动生效"——依赖方向倒挂：内核循环认知每个功能域。
- 定性：丑——M28 参数对象化/接口面整理同域。

### H-05 装配顺序隐式契约（yml 行序承载语义）｜病候选 ★05 候选
- 位置：WebPlugin.java:150-158（注释自证：「web 行先于 cli 行装配（回答者路由契约）……否则 resume 续接语义就此永远失效」，BUG-20260923-01 实证）；CliPlugin.java:307-308（「mcp 行须先于 cli 行——optionalInject 声明的时序契约」）；agent-demo.yml:96-103（行序契约的配置面）。
- 机制：行序错了不报错、静默改变语义（审批路由、resume、可选服务时序）——部署者加一行插件必须懂全部行序玄学。
- 影响面：阻塞**任意顺序安全挂载**；03 的 agents[] 预演（批量自启的顺序语义）将直接实证此条。
- 定性：**病候选**——修法轻（Boot 装载期校验行序契约、违规 fail-fast 点名），接口手术精度内；是否够 05 触发由 03 预演结果裁定。**03 预演已确证**：agents[] 批量挂载场景实证行序玄学被放大（rehearsals.md 预演三第 4 步）——按"实质拖累挂载"移交 05（唯一候选，待 04 demo 卡点合并圈定）。
- **已修（2026-09-27 工单 05，用户裁定修）**：①web-cli 半条——`InteractionService.hasAnswerer()` 只读探测（新接口方法，未来新呈现位自动被约束）+ WebPlugin apply 期 fail-fast（web 后于 cli 启动即点名「行序契约」与调整指引，Boot 审计整树回滚）；契约知识归当事插件、core 零改动。②mcp-cli 半条——复核确认已被 ADR-0019 epoch 自愈（connectorStatus 在 CliPlugin optionalInject 声明内，mcp 后置触发自动重载补订阅），契约降级为「省一次启动期重载」的建议，CliPlugin 注释已更正。
- **修复 v2（2026-09-27 用户验收实测补强）**：用户手动对调 web/cli 行实测，fail-fast 未响——探针定位出**更深的机制**：cli 行的 apply 即 REPL 主循环（同步阻塞至会话退出），其后所有行在 REPL 退出前不装载；交互场景下 web 行后置的病灶不是「路由翻转」而是「**Web 呈现位静默缺席**」（无报错、无 Web 横幅）。补强：①DuoMain 启动预检 `validatePresenterRowOrder`（boot 前解析行序，cli+web 同在册且 web 后置即 exit 2 点名——产品知识在 example 层，core 仍零改动）；②WebPlugin 的 hasAnswerer fail-fast 保留（守卫「web 回答者非首个注册」的翻转路径）；③yml 注释同步「cli 行 apply 即 REPL 主循环」语义。

### H-06 CLI 呈现位私有事件/工具认知｜丑（M28+ 呈现位解耦域）
- 位置：CliPlugin.java:502-523（switch SessionEvent 类型：SUBAGENT_SPAWNED / SUBAGENT_COMPLETED 过程行，内嵌 `SubagentManager.FINAL_ANSWER_MARKER` 字面量解析）；:913,928（turnListener 特判 todo_write 工具名）。
- 机制：呈现位写死对具体功能域事件/工具的认知；其他插件发同类事件 CLI 不呈现。
- 定性：丑——呈现位解耦属后续域（与 H-02 同向），M28 评估。

### H-07 计划模式域内嵌耦合｜丑（M28 评估插件化拆分）
- 位置：agent/plan/PlanMode.java（实测 4 处 exit_plan_mode 引用）+ PresenterAssembly.registerInteractionTools 把 exit_plan_mode / ask_user 塞进固定交互工具清单（:398-421）。
- 机制：计划模式是 agent 内嵌域而非插件——替换/扩展计划面（第三方计划工具、不同呈交策略）不存在挂点。
- 定性：丑——域拆分评估归 M28；与 H-01 同根（装配层按具体类记账）。

### H-08 装载 FQCN 直连 + 无批量装载｜病（修复出栈——不进 05）
- 位置：BootLoader.java:121（`Class.forName(row.name()).getDeclaredConstructor().newInstance()`）；agent-demo.yml 全部插件行为手写全限定名。
- 机制：插件发现=手写配置行；无目录扫描、无 jar 级装载、无 agents[] 批量自启。
- 影响面：阻塞 drop-in 挂载与批量自启（03 agents[] 预演主卡点）。
- 定性：病——**但修复属装载机制新功能**（spec Out of Scope 钉死），归宿 1.0 后按需立项；本条作为独占面 C7 的病灶注记，05 不修。

### H-09 三呈现位接线同构三连抄（含最大装配点）｜丑（M28 挂账主体）
- 位置：CliPlugin.apply 接线串（:236-330）≈ WebPlugin.apply（:162-262）≈ HeadlessRunner（:64-89）三份同构；chatAgent 全参重载 14 参（PresenterAssembly.java:322 / ToolCallingAgent.java:204，10 个叠层重载）；`CliPlugin.registerCommands` **17 参**为全仓最大装配点（:535-543）；WebFace.start 13 参 6 层重载。
- 影响面：每加功能 +1 参 +2 重载 + 三处锁步——M23 headless 首版漏挂管线超时/审计回答者的事故根因（review-log:57）。
- 定性：丑——ADR-0029 M28 挂账（装配参数对象化 + Data Clumps）的主体；**升级触发**：04 demo 或新呈现位实验证明阻塞则升病。

### H-10 WebFace 单体（端点私有内嵌）｜丑（M28 挂账）
- 位置：WebFace.java（单文件 1800+ 行；端点集中在私有 registerEndpoints :634；start 13 参）。
- 机制：插件加不了 HTTP API/页面；标签/审批路由/导出全内嵌。
- 定性：丑——ADR-0029 M28「WebFace 大文件拆分」挂账本体；端点注册口设计随拆分评估。

### H-11 模块依赖逆挂｜丑（M28+ 结构域）
- 位置：duo-harness-agent pom 依赖 tools/attachment/session/llm；cli/web 依赖几乎全家（boundary.md §4 依赖方向图）。
- 机制：功能域消费方与提供方同 classpath——"第三方插件独立交付"当前不成立（H-08 未解前此条不咬人）。
- 定性：丑——随装载机制（H-08）与依赖整理演进，M28+ 结构域。

### H-12 fileRefs 服务机制旁路（自产自用直传）｜病（本期无卡点实证——归宿 M28）
- 位置：WebPlugin.java:131（装配期自建 FileReferenceService）、:256-257（直传 WebFace；注释原话「不走服务声明」）。
- 机制：服务名常量在册、无常驻提供方（01 工单 provide 全量实测）——服务注册表被绕过，"发布 fileRefs 服务"这一挂载场景不存在。
- 定性：**病**（阻塞替换/第三方补全实现挂载）——但本期预演/demo 无对应卡点，不满足 05 触发判据（"能指着预演/demo 卡点说"）；归宿 M28（修法轻：装配改 provide + 消费方 optionalInject 服务化）。

### H-13 服务名撞名：presenter｜丑（M28 立项对账单列条目）
- 位置：SubagentHost.java:29（`SERVICE_NAME = "presenter"`，PresenterAssembly.java:678 装配期发布）。
- 机制：子代理宿主服务名与「呈现位 presenterId」词汇撞车——术语表「呈现位」词条在册，服务名却指向另一个概念。
- 定性：丑——改名是破坏性接口变更（服务名=视图方法名逐字契约）；**M28 立项对账时单列裁定**（本条即对账锚点）。

### H-14 ConnectorStatusBoard 静态单例旁路作用域｜丑（M28+）★补充扫描新发现
- 位置：ConnectorStatusBoard.java:27,34（`static final SHARED` + `shared()`）；McpClientSupport.java:29 经单例取实例再发布为服务（:89）。
- 机制：服务注册表登记的是 JVM 全局单例——插件树作用域隔离（子 Context、销毁回滚）对它无效；多 Boot 树/测试并行即共享状态。
- 定性：丑——单树部署无害；M28+ 随依赖整理收敛为实例持有。

### H-15 user.dir 取值同形 22 处｜丑（backlog 已挂账，对账收录）
- 位置：`Path.of(System.getProperty("user.dir"))` 全仓主源码 22 处（M26 四轮审查累计；backlog「user.dir 取值提取」条目在册）。
- 定性：丑——非插件机制旁路，属重复取值源；**收录本册仅为 M28 立项对账防漏**（重复到阈值即提取先例 M24-08）。

### H-16 Boot 装载职责无插件复用面｜丑（1.0 后装载设计域）★03 预演新发现
- 位置：BootLoader 的行解析 / problems 点名报错（:118-127 一带）/ disabled 语义——均为内核私有，无插件可调用的装载 API。
- 机制：插件要做批量挂载（agents[] 声明式自启）只能经 `ctx.plugin` 复刻装载语义——旁路 Boot 校验与点名报告，造第二套装载行为（rehearsals.md 预演三卡点 ε）。
- 定性：丑——正确解法是把装载职责开放为可复用面，与 H-08 类发现同批设计（1.0 后）；插件侧复刻是反模式，不鼓励。

## 核对通过项（本轮核实为"不存在"，防后续重查）

1. **后端"拒绝"魔法串审批判定**——已由 M16 /api/answer 结构化协议消化（WebFace.java:1333 注释明示「不做字符串嗅探」）。
2. **agent 模块内呈现位字面量散布**——无（仅常量定义与 javadoc）。
3. **具体工具类 instanceof 下探**——全仓仅 H-01 两处，无第三处。

## 汇总（供 03/05 圈定与 M28 对账）

| 定性 | 条目 | 去向 |
|---|---|---|
| 病候选 → **已修** | H-05 装配顺序隐式契约 | 工单 05 双重修复：①web 装配期 fail-fast（hasAnswerer 探测）②DuoMain boot 前预检（cli apply=REPL 主循环、其后行滞后的静默缺席形态——用户验收实测发现）；mcp-cli 半条确认为 epoch 自愈降级建议 |
| 病（触发不足） | H-12 fileRefs 旁路 | M28（本期无预演/demo 卡点，不满足 05 判据） |
| 病（修复出栈） | H-08 装载 FQCN/无批量 | 1.0 后（新功能非手术，spec Out of Scope） |
| 丑 → M28 | H-01、H-03、H-04、H-06、H-07、H-09（挂账主体）、H-10（挂账）、H-11、H-13（对账单列）、H-14、H-15 | M28 立项对账按本册挑单 |
| 丑 → 1.0 后装载设计域 | H-16（Boot 职责复用面，与 H-08 同批设计） | 1.0 后 |
| 丑 → backlog/后续域 | H-02 前端渲染（「工具名协议化渲染」）、H-06 呈现位解耦部分 | M29 视觉期评估 / 后续域 |
