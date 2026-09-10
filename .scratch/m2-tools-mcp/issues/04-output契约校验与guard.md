# 04 — output 契约校验 + guard 单调否决

## What to build

工具域两项强化收口：(1) **输出契约**——ToolDefinition 增加输出契约声明（结果 JSON Schema），执行结果过 networknt 校验，违约转 error 结果（点名违约原因，与工具异常收敛同一出口）；本地工具声明即校验；MCP 远端声明 outputSchema 的工具（工单 02 同步的）接线同标准，未声明者保持宽松透传（双轨制完整落地）。(2) **guard 单调否决**——`guard(registrant, check)`：执行前动态检查（在审批之后、工具本体之前），返回理由即拒绝（error 结果）、返回 null 即放行；**没有"允许"结果，顺序无法翻回**；普通 ctx 注册全局生效、作用域注册只影响该作用域。完成的判据：本地工具违约被点名拒绝、MCP 声明式契约生效、guard 拒绝无法被后续监听器翻回（接缝 B 全覆盖）。

## Blocked by

02（MCP 工具同步需先存在，才能接线声明式 outputSchema 校验）

## Status

done（2026-09-10 用户验收通过：三幕演示 12 条对照全中，IDEA 运行）

## Checklist

- [x] 输出契约：ToolDefinition 增 output() 声明 + 校验；违约 → error 结果点名原因
- [x] MCP 工具接线：远端 outputSchema 同步带入本地契约；未声明保持透传（双轨制完整）
- [x] guard API：单调否决（理由即拒绝、null 即放行）；随注册作用域销毁摘除
- [x] guard 时机：审批（03）之后、工具本体之前（含"审批拒绝则 guard 不跑""guard 拒绝则本体不跑"两向测试）
- [x] 引入 networknt json-schema-validator（**2.0.0**；偏离票据的 1.5.0——SDK 0.18.1 的 mcp-json-jackson2 硬性要求 2.0.0，1.5.0 缺 SDK 所需的 Dialects 类）
- [x] 接缝 B 测试：本地契约违约 / MCP 声明式契约 / guard 拒绝不可翻回 / guard 随作用域失效

## 实施记录

### 两个用户决策（偏离票据原文字面）

1. **SDK 升级 0.10.0 → 0.18.1**：`McpSchema.Tool` 自 0.14.0 才有 `outputSchema` 字段，
   0.10.0 无法做"声明式接线"。升级是所有 ≥0.14 版本的最小变更路径
   （三处改动：StdioClientTransport 双参构造、fixture 的 transport provider 与
   Tool 构造器 builder 化），仍是 Jackson 2 栈。客户端 API 全兼容，17 个既有
   mcp 用例回归全绿即升级成功的证据。
2. **guard "作用域注册局部生效"落地为生命周期局部**：内核没有调用方作用域概念
   （execute 不带作用域、事件表全树共享），真正的调用方作用域需要改 execute 签名
   或加 ambient 机制。落地为与 `on()` 同款语义：guard 随注册作用域销毁自动摘除
   （注册即作用域 effect），生效范围为全部工具调用。"仅作用域内调用生效"记入
   limitations（#5）。

### 执行管线全景（工单 03+04 后）

```
pre-execute waterfall（准入否决 / ask 声明）
  → 审批裁决（被声明的 ask 由策略解析监听器裁决；未解析即拒）
  → guard 链（顺序执行、首个拒绝即短路返回）
  → execute 本体（异常收敛为 error 结果）
  → 输出契约校验（声明即校验；违约转 error 结果点名原因）
  → post-execute waterfall（结果治理）
```

### 双轨制与双道防线

- **双轨制**：`ToolDefinition.output()` 默认 null = 宽松透传；声明即校验。
- MCP 工具：同步时远端 `outputSchema` 转入本地契约；调用映射优先返回
  `structuredContent`（outputSchema 校验的对象），无则回退 text content。
- **双道防线**：SDK 0.18 的 server 侧会先校验 structuredContent 对 outputSchema
  （违约在远端即 isError 返回——防线一）；若 server 放行（版本不一致等），
  本地工具域契约校验兜底（防线二）。测试断言违约必被点名，防线归属在注释中如实标注。

### networknt 1.5.0 → 2.0.0（实现中发现）

SDK `mcp-json-jackson2:0.18.1` 的 compile 依赖是 networknt **2.0.0**；按票据引入 1.5.0
会在 fixture 子进程抛 `NoClassDefFoundError: Dialects`（Maven 最近优先让 SDK 撞上旧版）。
跟随 SDK 升至 2.0.0 并适配新 API（`SchemaRegistry`/`SpecificationVersion`/`Error`
替代 1.5 的 `JsonSchemaFactory`/`SpecVersion`/`ValidationMessage`）。

