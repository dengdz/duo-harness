# M37 桌面端端到端验收件（工单 08）

> done 判据 = 用户手动运行本验收件确认通过（duo-acceptance 流程）。作者自跑证据在案（见「作者自跑记录」）；标 **[真人]** 的条目为 mac 交互面/LLM 依赖项，程序化不可达，需你亲手走。

## 一、作者自跑证据（已留档，2026-10-04 首跑 + 2026-10-05 用户代测会话）

- **dev 态全链冒烟**（`npm run smoke`，15s 收口，断言硬失败即非零退出）：拉起 → 窗口加载（截图 [.scratch/m37-desktop/smoke-window.png](smoke-window.png)）→ 关窗拦截隐藏 → 托盘切换复原（复原图 [smoke-window-reopened.png](smoke-window-reopened.png)）→ 单实例二次启动即退 → `duo://open` 收达聚焦 → **外部 kill 后端 → 自动重启换址 → 原窗重连** → 无 java 孤儿
- **打包态实证**：`CSC_IDENTITY_AUTO_DISCOVERY=false npx electron-builder --mac` 出 `out/mac-arm64/duo.app` + zip；`open duo.app` → **后端自包拉起**（`/usr/bin/java -jar .../duo.app/Contents/Resources/backend/duo-harness-1.2.0.jar` 实录，GUI 启动环境 login shell 探测过）；**未运行拉起**：退出后 `open duo://open` → Info.plist 协议拉起 duo.app（5 进程实录）；包内核验（jar/icns/Info.plist CFBundleURLTypes）全过
- **用户代测会话（2026-10-05，受用户委托「你帮我测试一下」）**：
  - T1 idle 基线：`turnActive=false` ✓
  - T2 busy 实测：真 LLM turn（1+1）——密轮询 0.5s 抓到 **`turnActive=true` 在飞**、收口回落 false ✓（首轮 3s 粒度错过快速 turn 窗口属测试方法问题，密轮询解决）
  - T3 长任务拦截：`sleep 30` bash 任务在飞（`turnActive=true` 4s 处实证）→ osascript Cmd-Q → **决策日志 `quit-probe turnActive=true` + 6s 后 electron/java 双存活**（对话框拦截生效、长任务未被误杀）✓——注：首次尝试用「数到15」流式过快、quit 时已收口判空闲直退（**正确行为**，等同 idle 直退；测试任务改 sleep 30 后成立）
  - T4 全链冒烟复跑：0 失败、11 条断言/证据、无 java 孤儿 ✓
  - 附注：T2/T3 测试消息会产生两个真实会话（1+1 / 数数），可在 Web 侧栏删除

## 二、端到端叙事 [真人]（spec User Story 16）

前置：打包 .app（或 `npm run dev`）+ `~/.duo/config.yml` LLM 可用。

1. 双击 duo.app（或点 dock 图标）→ 窗口出现、Web UI 可用
2. 发一个会长跑的任务（如「看看这个仓库的结构」）
3. 切到别的应用（窗口隐藏或被遮挡）
4. （通知功能已砍除——此步跳过）
5. 等任务跑完，窗口切回时看到结果
6. 任务在跑时 **Cmd-Q** → 「后端还有 agent 在跑」确认框 → 点「取消」→ 应用常驻、任务继续
7. 任务跑完后再 Cmd-Q → 干净退出 → 终端 `ps aux | grep duo-harness` 无残留

## 三、S4 五场景 [真人项标注]

| 场景 | 操作 | 预期 | 形态 |
|---|---|---|---|
| 托盘 | 关窗 → 右键菜单栏 ● → 左键 ● | 菜单三项（打开主窗/打开数据目录/退出）；左键回窗 | [真人]（程序化 toggle 已锁） |
| 通知 | 任务跑着切走 / 卡审批 / 强制出错 | 三类通知；点击聚焦主窗 | [真人]（门控与发射已程序化锁） |
| 深链 | 浏览器开 `duo://open`；再双击 App 图标 | 唤起聚焦；不出现第二实例 | 程序化已锁 + [真人] 可复走 |
| 探活 | 场景二第 6 步 | 确认框拦下、取消回常驻 | [真人]（决策四分支已程序化锁） |
| 崩溃恢复 | 终端 `kill <java pid>` | 恢复对话框（诊断可见）→ 重启后窗口恢复可用 | [真人]（检测/重启/重连已程序化锁） |

## 四、打包态两项（08 承接验证，已程序化实证）

- 未运行拉起：退出后 `open duo://open` → duo.app 被协议拉起（ps 实录 5 进程）✓
- 通知 permission 打包态：dev 态 granted 实证；**打包态点真通知一次即复验**（并入上面二/3 步顺带完成）

## 五、已知的非缺陷现象

- 冒烟轮会发一条「duo 通知管线」测试通知（自动化证据，可在通知中心忽略）
- 首次 `npm run dev` 前需 `npm install`；后端代码更新后需重出 fat-jar（`mvn -pl duo-harness-example -am package -DskipTests`）
- 桌面运行时 cli 行的 REPL 无终端可见（阻塞等待输入，无害）；纯桌面可删 plugins.yml 的 cli 行
