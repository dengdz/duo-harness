# 08: mac 打包 + 应用图标 + 文档 + 验收件收口

## What to build

M37 收口四件套。其一打包：electron-builder 出 macOS 包（app/dmg；本机跑与轻打包优先——安装器/签名/公证不在本期），产物可双击启动走通 02–07 全部能力。其二**应用图标（2026-10-04 用户裁定增补：要有个 logo；内置 JRE 维持 1.4.0 候选不进本期）**：设计首版 duo 图标（品牌蓝圆角方 + 白色字标，多尺寸 icns），落 dock/访达展示；托盘图标同步换 logo 单色模板版（退役工单 03 的运行时圆点）。其三文档：README 增桌面段（前提 JDK 21/构建/运行）；交货指南续章（壳工程构建与打包说明）；已知限制页补三条（桌面壳仅 macOS 验证、Win/Linux 未验；JDK 21 为运行前提；桌面复用 `plugins.yml`——cli 行 REPL 形态说明与「纯桌面可删 cli 行」提示）。其四验收件：按 spec User Story 16 成文端到端验收件（双击图标 → 窗口可用 → 发任务 → 切走收审批通知 → 点回作答 → 收完成通知 → 退出被探活拦 → 确认后干净退出）+ S4 五场景（托盘/通知/深链/探活/崩溃）清单——**作者先自跑全流程并留证据（截图/输出），再交用户手动验收**（M35-09 判据）。

> 范围注（2026-10-04，用户裁定）：轻打包维持、内置 JRE 不入本期（「双击即用」包 = 内置 JRE + 更新链 + CLI 命令安装，合流 1.4.0 候选）；图标为首版视觉，样式验收期看实图可调。

## Blocked by

03, 04, 05, 06, 07

## Status

in-progress（2026-10-04 实现；**2026-10-05 用户裁定「双击即用」打包路线取消——electron-builder 链退役、npm start 为唯一使用形态，打包项转 removed**；图标/文档/验收件部分有效，done 判据 = 用户按 [acceptance.md](../acceptance.md) 手动验收通过）

## Checklist

- [x] 应用图标首版设计落位（「哆」字标黑底 ZCode 样式——用户裁定两连；多尺寸 icns + 托盘模板版；渲染图 [resources/icon-1024.png](../../desktop/resources/icon-1024.png)）
- [x] ~~electron-builder mac 包产出~~（2026-10-05 用户裁定打包路线取消，npm start 为唯一使用形态；打包链退役——electron-builder.yml/钩子/devDep 移除）
- [x] README 桌面段 + 交货指南续章（新页《桌面壳构建与打包》+ 侧栏两处）+ 已知限制三条
- [x] 端到端验收件成文 + S4 五场景清单（[acceptance.md](../acceptance.md)）
- [x] 作者自跑全流程留证据；逮出问题修复后验收件同 diff 更新
- [ ] 交用户手动验收（done 判据 = 用户确认通过）

## Comments

