# M21 spec：输入面与会话工具（0.16.0）

> 状态：已立项（2026-09-20 grill 六问裁定，[ADR-0022](../../docs/adr/0022-输入面与会话工具.md)；事实基础 = [DSH 输入面与会话工具研究三件套](../../docs/research/DSH/输入面与会话工具/架构设计.md) 锚点 ddefc45f）。本文是实现的唯一依据；工单见同目录 `issues/`。

## Problem Statement

duo 的输入面只有键盘文本：图片进不来（截图要用户自己转格式想办别的办法）、文件只能靠模型猜路径；会话历史只有侧栏列表——"上次讨论过 X"这类检索与把对话带走的导出都不存在。对照系 DSH：附件内容寻址库 + read_image 多模态是默认启用能力，@file 路径提常有补全索引，会话检索/导出齐备（尽管其检索是双层深 opt-in）。

## Solution

- **附件**：图片经 Web 拖拽/粘贴或模型 read_image 进入 `~/.duo/attachments/v1` 内容寻址库（SHA-256 硬链接去重、只读、永不自动删除）；会话日志只存引用块；消息多部件化让视觉模型"看见"图片。
- **@file**：输入框 `@` 补全工作区路径，`@path` 作为提及文本进消息，模型经 read 工具自取内容（提及模式，零内容注入）。
- **会话查询/导出**：`session_search` 工具让模型搜历史，Web 侧栏搜索框让人搜历史（内存倒排索引，后端无关）；`/export [markdown|json]` 把当前会话带走。

## User Stories

1. As a 用户, I want 在 Web 输入框拖拽/粘贴图片, so that 截图直接给模型看而不用手动转格式
2. As an agent, I want 收到带图消息后"看见"图片内容, so that 基于视觉信息回答而非空猜
3. As a 部署者, I want 附件以内容寻址落 `~/.duo/attachments` 且永不自动删除, so that 历史消息的图片引用永远可解析、磁盘同图不重复
4. As an agent, I want read_image 读本地图片文件, so that 用户给路径即可让我看图
5. As a 部署者, I want 非视觉模型部署（vision 缺省 false）收图即拒、read_image 执行前即拒, so that 纯文本部署零感知、不浪费任何轮次
6. As a 用户, I want Web 面把消息中的图片渲染出来, so that 确认模型看到的和我发的一致
7. As an agent, I want 同一张图重复读取存储层去重, so that 磁盘不因重复读膨胀
8. As a 部署者, I want 格式白名单 + 魔数嗅探 + 大小/像素上限多重校验, so that 伪格式与巨图在入口即被拦
9. As a 部署者, I want 图片入库前规范化（长边受限重编码）, so that 巨图/异格式统一为字节预算内的规范对象
10. As a 部署者, I want 请求前按模型目标尺寸确定性缩放并缓存变体, so that 省 token 且同图同变体零重复计算
11. As a 部署者, I want `imageDelivery: files` 时上传换 file_id, so that 大图传输更高效
12. As a 部署者, I want Files 链路带本地索引去重、配额回收、失效重传、失败回退 inline, so that 投递优化自愈不添堵
13. As a 用户, I want Web 输入框 `@` 补全工作区路径（目录下钻、引号路径）, so that 快速引用文件不用手打全路径
14. As an agent, I want @file 指南约束"未 read 不得声称已看过", so that 我不会凭空声称读过某文件
15. As a 用户, I want CLI 直接打 `@路径` 文本, so that 终端同样能用引用语法（无补全 UI 也不碍事）
16. As a 部署者, I want 补全索引排除 .git/node_modules/target 等目录, so that 候选干净、索引快
17. As an agent, I want session_search 按关键词搜历史会话, so that 找到"上次讨论过 X"的会话与最强匹配出处
18. As a 用户, I want Web 侧栏搜索框按内容找会话并点击打开, so that 不用逐个翻标题
19. As a 用户, I want `/export markdown` 得到人读对话记录（角色/时间戳 + 工具摘要 + 附件引用清单）, so that 分享与归档
20. As a 用户, I want `/export json` 得到会话 JSONL 原样副本, so that 完备归档与程序化处理
21. As a 部署者, I want session-query 插件行 opt-in、索引懒构建 + mtime 增量, so that 不装零感知、装了启动也零成本
22. As a 部署者, I want 检索服务接口后端无关, so that 后期内存倒排转 SQLite 时上层零改动
23. As a 子代理使用者, I want 附件引用块不进子代理上下文, so that 子代理轻装且无图片语义负担
24. As a 部署者, I want read_image 归本地读类三档放行, so that read-only 档也能读图（本地读语义一致）
25. As a 用户, I want 授权读取端点验证"该会话日志确实引用了此附件", so that 伪造 attachmentId 探测不到任何东西
26. As a 部署者, I want reasoning 等内部内容不入检索索引, so that 检索命中都是真实对话与工具事实

## Implementation Decisions

（全部承 ADR-0022；模块内文件布局由实现定）

