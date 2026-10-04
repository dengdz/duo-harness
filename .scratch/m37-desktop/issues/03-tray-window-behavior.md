# 03: 托盘常驻与窗口形态——mac 惯例

## What to build

桌面壳的常驻身份与窗口行为（ADR-0039 决策一「原生体验」组成）：主窗关闭（红点）= 隐藏不退出——mac 平台惯例，dock 与托盘保持；托盘常驻：模板图标、点击切换主窗显隐、托盘菜单（打开主窗 / 打开数据目录（`~/.duo`）/ 退出）；应用菜单用 macOS 默认模板（About / Edit 复制粘贴 / Quit 等平台惯例项）+「打开数据目录」。菜单文案走壳侧资源，退出项接 04 的退出编排（本票先置占位，04 换真）。

端到端可验：点红点关窗 → 应用还在（dock/托盘在）→ 点托盘图标窗口回来；托盘/应用菜单三项可用。

## Blocked by

02

## Status

in-progress（2026-10-04 实现完成、双轴审查修复毕、程序化验证绿；done 判据 = 用户手动验收——托盘左右键手势与 dock 点击的真人操作项待验）

## Checklist

- [x] 主窗关窗隐藏（mac 惯例；二次点托盘/dock 恢复——dock 走 app.on('activate')）
- [x] 托盘图标 + 点击显隐 + 托盘菜单三项（mac 双手势：左键=切换显隐、右键=弹菜单；见 Comments 手势记档）
- [x] 应用菜单 mac 默认模板 + 打开数据目录项（showItemInFolder 定位 `~/.duo`，DUO_HOME 环境级对齐后端 DuoHome）
- [x] S3 缝扩展：窗口显隐与菜单动作的编排逻辑单测（window-control.ts 10 例，Electron 无关假体）
- [x] 真机验证：关窗/托盘往返程序化实证（smoke 断言 + 双截图）；真人手势项留用户验收（见 Comments）

## Comments

- **实现形态**：编排逻辑全在 `window-control.ts`（Electron 无关：showMainWindow/toggleMainWindow/shouldInterceptClose/createTrayActions，假体可测——工单 02 backend.ts 分层先例），main.ts 只接线。关窗拦截仅 darwin 生效（`interceptHideOnClose` 平台门——其他平台关窗即退出，window-all-closed 兜底可达，消除「注释说非 darwin 退出实际不可达」矛盾）；真退出经 before-quit 置位 quitting 放行 close（Electron 语义：app.quit 先发 before-quit 再关窗，置位必先于拦截判定，无竞态）。数据目录 `DUO_HOME > ~/.duo`（环境级对齐 DuoHome，sysprop 测试口对壳不可见已注释声明）。
- **mac 托盘手势记档（如实）**：Electron 设了 context menu 后左键即弹菜单、click 事件不触发——「点击显隐」与「菜单三项」拆成双手势：**左键=切换主窗显隐、右键=弹菜单**（手动 popUpContextMenu，不 setContextMenu）。程序化 toggle 已实证（冒烟断言 visible=false→true），真人左右键/dock 点击属 mac 交互面无法脚本化——留用户手动验收。
- **真机验证证据**：`npm run smoke` 10s 收口——首截屏 → win.close() 触发拦截（`after-close visible=false` 断言过）→ toggleMainWindow 复原（`after-toggle visible=true` 断言过）→ 复原截屏（[smoke-window-reopened.png](../smoke-window-reopened.png)；与首图字节级相同——UI 静态所致，复原以断言日志为准）；退出无 java 孤儿（ps 实证 0）。smoke 断言不符即 exit(1)（审查修复：静默打印会让 CI 假通过）。
- **验证**：tsc strict 绿；vitest 29 例全绿（backend 19 + window-control 10：切换/拦截/销毁重建/三动作路由）；全仓回归未跑（本票零 Java 改动，验证面=壳）。

## 审查轮（2026-10-04 · 双轴（Standards/Spec）+ 行级/Java 轴豁免判定）

**覆盖**：desktop/ 全部变更（window-control.ts 新增/main.ts 重写/测试新增 + 票据）= 已审 100%（本票零 Java diff——行级轴 OCR 无 TS 规则组、Java 规范轴无适用对象，豁免待用户确认）

### 阻断（已修复）
- **[Standards P2] 托盘重建不回写全局 window**：controlDeps.createWindow 只返回新窗不写模块变量——销毁重建后 getWindow 永取旧销毁窗、窗体逐次累积。修复：工厂内回写 `window = createWindow(url)`。
- **[Spec A2] 「点击显隐」未实现 + tray GC 风险**：context menu 吞左键致 toggle 仅程序化可达——改双手势（click→toggle / right-click→popUp）；tray 局部变量提升为模块级（Electron Tray 被 GC 致托盘消失的惯例坑）。
- **[Spec A1] dock 恢复缺失**：票面「二次点托盘/dock 恢复」只做了托盘半边——补 `app.on('activate')` → showMainWindow。
- **[Standards P3-2/Spec A5] smoke 静默假通过**：visible 状态只打印不断言——改 smokeAssert 不符 exit(1)。

### 建议（处置：已修 4 / 记档 2）
- **[Standards P3-3·已修] 非 darwin 拦截矛盾**：close 拦截加平台门（darwin only），window-all-closed 的非 darwin 退出分支恢复可达。
- **[Standards P3-5/Spec A3·已修] 打开数据目录语义**：openPath 吞错改显式报错框；「Finder 定位」改 showItemInFolder（票面语义）；应用菜单与托盘动作路由合一（去 Duplicated Code）。
- **[Standards P3-4·已修] 托盘回调崩壳路径**：编排抛错经 safely() 兜底进对话框，不再 uncaughtException。
- **[Spec B1-B4·记档] 越票面项均有依托**：非 darwin 退出分支（spec「壳代码跨平台写法」）、DUO_HOME 对齐（后端 DuoHome 同名环境变量）、smoke 序列扩展（本票可验句）、运行时生成模板图标（spec「模板图标」）。
- **[Standards P3·记档] smoke 固定 delay**：hide 同 tick 生效 500ms 余量足；1500ms 渲染等待为工单 02 既有形态——观察项。
- **[Standards P3·记档] createWindow 后端未就绪抛错**：防御路径（backend 单次赋值后恒非空），safely 已兜。

### 测试覆盖
- window-control 10 例：切换双态/拦截三态（quitting/销毁/null）/销毁重建/三动作路由——断言外部形态（事件序列/返回值/revealed 列表），不复述实现；smoke 断言补运行期关窗拦截与复原的进程级证据。

**测试收口**：审查修复后 tsc 绿 + vitest 29 例绿 + 冒烟断言过（exit 0）。双轴报告全文见本轮对话记录，本节为合并处置版；行级/Java 轴豁免（零 Java diff）待用户确认后记档生效。
