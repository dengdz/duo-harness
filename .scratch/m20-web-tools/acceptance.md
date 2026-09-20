# M20 验收件（用户手动运行验证）

> 前置：`~/.duo/config.yml` 已配 `llm:` 段（DeepSeek 等 OpenAI 兼容 provider）。可选：`export TAVILY_API_KEY=…` 并解开 `agent-demo.yml` 的 `search` 注释段以验收 web_search。

## 汇总 Demo（交互 REPL，同时起 Web 面）

```bash
./mvnw -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

（AgentReplMain 固定装载 `agent-demo.yml`——已含 `web-tools` 行；终端 REPL 与 http://127.0.0.1:18080 Web 面同时可用。启动即工具循环，不是 M1/M2 脚本 Demo。）逐条对照：

| # | 输入 | 预期 | 对应工单 |
|---|---|---|---|
| 1 | `抓一下 https://example.com 讲讲它是什么` | `[调工具] web_fetch` → 回答引用页面内容；工具行/卡片展开可见 `Fetched … (HTTP 200)` 头行与 Markdown 正文（不可信声明在正文前） | 01 |
| 2 | `抓一下 https://httpbin.org/status/404` | 头行 `(HTTP 404)`，模型如实说"页面不存在"而非工具报错（非 2xx 是结果） | 03 |
| 3 | `抓一下 http://localhost:18080` | `[web_fetch 错误]` 拒绝，理由含"非公网"（回环目标拦截） | 02 |
| 4 | `抓一下 http://[::1]:18080`（或内网 IP） | 同样拒绝（IPv6 回环/私网段拦截） | 02 |
| 5 | `/permission read-only` 后重试第 1 条 | `[待审批]`——read-only 档联网 ask；批准后正常抓取 | 06 |
| 6 | `/permission workspace-write` 后联网 | 免审批直接抓取（默认档日常可用） | 06 |
| 7 | （配了 TAVILY_API_KEY）`搜一下 jsoup 教程，挑一篇靠谱的抓全文` | `web_search` → Sources 列表 → 模型自选一条 `web_fetch` 深入——两工具接力 | 05 |
| 8 | 未配 key 的部署问 `你有哪些工具` | 工具清单含 web_fetch、不含 web_search（fetch-only 降级，工具面收缩） | 05 |

## 自动化佐证（agent 侧已跑）

- `duo-harness-tools` 模块 161 测试全绿，其中 M20 新增 43 条：
  - `WebFetchToolTest`（16）：渲染格式 / 截断 footer / 超时 / UA / 回环拒 / 同源重定向逐跳 / 跨源拒 / 超跳数 / 二进制与缺 Content-Type 拒 / JSON 透传 / 未知 charset 拒 / 非 2xx 结果 / CL 预检 / 恰好填满不截断 / 深嵌套占位
  - `UrlGuardTest`（7）：字面预检 / 保留段全拒（IPv4+IPv6）/ 全地址集一内一外整体拒 / 解析失败 / 同源强制
  - `BodyReaderTest`（4）：限内读取 / 超限截断 / 恰好填满 / 看门狗关流
  - `HtmlToMarkdownTest`（12）：标题 / 列表嵌套 / 链接 / 围栏码 / 行内格式 / 表格降级 / 图片 alt / 引用 / 转义 / script 剔除 / 深嵌套占位 / 空白规整
  - `WebSearchToolTest`（7）：Tavily 请求形态与渲染 / 空结果 / provider 错误透出 / 缺 query / 装配语义三态 / 非法 type 拒
  - `WorkspacePolicyTest` 增网络读三档判定矩阵
- 工具目录对账（`ToolCatalogTest`）：11 个注册工具（含 web_fetch / web_search）名称与描述与 `docs/05-参考/工具目录.md` 全量一致

## 已知边界（验收时不必惊讶）

- 重定向到**不同 host** 会被拒绝并提示直接抓取目标 URL（同源强制，ADR-0021）
- PDF / 图片 / 无 Content-Type 直接拒绝（不支持的内容类型）
- 表格转出来是「单元格 | 单元格」文本行（刻意降级，ADR-0021 决策 5）
- 搜索无 key 时 web_search 不存在——这是设计（fetch-only 降级），不是丢工具
