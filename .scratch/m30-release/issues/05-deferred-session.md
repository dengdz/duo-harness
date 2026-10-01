# 01: 会话持久化 defer 化——创建与落盘解耦（ZCode deferred/draft 对齐）

## What to build
对齐 ZCode 的 deferred/draft 三层防线（研究锚点：docs/research/ZCode/Agent循环与会话/会话事件模型与持久化.md「增量补录 · 新会话 deferred/draft 三层防线」2026-10-01，锚点 29628c9），消灭「页面加载/服务端重启即产生空会话文件」：

1. **创建仅内存态**：WebFace/WebTabs 标签懒创建、`/api/session/new`、DuoMain 启动自建——只建内存 Session 对象，**不落任何文件**。排查清 0 字节文件的产生点（存在「先 createFile 后写头」的路径，与版本头写入分离所致）。
2. **首条真实事件才落盘**：SessionStore 写版本头的时机推迟到首个 `appendEvent`（user/message、permission/mode 等任意真实事件）——版本头与事件同批写，消除 0 字节窗口；crash 前无事件 = 无文件 = 重启消失（ZCode「draft 重启即消失」同语义）。
3. **列表天然干净 + 防御兜底**：deferred 后无文件即无侧栏条目（文件枚举数据源天然过滤）；`/api/sessions` 补一层零事件过滤防御。
4. **CLI 呈现位同口径核查**：工具表/会话表呈现位间共享的教训（M29 工单 12 present 单边摘除漏 CliPlugin）——CLI 的会话创建路径一并核对。
5. **边界确认**：deferred 会话的 SSE 绑定与输入接收（首条消息到达前页面要能正常收发）；tabId 重绑语义不变；投影/检索索引跳过零事件会话；会话文件版本头迁移链无新增负担（无头文件 = v0 语义，本就兼容）。

## Why
M29 工单 12 验收期实测：页面加载与服务端重启即懒创建会话文件，2 天产生 370 个空会话文件（252 个 79B 版本头 + 118 个 0 字节）全部进侧栏。ZCode 对照（源码实读）：创建与持久化解耦——draft 纯内存不落盘、重启即消失、列表三层过滤，空会话既不堆积也不可见。

## Blocked by
无。研究底座已备（会话事件模型与持久化.md 增量段）。

## Impact 面预估
- duo-harness-session（SessionStore 写头时机 + Session 对象生命周期标记）
- duo-harness-web（WebFace/WebTabs 懒创建、/api/sessions 过滤）
- duo-harness-cli（会话创建路径核查）
- duo-harness-example（DuoMain 启动自建）
- 测试：空会话零文件断言（新标签打开 → 无新文件；首条消息 → 文件出现且头+事件同批）、SSE 收流、crash 语义

## Status
ready-for-agent（2026-10-01 用户裁定：归属 **M30（0.26.0）发布产物**期；同日 M30 启动 grill 纳入范围并迁入本统一目录——原路径 `.scratch/m30-session-lifecycle/issues/01-deferred-session.md`，编号改 05，见 ADR-0032 工单组织节）

## Checklist
- [ ] 排查 0 字节文件产生点（createFile 与写头分离路径）
- [ ] Session 增加持久化状态标记（deferred → persisted），落盘推迟到首个 appendEvent
- [ ] WebFace/WebTabs/`/api/session/new`/DuoMain 四处创建点核对不落盘
- [ ] CLI 创建路径核对
- [ ] /api/sessions 防御过滤
- [ ] 边界：SSE 收流 / tabId 重绑 / 投影检索跳过 / 版本头迁移无负担
- [ ] 测试：新标签零文件断言、首消息头+事件同批、crash 语义
- [ ] 浏览器端到端：开页→侧栏无新空会话；发首条消息→会话出现且有完整内容