1. **模块落位**：新模块 `duo-harness-attachment`（附件库 + 规范化/变体管线，被 tools 与 web 双向消费）；新模块 `duo-harness-session-query`（索引 + 检索服务 + 导出渲染，消费 session 文件）；read_image 落 tools 模块 `fs` 子包（依赖 attachment 模块）；@file 补全索引与指南落 agent 模块 `fileref` 子包（web 消费）。
2. **附件库**：`~/.duo/attachments/v1`；SHA-256 内容寻址、tmp fsync → 硬链接发布（EEXIST 比对去重）→ 0400 只读；无 GC。会话日志只存附件引用块（向前兼容新块类型），字节永不进日志；Web 渲染走授权读取端点。
3. **存储规范化**：解码（TwelveMonkeys：JPEG 强化解码 + WebP）→ 格式/像素/边长校验 → 长边受限重编码（8-bit sRGB、字节预算内）；格式白名单 png/jpeg/gif/webp + 魔数嗅探 + 声明与解码比对三重验证。
4. **请求变体**：variantId = sha256(attachmentId + 目标宽高/字节预算 + 编码版本)；Thumbnailator 长边缩放 + 字节预算适配；缓存 `~/.duo/cache/attachments/`；同变体并发单飞。
5. **Files API**（`imageDelivery: files`）：`POST {base}/files` 上传换 file_id；本地索引文件去重（键 = scope+variantId）；配额满回收最旧自有文件后重试；file_id 失效重传一次；上传失败整体回退 inline。仅 DeepSeek 形态端点支持（inline 为通用缺省）。
6. **视觉闸门**：`llm.vision`（缺省 false）——Web 收图即拒、read_image 执行前即拒（零 I/O）。
7. **llm 多部件**：消息 content 扩展为 text + image（base64 data URI，OpenAI 兼容形态）多部件；vision=false 时多部件不可达。
8. **@file 提及模式**：grammar（@ 前行首/空白；引号路径 `@"..."`；控制字符拒绝）+ 工作区路径索引（maxEntries 截断、排除目录清单、目录 symlink 不跟随、越界返空、子树不可读贡献 0 候选）+ 指南片段（仅 read 工具在册时注入 prompt 注册表）。
9. **会话检索**：`session_query` 插件行 opt-in；内存倒排索引（分词 AND + snippet，懒构建 + mtime 增量）；索引内容对齐 DSH 清单（消息文本/tool 调用名+参数/tool 结果/todo/turn 错误；reasoning 不入）；`session_search` 单工具（query → 命中会话 + 最强匹配事件 + snippet）；Web 侧栏搜索框；子代理会话不索引。服务接口后端无关（后期转 SQLite 承诺）。
10. **会话导出**：`/export [markdown|json]`（缺省 markdown）注册进命令注册表（双面、busySafe、审计白得）；读前 flush 持久化屏障、fail-loud；只导当前会话；CLI 写盘 cwd（`duo-session-<id>.md/.jsonl`）、Web 走下载流；markdown 含角色/时间戳头部、工具摘要行、尾部附件引用清单。
11. **横切**：read_image 归本地读类（三档放行）；附件库写不受三档管辖（harness 自身数据目录）；子代理上下文过滤附件引用块（框架过滤）；图片一期不计入治理 token 估算（真实 usage 侧自然覆盖）。
12. **配置面**：`llm.vision`（缺省 false）、`llm.imageDelivery: inline|files`（缺省 inline）；附件段（imageBytes 20MiB、imagesPerMessage 20、messageImageBytes 100MiB 等对齐 DSH 量级）；非法值启动即 FAILED 点名（parsePageSize 同纪律）。

## Testing Decisions

- **好测试只测外部行为**：存储产物与去重结果、端点状态码与授权拒绝、检索命中与 snippet、导出文件内容等价性——不复述内部实现。
- **Seam 布局**（已确认，2026-09-20）：
  1. `AttachmentStore` 直调——主战场：准入校验/规范化字节寻址/硬链接去重/0400/变体确定性（`@TempDir` + 代码生成测试图，零真实图片资源）；
  2. `read_image` execute 直调——能力闸门/魔数嗅探/先持久化再返回/三档判定；
  3. WebFace HTTP 级——上传端点、授权读取端点（伪造引用拒）、/export 下载流；
  4. `@file` grammar + 索引纯函数——token 识别/mention 格式化/越界与 symlink 防护/排除目录；
  5. `SessionQueryService`——JSONL 夹具检索（短语命中/snippet/懒构建/mtime 增量/reasoning 不入索引）；
  6. 导出渲染——markdown 快照 / JSONL 原样副本等价 / flush 屏障；
  7. 工具目录对账 + 装配（新工具进文档、新插件行装配）。
- **零真实外网、零真实图片资源**：测试图全部代码生成（TwelveMonkeys/Thumbnailator 编码）。

## Out of Scope

- 计费口径统计（backlog 维持）；ZIP 全打包导出（附件引用清单 + 库内永不删除已覆盖信息）；@session 跨会话引用；CLI `@` 补全 UI；子代理附件传递；图片 OCR/转文字；附件库 GC/retention（永不删除是设计）；Files API 非 DeepSeek 形态端点适配；请求变体的模型目录级目标尺寸（一期统一目标，接口预留）。

## Further Notes

- limitations 收口新增三条：内存倒排索引量级边界（后期转 SQLite，接口已后端无关）、治理 token 估算不含图片（真实 usage 侧覆盖）、Files API 仅 DeepSeek 形态端点（inline 通用兜底）；CHANGELOG 同 diff 记账。
- 新依赖（grill A1 已获批）：TwelveMonkeys ImageIO（imageio-jpeg/imageio-webp）+ Thumbnailator；不引入 SQLite。
- 文档同 diff：工具目录（read_image/session_search）、插件配置参考（附件段/会话检索段/llm 新字段）、运行 Demo、demo yml；config.mts 已含 ADR-0022 条目。
- 术语表已落"附件与会话工具域"四词条（附件库/附件引用块/提及模式/会话检索）。
