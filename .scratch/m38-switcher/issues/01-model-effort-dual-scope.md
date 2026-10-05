# 01: 后端核心契约——/model、/effort 升双面 + Web 执行链接 Swappable

## What to build

模型/思考实时切换的后端契约面（ADR-0040 决策三：本呈现位独立）。三件事：①`/model`、`/effort` 命令从 CommandScope.CLI 升 **ANY**（CliPlugin 注册点，预设校验/补全逻辑随行）；②预设解析从 CLI 闭包上提——`llm.models` 白名单的读取共享化（Web 斜杠分发与后续端点都能取）；③**WebPlugin 执行链接入 SwappableLlmAdapter**（现装配裸 adapter）：包装后 `/model x`、`/effort x` 经 Web 斜杠分发路径可切，`model/intent`、`model/effort` 事件照发，「下一轮生效」语义与 CLI 完全一致。CLI 行为零变化。

验收标准：Web 面发 `/model <预设>` 后下一轮对话使用新模型（事件与请求断言）；CLI 侧回归零变化；档位命令不受影响。

## Blocked by

None (can start immediately)

## Status

ready-for-agent

## Checklist

- [ ] `/model`、`/effort` 升 CommandScope.ANY（预设校验随行，未知预设报错形态对齐 CLI 既有）
- [ ] 预设解析上提共享（LlmConfig.models 读取不再闭包私有；Web 面可取清单与当前值）
- [ ] WebPlugin 执行链接入 SwappableLlmAdapter（换链 + `model/intent`/`model/effort` 事件照发）
- [ ] 回归锁三件：命令 scope 断言（ANY）/ Web 装配换链断言（下一轮请求用新模型）/ 两类事件发射断言
- [ ] CLI 侧回归：`/model`、`/effort` 既有测试全绿，行为零变化
- [ ] CHANGELOG 记账（未发布段）
