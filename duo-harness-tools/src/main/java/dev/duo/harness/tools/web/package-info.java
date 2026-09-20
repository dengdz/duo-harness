/**
 * web 工具族（M20，ADR-0021）：联网能力的模型侧工具集合——
 * {@link dev.duo.harness.tools.web.WebFetchTool web_fetch}（HTTP 抓取 + HTML→Markdown
 * + SSRF 三道防线）与 {@link dev.duo.harness.tools.web.WebSearchTool web_search}
 * （搜索 provider 可配，首发 Tavily）。单插件行 opt-in（{@link dev.duo.harness.tools.web.WebToolsPlugin}）；
 * search 段未配置时工具不注册，部署呈 fetch-only 形态。
 */
package dev.duo.harness.tools.web;
