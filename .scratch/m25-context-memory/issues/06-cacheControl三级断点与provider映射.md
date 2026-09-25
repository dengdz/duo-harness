# 06: cacheControl 三级断点与 provider 映射

## What to build
请求侧的缓存省钱：对每轮重复携带的稳定前缀打三级缓存断点（身份前缀 / 稳定身份 / 动态段），provider 侧命中缓存即省 token 提速。不支持的 provider 静默降级（不带断点参数、零报错）。用量透出：缓存命中与计费差异在用量统计可见（用户故事 16）。交付后的可感行为：多轮对话的 token 账单里出现缓存命中项。

设计要点：三级断点的具体划分边界与各家 provider 的映射策略按探测工单 10（系统提示与上下文工程）落地；断点失效自然回源（用户故事 13，无隐性成本惊喜）。

## Blocked by
01

## Status
ready-for-agent

## Checklist
- [ ] 三级断点划分与请求装配（身份前缀 / 稳定身份 / 动态段）
- [ ] provider 映射（anthropic / openai-compat / glm / deepseek 四行策略；不支持静默降级零报错）
- [ ] 用量透出（缓存命中在用量统计可见）
- [ ] tdd 红绿循环（断点位置断言 + 降级用例，seam 先与用户确认）
- [ ] 术语表「cacheControl 断点」词条同 diff
