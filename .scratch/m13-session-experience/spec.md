# M13 Spec：会话体验——尾部窗口快照与分页范式

> ADR-0013（2026-09-15 grill 裁定，九问全裁定）为直接依据；术语沿用术语表"会话体验域"（尾部窗口快照、会话标题）。基线 0.7.0 已发布；目标版本 **0.8.0**（开工首日切分支）。

## Problem Statement

会话一旦变大（80K+ token），Web 体验三处退化：打开/刷新会话要**全量回放**——传输整个日志、前端渲染全部消息 DOM，首屏卡顿；切换或新建会话整页 `location.reload()`——白屏一闪、输入状态丢失；侧栏是一列裸会话 id（`20260915-174057-8567`），既分不清哪场是哪场，也看不出哪些正被终端占用（点了才弹撞锁报错）。

## Solution

三场景（首连 / 刷新 / 切换）统一改为**尾部窗口快照**：服务端投影后取尾部 50 条消息（映射为其事件区间）整窗下发，更早历史在滚动到顶时按页向上加载；切换/新建改为断开 SSE → 换绑 → 重连收快照，`location.reload()` 删除。治理阈值（spill/修剪/压缩比/窗口等）从类常量改为 web/cli 装配的可选 `governance` 配置段。侧栏升级为成品：会话标题（首条消息后 LLM 生成一次，失败降级首条截断）+ 被占会话灰显"使用中"。/compact 手动压缩出局（归 M16 命令注册表）。

## User Stories

1. As a Web 使用者, I want 打开或刷新大会话时只加载最近 50 条消息, so that 首屏秒级呈现而不是等全量回放
2. As a Web 使用者, I want 滚动到顶部自动加载更早的历史, so that 不离开当前位置就能继续回看
3. As a Web 使用者, I want 历史顶部看到"更早还有 N 条"的占位, so that 我知道历史没有丢、还剩多少
4. As a Web 使用者, I want 历史全部加载完后占位消失, so that 界面不残留无效提示
5. As a Web 使用者, I want 向上加载后视窗停在原来阅读的位置, so that 阅读不被跳屏打断
6. As a Web 使用者, I want 切换会话不整页重载, so that 切换瞬时完成、输入框内容与页面状态不丢
7. As a Web 使用者, I want 新建会话无刷新进入空态, so that 开新话题同样顺滑
8. As a Web 使用者, I want 断线重连仍只补增量事件, so that 网络抖动不会重复拉取历史（ADR-0010 语义保留）
9. As a Web 使用者, I want 切换回看过的会话仍从尾部快照开始, so that 行为一致可预期
10. As a Web 使用者, I want 历史分页里的工具卡与交互卡完整呈现, so that 旧对话可以原样回看
11. As a Web 使用者, I want 侧栏显示每场对话的标题, so that 一眼认出哪场是哪场
12. As a Web/CLI 使用者, I want 首条消息发出后自动生成会话标题, so that 不用手动命名
13. As a 使用者, I want LLM 不可用时标题退化为首条消息前 20 字, so that 侧栏在任何情况下可读
14. As a 使用者, I want 标题生成失败时静默（仅日志留痕）, so that 不被打扰
15. As a 使用者, I want 标题生成异步执行, so that 对话延迟不受影响
16. As a 多标签页使用者, I want 浏览器标签页显示会话标题, so that 多个 duo-harness 标签能分辨
17. As a Web 使用者, I want 侧栏把被占用的会话灰显并标"使用中", so that 不误点会撞锁的会话
18. As a Web 使用者, I want 点被占会话仍得到明确的占用报错, so that 语义与 0.7.0 一致（标注只提供预期，不禁用）
19. As a 部署者, I want 治理四阈值与折叠参数经 yml 配置, so that 不改代码就能调优治理行为
20. As a 部署者, I want 不配置 governance 段时行为与 0.7.0 完全一致, so that 升级零破坏
21. As a 部署者, I want CLI 装配同样支持 governance 段, so that 双入口治理行为一致可调
22. As a 部署者, I want 80K token 会话刷新在 2 秒内, so that 大会话长期可用（验收硬指标）
23. As a 框架维护者, I want 会话切换仍走换绑 + 独占锁语义, so that 无刷新化不破坏分脑保护

