# 05: 系统通知管线——前端 Web Notification 路径

## What to build

三类事件的到达通道（ADR-0039 Q10 三项全要；spec 落钉为前端 Web Notification API 路径）：渲染层在 turn 完成、审批/提问卡等待人答、执行出错三个时点发 Web Notification（错误附一句摘要）——Electron 将其透传到 macOS 通知中心；渲染层以 `document.visibilityState` 门控（窗口聚焦中不扰）；通知点击 → 聚焦主窗（壳侧承接 click 事件转发）。改动面：duo-harness-web 前端（渲染层已知事件处加通知调用）+ 壳侧点击承接，后端零改动。若 Electron 通知权限/平台行为有出入，壳侧订阅兜底为备选开关（工单期验证后定稿，不回 ADR）。

端到端可验：发长任务切到别的应用 → 审批等待时收到通知 → 点通知主窗聚焦并看到审批卡。

## Blocked by

02

## Status

done（2026-10-05 验收通过，交付终态 = 功能移除〔用户裁定〕——实现与两轮修复在案，投递层重启条件需重新立项）

## Checklist

- [x] 渲染层三类事件通知调用（含可见态门控、错误摘要文案——事件点实为五个，映射见 Comments）
- [x] 壳侧通知点击 → 聚焦主窗承接（preload 桥 → IPC → showMainWindow）
- [x] Electron 通知平台行为验证（permission granted 实测；**发现 visibilityState 失真缺陷**——门控源改主进程 isVisible，兜底开关未启用原因记档）
- [x] S3 缝扩展：通知门控单测（notify-gate 4 例三态矩阵：可见不扰/hidden+granted 发/未授不发/未知态 fail-closed）
- [x] 真机验证：门控双态 + 真通知发射（smoke 断言 + permission 钉死）；三类真人触发始终未在用户侧复现——**功能经用户裁定移除（2026-10-05）**，重启条件见 Status

## Comments

- **移除裁定（2026-10-05）**：聚焦门控修复（BUG-20261005-01）后用户重验仍无通知——投递层（疑似未签名应用 macOS TCC 授权）未及深挖，用户裁定砍除。移除面：渲染层五事件点与助手函数（app.js 回退至 e95294dc 形态）、notify-gate/preload/测试三文件删除、main.ts 桥接线与 smoke 通知断言移除、esbuild preload 打包步移除。保留面：无（桥零方法，preload 整文件退役）。重启设计要点（1.4.0）：签名/公证与 TCC 授权先行、门控判聚焦（isFocused）、诊断不再静默吞。

- **门控可见态源偏离记档（票面明写 visibilityState 门控）**：Electron（macOS）实测 `win.hide()` 后渲染层 `document.visibilityState` 仍为 visible（诊断脚本三态实证，首跑冒烟断言失败逮出）——渲染层可见信号不可靠。处置：门控**语义**不变（notify-gate.ts 纯函数保留 visibilityState 形参，S3 4 例锁定），**可见态源**改主进程 `win.isVisible() && !isMinimized()`（preload 经 sendSync 取）。spec 通知管线定稿节已记档；票面预设「壳侧订阅兜底开关」未启用——实测出入是可见态信号而非通知透传，第三路径为主路径修源，无需兜底。
- **事件点五而非三（Q10 映射）**：审批等待（approval/requested）+ 提问等待（question/requested）= Q10「审批/提问等待」一类；turn 完成（assistant/message 实时帧）=「turn 完成」；执行出错（run/error）+ 任务已中断（assistant/interrupted）=「错误中断」一类。全部 `!replaying` 门控（历史重放不扰）。
- **计划复核通知去重早退修复（审查实锤）**：approval/requested 分支的 exit_plan_mode 卡片去重 `return` 原在通知调用之前——同会话主路径（tool/call 先渲染计划卡）下「计划等待复核」通知永不可达。改形：去重只约束卡片渲染，通知放行。
- **preload 打包形态（记档）**：sandboxed preload 不可 require 本地文件——esbuild 单文件打包（新 devDependency，ADR-0039 Q2 Node 工具链伞内：vitest 传递依赖本就在 lockfile，升直+作 build 承重步）；tsc 参与 preload 类型检查但产物不可运行（tsconfig 注释声明顺序依赖，构建必须走 npm run build）。
- **真机证据**：smoke 10s 收口全断言过——桥在场 / visible 态门控 false（聚焦中不扰）/ 隐藏态门控 true（放行）/ permission=granted 显式钉死 / 隐藏态真通知发射（落 macOS 通知中心，点击回主窗走 focusWindow IPC）/ 无 java 孤儿。**真人项**：三类通知的真人触发 + 通知点击聚焦（LLM 依赖 + mac 交互面）——与 03 并作 mac 验收件。
- **验证**：node --check app.js 绿；tsc strict 绿；vitest 42 例全绿（notify-gate 4 + backend 21 + window-control 10 + quit 8——quit 含 04 遗留）；真机冒烟全断言；浏览器直开零回归（无桥 early-return，不申请通知权限）。

