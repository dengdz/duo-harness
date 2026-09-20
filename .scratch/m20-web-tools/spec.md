# M20 spec：web 工具族（0.15.0）

> 状态：已立项（2026-09-20 grill 六题十二裁定，[ADR-0021](../../docs/adr/0021-web工具族.md)；事实基础 = [DSH web 工具族研究三件套](../../docs/research/DSH/web工具族/架构设计.md) 锚点 ddefc45f）。本文是实现的唯一依据；工单见同目录 `issues/`。

## Problem Statement

duo agent 的能力边界止步于本地 workspace：读代码、改文件、跑命令都在，但**够不到互联网**——查一份库文档、搜一个报错的成因、读一篇技术方案，都得用户自己开浏览器、复制粘贴进对话。工具目录自 M12 后联网零覆盖；对照系 DSH 把 `web_search`/`web_fetch` 列为默认启用工具，证词明确：联网是 agent 能力而非工具 UX，缺位即生产力缺口。

## Solution

yml 一行装配 web 工具族（ADR-0021）：

- **`web_fetch`**：agent 给一个 http(s) URL，拿回"最终 URL + 状态码 + 干净的 Markdown 正文"——脚本/样式/隐藏节点剔除，标题列表代码块结构保留，SSRF 三道防线确保它摸不到回环与内网。
- **`web_search`**：配置搜索段（Tavily key）后，agent 用单个关键词搜全网，拿回"标题 + 链接 + 摘要"列表，再决定抓哪个深入。
- 没配搜索段的部署自动呈 **fetch-only** 形态：`web_search` 不注册、模型工具清单干净收缩，永不撞必然失败的调用。

## User Stories

1. As a 部署者, I want yml 一行启用 web 工具族, so that 零编码给 agent 联网能力，不装零感知
2. As a 部署者, I want 未配置搜索段时 `web_search` 完全不注册, so that 模型不浪费轮次调用必然失败的工具（fetch-only 降级）
3. As an agent, I want `web_fetch` 抓取任意 http(s) 页面并拿到 Markdown 正文, so that 阅读文档/README/技术页时保留标题与代码结构、可定位章节
4. As an agent, I want fetch 结果头行携带最终 URL 与 HTTP 状态码, so that 跟随重定向后知道真实来源，非 2xx 时自行判断而非误当成功内容
5. As an agent, I want 外网正文前有"不可信数据"声明, so that 我不把网页内容当指令执行（提示注入防线）
6. As an agent, I want 输出被截断时收到明确的截断 footer, so that 我知道去抓更具体的 URL/章节，而不是误以为已读全文
7. As a 部署者, I want fetch 目标为 localhost/内网 IP/解析到内网的域名时被拒绝, so that 恶意网页无法借 agent 探测或触达我的内网
8. As a 部署者, I want 重定向跳向非同源目标被拦下, so that 公网页面的 302 不能绕过 URL 预检与 DNS 校验
9. As an agent, I want 嵌套超深的病态 HTML 返回固定占位符, so that 转换不耗尽管线超时、会话不被一个页面挂死
10. As an agent, I want PDF/图片/未知编码被拒绝并说明原因, so that 我改走别的路径而不是收到一堆乱码
11. As an agent, I want `web_search` 用单个关键词搜索, so that 找到候选链接与摘要后再决定抓哪页深入
12. As an agent, I want 搜索结果以"- [标题](url) — 摘要"列表返回, so that 快速比较多个来源的相关性
13. As a 部署者, I want 搜索 key 支持 yml 字面量或 `TAVILY_API_KEY` 环境变量, so that key 不进 git 仓库、demo 与生产两种配法都顺
14. As a 部署者, I want 非法 config（负数超时、未知 provider 类型、非正整数限额）启动即 FAILED 并点名, so that 配错立即暴露而非运行时诡异行为
15. As a 用户, I want `read-only` 档下联网操作一律审批, so that "只读"承诺包含"不出网"，越界必经我确认
16. As a 用户, I want `workspace-write`（默认档）下联网免审批, so that 日常调研不被"每页一批准"打断
17. As an agent, I want fetch/search 声明并发安全, so that 多路调研在一轮内并行完成而非逐个串行等待
18. As a 用户, I want 卡死的 fetch 在 30s 超时以错误结果回填, so that 会话不被一个挂死的抓取永久占住
19. As a 子代理使用者, I want subagent 模板可点名 web 工具, so that 调研型子代理能自行联网而父代理只收结论
20. As a 部署者, I want PreToolUse/PostToolUse 钩子对 web 工具自动生效, so that 已有的 hooks 策略（如域名拦截）无需任何改造即覆盖联网行为
21. As a 用户, I want Web 面工具卡与 CLI 叙述行照常呈现 fetch/search 过程, so that 双面呈现一致、无界面特化成本
22. As a 部署者, I want User-Agent 可配, so that 目标站点可识别请求来源，被反爬拦截时有自救手段

## Implementation Decisions

（全部承 ADR-0021，此处记实现约束；模块内文件布局由实现定）

