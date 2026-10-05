# 04: composer 模型/思考选择器——预设切换与下一轮生效提示

## What to build

composer 左下角第二枚选择器（ADR-0040 决策二/三，紧邻档位选择器）：**模型/思考切换**——点开列 `llm.models` 预设清单与四档思考（off/low/medium/high），点选经斜杠分发 `/model <预设>`、`/effort <档>` 触发换链（01 交付的 ANY 双面命令），**下一轮生效**语义在 UI 明示（切换后轻提示「下一轮生效」，busy 中切换同样放行——换链即换，在飞 turn 不打断）。当前值高亮跟随 `model/intent`、`model/effort` 事件。预设清单与当前值数据源：`GET /api/llm-config`（02 交付）。

验收标准：点选预设/思考档 → 下一轮对话真实使用（请求断言）→ 选择器高亮同步；busy 中切换不打断在飞 turn。

## Blocked by

01, 02

## Status

ready-for-agent

## Checklist

- [ ] 模型/思考选择器 UI（紧邻档位选择器；llm.models 预设 + off/low/medium/high 四档）
- [ ] 点选触发 `/model`、`/effort` 斜杠切换 + 「下一轮生效」轻提示
- [ ] 当前值高亮跟随 model/intent、model/effort 事件（会话切换/重开恢复正确）
- [ ] 未知预设/非法思考档的防御（清单外值不发起切换）
- [ ] node --check 绿 + 浏览器手验（切换→下一轮真实生效截图留档）
- [ ] CHANGELOG 记账（未发布段：Web 面模型/思考实时切换）
