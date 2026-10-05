# 01: 后端核心契约——/model、/effort 升双面 + Web 执行链接 Swappable

## What to build

模型/思考实时切换的后端契约面（ADR-0040 决策三：本呈现位独立）。三件事：①`/model`、`/effort` 命令从 CommandScope.CLI 升 **ANY**（CliPlugin 注册点，预设校验/补全逻辑随行）；②预设解析从 CLI 闭包上提——`llm.models` 白名单的读取共享化（Web 斜杠分发与后续端点都能取）；③**WebPlugin 执行链接入 SwappableLlmAdapter**（现装配裸 adapter）：包装后 `/model x`、`/effort x` 经 Web 斜杠分发路径可切，`model/intent`、`model/effort` 事件照发，「下一轮生效」语义与 CLI 完全一致。CLI 行为零变化。

验收标准：Web 面发 `/model <预设>` 后下一轮对话使用新模型（事件与请求断言）；CLI 侧回归零变化；档位命令不受影响。

## Blocked by

None (can start immediately)

## Status

in-progress（2026-10-05 实现完成、四轴审查修复毕、三模块 1064 例全绿；done 判据 = 用户验收或授权代验）

## Checklist

- [x] `/model`、`/effort` 升 CommandScope.ANY（预设校验随行，未知预设报错形态对齐 CLI 既有）
- [x] 预设解析上提共享（PresenterAssembly.switchModel/switchEffort/describeModels/describeEfforts 共享单点；控制器按呈现位持有状态）
- [x] WebPlugin 执行链接入 SwappableLlmAdapter（换链 + `model/intent`/`model/effort` 事件照发）
- [x] 回归锁三件：命令 scope 断言（ANY）/ Web 装配换链断言（状态载体 + 事件；「真实请求用新模型」弱化记档见 Comments）/ 两类事件发射断言（intent+effort 双发）
- [x] CLI 侧回归：三模块 1064 例 0 失败（CliPluginTest 全绿，行为零变化）
- [x] CHANGELOG 记账（未发布段）

## Comments

- **实现形态**：新契约三件——`ModelSwitchController`（呈现位切换面接口）/ `ModelSwitchRegistry`（SERVICE_NAME `modelSwitch`，CommandsPlugin 双服务发布）/ PresenterAssembly 共享单点（switchModel/switchEffort/describeModels/describeEfforts + registerModelSwitchCommands 查重注册）。命令 handler 按发起呈现位（context.presenter()）取控制器——**本呈现位独立**的执行绑定；查重先到先得（web 行先注册生效、cli 查重跳过、纯 CLI 由 cli 注册）。CLI 的 activeConfig/swappableLlm 字段与换链 UX（mock 文案/消息文本）逐字保留。
- **回归锁弱化记档**：「下一轮请求用新模型」的请求级断言需 WebPlugin 有 llmOverride 注入口（现无，加注入口属超范围）——以「状态载体推进 + 事件落盘 + 换链后视图」三面锁语义（WebModelSwitchTest），真实请求路径由 LlmAdapters.withRetry 既有行为与逻辑单测（ModelSwitchLogicTest 的 next().model() 断言）合成覆盖。
- **验证**：agent/cli/web 三模块 1064 例 0 失败（新锁 6 例：逻辑 4 + web 装配 2）；CHANGELOG 未发布段记账；两 grep 干净。

## 审查轮（2026-10-05 · 四轴合并轴）

**覆盖**：9 文件（agent 契约三件/PresenterAssembly/CliPlugin/WebPlugin/两测试/CHANGELOG）= 100%

### 阻断（无 BLOCKER；P1/P2 修复 7 件）
- **[轴三1·已修] Web 多标签并发双写**：busySafe 命令不经 Web 单飞互斥，并发 /model 读-改-写竞争——两控制器 switch 方法 synchronized 串行化（含注释说明动因）。
- **[轴一1·已修] describe 拼装二次上提**：两控制器 ~30 行近逐字重复上提为 PresenterAssembly.describeModels/describeEfforts（顺带修复轴三5：无参+白名单缺席的 CLI UX 漂移——「不可切」文案逐字保留）。
- **[轴三4/Java COL-16·已修] registry register 判空**（ConcurrentHashMap 不容 null → IllegalArgumentException）。
- **[轴一2·已修] Registry javadoc 笔误**（「命令命令名」叠词 + 不通语句重写）。
- **[Java COM-02·已修] Controller 接口 javadoc 补齐**（switchModel/switchEffort @param/@return）。
- **[轴三5·已修] 归并约定注释**（非 WEB 一律按 CLI 取——新呈现位接入时扩）。
- **[轴二4·已修] effort 经 WEB 分发的事件锁补齐**（model/effort 事件断言）。

### 建议（处置：记档 2）
- **[轴三2·记档] 换链三步非原子**（swap→append→activeConfig 回写）：append 罕败时链已换、状态滞后——下次成功切换自愈；原子化需状态回写下沉共享函数（record 扩展），收益不成比例。
- **[轴二4·记档] 请求级断言弱化**：见 Comments（注入口超范围，三面合成覆盖）。

### 测试覆盖
- 逻辑单测 4 例（白名单外拒切/换链+事件/同值 noop/effort 校验+事件）+ web 装配 2 例（ANY scope、WEB 分发换链+事件+effort、CLI 独立不波及、未知预设拒切）——env 鲁棒（surefire DUO_LLM_MODEL 注入下两段式装载锚定生效模型）。

**测试收口**：审查修复后三模块 1064 例 0 失败（exit 0）。四轴报告全文见本轮对话记录，本节为合并处置版。
