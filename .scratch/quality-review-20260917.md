# duo-harness 全面质量评审报告

> 评审日期：2026-09-17 · 评审对象：`0.10.0` 分支 @ 640e9c6（工作区含 21 个未提交文件）
> 评审方式：全量源码走读（182 个主类 + 64 个测试类抽读）+ 全量测试实跑 + 文档-代码逐项对账
> 测试实证：`mvn test` BUILD SUCCESS —— **457 用例，0 失败，0 错误，1 跳过**（默认关闭的性能基准）

---

## 一、项目快照

| 项 | 值 |
|---|---|
| 技术栈 | Java 21（虚拟线程）+ Maven 多模块（9 模块）+ Jackson + SLF4J + MCP SDK 0.18.1 |
| 主源码 | 182 类 / 约 13.4K 行（agent 47、tools 39、core 30、example 29、llm 15、mcp 9、session 7、web 3、cli 3） |
| 测试 | 64 类 / 457 用例，全部通过 |
| 文档 | VitePress 文档站五章节 + 15 篇 ADR + limitations 唯一权威清单 + 15 个里程碑工单归档（.scratch） |
| CI | 仅 docs.yml（文档站部署）；**无 Java 构建测试流水线** |

## 二、量化打分

### 代码质量（7 子项）

| # | 维度 | 得分 | 依据与扣分点 |
|---|---|---|---|
| 1 | 目录分层与模块结构 | **9.0** | + 9 模块单向依赖无环（模块划分.md 与实际 pom 逐一对上）；core 的 `api`/`internal` 边界干净（第三方依赖不渗透契约层）；agent 按五域拆子包 + 6 类契约门面；每个包带 package-info。<br>− `tools` 根包 20 类、`llm` 根包 13 类，超出自定拆包阈值（duo-project-structure：约 10 类）——契约类平铺尚可辩护，但已是债务 |
| 2 | 命名规范 | **9.0** | + 域前缀体系一致（`Fs*`/`Subagent*`/`Web*`/`Mcp*`）；常量语义命名（`MAX_STREAM_CHARS`、`TAIL_WINDOW_MESSAGES`）；无 `controller/service/util/impl` 大筐（符合自定规范）。<br>− `bail` 与 `serial` 双名同义（limitations #2 已承认）；`Session.list` 内局部 `record Entry` 与 `SessionSummary` 字段名不一致（`ms` vs `lastModifiedMs`）转换冗余 |
| 3 | 可读性（注释与 JavaDoc） | **8.5** | + 注释普遍解释"为什么"而非复述代码：POSIX 锁陷阱（Session L140-153）、虚拟线程 pin 规避（ContextImpl L31-33）、审查编号留痕（M10-03、M13-05、BUG-20260914-02）；JavaDoc 写明 null 契约与线程约定。<br>− `Session.java` 两处 javadoc 错位：`lock()` 的旧 javadoc（L137-138）挂在 `HELD_LOCKS` 字段上、`latest()` 的描述（L240）挂在 `SessionSummary` record 上；`WebFace.registerEndpoints` 单方法 333 行稀释可读性 |
| 4 | 重复代码 | **7.5** | + `PresenterAssembly` 单点消除 CLI/Web 双份装配漂移（ADR-0011）；`messageWindow` 统一 tailWindow/windowBefore 两路边界语义。<br>− `WebFace` 15+ 处"setHeader → sendResponseHeaders → try(write)"四行响应样板无 helper；`OpenAiCompatAdapter` 的 SSE `data:` 行解析在 `streamLines` 与 `aggregateTurn` 双实现；`new ObjectMapper()` 散布（见 #9） |
| 5 | 错误处理 | **9.0** | + 异常语义体系完整（PluginException / PluginConfigException 带字段路径 / BootException 带阶段标签 / SessionLockedException.brief()）；幂等 dispose（CAS）+ 兄弟副作用失败 suppressed 聚合不掩盖（ContextImpl.dispose）；锁内检查防僵尸副作用；审批 fail-closed + 刷新宽限去抖（WebFace FAIL_CLOSED_GRACE_MS）；Web 错误响应不回显内部细节；工具异常统一收敛 error 结果不上抛；boot 失败整树逆序回滚。<br>− `bindSession` 等个别空 catch（有注释说明无害，但无日志留痕） |
| 6 | 测试覆盖 | **8.5** | + 457 用例全绿；接缝测试法（真实 Session + 脚本化 adapter，非全 mock）；端到端真实 HTTP（WebFaceTest 33 用例）与子代理全链路（SubagentEndToEndTest）；`ToolCatalogTest` 对账测试防文档-代码漂移；surefire 测试隔离（DUO_HOME 指向构建目录不碰真实 ~/.duo、fork 超时 240s 防挂死）；性能基准默认跳过可开关（SessionPerfBenchmarkTest）。<br>− 无 JaCoCo 覆盖率度量；core `recheck` 真并发竞态路径未测（limitations #3 已如实声明）；治理 compaction 的 LLM 依赖路径测试较薄 |
| 7 | 性能与安全 | **7.5** | + 事件快照读侧零拷贝（ADR-0014）+ 单趟窗口算法（17.1ms→7.6ms 实测记录）；虚拟线程全面使用（pin 风险有明确规避约定）；Web 加固成体系（1MB body 上限、SESSION_ID 白名单防路径穿越、静态资源后缀白名单、错误脱敏）；bash 工具进程树终止 + 双流护栏 + 超时 clamp + env 硬化 + stdin 空设备；MCP 断连指数退避 + 预算耗尽放弃 + 停止竞态处理；会话进程级独占锁（POSIX 陷阱两次踩坑均根治）。<br>− Web API 无 Host/Origin 校验（见严重问题 #2）；LLM 流式读取无 idle 超时（见中等 #5）；`/api/subagent/events` 与 `Session.titleOf` 全量 `readAllLines` 入内存（大会话峰值） |

