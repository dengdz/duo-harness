# 06: 收尾——smoke 扩展 + 文档 + 全量回归

## What to build

M38 收口三件套。其一 smoke 扩展：档位选择器与模型/思考切换的程序化断言（executeJavaScript 触发选择器 → 断言 permission/mode 事件与换链生效；档位四态遍历）。其二文档：README 能力概览补（会话内切换）、docs/02-指南/Web界面使用说明 补（选择器使用）、已知限制（模型/思考下一轮生效、配置重启生效、跨呈现位不同步——本呈现位独立裁定）。其三全量回归：mvn 全量 + vitest + smoke 全绿，产验收记录。

## Blocked by

03, 04, 05

## Status

ready-for-agent

## Checklist

- [ ] smoke 扩展：档位四态遍历断言 + 模型/思考切换断言（程序化触发→事件/状态实证）
- [ ] README 能力概览 + docs/02-指南/Web界面使用说明 补选择器与配置管理使用
- [ ] 已知限制补三条（模型/思考下一轮生效、配置重启生效、跨呈现位不同步——本呈现位独立裁定）
- [ ] 全量回归：mvn 全量 0 失败 + vitest 全绿 + smoke 全断言
- [ ] 验收记录更新（acceptance 体系沿 M37 形态）