- **图标（用户裁定两连落实）**：字标「哆」（PingFang SC 无头 Canvas 渲染自动缩放适配，gen-icon.mjs 编排 + icon-render.js electron 渲染）+ 底色 ZCode 样式深蓝黑阶渐变（#333A46→#14171E）；托盘同源黑色模板版（resources/tray-template.png，main 读图退役运行时圆点）。产物 [icon-1024.png](../../desktop/resources/icon-1024.png)。
- **打包态验证（08 承接两项 + 启动）**：①后端自包拉起——`open duo.app` → `/usr/bin/java -jar .../Resources/backend/duo-harness.jar`（GUI 启动环境 login shell 探测实证过）；②未运行拉起——退出后 `open duo://open` → Info.plist 协议拉起（5 进程实录）；③通知 permission dev 态 granted 实证、打包态点击复验随真人项。退出即无孤儿。
- **extraResources 冗余修复（审查 S1）**：原 glob 三历史版本全入包（67MB）——beforeBuild 钩子拷最新版单文件（22MB），resolveJarPath 稳定名单 `duo-harness.jar` 优先 + 版本 glob 兜底（打包/dev 双态，resourcesPath 注入缝可测）。注：beforeBuild 上下文无 appOutDir（实测 undefined），钩子用 __dirname 定位。
- **文档三处**：新页《桌面壳构建与打包》（构建/--smoke/打包三节，产品文档口吻）+ 侧栏两处注册 + README 桌面段 + limitations M37 三条（macOS only/JDK 21 前提/cli 行形态）；「交货指南续章」落为独立新页（读者域更合理，偏差记档）。
- **CHANGELOG 记账**：桌面壳全能力单条 Added（02-07 累积兑现 05 轮裁定），Added/Fixed 分节整理。
- **smokeSequence 分段函数化（06/07 两轮挂账兑现）**：三段函数（LaunchHideNotify/SingleInstanceDeepLink/CrashRecovery）+ 总序列编排——05 轮「双描述漂移」与 06 轮「拆分临界」两处观察就此销账。
- **验证**：tsc 绿；vitest 52 例全绿（打包态稳定名单候选 1 例新增）；docs:build 绿（新页/侧栏/链接）；打包态启动 + 深链 + 清场实证；机械自检两 grep 干净。

## 审查轮（2026-10-04 · 双轴（Standards/Spec）合并轴 + 行级/Java 轴豁免（零 Java diff，沿 03-07 口径））

**覆盖**：desktop 全部变更（图标两脚本/资源产物/main/backend/builder/测试）+ 文档四处 + CHANGELOG + 验收件 = 100%

### 阻断（无硬违规；P2×2 已修复）
- **[Standards S1 P2·已修] extraResources 三历史版本全入包（~67MB 冗余）**：beforeBuild 钩子拷最新版单文件 + resolveJarPath 稳定名单优先。
- **[Standards S2 P2·已修] resolveJarPath 直读 process.resourcesPath 破注入缝**：改参数缺省注入（可测）+ 打包态候选测试 1 例。

### 建议（处置：已修 5 / 记档 5）
- **[Spec 缺口·已修] smokeSequence 分段函数化**（06/07 两轮挂账）——三段函数 + 总编排；:307 陈旧序列注释随之消除（双描述漂移第 5 犯位置）。
- **[Standards S4 P3·已修] gen-icon spawn 无 error 监听 / iconset 无 finally 清理 / icon-render 无 catch**——三处补齐。
- **[Standards S5 P3·已修] JRE 引注颗粒度**——limitations 与交货页改引「工单 08 范围注（用户裁定留 1.4.0 候选）」。
- **[Standards P3·已修] smoke 失败兜底置位对称**（成对路径并排核对口径延续）。
- **[Standards P3·已修] 头注释序列补崩溃段**（双描述漂移家族位置）。
- **[Spec C8·记档] 「交货指南续章」落为独立新页 + app/zip 非 dmg**——读者域与 CHANGELOG 自洽，收口确认口径。
- **[Spec D1·记档] 对话框诊断双层截断**（8KB 句柄 / 1200 对话框 / 120 日志行）。
- **[Spec C9·记档] 打包态通知 permission 转真人复验**（acceptance 二/3 步顺带）。
- **[Standards C6·记档] main.ts 薄壳张力**（07 已记，本期编排入壳——纯判定已在 quit-orchestration，Electron 段留壳可辩护）。
- **[Standards·记档] main.ts Divergent Change 临界**（约 400 行，1.4.0 若再扩考虑拆 presenter 模块）。

### 测试覆盖
- vitest 52 例全绿（新增打包态稳定名单候选 1 例）；docs:build 绿（新页/侧栏/链接）；打包态三项程序化实证（自包拉起/未运行拉起/包内核验）；dev 冒烟全断言。缺口：对话框本体与通知点击真人项（acceptance 二节）。

**测试收口**：审查修复后 tsc 绿、vitest 52 例绿、docs:build 绿、打包态启动+深链实证（exit 0）。双轴报告全文见本轮对话记录，本节为合并处置版。
