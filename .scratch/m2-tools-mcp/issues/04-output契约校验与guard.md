# 04 — output 契约校验 + guard 单调否决

## What to build

工具域两项强化收口：(1) **输出契约**——ToolDefinition 增加输出契约声明（结果 JSON Schema），执行结果过 networknt 校验，违约转 error 结果（点名违约原因，与工具异常收敛同一出口）；本地工具声明即校验；MCP 远端声明 outputSchema 的工具（工单 02 同步的）接线同标准，未声明者保持宽松透传（双轨制完整落地）。(2) **guard 单调否决**——`guard(registrant, check)`：执行前动态检查（在审批之后、工具本体之前），返回理由即拒绝（error 结果）、返回 null 即放行；**没有"允许"结果，顺序无法翻回**；普通 ctx 注册全局生效、作用域注册只影响该作用域。完成的判据：本地工具违约被点名拒绝、MCP 声明式契约生效、guard 拒绝无法被后续监听器翻回（接缝 B 全覆盖）。

## Blocked by

02（MCP 工具同步需先存在，才能接线声明式 outputSchema 校验）

## Status

ready-for-agent

## Checklist

- [ ] 输出契约：ToolDefinition 增 output 声明 + networknt 校验；违约 → error 结果点名原因
- [ ] MCP 工具接线：远端声明 outputSchema 的工具注册时带契约；未声明保持透传（双轨制完整）
- [ ] guard API：单调否决（理由即拒绝、null 即放行）；普通 ctx 全局 / 作用域注册局部生效
- [ ] guard 时机：审批（03）之后、工具本体之前
- [ ] 引入 networknt json-schema-validator（1.5.0，nexus 已核实；新依赖已获同意）
- [ ] 接缝 B 测试：本地契约违约 / MCP 声明式契约 / guard 拒绝不可翻回 / guard 全局与作用域两档