## 审查轮（2026-10-04 · 双轴（Standards/Spec）+ 行级/Java 轴豁免（零 Java diff，沿 03 挂账口径））

**覆盖**：desktop 三文件（notify-gate 新增/preload 新增/main.ts diff）+ app.js diff + package.json = 100%

### 阻断（已修复）
- **[Standards P2] 计划复核通知主路径不可达**：卡片去重早退先于通知调用——同会话实时流 tool/call 必先渲染计划卡，「计划等待你复核」永不可达。修复：去重只约束卡片渲染，通知移出早退（已修）。
- **[Standards P1 红线 6·裁定不修] CHANGELOG 缺记账**：本票浏览器直开行为零变化（无桥 early-return）、桌面壳能力随 08 打包交付——沿「桌面壳记账随收口票」口径（02/03 先例），08 同 diff 一条 Added 覆盖壳全部能力。主审裁定记档于此。

### 建议（处置：已修 4 / 记档 3）
- **[Standards P3·已修] main.ts 头注释 --smoke 序列漂移**（同文件双描述）——补通知桥两步。
- **[Standards P3·已修] 构建顺序脆弱**：tsc 产物 dist/preload.js 在 sandboxed preload 必炸、esbuild 为承重步——tsconfig 注释声明（勿单独以 tsc 产物起壳）。
- **[Standards P3·已修] firstLineSnippet 补注释**（注释密度惯例）。
- **[Spec·已修] permission 显式断言**：gateHidden=true 已隐式锁定 granted，补显式 smokeAssert 双保险。
- **[Standards P3·已修] preload version 字段零消费方删除**（Speculative Generality）。
- **[Spec F1/F2·记档] 门控源偏离 + 兜底开关未启用**：四处落位（preload/main 注释、spec 定稿节、票面本节）。
- **[Spec F3·记档] esbuild 红线 4 伞内**（传递依赖升直 + build 承重步，ADR Q2 凭据）。
- **[Standards·记档] 「任务完成」按每条 assistant/message 通知**：一 turn 多条会重复扰——事件词汇表无 turn 完成专用事件，当前点是可达最近似，观察项（真任务频度低，真人验收期复评）。

### 测试覆盖
- notify-gate 4 例（三态矩阵 + 未知态 fail-closed）；smoke 运行期断言（桥/门控双态/permission）；app.js 事件点为接线层（node --check + 冒烟页面加载验证），决策逻辑零渲染层副本。缺口：三类真人触发 + 点击聚焦（挂账真人验收）。

**测试收口**：审查修复后 node --check 绿、tsc 绿、vitest 42 例绿、冒烟全断言过（exit 0）。双轴报告全文见本轮对话记录，本节为合并处置版。