### 文档质量：**7.5**

+ 文档站五章节骨架齐备（入门/指南/架构/参考），15 篇 ADR 落卷即冻结，limitations.md 是全仓库少见的"已知限制唯一权威清单"实践；运行Demo.md 的每条预期与代码逐项吻合（端口 18080、尾部 50 条窗口、fail-closed 语义、三档权限）；插件配置参考逐行注解与 agent-demo.yml 一致；模块划分.md 与实际包结构一致；"行为语义的权威是源码 JavaDoc"的单源约定避免了双维护。
− **README.md 严重过时**（详见第四节不一致清单 #1、#2）——项目第一门面落后 8 个版本；docs/index.md "03 高级"长期"建设中"。

### 工程配置：**6.5**

+ 版本管理规范（jackson BOM + 第三方版本全部集中根 pom 带注释说明耦合原因，如 networknt 2.0.0 随 mcp-json-jackson2 的硬依赖）；子模块内部依赖用 `${project.version}`；surefire 配置细心（测试环境隔离 + fork 超时兜底）；.gitignore 覆盖完整（target/IDE/密钥/node_modules/vitepress cache 均验证未入库）；docs CI 配置规范（npm 缓存 + concurrency 取消）。
− **无 Java CI**（build+test 无门禁，457 用例只在本机跑）；无静态分析/格式工具（checkstyle/spotbugs/error-prone 均未配置）；无 JaCoCo；**无 Maven wrapper（mvnw）**——构建复现依赖本机 maven；版本升级靠手工改根 pom（可考虑 flatten-maven-plugin/versions 插件自动化）。

### 整体结构与可维护性：**9.0**

+ 里程碑制演进（M1→M15 全程工单归档）、ADR 决策留痕、bug 台账与 backlog 闭环、双会话协作约定（AGENTS.md 路由 + skills 体系）——工程过程成熟度远超个人项目平均水平；契约门面稳定，破坏性变更（如 0.3.0 LlmConfig 上移、0.9.0 agent 拆包）在 CHANGELOG 中均有显式迁移说明。
− 文档-代码同步依赖手工纪律（README 已漂移即为例证）。

## 三、加权总分：**8.0 / 10**

