# 05: web_search 全链路（Tavily）

## What to build

配置搜索段后 agent 可用 `web_search` 搜全网（ADR-0021 决策 2/3/6/7）：单 query、归一化 Sources 列表；未配置时工具不注册（fetch-only 降级以工具面收缩兑现）。

## Blocked by

01

## Status

done

## Comments

- 2026-09-20（夜间自主实现）：实现完成、模块测试全绿。**待用户手动验收**。

- 2026-09-20（验收实测反馈修复）：URL 归一化去重：验收实测同一站点 http/https/www 三种变体重复占坑——按 host（去 www）+路径+查询串去重保留首条；新增归一化去重用例。
- 2026-09-20：用户手动验收通过（含 read-only ask 复验、baeldung 正文修复复验、Tavily 搜索接力），工单收口。
## Checklist

- [ ] config `search` 段解析：`type=tavily` / `apiKey` / `apiKeyEnv`（缺省 TAVILY_API_KEY）/ `baseUrl`（缺省官方端点）/ `maxResults`（缺省 8）；非法值启动 FAILED 点名；**段缺席或 key 链皆空 → web_search 不注册**（注册语义测试锁定）
- [ ] key 解析链：config 字面量 → 环境变量兜底 → 皆空视为未配置
- [ ] `TavilyProvider`：POST `{baseUrl}/search`、Bearer 鉴权、归一化 `sources[] {url, title, snippet}`；网络错误/非 200 → 结构化错误附 provider 原文（限流 429 无特判）
- [ ] `web_search` 工具：单 `query` 字符串参数（空/空白结构化错误）；结果截断 8 条；渲染 = 不可信声明 + `Sources:` 列表（`- [标题](url) — 摘要`）+ 引用指引；空结果 "No results found."
- [ ] `isConcurrencySafe=true`、30s 超时、不豁免管线超时
- [ ] Mock Tavily 测试：正常归一化 / 空结果 / provider 错误 / key 缺失不注册
- [ ] demo yml 注释示例（env 方式，不落字面量）
