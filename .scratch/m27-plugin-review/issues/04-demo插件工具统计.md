# 04: demo 插件——工具使用统计员（零内核改动接入）

## What to build

新独立 Maven 模块实现一个完整可用的第三方插件，验证"现有插口够用"：

- **监听**：工具域 post-execute 瀑布事件，按工具名累计调用次数与成败；
- **命令**：注册 /toolstats（inject commands 服务），现场输出统计报表；
- **工具**：注册一个模型可调的统计查询工具（inject tools 服务），模型能回答"最近哪些工具用得多"。

**接入口径（验收核心）**：example 模块依赖清单加一行 + 启动 yml 加一行插件行，除此之外不动任何既有文件的一行。服务消费走声明纪律（必需 inject / 可选 optionalInject，ADR-0019），服务名对照既有先例（camelCase、视图接口方法名逐字一致）。不走"扫会话日志文件"等绕机制实现（那是扫描册要登记的反面形态）。

spec：`.scratch/m27-plugin-review/spec.md`（demo 功能节）。CHANGELOG 记账：新模块/新命令/新工具为用户可见变更（红线 6）。

## Blocked by

01（插口用法以边界矩阵为准）

## Status
done（2026-09-27 用户手动验收通过——实录对照全符：空跑 /toolstats 见引导语；模型跑 2 条 bash 后 /toolstats 精确显示「bash: 2 次（失败 0）」；问模型统计其自调 tool_stats 回 JSON 并自行渲染表格。验收中两个提示均属既有机制正常工作：会话占用提示为独占锁语义（另一进程持会话 3e5b，自动新建 569e）；「管线缺省超时跳过重复挂载」为 M18-05 查重兜底）

## Checklist
- [x] 独立 Maven 模块 duo-harness-stats + ToolStatsPlugin：事件监听（tools/post-execute 计数）、/toolstats 命令（busySafe=true 纯内存只读）、tool_stats 查询工具三件齐；inject "tools"+"commands" 声明纪律，视图接口方法名=服务名
- [x] 测试三件全绿：ToolStatsTest（6 用例含并发 4000 次计数）、ToolStatsPluginTest（装配接缝：命令在册/工具在册/公开 waterfall 派发后经 tool_stats 回读 JSON 断言）、BootYmlTest（yml 三行装载 ACTIVE，BUG-20260915-02 教训）
- [x] 接入动作 diff 实证：既有文件仅新增行零修改零删除（根 pom module 行、example 依赖块、agent-demo.yml 插件行、CHANGELOG 未发布段、工具目录条目）
- [x] CHANGELOG 记账落盘（未发布段）
- [x] 验收件三件套备好（见下）
- [x] 全仓 14 模块 `mvnw test` BUILD SUCCESS（两轮：首轮 example 目录守卫红 → 补工具目录条目 → 终验全绿）+ 两 grep 零命中（调试残留/记账实证）

## Comments

- 2026-09-27 开工。主源码四件（pom / ToolStats / StatsViews / ToolStatsPlugin）+ 测试三件，模板先例：EchoToolPlugin（工具注册）、HooksPlugin（瀑布监听）、PresenterAssemblyTest（awaitStartup 挂载惯用法）。
- 2026-09-27：**工具目录守卫执法（正面信号）**——`ToolCatalogTest` 炸出"tool_stats 未入目录/描述须与注册逐字一致"：demo 工具被当自家工具执法，注册扩展点端到端生效的活证。已按守卫要求补 `docs/05-参考/工具目录.md` 条目（描述逐字对齐注册值）。
- 2026-09-27：**发现一处 javadoc-实现出入（记档 M28 清理）**——PluginSnapshot javadoc 称"Boot 装载为 yml id"，实测快照 name 为类 FQCN；BootYmlTest 断言按实现事实写（endsWith 类简名），javadoc 修正归 M28（非硬连点，不入扫描册）。
- 2026-09-27：口径注记——Q1 裁定"依赖清单加一行 + yml 加一行"，实证接入动作为**五处纯新增**：根 pom `<module>` 行（构建注册，Maven 必需）、example 依赖块、yml 插件行、CHANGELOG 未发布段、工具目录条目（守卫强制）。全部为新增行，零既有行改动——口径精神（不动既有代码）成立，处数如实记档。
- 2026-09-27：统计口径写进 JavaDoc 与目录：计入进入执行段的调用（pre-execute 否决/审批挂起不计）；统计者也被统计。

### 验收件（工单级三件套，2026-09-27 备，agent 已在隔离环境自跑测试全绿）

**可运行演示**（真实环境，一条命令 + 三步操作）：

```bash
# 启动完整 demo 装配（CLI REPL + Web :18080 + tool-stats 行）
mvn -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.DuoMain
```

1. REPL 起来后先敲 `/toolstats` → 应见「暂无工具执行记录（统计自插件挂载起）。」
2. 给模型派一个用工具的活（如「列出当前目录下有哪些 md 文件」）→ 等它跑完
3. 再敲 `/toolstats` → 应见按工具名的计数行（如 `- glob: 3 次（失败 0）`）；对模型说「查一下现在的工具使用统计」→ 模型应调 tool_stats 工具回 JSON

**过程日志**：模块测试 8/8（ToolStatsTest 6 + 装配接缝 1 + yml 路径 1）；全仓 14 模块 BUILD SUCCESS；ToolCatalogTest 全量装配对账绿（demo yml 含 tool-stats 行启动成功、工具入册）。

**预期对照表**：

| 标志 | 应出现 |
|---|---|
| 启动 | REPL 与 Web 正常起，无 tool-stats 装载报错（行缺席才零感知，在场即 ACTIVE） |
| 空跑 `/toolstats` | 「暂无工具执行记录」引导语 |
| 模型用工具后 `/toolstats` | 按总量降序的计数行，格式 `- 工具名: N 次（失败 M）` |
| 问模型统计 | 模型调 tool_stats 返回 JSON（usage 数组，tool/total/failed 三字段） |
| 接入口径 | `git diff pom.xml duo-harness-example/` 全部为新增行，零既有行改动 |

## 验收实录（2026-09-27 用户亲手跑，全部符合）

- 启动：REPL + Web(:18080 鉴权 token) 正常；会话占用提示（独占锁拒绝 + 自动新建）与「管线超时跳过重复挂载」（M18-05 查重）均为既有机制正常工作。
- 空跑 `/toolstats` → 「暂无工具执行记录（统计自插件挂载起）。」
- 「列出当前目录的 md 文件」→ 模型跑 2 条 bash（两次审批卡 y 批准，四值键位正常）→ `/toolstats` 显示「bash: 2 次（失败 0）」——计数与实际调用精确吻合。
- 「查一下工具使用统计」→ 模型自调 `tool_stats {}` → `{"usage":[{"tool":"bash","total":2,"failed":0}]}` → 模型渲染表格解读「合计 2 次调用、0 次失败」。
