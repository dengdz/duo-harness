# M30（0.26.0）发布产物——「拿得出手」

Status: ready-for-agent（范围与发布形态见 [ADR-0032](../../docs/adr/0032-M30发布产物立项决策.md)；5 张工单已拆，见 issues/——01 与 05 无阻塞可开工，02←01，03/04←02）

## Problem Statement

- **发布产物为零**：项目只能 clone + `mvn exec` 跑起来——无 shade 打包、无 release workflow（现仅 ci.yml 门禁 + docs.yml Pages 两件）、GitHub 无任何 Release。README 首段能力叙述停在 0.15.0，「一条命令看它做什么」还是 M1/M2 的 demo 叙事——外部访客拿不到「这是什么、怎么跑、去哪读文档」的三分钟答案。
- **「`java -jar` 即跑」现状不成立**：DuoMain 缺省 yml 经 `Path.of(getResource().toURI())` 文件化读取（`HeadlessArgs.java:77` 与 DuoMain 同型两处），fat-jar 内资源 URI 为 `jar:` 形态必炸；web 静态资源（`getResourceAsStream`）与 `~/.duo/config.yml`（文件系统路径）不受影响。
- **空会话污染侧栏**：页面加载/服务端重启即懒创建会话文件，M29 工单 12 验收期实测 2 天产生 370 个空会话（252 个 79B 版本头 + 118 个 0 字节）全部进侧栏；ZCode 对照 deferred/draft 三层防线（创建仅内存 / 首条真实事件才落盘 / 列表过滤），空会话既不堆积也不可见。

## Solution

四件（范围锁定，backlog 不拉项，ADR-0032）：

**发布产物三件**：①shade fat-jar（`java -jar` 即跑，前提 JDK 21 + `~/.duo/config.yml`）+ 缺省 yml 的 Boot API 资源化改造；②独立 release.yml——push tag `v*` 触发，tag 与 pom 版本一致性校验 → 全量 verify → SHA256 → 创建 GitHub Release，notes 从 CHANGELOG 对应版本段摘录；③README 重写（中文为主全新门面，验收期配 web 面全景截图一张）。

**会话生命周期一件**：会话持久化 defer 化（创建仅内存态 / 首条真实事件才落盘 / 列表天然干净 + 防御兜底 / CLI 呈现位同口径核查 / 边界确认），见 issues/05。

发版时序钉死：验收后 14 处 pom 对齐 0.26.0 → CHANGELOG 落段 → 合并 main → 打 tag `v0.26.0` → release.yml 校验发版。

## User Stories

1. 作为想试用 duo 的开发者，我装好 JDK 21、写好 `~/.duo/config.yml`、`java -jar duo-harness-0.26.0.jar` 一条命令跑起来——终端 REPL 与浏览器双面立即可用。
2. 作为访问 GitHub Releases 的人，我按 tag 找到每个版本：jar + `.sha256` 校验和可下载可验证，Release 说明与 CHANGELOG 同源。
3. 作为第一次打开 README 的人，我三分钟内知道项目是什么、三行跑起来、知道文档站在哪。
4. 作为 web 面用户，我打开页面、重启服务都不再产生空会话文件，侧栏只有真实发生过的会话；deferred 会话的首条消息收发、SSE 与 tabId 重绑一切如常。
5. 作为 CI 的维护者，我确认发版权限（`contents: write`）只在 release.yml 一个文件里，tag 打错版本号时 workflow fail-fast 不出假 Release。

## Implementation Decisions

