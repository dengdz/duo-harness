# M28 度量对照（改前 → 改后）

> 工单 10 产物。基线见 [metrics-baseline.md](metrics-baseline.md)；复跑 `sh .scratch/m28-refactor/metrics.sh`。时点：2026-09-28，0.23.0 分支（工单 01~09 落地后）。

## ① 最大文件行数 top10

| 项 | 改前 | 改后 | 达标 |
|---|---|---|---|
| 全仓最大 | WebFace **1812** | CliPlugin 1296（含工单 04 重写 +23 行） | ✅ WebFace 拆分后退出 top10 |
| web 域最大 | WebFace 1812 | WebEndpoints 583 | ✅ ≤600 达标 |
| WebFace 门面 | — | 365 | 拆出七协作域（栅栏/标签/SSE/端点×2/HTTP/视图） |

## ② 装配点参数个数

| 装配点 | 改前 | 改后 | 达标 |
|---|---|---|---|
| CliPlugin.registerCommands | **17**（全仓最大） | **4**（CommandChain 8 + CommandState 7 分组） | ✅ |
| PresenterAssembly.chatAgent | 14（重载族 11 个） | 5（便捷版）；全参形态 = AgentSpec（1 参），族 11→2 | ✅ |
| ToolCallingAgent 构造 | 14（重载族 10 个） | 8（便捷版）；全参 = AgentSpec | ✅ |
| WebFace.start | 13（重载族 5 个） | **13（保留）——唯一 >8 残留，已裁保留** | ✅ 用户裁定（2026-09-28）：外部公开签名（WebPlugin + 6 测试类 25 处调用），兼容成本高于收益 |
| registerCommands 方法体 | 89 处裸参数引用 | chain./state. 前缀显式归属 | ✅ |

## ③ user.dir 同形计数

| 项 | 改前 | 改后 | 达标 |
|---|---|---|---|
| main 源码字面 | **22**（19 精确同形 + 3 字符串形态） | **3**（全部在 `Cwd` 类内：javadoc 1 + path() 1 + text() 1） | ✅ 取值源一处（17 文件 7 模块 → core 单点） |

## ④ 重复代码组

| 组 | 改前 | 改后 | 达标 |
|---|---|---|---|
| A 接线三连抄 | 3 份 | **3 份（残差，已裁保留）** | ✅ 用户裁定（2026-09-28）：记档不修——差异面为控制流非参数，模板化=重新设计；未来加第四呈现位时顺势合并 |
| B 路径解析双实现 | 2 处 | 1 处（resolveAgainstRoot 单点） | ✅ |
| C 汉字判定三处 | 已收敛（M26） | 1 处（isHan 单一事实源）维持 | ✅ |

## 对账单逐条销号（ADR-0030 范围）

| 条目 | 状态 | 证据 |
|---|---|---|
| H-09 装配参数堆（主体） | ✅ 销号 | 17→4、14→8、chatAgent 族 11→2（工单 03/04） |
| H-09 接线三连抄 | ⚠️ 残差待裁 | 见④组A；参数堆主病灶已消 |
| H-10 WebFace 单体 | ✅ 销号 | 1812 → 门面 365 + 七协作域（工单 06） |
| H-15 user.dir 散落 | ✅ 销号 | Cwd 单一取值源（工单 08） |
| H-12 fileRefs 旁路 | ⚠️ 残差挂账 | 服务化实测断链（声明检查 + epoch 循环两难）回退直传；正解（提供方归位 fs 插件）挂 backlog，后续结构域立项 |
| H-01 类型下探 ×2 | ✅ 销号 | PlanSessionBinder / VisionGateAware 能力探测（工单 03） |
| H-04 执行链依赖倒挂 | ✅ 销号（三端口） | MemoryInjector/AgentsMdInjector/PlanBashGate；attachment 两类残差归 H-11（M28+ 既定） |
| H-03 常量钉死 | ✅ 销号 | 常量出内核，身份呈现位自declare（工单 05） |
| H-13 服务撞名 | ✅ 销号 | "presenter" → "host"（SERVICE_NAME/视图方法/inject 一次改齐） |
| PluginSnapshot javadoc | ✅ 销号 | 注释认事实（工单 09） |
| H-06/H-07（评估条） | 记档不修 | spec 期裁定（撞不重新设计/不再架构红线），工单 05/Out of Scope 在案 |
| H-11/H-14（M28+） | 不进本期 | 扫描册原判维持 |
| 模式册 P-03 ObjectMapper | 挂账 backlog | 对账册裁定 |

## M27 基线不破核对

- [x] demo 插件（duo-harness-stats 模块）本期零改动——「零既有行改动」接入原样健在
- [x] 行序契约 fail-fast 测试绿（WebPluginRowOrderContractTest 含于全仓 verify）
- [x] 全仓 `mvn verify` 绿（2026-09-28 多轮 exit 0，140 测试类）
- [ ] CI 绿：待推送后 GitHub Actions 出示（本地无法代跑）

## CHANGELOG 对账（红线 6）

- [x] Changed 段四条插件开发者可见变更在册（host 改名 / 常量出内核 / fileRefs 正门 / 装配面重整）
- [x] 纯行为不变的拆分/参数对象化/消重按 spec 口径不记账

## 待用户

- [ ] 残差裁定：接线三连抄模板化（工单 04）+ WebFace.start 13 参（本表②）
- [ ] 手动验收（duo-acceptance 三件套：CLI REPL / Web 面对话与审批 / headless + demo 插件）
- [ ] 四轴审查发起（duo-code-review）与发版（duo-release-workflow）
