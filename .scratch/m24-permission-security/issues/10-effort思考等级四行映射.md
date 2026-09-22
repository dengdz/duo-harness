# 10: /effort 思考等级四行映射

## What to build

`/effort` 四档归一 off/low/medium/high（缺省 medium）；无参显示当前档；切换落会话事件、下一 turn 生效；请求侧参数映射按 provider 声明走四行——anthropic→thinking + budget、openai-compat→reasoning_effort、glm→thinking 开关、deepseek→显式降级标注（提示「思考请切 reasoner 模型」）；不支持即显式降级标注（哪档映射到什么/为什么不生效），永不静默；辅助性请求（标题生成等）强制 low 档，不因用户调 high 而烧大钱。

决策依据：ADR-0026 决策六 + 决策七（Anthropic 行落地面）；docs/research/ 两家 LLM与上下文/LLM调用层.md。

## Blocked by

08（映射表驱动与 Anthropic thinking+budget 落地面）

## Status
ready-for-agent

## Checklist
- [ ] `/effort` 命令 + 会话事件 + 缺省 medium（无参显示当前）
- [ ] 四行映射请求体（适配器测试断言参数：thinking+budget / reasoning_effort / thinking 开关）
- [ ] DeepSeek 显式降级标注；GLM 开关二值化映射
- [ ] 辅助性请求强制降档
- [ ] 测试（先例 OpenAiCompatAdapterTest + 工单 08 Anthropic 适配器测试 + ChatAgent seam）
- [ ] 工单级验收件：各 provider 档位切换实测演示，用户手动确认
- [ ] CHANGELOG 记账（0.19.0 段）
