# 06: Web 鉴权令牌

## What to build

Web 启动生成随机 token（安全随机数）、控制台打印带 token 的 URL（`http://127.0.0.1:<port>/?token=<t>`）；浏览器首载后存 localStorage，后续 HTTP 头 + SSE query 携带；校验失败一律 403（fail-closed，覆盖静态资源与全部 /api 端点）；yml `web.auth: none` 显式关闭，关闭时启动横幅警示「鉴权已关闭」；token 进程生命周期一次一发、无过期轮换。

决策依据：ADR-0026 决策五；backlog「Web 鉴权令牌 + bind」M24 正式范围（ADR-0024）；术语表新增「鉴权令牌」词条。

## Blocked by

无（可立即开工）

## Status
in-progress

## Checklist
- [x] token 生成与带 token URL 打印
- [x] 全端点 fail-closed 403 / 带 token 放行（HTTP 头 + SSE query 双通道）
- [x] 前端 localStorage 持有与自动携带
- [x] yml 显式关闭开关 + 启动横幅警示
- [x] 测试（先例 WebFaceTest / BootTest）
- [ ] 工单级验收件：无 token 403 / 带 token 放行 / 关闭横幅演示，用户手动确认
- [x] CHANGELOG 记账（0.19.0 段）

## Comments
- 2026-09-23：实现与三轴审查完成（报告见下），全量 BUILD SUCCESS（858 用例 0 失败，2 既有 skip）。**待用户手动验收后转 done。**
- **首载自断阻断记档（三轴 Spec 轴抓出）**：link/script 子资源不继承父页查询参数——鉴权开启时 index.html 的 /web/* 子资源 URL 由服务端在响应时注入 token（正则改写，只发生在已过闸的响应上，token 不落模板文件）。修复过程中自测还抓到 quoteReplacement 把 $1 组引用转义成字面量的二次 bug（测试先行锁定）。
- **行为语义记档**：① 服务端重启换 token 后，旧标签页 localStorage 残留旧 token 会持续 403——自愈路径 = 从启动日志复制新 URL 重开（旧标签不自愈，记档不修）；② Web 卡片无法预知候选态的问题不存在于本单，token 校验对所有请求无差异 403，无信息泄露（空响应体）。
- **记档不修**：横幅明文 token 进终端日志/录制属设计取舍（URL 即入口，ADR 要可点）；app.js fetch 包装仅支持字符串 URL + 纯对象 headers（Headers 实例不展开——约定已注释固化，当前全库纯对象）；queryParam 不做 URL 解码（token 纯 hex 不受影响）；parseAuth 无独立单测（薄解析层，由装配测试与验收覆盖）。
- 模块/文档同 diff：插件配置参考 web 行补 auth 字段、术语表「入口栅栏」词条两级改三级、CHANGELOG 0.19.0 段记账。

## 审查报告（第 1 轮·三轴）：工单 06 全 diff（基点 f33381f 工作树，3 改 + 1 新增测试文件）

**覆盖**：10 文件 = 已审 10 + 跳过 0（覆盖率 100%）；行级轴名单 3 文件（WebFace/WebPlugin/app.js），测试/文档由 Standards/Spec 轴覆盖

### 阻断
- **Spec 轴 (a)1：静态子资源 403 首载自断**——index.html 以 link/script 引用 /web/*，子资源请求不带父页 query 也不走 fetch 包装，token 开启时全部 403、页面只剩裸 HTML（已修：handleIndexPage 响应时注入 token 到子资源 URL + 回归测试锁注入与放行）
- **Standards 硬违规：CHANGELOG 未记账**（红线 6，已修）
- **Standards 硬违规：插件配置参考 web 行缺 auth 字段**（红线 3，已修）

### 建议
- 行级轴 medium ×3：横幅明文 token 进终端日志（设计取舍记档）；localStorage 未包 try/catch（隐私模式白屏，已修降级内存态）；token 存后未清地址栏（复制/分享泄漏，已修 history.replaceState）；服务端换 token 后旧标签无自愈（记档——重开带 token URL 即可）
- 行级轴 low ×3：Headers 实例展开丢头（注释固化约定）、queryParam 不解码（token 纯 hex 不受影响）、errorFrom 绕行写法（同构先例）
- Standards 基线：13 参望远镜重载（仓库惯例延续，再加参转参数对象）
- 术语表「入口栅栏」两级→三级口径未同步（红线 3，已修）

### 测试覆盖
- 新增 WebFaceAuthTest 4 用例：无/错 token 全端点 403、头与查询双通道放行、auth 关闭放行、index 子资源注入断言
- 缺口：工单级验收件待用户手动确认（真实浏览器首载流程）

## Standards 轴原样分列

- 硬违规 2：红线 6（CHANGELOG）、红线 3（web 行 config 清单缺 auth）——均已修
- 核对通过：红线 2（token 运行时生成不落库）、SecureRandom/HexFormat、常量时间比对、403 空体无信息泄露、token 仅 [0-9a-f] 与 queryParam 不解码约束兼容
- 基线：fetch 全局包装副作用面（注释固化）、URL token 未清（已修）、13 参重载（惯例）、queryParam 复用（良好）

## Spec 轴原样分列

- (a)：静态子资源 403 自断（阻断，已修）、CHANGELOG 缺账（已修）、parseAuth 无测试（记档）、验收件待手动
- (b) Scope creep：无
- (c) 实现正确性：17 条 route 全经 entryGate 单一漏斗、localStorage + 头 + query 双通道、fail-closed 403、web.auth 开关 + 横幅、token 进程生命周期——全部对照通过；次要观察（重启换 token 旧标签 403 无前端自愈提示）记档

## 行级规则轴原样分列

- 存留 6 项：medium 3（横幅明文记档、localStorage try/catch 已修、replaceState 已修）、low 3（Headers 展开注释固化、queryParam 不解码记档、errorFrom 绕行同构先例）
- 安全核心面核对通过：MessageDigest.isEqual 常量时间、403 空体、hex token、双通道、无 innerHTML 注入面、无 Referer 外泄
- 已过滤误报 8 条（active.token 为子任务业务 token 非鉴权、无声明未用/拼写错误/监听泄漏等）

## 审查报告（第 2 轮）：修复复核

第 1 轮修复含阻断级新代码（index 注入）——新增回归测试锁定注入与放行，注入实现的首版 quoteReplacement 二次 bug 被该测试当场抓出修正（测试先行的价值实证）；其余为小改由全量回归锁定。按 SKILL 第 4 步未触发三轴复跑。
