# M28 度量基线（改前）

> 工单 01 产物。复跑方式：`sh .scratch/m28-refactor/metrics.sh`。基线时点：2026-09-28，分支 `0.23.0`（代码 = main 7b409f7，工作树仅文档/工单新增）。目标值口径见 [spec](spec.md) Implementation Decisions「度量口径」。

## ① 最大文件行数 top10（src/main/java）

| # | 行数 | 文件 |
|---|---|---|
| 1 | **1812** | web/WebFace.java（拆分对象，目标 ≤600） |
| 2 | 1273 | cli/CliPlugin.java |
| 3 | 1078 | session/Session.java |
| 4 | 685 | agent/presenter/PresenterAssembly.java |
| 5 | 644 | agent/internal/ToolCallingAgent.java |
| 6 | 581 | session-query/FtsSessionIndex.java |
| 7 | 535 | agent/governance/ContextGovernance.java |
| 8 | 500 | llm/internal/AnthropicMessagesAdapter.java |
| 9 | 425 | web/WebPlugin.java |
| 10 | 424 | session/SessionEvent.java |

## ② 装配点参数个数

四个已知装配点全参形态实测（2026-09-28 探查 + 脚本定位）：

| 装配点 | 全参形态 | 参数个数 |
|---|---|---|
| CliPlugin.registerCommands | :537 | **17**（全仓最大，目标 ≤8） |
| PresenterAssembly.chatAgent | :322 | 14（重载族 11 个） |
| ToolCallingAgent 构造 | :204 | 14（重载族 10 个） |
| WebFace.start | :328 | 13（重载族 5 个） |

粗筛口径说明：脚本单行签名 ≥8 逗号粗筛为 0 命中——四个装配点签名均跨多行，粗筛抓不到；精确值以工单 03/04/05/06 收口时逐一复核为准，脚本粗筛仅供趋势对照。

## ③ user.dir 同形计数

- 总命中（`System.getProperty("user.dir")`）：**22**
- 精确同形（`Path.of(System.getProperty("user.dir"))`）：**19**
- 分布：17 文件 7 模块（example 6、agent 5、cli 4、web 3、session-query 2、tools 2、hooks 1）
- 目标：全仓仅剩 core 取值源一处

## ④ 重复代码组计数

| 组 | 基线状态 | 归宿 |
|---|---|---|
| A 接线三连抄 | **3 份在位**（CliPlugin.apply ≈ WebPlugin.apply ≈ HeadlessRunner） | 工单 04 消为 1 份 |
| B 路径解析双实现 | **2 处在位**（WorkspacePolicy 同文件：contains 与 resolveInWorkspaceOrNull 同形 resolve） | 工单 08 消为 1 处 |
| C 汉字判定三处 | **已收敛**（QueryTokenizer.isHan 单一事实源，tokenize/入库预分词/短语提取三处共用——M26 收口审查「合并修复 13」已达） | 无待办；工单 08 该项即达成，勿重复立项 |

立项口径勘误注：ADR-0029「Data Clumps 三处同构」中汉字判定一组已由 M26 收口修复收敛，M28 实际待消组 = A + B 两组。度量④收口口径：接线 1 份 + 路径解析 1 处 + 汉字判定维持 1 处。

## 基线 verify

`./mvnw -B -ntp verify`：**绿**（exit 0，14 模块，2026-09-28 15:04）。注：首轮 verify 在 SkillRegistryTest.watch 用例偶发 1 error（见 bug-log BUG-20260928-01 观察条），复跑全绿坐实环境敏感偶发，观察已销。
