# 06: 深链 duo://open——外部唤起主窗

## What to build

`duo://` 协议注册与最小路由（ADR-0039 Q11 仅 `duo://open`，路由面留白）：壳启动注册默认协议（macOS）；两条到达路径都收——本实例运行中经 `open-url` 事件、壳未运行时经系统拉起新实例后 `second-instance` 转发——统一为唤起/聚焦主窗；未知 path 静默忽略不报错（路由面留白语义）。壳二次启动本身不重复拉起后端（单实例语义，后端归属首实例）。

端到端可验：应用运行中浏览器打开 `duo://open` → 主窗聚焦；应用未运行时打开 → 应用拉起且主窗就绪；再次双击应用图标不产生第二实例。

## Blocked by

02

## Status

removed（2026-10-05 用户裁定：双击即用打包路线取消，深链功能移除；**单实例锁保留于壳**（防重复拉起后端），二次启动聚焦保留）；实现期真机两项实证（单实例/运行中唤起）与「未运行拉起」打包态验证项随路线取消一并终结。移除 diff 见深链移除提交

## Checklist

- [x] 协议注册（打包态 `protocols` 写 Info.plist 持久；dev 态运行时 setAsDefaultProtocolClient 带 execPath+appdir——见 Comments 形态记档）
- [x] `open-url` / `second-instance` 两路统一聚焦；单实例锁防重复拉起后端
- [x] 未知 path 静默忽略（路由面留白）
- [x] S3 缝扩展：判路与 argv 提取纯函数单测（deep-link 8 例）；**两路接线与单实例语义以 smoke 断言程序化替代单测**（S3「不锁 Electron API 细节」口径，见 Comments）
- [x] 真机验证：运行中唤起 ✓（open-url/argv 转发双路实证，聚焦断言过）；二次启动不重复 ✓（即退 + 后端不重复）；**未运行拉起转 08 打包态**（dev LS 注册形态不可靠，记档）

## Comments

- **quit-before-ready 实测缺陷（exit(0) 依据）**：`app.quit()` 在 ready 前调用会被 Electron 丢弃——首跑冒烟逮出：二实例带全量启动链驻留（自己拉起了后端 port 59764）。修复：`!gotLock` 分支改 `app.exit(0)`（立即无条件；二实例无可清理对象）+ whenReady 守卫兜底不拉后端。此 Electron 行为值得记档（官方文档单实例模式示例用 app.quit——在含 before-quit preventDefault 编排的壳里不可靠）。
- **协议注册双形态记档**：打包态 electron-builder `protocols` 键写 Info.plist（持久，随 app 安装）；dev 态 `setAsDefaultProtocolClient('duo', execPath, [appdir])` 运行时注册——LS 拉起时 URL 经 argv 转发首实例（second-instance argv 提取）。dev 注册**系统级持久残留**：指向 dev electron 路径，工作区删除后 LS 有死链（`removeAsDefaultProtocolClient` 可清，未自动清——dev 机可接受，记档）。
- **越票面记档**：`second-instance` 无深链时也聚焦（「无深链的二次启动：聚焦即最小响应」——票面只写深链两路，此为自加最小响应，safe-wrapped）。
- **真机验证证据**：smoke 断言三项全过——单实例锁（二实例即退 `secondExited=true`）、运行中唤起（`lastDeepLink==='duo://open'`，open-url/argv 转发双路）、聚焦（visible=true）；无 java 孤儿。**未运行拉起**：dev 形态 LS 注册不可靠（execPath 指向 node_modules），转 08 打包态以 Info.plist 验证——**工单 08 需承接此验证项**。
- **验证**：tsc strict 绿；vitest 48 例全绿（deep-link 8：判路四态/host 大小写/argv 提取双态）；真机冒烟 10s 收口全断言；无 java 孤儿；机械自检两 grep 干净。

## 审查轮（2026-10-04 · 双轴（Standards/Spec）+ 行级/Java 轴豁免（零 Java diff，沿 03/05 口径））

**覆盖**：deep-link.ts/deep-link.test.ts 新增 + main.ts diff + electron-builder.yml = 100%

### 阻断（已修复）
- **[Standards 阻断] second-instance 无深链分支缺窗守卫**：ready 后窗建前（JVM 拉起数秒窗口）二次启动 → showMainWindow → createWindow throw（后端未就绪）→ 回调无 safely → 壳崩。修复：safely 兜底（与菜单/托盘回调同口径）。

### 建议（处置：已修 3 / 记档 5）
- **[Standards P3·已修] 双描述漂移第 3 犯**：文件头 --smoke 序列与工单清单滞后——头注释重写为模块导览（02-06 编排模块索引 + 序列并入），消除复制面。
- **[Standards P3·已修] deep-link 两导出常量零外部消费方**——收窄为模块内。
- **[Standards P3·已修] exit(0) 注释与现状分层**：实测观察属无守卫时代代码态，注释改写为「quit 被丢→守卫兜底不拉后端→退出本身仍须 exit」三层如实。
- **[Spec K/J·已修] 票面/spec 记档缺口**——本节即补；spec Further Notes 06 条目随提交落。
- **[Spec B·记档] dev 态 LS 注册系统级残留**：dev 机可接受，无自动清理。
- **[Spec D·记档] second-instance 无 URL 聚焦越票面**：自加最小响应，合理。
- **[Spec H·记档] S3「两路接线与单实例语义」以 smoke 程序化替代单测**：S3 口径内。
- **[Standards P3·记档] smokeSequence 逼近拆分临界**（7 段 42 行）+ argv[1] 非呃形态防御 + exit code 断言——观察项，08 期一并整理。

### 测试覆盖
- deep-link 8 例（判路：open/未知 path/非 scheme/无 authority/大小写；argv：末个提取/双态）——纯函数全锁；两路接线与单实例以 smoke 断言程序化替代（口径内）；缺口：未运行拉起（08 打包态）。

**测试收口**：审查修复后 tsc 绿、vitest 48 例绿、冒烟全断言过（exit 0）。双轴报告全文见本轮对话记录，本节为合并处置版。