| 板块 | 得分 | 权重 |
|---|---|---|
| 代码质量（7 子项均值 8.43） | 8.43 | 40% |
| 文档质量 | 7.5 | 20% |
| 工程配置 | 6.5 | 20% |
| 整体结构与可维护性 | 9.0 | 20% |
| **加权总分** | **7.97 ≈ 8.0** | |

**总体结论**：这是一个成熟度显著高于 typical 个人项目的 0.x 阶段框架——错误处理、并发纪律、安全边界意识、文档-代码对账纪律都在水准之上，测试实证 457 用例全绿。主要短板集中在两处：**工程防护网**（无 CI 门禁、无覆盖率/静态分析度量，质量高度依赖开发者自律）与**门面文档失修**（README 落后 8 个版本）。代码本体下一步的收益点在 WebFace 的结构化拆分与日志体系统一。

## 四、文档缺失 / 与代码不一致清单

| # | 位置 | 差异 |
|---|---|---|
| 1 | `README.md` 第 3 行 | 声称"最新发布 0.2.0；main 分支已落地 M3-M8"——实际 CHANGELOG 已定稿 **0.10.0**（M15 子代理，2026-09-17），落后 8 个版本、7 个里程碑；M9 上下文治理 / M12 权限预设 / M15 子代理均未提及 |
| 2 | `README.md` 模块表 | 缺 `duo-harness-cli` 行（M11 引入的真实模块），grep 验证 README 全文 0 次提及该模块 |
| 3 | `pom.xml` version=0.9.0 vs CHANGELOG 0.10.0（已定稿） | 当前 0.10.0 分支 21 文件未提交、release 收口提交未打——属进行中状态提示（项目惯例是收口时升版本），非缺陷，但注意下次 release 前对齐 |
| 4 | `docs/index.md` "03 高级 · 建设中" | 长期缺口（非不一致，如实标注） |
| 5 | 正面确认（抽查一致）：`docs/01-入门/运行Demo.md`、`docs/05-参考/插件配置参考.md`、`docs/04-架构/模块划分.md`、`docs/limitations.md`、`WebPlugin` 默认端口 8080 与文档一致、`ToolCatalogTest` 对账测试在防漂移 | — |

## 五、Top 10 优先问题（按严重程度）

### 严重

**S1. 无 Java CI 门禁**
- 位置：`.github/workflows/`（仅 docs.yml）
- 影响：457 个用例只在本机执行，push/PR 无回归防线；`recheck` 竞态等敏感路径的回归无法被及时捕获；换机器/协作者时质量基线不可复制。
- 建议：新增 `ci.yml`（JDK 21 + maven 缓存，`mvn -B verify`），PR 与 push main 触发；顺手加 JaCoCo 报告上传；成本半天，收益最大。

**S2. Web 本地 API 缺 Host/Origin 校验（浏览器 origin 攻击面）**
- 位置：`WebFace.registerEndpoints`（全部端点，尤其 `/api/message`、`/api/answer`）
- 影响：loopback 绑定只防局域网。用户浏览器中任意网页可向 `127.0.0.1:18080` 发跨站 POST（无 CSRF token、无 Origin 校验、无 Host 校验，DNS rebinding 亦可绕）：恶意页面可驱动本机 agent 执行工具，或在 workspace-write 档下诱导用户在自家页面上误点审批卡片。limitations.md #1（"无鉴权"）未覆盖这一攻击面。
- 建议：入口统一校验 `Host` 头（仅允许 `127.0.0.1:<port>`/`localhost:<port>`，直接封死 DNS rebinding）+ 对写端点校验 `Origin` 为空或同源；或要求自定义头 `X-Duo-Client`（带自定义头的跨站简单请求会被 CORS 预检拦截）。约 20 行。

### 中等

**M1. README.md 严重过时 + 模块表缺 cli**
- 位置：`README.md` 全文
- 影响：对外第一门面，版本/能力陈述与实际差 8 个版本，误导评估者与使用者。
- 建议：对齐 CHANGELOG 0.10.0 口径；补 cli 模块行与子代理、权限预设能力条目。