## Implementation Decisions

- **尾部快照协议（SSE 首连改造）**：快照模式首帧携带 `{mode: "tail-snapshot", hasMore, earlierCount}`；随后的事件帧从投影映射的事件下标 K 起发送——**分页单位（消息）是服务端投影语义，传输单位仍是事件帧，前端事件渲染器零改动**；游标续接语义不变（增量回放照旧）。首屏 50 / 每页 50 为常量。
- **历史分页端点**：按"某事件序号之前"取窗口（limit=50）→ 返回 `{events, hasMore, earlierCount}`；服务端每次全量投影确定消息边界（内存毫秒级），`events()` O(n) 优化（S4）明确留 M14。
- **无刷新切换/新建**：前端主动断开 SSE → 调既有换绑端点 → 重连 SSE 收尾部快照 → 整窗替换。会话变更回调重建 agent 的既有语义不动（换绑 + 独占锁）。
- **治理阈值配置**：`web` / `cli` 插件 config 新增可选 `governance` 段（spill 阈值 / 修剪阈值 / 压缩比例 / 窗口 tokens / 保留比 / 最小折叠数，字段可省）；装配器构建治理器时消费，缺段回退现内置常量（0.7.0 行为）。**双开装配两段配置漂移是已知取舍**（ADR-0013 记录），验收清单含一致性提醒。
- **会话标题**：会话事件词汇新增 `title` 类型（append 单写，投影 latest-wins）；标题生成器挂共享装配器（CLI/Web 同源）——首条 `user/message` 落日志后异步触发一次（in-flight 去重），独立直答请求（非流式、关 thinking、超时 + 输出上限），失败/超时降级首条消息前 20 字截断并同样落 `title` 事件；不重生成、不可改名。侧栏与 `document.title` 消费投影。
- **占用标注**：`/api/sessions` 逐会话 tryLock 探测，响应行新增 `occupied` 字段；前端灰显 + "使用中"角标，点击行为不变（撞锁报错保留）。
- **文档**：工具目录不动；limitations 新增三条候选（标题不演进不可改名 / 分页页长常量不可配 / 分页投影仍全量）；运行Demo 与插件配置参考同步 governance 段与新体验描述。

## Testing Decisions

- **只测外部行为**：HTTP 端点响应形态、SSE 帧序列、投影结果——不测内部投影实现细节；前端（app.js）不自动化，走验收的视觉确认。
- **复用既有 seam，不新增**：
  - **WebFace seam**（`WebFaceTest` 先例：真实 HttpServer + 请求断言）：首连快照头帧与事件起点、游标增量回放不回归、历史分页端点窗口与 hasMore、`/api/sessions` 的 `occupied` 字段、切换换绑语义保留
  - **session seam**（session 模块单测先例）：`title` 事件投影 latest-wins、既有投影不受影响
  - **标题生成器 seam**（mock `LlmAdapter` 先例）：成功生成、失败降级截断、in-flight 去重（多次首条消息只生成一次）
  - **治理配置 seam**（装配断言先例）：缺段回退缺省常量、配段后阈值生效
- **验收**：acceptance.md（里程碑级）——17+ 验收点含 80K 会话刷新 < 2s 实测、双开 governance 一致性提醒、无刷新切换视觉确认。

## Out of Scope

- /compact 手动压缩与"折叠持久化"语义（M16 命令注册表）
- 标题演进重生成、用户改名钉住（revision/supersede 机制后续按需）
- `events()` 全量拷贝优化（S4）、前端消息虚拟化（S5）
- 分页页长配置化；多标签页当前会话协调（M8 limitation 维持）
- 鉴权与局域网暴露；侧栏分页（会话列表本身的分页）

## Further Notes

- 理解关卡欠账（M3–M12）按路线图应在 `/implement` 前清零——开工前触发 duo-comprehension。
- DSH 参照：journal-stream 范式 + `session-title` 精查（`.scratch` 研查记录）；差异留档：DSH 的演进重生成/钉住不进本期。
- 风险：向上分页与治理折叠的游标对齐在**日志层不存在**（治理纯读侧、日志完整），但**投影边界**（消息 ↔ 事件区间映射）是本 spec 实现的核心正确性点，工单单列用例锁定。
