# M21 验收件（用户手动运行验证）

> 前置：`~/.duo/config.yml` 已配 `llm:` 段（DeepSeek 等 OpenAI 兼容 provider）；验收视觉多模态需 `llm:` 段补 `vision: true` 且 `~/.duo/config.yml` 的 provider 支持图片输入（DeepSeek 文本模型只可验"闸门拒绝"路径）。`agent-demo.yml` 已含 `attachment` / `session-query` 行（M21 起），无需改配置。

## 汇总 Demo（交互 REPL，同时起 Web 面）

```bash
./mvnw -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

浏览器打开 http://127.0.0.1:18080（**首次请硬刷新 Cmd+Shift+R**，M21 改过前端静态资源）。逐条对照：

| # | 操作/输入 | 预期 | 对应工单 |
|---|---|---|---|
| 1 | Web 输入框拖拽或粘贴一张 png（`vision: true`） | 图片上传入库 → 消息渲染图片 → 模型以图片部件"看见"并回答 | 01/04/05 |
| 2 | `vision: false` 时重复上一条 | 上传/发送明确拒绝（409 "当前模型不支持图片"），零轮次浪费 | 05 |
| 3 | `用 read_image 看一下 <本地图片路径>` | `[调工具] read_image` → 图片入库返回引用 → 模型基于图回答；同图再读一次不重复占空间（SHA-256 去重） | 03/01 |
| 4 | `ls ~/.duo/attachments/v1/`（两次读图后） | 库内按内容寻址落盘（两字符前缀目录/文件名），0400 只读、无 GC | 01 |
| 5 | 输入框敲 `@` → 输 `pom` → `↓` `Enter` | 下拉弹出工作区候选（[目录]/[文件] 标记）→ 输入框插入 `@选中路径 `（目录带尾 `/`，可继续下钻） | 07 |
| 6 | 发送 `看看 @pom.xml 有哪些依赖管理` | 模型调 `read` 读文件后回答——@ 只是指路，内容永远经 read | 07 |
| 7 | 终端 `touch "my report draft.txt"` 后输 `@"my` 再输一次 | 第一次可能无匹配（触发重建），紧接着出现该文件（带外改文件可见性） | 07 |
| 8 | 侧栏搜索框输 `权限档` 回车 | 命中会话列表（标题/事件类型/带【】摘录）；点击命中切换会话；`×` 关闭；搜不到提示"无命中"且列表照常可用 | 08 |
| 9 | 对话问 `搜一下之前哪个会话讨论过附件库` | 模型调 `session_search` → 引用会话 id 与摘录回答 | 08 |
| 10 | 终端敲 `/export` | 当前目录生成 `duo-session-<id>.md`（角色/时间戳 + 工具摘要 + 附件清单）并回显路径 | 09 |
| 11 | 终端敲 `/export json` 后 `diff duo-session-<id>.jsonl ~/.duo/agent-sessions/<id>.jsonl` | 无差异（逐行等价；导出后没继续聊的前提下） | 09 |
| 12 | Web 敲 `/export` | 浏览器自动下载 `duo-session-<id>.md`；对话区命令行 + 结果 URL 照常呈现 | 09 |
| 13 | `/export xml`（两面各一次） | 点名 `[/export 错误] 未知格式: "xml"`，不产生文件 | 09 |
| 14 | `vision: false` 时问 `看一下 xxx.png` | read_image 执行前即拒（"当前模型不支持图片"）——纯文本部署零感知 | 05 |

## 自动化佐证（agent 侧已跑）

- 全量套件 BUILD SUCCESS（`./mvnw test`）；M21 新增/ touched 模块计数：
  - `duo-harness-attachment`：AttachmentStoreTest 等 12 用例（准入校验/规范化字节寻址/硬链接去重/0400/变体确定性）
  - `duo-harness-session-query`：39 用例——分词 7 / 倒排索引与防护矩阵 25 / 工具与插件装配 7
  - `duo-harness-agent`：fileref 纯函数与防护矩阵 6 + 补全服务 7 + @file 指南注入 2 + /export 命令 6 等
  - `duo-harness-web`：附件端点 6 + 会话检索端点 3 + @ 补全端点 3 + /export 下载流 3 等
  - `duo-harness-cli`：CliPluginTest 14（含 /export 注册序清单）
  - `duo-harness-example`：工具目录对账（ToolCatalogTest）12 个注册工具含 session_search，名称与描述全量一致
- 隔离实例浏览器实测：@ 下拉触发/选中/定位、检索命中/切换/空态、/export 命令分发 → SSE done 事件 → 自动下载触发均通过（含 DOM 级断言）

## 已知边界（验收时不必惊讶）

- **read_image 读 ~/Desktop、~/Documents、~/Downloads 等目录会报 "Operation not permitted"**——macOS TCC 隐私保护拦截（启动 harness 的终端/IDE 未获"桌面/文稿"访问权）。解法：系统设置 → 隐私与安全性 → 给终端/IDE 授予完全磁盘访问；或把图片放工作区/ /tmp 等非保护目录。与 duo-harness 代码无关（任何进程读这些目录都一样）

- `imageDelivery: files` 未接线——图片投递只有 inline base64（`FilesApiUploader` 客户端已就位，集成属后续；limitations M21 #3）
- 会话检索是内存倒排索引（个人会话量级）；中文按单字 AND，跨字噪音命中可能——连续原词命中会排最前（limitations M21 #1）
- 补全/检索均懒构建：首次使用才扫；带外新建文件的第一次查询可能 miss（触发重建），紧接着可见
- `vision: false`（缺省）部署：上传、read_image 全被闸门拒绝——这是设计，非故障
- 子代理会话（subagents/ 子目录）不索引、不出现在检索命中——设计（ADR-0022 决策 8）