**M2. 日志规范分裂：47 处 System.out，5 个模块无 slf4j 依赖**
- 位置：`WebFace`（6 处诊断日志）、`WebPlugin`、`ContextGovernance`、`SessionTitles`；`llm/session/agent/web/cli` 五个 pom 无 slf4j-api
- 影响：core/tools/mcp 用 SLF4J、agent/web 用 stdout——诊断信息无级别、无法关断、无法接日志后端；与项目自身"清理与隔离错误改为可观察的 warn 日志"的 M1 决策不一致。
- 建议：五模块补 slf4j-api 依赖（test 用 slf4j-simple 已有先例），诊断性 println 换 log.debug/info；CLI 的 REPL 交互输出保留 stdout（功能输出，非日志）。

**M3. LLM 流式读取无 idle 超时**
- 位置：`OpenAiCompatAdapter`（HttpClient 仅 connect/header 10s 超时；`readLine` 无超时）
- 影响：provider 半开连接（发完响应头后断流不关）会永久阻塞 agent 循环，CLI 挂死、Web 面单飞标志永久 busy——只能 Ctrl-C。
- 建议：流读取包一层 idle 超时（如 60-120s 无新字节即抛 RetryableLlmException），或对 `streamTurn` 设整体 deadline。

**M4. WebFace 巨类 + registerEndpoints 333 行**
- 位置：`duo-harness-web/.../WebFace.java`（777 行，全项目最大类）
- 影响：8 个端点全内联在一个方法，15+ 处响应写入样板重复；新增端点与改安全策略都要动这段。
- 建议：按端点拆 private handler 方法（`handleMessage`/`handleSwitch`…），提取 `respond(exchange, status, contentType, body)` helper；registerEndpoints 退化为路由表。

**M5. `/api/answer` 以魔法字符串"拒绝"作前后端协议**
- 位置：`WebFace.java` L487：`!"拒绝".equals(values.get(0))`
- 影响：判定语义脆弱——审批按钮值是隐式字符串协议；用户在提问卡自由输入"拒绝"二字会被误判为审批拒绝；换措辞即静默破坏语义。
- 建议：前端改发结构化字段 `{decision: "approve"|"reject"}` 与 `{answer: [...]}`，或按钮值改用非自然语言常量；兼容期双判。

### 轻微

**L1. Session.java 两处 javadoc 错位**
- 位置：`Session.java` L137-138（`lock()` 的旧 javadoc 挂在 `HELD_LOCKS` 字段上）、L240（`latest()` 的描述挂在 `SessionSummary` record 上）
- 影响：注释与目标不匹配，误导读者。
- 建议：删除孤儿段或挪回对应方法。

**L2. 热路径 `new ObjectMapper()`**
- 位置：`ToolCallingAgent.argumentsAsJson`（L167，每次工具调用新建）、`FsBashTool.parameters()`、`FsToolsTest.json()`
- 影响：ObjectMapper 创建重量级，每次工具调用一次纯属浪费（微秒级×高频）；与仓库其余 10 处静态共享惯例不一致。
- 建议：提为 `private static final ObjectMapper`（只读线程安全）。

**L3. 全量读文件入内存的两处 + 无 mvnw**
- 位置：`WebFace /api/subagent/events`、`Session.titleOf`（`Files.readAllLines`）；仓库根无 `mvnw`
- 影响：大会话（数万事件）HTTP 回放/侧栏标题探测有内存峰值；新环境构建依赖本机 maven（本次评审即遇到 mvn 不在 PATH）。
- 建议：readAllLines 改 BufferedReader 逐行（titleOf 找到即停）；加 Maven wrapper 并提交。

## 六、值得保持的亮点（抽样）

- `Session` 的 POSIX 锁处理：两次踩坑（关闭探测 fd 释放全部锁）都以注释+注册表+串行闸根治，是全仓库注释质量的样板。
- `ConnectionSupervisor` 停止竞态处理（连接成功但已 stop → 不置 CONNECTED、关新连接、不计失败）。
- `ContextImpl.dispose` 的 suppressed 异常聚合与锁内销毁检查。
- `ToolCatalogTest` / 文档"对账测试防漂移"机制。
- limitations.md 的"已知限制唯一权威清单"实践——每条带出处（ADR/工单/实测）与去向。