1. **落位**：`duo-harness-tools` 新 `web` 子包；`WebToolsPlugin` 单插件行 opt-in，config 可选。行在场即注册 `web_fetch`；config `search` 段非空才注册 `web_search`。inject 依赖 `tools` 服务。
2. **注册语义**：配置驱动注册、注册即可用——provider 契约**不含** `available()` 探测位（与 DSH 的解耦式注册刻意不同）。
3. **fetch 网络层**：JDK `HttpClient`（零新 HTTP 依赖），`redirect` 手动跟随。SSRF 三道：① URL 预检（仅 http/https、拒内嵌账密、长度 ≤2048）；② DNS 解析**全地址集**逐一公网判定，任一非公网整体拒绝（地址分类用 JDK 内建：回环/RFC1918/链路本地/组播/保留段全拒；**解析器做成可注入函数**供测试）；③ 重定向强制同源（scheme+host+port）且每跳重走①②，跳数上限 5。连接 pinning 不做，TOCTOU 窗口记 limitations。
4. **fetch 内容层**：jsoup 解析清洗（剔除 script/style/noscript/template/iframe/object/embed/hidden 类不可见节点）→ 自写轻量转换器输出 Markdown（标题/ul/ol/链接/围栏代码块/粗斜体；表格降级文本化）。深度护栏：DOM 嵌套 >512 返回固定占位符；转换异常同占位符。content-type：`text/*`、json、xml 透传；PDF/图片/二进制/未知 charset 拒绝（结构化错误，带补救指引）。charset 按 Content-Type 头解码，缺省 UTF-8。非 2xx 是正常结果：头行带状态码、错误页正文照常转换给出。
5. **fetch 返回形态**：`Fetched <最终URL> (HTTP <状态码>)` 头行 + 不可信数据声明 + Markdown 正文（+截断 footer）。三层限额：响应体 5MB（Content-Length 超限直接错误；流式超限截断置 truncated）、解码后 100k 字符、完整输出 200k 字符。
6. **search**：`WebSearchTool`（单 `query` 字符串参数）+ `TavilyProvider`（`POST {baseUrl}/search`，Bearer 鉴权）。归一化 `sources[] {url, title, snippet}`，工具层截断上限 8 条；空结果返回 "No results found."。返回形态：不可信声明 + `Sources:` 列表 + 引用指引。provider 接口按"一次调用返回归一化结果"设计，无流式、无 answer 字段（Tavily 形态），为 Brave/Perplexity 增量留位。
7. **key 解析链**：`config.search.apiKey` 字面量 → `TAVILY_API_KEY` 环境变量 → 皆空则 search 段视为未配置（不注册工具）。demo yml 只示范 env 方式。
8. **权限**：`WorkspacePolicy` 新增网络读类别（独立于本地读集合）：`read-only` 档 ask、`workspace-write`/`danger-full-access` 档放行；URL 外泄通道已知边界不设防（ADR 明示）。
9. **调度声明**：两工具 `isConcurrencySafe=true`、不豁免管线超时；fetch 自身超时 30s（`timeoutMs` 可配）在管线缺省 120s 内。
10. **配置面**（`WebToolsPlugin` config，非法值启动 FAILED 点名）：`timeoutMs=30000`、`maxResponseBytes=5000000`、`maxBodyChars=100000`、`maxOutputChars=200000`、`maxRedirects=5`、`userAgent`（缺省 `duo-harness/<版本>`）、`search.type=tavily`、`search.apiKey`、`search.apiKeyEnv=TAVILY_API_KEY`、`search.baseUrl`（缺省官方端点）、`search.maxResults=8`。
11. **机制零特化**：呈现走既有工具卡/叙述行；hooks 经既有管线自动覆盖；subagent 模板按工具名点名即可选装；无新会话事件词汇。

## Testing Decisions

- **好测试只测外部行为**：工具 `execute` 的返回文本与结构化结果、档位判定的结论、插件启动 FAILED 的点名——不测内部方法调用关系。
- **Seam 布局**（已确认，2026-09-20）：
  1. `ToolDefinition.execute` 直调——主战场，Mock HTTP Server（JDK HttpServer 随机端口，先例 `MockOpenAiServer`）供靶：正常页/重定向链/非 2xx/超限/二进制拒绝；
  2. SSRF 校验器——唯一新 seam：DNS 解析为构造注入函数，测试注入假 resolver 覆盖"多地址集一内一外整体拒绝""字面量内网 IP""重定向同源"等，零真网络零真 DNS；
  3. Markdown 转换器纯函数直测：映射、深度护栏、表格降级、失败占位；
  4. `WorkspacePolicy` 三档判定扩展（先例 `WorkspacePolicyTest`/`BashTierLinkageTest`）；
  5. 插件装配：config 非法值 FAILED、search 段空不注册、key 解析链两源；
  6. 工具目录对账测试（既有守护）自动覆盖新工具。
- **零真实外网**：全部 HTTP 走 mock，全部 DNS 走注入。

## Out of Scope

- Brave / Perplexity / DeepSeek provider（接口留位，增量实现）
- 连接 pinning / DNS rebinding 全量纵深（TOCTOU 记 limitations，M22 沙箱复审时再议）
- `queries[]` 多查询数组合并（DSH 形态；duo 用并发池替代）
- search 结果缓存、robots.txt 遵循、网页截图/渲染、PDF 内容提取
- 表格 GFM 全量保真（降级文本化）、nav/header/footer 的强化剔除（仅剔不可见节点）
- URL 外泄通道设防（已知边界不设防，ADR-0021 明示）
- Web 面鉴权与局域网暴露（维持 M8 既有挂账）

## Further Notes

- limitations 收口时新增三条：TOCTOU 重解析窗口、URL 外泄通道不设防、表格 Markdown 降级；CHANGELOG 同 diff 记账；`docs/05-参考/工具目录.md`、`插件配置参考.md`、`运行Demo.md` 同 diff 更新。
- Tavily 免费档约 1000 次/月，个人使用量级足够；耗尽时工具返回 provider 错误原文（限流 429 无特判）。
- 术语表已落"web 工具族域"四词条（web 工具族 / SSRF / 网络读 / fetch-only）。
