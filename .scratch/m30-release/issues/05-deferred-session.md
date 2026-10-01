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
done（2026-10-01 用户验收通过——浏览器端到端全过：新建零落盘零侧栏条目、发消息物化入列、合成条目经用户裁定移除改顶层 currentId/currentTitle）

## Checklist
- [x] 排查 0 字节文件产生点（createFile 与写头分离路径——Session.create 两段式即根源，javadoc 记档）
- [x] Session 增持久化状态标记（deferred → persisted），落盘推迟到首个 appendEvent（createDeferred 零文件系统触碰；persist 物化分支成功后置位）
- [x] WebFace/WebTabs/`/api/session/new`/DuoMain 四处创建点核对不落盘（WebPlugin 启动自建 + onNewSession 回调两处、CLI 启动两处 + /new 一处全切 createDeferred）
- [x] CLI 创建路径核对（CliPlugin 三处同切；headless/子代理/演示入口保留 create——边界记档）
- [x] /api/sessions 防御过滤（落 Session.list 单点——hasAnyEventLine 头-only 过滤，web 侧栏/CLI 续接/检索索引三消费方同源；deferred 当前会话不进列表，身份走顶层字段——用户裁定）
- [x] 边界：SSE 收流（deferred 期订阅合法）/ tabId 重绑（内存态零改动）/ 投影检索跳过（投影天然空 + FtsSessionIndex 零事件跳过）/ 版本头迁移无负担（物化即写 CURRENT 头）
- [x] 测试：新标签零文件断言、首消息头+事件同批、crash 语义（SessionDeferredTest 7 用例含物化失败重试）
- [x] 浏览器端到端：开页→侧栏无新空会话；发首条消息→会话出现且有完整内容（2026-10-01 用户验收，含新建只切空态页不进侧栏的最终形态）

## Comments

### 实现记录（2026-10-01）

- **createDeferred 工厂**（Session）：纯内存对象——不建目录不落文件不取锁；jsonl 预计算路径、cwd 保留字段、SSE 订阅合法；close 幂等（remove no-op + null 安全）
- **物化**（persist 锁内 ensureLocked + 同批写）：CREATE 原子建文件 → 取锁进注册表（含 HELD_LOCKS 预检防同进程碰撞路径重引 POSIX 陷阱）→ 版本头与首事件**拼接同字节序列一次写循环一次 force**（无头-only 窗口）→ 成功后置 persisted/formatVersion；重试幂等（半途已取锁跳过）+ truncate 归零防「残留+重复头」损坏；取锁后复查 closed 防 close 竞态锁泄漏
- **防御过滤**（Session.list/latest 共用 hasAnyEventLine）：0 字节 + 头-only 跳过；持锁会话经 heldSession 内存直取判定**绝不开 fd**（POSIX 释放陷阱，titleOf C2-02 同款防御）；检索索引（FtsSessionIndex）同口径跳过 + 陈旧条目摘除
- **合成条目**（sessionsJson）：deferred 当前会话以合成条目入侧栏（工单 L8「无文件即无侧栏条目」的字面延伸——当前项呈现由合成承担，文件列表天然干净；ZCode 对照系 draft 同语义）；occupied=true/lastModifiedMs=now 为呈现占位（注释记档）
- **边界核查**：markStart 纯内存零 append（不会提前物化）；headless（必有任务）/ 子代理（spawn 即写）/ 演示入口（非发布链路）保留 create——工单范围外记档

### 审查轮（2026-10-01·四轴）

**覆盖**：主代码 5 文件（Session/WebPlugin/CliPlugin/WebSessionEndpoints/FtsSessionIndex）行级 100% + 测试类 3 个（Standards/Spec 轴）+ Java 规范轴 8 文件 ≈200 行

**阻断**：无

**建议（已修复 10 项）**：
- **POSIX 释放陷阱复发**（Standards P1）：hasAnyEventLine 开 fd 读含持锁文件——heldSession 内存直取短路（零 fd）
- **persisted 前置位**（Spec）：物化后写失败重试走无头路径——ensureLocked 拆分 + 成功后置位
- **close 竞态锁泄漏**（行级 medium）：close 与首次物化交错 → 已闭实例永久持锁——取锁后复查 closed 回滚
- **物化重试损坏文件**（行级 low）：写失败残留 + 重试追加 = 重复头——truncate 归零
- **FTS 索引未跳过零事件会话**（Spec 实质缺口，工单 L10 明文）——过滤 + 陈旧条目摘除
- **ensureLocked 丢 HELD_LOCKS 预检**（Standards P2）：碰撞路径 closeQuietly 重引陷阱——预检恢复
- **CHANGELOG 未记账**（Standards P1，红线 6）+ **术语表词条未落**（Standards P2，spec.md:60 明令）——均为实现期遗漏，补 Changed 条目 + deferred 会话/空会话词条
- **COM-07 文案漂移**（Java 规范 CRITICAL）：list/latest 的 catch 注释与异常文案未跟上内容读取守卫
- **缩进错乱 + Javadoc 漂移**（Standards P3）+ **WebPlugin 注释与合成条目相左**（行级）
- **assertFalse 可读性**（Java 规范建议，4 处）

**记档（不修，附理由）**：
- hasAnyEventLine fd 开关与 LOCK_GATE 间隙竞态（行级 low）：与既有 titleOf/latestEventTextFromDisk 同款已接受残余风险，窗口极窄，根治需结构性改动（扫描 fd 纳入 LOCK_GATE 或无 fd 判读），不入本票
- ~~合成条目 scope creep~~ **已由用户验收裁定闭环（2026-10-01）**：否决合成条目——deferred 当前会话**不进侧栏列表**（列表只含真实会话，ZCode draft 同语义），当前会话身份改经 /api/sessions 顶层 `currentId`/`currentTitle`（缺省「新会话」）供前端标签态；发首条消息物化后自然入列（标题生成前的瞬态显示 id 为既有 M13-06 回退行为）。WebSessionEndpoints 删合成分支 + app.js refreshSessions 取值改顶层 + WebFaceTabTest currentIdOf 读顶层——web 回归 63/63 绿
- headless/子代理/演示入口保留 create：必有事件或非发布链路，deferred 无收益
- createDeferred(Path) 单参仅测试消费：与 create 对称 API 面保留

**测试覆盖**：SessionDeferredTest 7 用例（零文件/同批写/重试幂等/列表过滤/共存/失败重试）；WebFaceTest/WebFaceTabTest 夹具按新语义修正（56+7 绿）；全量 verify 终态 BUILD SUCCESS；合成条目分支由 unknownTabIdLazilyCreatesOwnSessionDefaultUntouched 隐式覆盖（tabA 懒建 deferred → currentIdOf 断言）