- **shade 挂 `duo-harness-example`**（唯一聚合全部插件装配的模块），`Main-Class` = `dev.duo.harness.example.DuoMain`，`finalName` = `duo-harness-<version>`；签名文件 exclude、`META-INF/services` merge（MCP SDK 依赖）。
- **缺省 yml 兼容改造走 Boot API 资源化**（用户裁定，拒绝临时文件桥接）：Boot 装载加资源流通道，新增重载不动旧签名；DuoMain/HeadlessArgs 缺省分支切换资源装载；`validatePresenterRowOrder` 预检输入同步适配（InputStream 单次读取——预检与装载的读取顺序在此钉死：预检先读全量字节做行序校验，通过后同一字节流开新流装载，或一次性读入字节数组两段共用，实现择一并测试锁定）。
- **Boot 资源通道落地同 diff 排查透传**（duo-workflow 经验档 2026-09-29 条）：`grep Boot.from` 列全部消费点逐一核对；新增重载不触既有装饰/包装实现。
- **release.yml 独立文件**：权限 `contents: write` 仅此文件；版本校验步骤 fail-closed（`v<pom.version>` 不等 tag 名即失败）；verify 即全量测试门禁；SHA256 产物命名 `duo-harness-<version>.jar.sha256`。
- **Release notes 摘 CHANGELOG**：脚本截取对应版本段（`## 0.26.0` 节）填 Release body，不用 GitHub 自动生成（避免 PR 列表与 CHANGELOG 双本账，红线 6）。
- **README 五段结构**：一句话定位 → 三行快速开始（JDK 21 + `java -jar` + config 最小配置样例【占位符，API key 永不入库——红线 2】+ 双呈现位第一眼）→ 能力概览（当前版本面，不按版本史）→ 模块表微调保留 → 文档站入口；演进史压缩成一句；demo 叙事段替换，Demo 降级到文档站「运行 Demo」页；全景截图 vendor 进仓库引用。
- **README 与 fat-jar 工单同 diff 或紧邻落地**（红线 3）：README 引用的 jar 名与用法依赖 02 的产物名定稿。
- **defer 化语义**：创建仅内存 Session（WebFace/WebTabs 懒创建、`/api/session/new`、DuoMain 启动自建四处创建点均不落文件）；SessionStore 版本头推迟到首个 `appendEvent` 与事件同批写；`/api/sessions` 补零事件过滤防御；CLI 创建路径同口径核查（M29 工单 12 present 单边摘除漏 CliPlugin 的教训）；deferred 会话 SSE 绑定与输入接收正常、tabId 重绑语义不变、投影/检索索引跳过零事件会话、版本头迁移链无新增负担（无头文件 = v0 语义本就兼容）。

## Testing Decisions

- **Boot 资源通道**：新增重载的单测（资源流装载 = 文件路径装载同语义：行序预检、Boot 失败点名、headless 分支）；DuoMain 缺省分支改造后以 **jar 形态实测**为完成判据（`java -jar` 真跑，非 mock classloader）。
- **fat-jar 验收件**：`mvn package` 产物 `java -jar duo-harness-0.26.0.jar` 真跑——终端 REPL 出现、浏览器双面可用、Ctrl-C 级联停止（会话锁释放）。
- **release.yml**：本地无法全验 CI，验收件 = 收口期真打 tag 后 Release 页可见 jar + 校验和 + notes 与 CHANGELOG 一致；版本校验步骤以错位 tag 名干跑验证 fail-fast。
- **defer 化**（issues/05 checklist 已列）：零文件断言（新标签打开无新文件）、首消息头+事件同批写、crash 语义（无事件 = 无文件 = 重启消失）、SSE 收流、`/api/sessions` 过滤、浏览器端到端（开页侧栏无空会话 → 发首条消息会话出现且内容完整）。
- **全量回归不动摇**：`./mvnw verify` 全绿（M29 基线 1080 用例）。
- 里程碑级验收（duo-acceptance）：`acceptance.md` 预期对照表含实测日志原文段——jar 真下载真跑、Release 页可见、README 目测、defer 化浏览器端到端。

## Out of Scope

- Maven Central、源码 jar / javadoc jar（ADR-0032 拒绝项）。
- jlink / jpackage 免运行时矩阵（留 1.0 后真有非开发者用户再议）。
- backlog 全部候选项：流式思考展示、查阅组卡嵌套、bind 地址配置化、Web 斜杠命令异步化、user.dir 取值提取、MCP 工具并发白名单等。
- 版本号自举机制（tag-pom 一致性校验已覆盖错位风险，不做版本单源化改造）。
- README 暗色主题/多语言门面（维持 M29 视觉基线）。

## Further Notes

- **工单拆分**（2026-10-01 /to-tickets 已发布，用户批准）：01 Boot 资源化 + DuoMain/Headless 缺省分支改造（无阻塞）→ 02 shade 打包（←01）→ 03 release.yml（←02）→ 04 README 重写（←02）；05 会话 defer 化（已迁入，无阻塞）。Frontier = 01 与 05 并行。
- 术语表：deferred 会话 / draft / 空会话等会话域词条随 05 实现工单同 diff 落表（ADR-0031 思考折叠卡先例）。
- M29 收口遗留记账：`.scratch/m29-docs-visual/issues/11-收口验收发版.md` 文件头 Status 与两格 checklist 未回填（tag 与合并实际已完成）——属历史工件滞后，不阻塞本里程碑，收口对账时顺手回填。
- 经验档锚点：duo-workflow 2026-09-29 里程碑启动版图对账（本次已执行，一致）；接口新增通道透传排查（01 工单适用）。
