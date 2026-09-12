# 03: ask_user 提问工具

**What to build:** 模型发起的交互：模型在循环中调用 `ask_user` 工具时，执行本体把问题经交互 seam 交给人，回答文本作为工具结果回填，模型据此继续。参数最小 schema：`question`（必填文本）+ `options`（可选字符串数组，空 = 自由文本回答）+ `multiSelect`（默认 false）。工具定义由 tools 域提供、装配决定挂载，走六段管线不豁免。问与答复用 tool/call、tool/result 事件，零新词汇。

**Blocked by:** 01（复用交互服务与回答者）

**Status:** ready-for-agent

## Checklist

- [ ] ask_user ToolDefinition：参数 schema 校验（question 必填；options/multiSelect 可选）
- [ ] 执行本体：经交互服务等人作答；无回答者 / 未作答 fail-closed（收敛为 error 结果，模型可见原因）
- [ ] 走六段管线：审批 / guard 对 ask_user 照常生效
- [ ] 测试（mock answerer + ToolsService）：选项回答 / 自由文本回答 / fail-closed 错误结果
- [ ] 测试（脚本 LLM 两轮）：第一轮调 ask_user → mock answerer 应答 → 第二轮回答引用用户答案（AgentReplMainTest 先例）
- [ ] 文档同步：CHANGELOG 未发布段记提问工具

## Comments

spec：[../spec.md](../spec.md)。schema 先窄后宽——`intent` 等扩展字段等 M7 plan-review 真用到再加；词汇表"提问工具（ask_user）"词条已立。