### 新增 11 个用例

tools 模块 `OutputContractAndGuardTest`（10）：违约点名 / 合规放行 / 未声明透传 /
工具异常优先于契约 / guard 理由拒绝与 null 放行 / 多 guard 顺序短路 / 拒绝不可翻回
（pre+post 监听器）/ guard 拒绝本体不跑 / 审批拒绝 guard 不跑 / 随注册作用域失效。
mcp 模块 `McpToolSyncTest`（+2）：远端 outputSchema 接线且 structuredContent 合规放行 /
远端契约违约被点名拒绝（SDK server 侧防线）。

## Comments

### 验收件：契约与 guard 三幕演示

**一条命令**（仓库根目录复制粘贴即跑）：

```bash
mvn -pl duo-harness-example -am package -DskipTests exec:java \
  -Dexec.mainClass=dev.duo.harness.example.contract.ContractGuardDemoMain
```

IDEA 等价路径：打开 `duo-harness-example/src/main/java/dev/duo/harness/example/contract/ContractGuardDemoMain.java`
→ main 方法旁绿色箭头 → Run。

**预期输出对照表**（`[审批决策]`/`guard 拒绝` 行是审计日志走标准错误；叙述走标准输出，幕末刷盘）：

| # | 应出现 | 含义 |
|---|---|---|
| 1 | `[第一幕] 输出契约：声明即校验，未声明宽松透传` | 契约三态对照开始 |
| 2 | `summarize … -> [放行] {text=摘要内容}` | 契约合规放行 |
| 3 | `search … -> [被拒] 工具 "search" 输出违约: 已找到 integer，必须是 string` | 违约点名原因 |
| 4 | `read_config … -> [放行] 任意形态都行` | 未声明契约宽松透传 |
| 5 | `[第二幕] guard 单调否决：理由即拒、null 放行、首个拒绝短路` | guard 段开始 |
| 6 | `调用 read_file(target=公开笔记)… -> [放行] 文件内容` + `第二道 guard 执行次数: 1` | null 放行，链继续 |
| 7 | `调用 read_file(target=机密档案)… -> [被拒] …目标文件含敏感词（guard）` | 理由拒绝且署名 guard |
| 8 | `第二道 guard 执行次数: 1（首个拒绝即短路，链终止）` | 计数不变 = 顺序短路 |
| 9 | `[第三幕] 治理链全景：审批 → guard → 本体 → 契约` | 全景开始 |
| 10 | `deploy … -> [被拒] …被审批策略拒绝（策略: always-deny）` | 审批先行拒绝 |
| 11 | `guard 执行次数: 0（审批拒绝在先，调用到不了 guard）` | 时序证据 |
| 12 | `=== 三幕结束（整树均已回滚）===` | 收尾 |

**实测日志原文段**（2026-09-10 本机跑出，节选）：

```
调用 search（返回 {"text":42}——text 应为 string，违约）:
  -> [被拒] 工具 "search" 输出违约: 已找到 integer，必须是 string
```

```
调用 read_file(target=机密档案):
[...ContractGuardDemoMain.main()] INFO ...ToolsServiceImpl - guard 拒绝：工具=read_file 理由=目标文件含敏感词
  -> [被拒] 工具 "read_file" 执行被拒绝: 目标文件含敏感词（guard）
  第二道 guard 执行次数: 1（首个拒绝即短路，链终止）
```

### 验收件：测试路径

```bash
mvn -pl duo-harness-tools -am test -Dtest=OutputContractAndGuardTest
mvn -pl duo-harness-mcp -am test -Dtest=McpToolSyncTest
```

套件叙述行（`@BeforeAll` 打出）：

```
=== 套件：OutputContractAndGuardTest —— output 契约：违约点名/合规放行/未声明透传；guard：理由拒绝/null 放行/顺序短路/拒绝不可翻回/时机在审批后/随作用域失效（10 用例） ===
```

MCP 声明式契约（真实 stdio 协议，SDK server 侧防线一 + 本地防线二）在
`McpToolSyncTest` 的 `remoteOutputSchemaIsWiredAndCompliantResultPasses` 与
`remoteContractViolationBecomesNamedError` 两用例覆盖。

### 状态

2026-09-10 用户验收通过（IDEA 运行三幕演示，12 条对照全中）。