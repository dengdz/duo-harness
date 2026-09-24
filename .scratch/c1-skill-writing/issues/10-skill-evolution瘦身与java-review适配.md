# 10: duo-skill-evolution 瘦身 + duo-java-review 入口适配

## What to build
duo-skill-evolution 328 → ≤120 行：删第六节使用示例（与三、五节重复的 sediment）；第四节操作步骤并为一段（模板格式指向 experience.md）；输出模板不写死"达，"人格前缀（全局规则由模型响应时套用，模板去除分层耦合）；三条"不要过度 X"合并为正向原则（"进化以『下次少犯一个已记录的错误』为唯一收益标准"）。duo-java-review：description 重写、L0/L1/L2 渐进加载结构保留、与 duo-code-review 的衔接改显式调用。

验收标准（重放 seam）：下一次真实复盘（任一工单收口）按瘦身后的新模板走通；java-review 入口在四轴派发 brief 中被显式调用。

## Blocked by
04

## Status
ready-for-agent

## Checklist
- [ ] duo-skill-evolution 瘦身至 ≤120 行（前缀解耦 + 正向原则）
- [ ] duo-java-review 入口适配（description + 显式调用衔接）
- [ ] 重放：一次真实复盘按新模板走通
