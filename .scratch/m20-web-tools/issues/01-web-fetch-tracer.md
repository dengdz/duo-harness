# 01: web_fetch tracer bullet——插件行 + 抓取全链路

## What to build

yml 加一行 `WebToolsPlugin`，agent 即可用 `web_fetch` 抓取正常网页：拿到"最终 URL + 状态码头行 + 不可信数据声明 + Markdown 基础正文（标题/段落/文本）"。这是本期最窄贯通弹道——装配、网络、清洗、转换、渲染、呈现一层不少；后续工单在此骨架上加防线、边界与搜索。

临时形态（02 替换）：redirect 策略显式 NEVER，3xx 按"非 2xx 是结果"语义渲染（头行带状态码、附 Location 提示让模型自行抓取）——不引入未经校验的自动跟随。

## Blocked by

无（可立即开工）。

## Status

done

## Comments

- 2026-09-20（夜间自主实现）：实现完成，模块测试 6/6 绿（渲染格式/截断 footer/3xx 结果/超时回填/UA 请求形态/结构化错误）。jsoup 依赖已入根 pom（grill 已获批）。**待用户手动验收**后置 done。

- 2026-09-20：用户手动验收通过（含 read-only ask 复验、baeldung 正文修复复验、Tavily 搜索接力），工单收口。
## Checklist

- [ ] `WebToolsPlugin` yml 一行 opt-in（config 可选），inject `tools` 服务，行在场即注册 `web_fetch`
- [ ] JDK HttpClient GET：`User-Agent`（缺省 duo-harness/版本，可配）、`accept` 头、redirect=NEVER（3xx 渲染为结果 + Location 提示）
- [ ] jsoup 清洗剔除不可见节点（script/style/noscript/template/iframe/object/embed/hidden 类）+ 基础 Markdown 映射（标题/段落/文本）
- [ ] 渲染格式：`Fetched <最终URL> (HTTP <状态码>)` + 不可信声明 + 正文 + 截断 footer（输出 200k 上限）
- [ ] `isConcurrencySafe=true`；`timeoutMs` 缺省 30s 生效（不豁免管线超时）
- [ ] Mock HTTP 测试（先例 MockOpenAiServer）：happy path / 输出截断 / 超时错误回填 / 3xx 结果渲染
- [ ] 演示可跑：demo yml 加行后真实抓取一个公网页面
