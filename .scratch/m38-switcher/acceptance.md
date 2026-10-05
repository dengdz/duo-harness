# M38 会话内切换与模型配置——验收件（工单 06）

> done 判据 = 用户手动运行本验收件确认通过（duo-acceptance 流程）。作者自跑证据在案；标 **[真人]** 的条目为交互面/LLM 依赖项，程序化不可达，需你亲手走。

## 一、作者自跑证据（2026-10-05，作者执行，命令与输出在案）

- **桌面壳 vitest**：`cd desktop && npm test` → 3 套件 **43/43 全绿**（backend / quit-orchestration / window-control；backend 套件含 M38-07 新增「cwd 选项透传 spawn、缺省不设」用例）
- **全链 smoke**：`cd desktop && npm run smoke` → 断言全过收口（退出码 0），本次实跑输出逐条：
  - 存量段：拉起截屏 → 关窗拦截隐藏（visible=false）→ 托盘切换复原（visible=true）→ 单实例二次启动即退 → 崩溃检测重启换址 → 窗口重连新后端
  - **M38-06 新增段（smokeSegmentSelectors，desktop/src/main.ts）**：档位菜单计划模式入口在册 → permission 三档遍历跟随（read-only→「变更前确认」/ workspace-write→「自动编辑」/ danger-full-access→「完全访问」）→ 模型切换跟随（点白名单异值项 → 标签实测 `deepseek-v4-pro`）→ 思考切换跟随（「最高」）→ 收尾回 workspace-write
- **后端全量**：`mvn clean test` → **1198 用例、0 失败 0 错误（4 跳过）、BUILD SUCCESS**（2026-10-05 实跑）。工法注记：先 clean——llm 模块 target 曾残留旧包名（web 时代）编译类被 surefire 误扫出 2 假失败，clean 后干净；该残留源自身已重写迁移（dev.duo.harness.llm.LlmConfigFileTest，5 用例全绿）
- **真机手验留档**（截图 [assets/](assets/)，browser-use 实操作业）：
  - [m38-05-llm-config-panel.png](assets/m38-05-llm-config-panel.png)：状态面板「模型配置」区——表单回填（apiKey 遮蔽占位「已配置（不填 = 保留原值）」）、写回成功横幅、错误横幅样式
  - [m38-07-workspace-pick.png](assets/m38-07-workspace-pick.png) / [m38-07-ws-pick-card.png](assets/m38-07-ws-pick-card.png)：工作区选择条（预填当前 cwd）+ 标题区「📁 工作区」常驻可见
  - [m38-composer-zcode-card.png](assets/m38-composer-zcode-card.png)：composer 卡片化工具行（三选择器 + ↑ 方块钮）
  - 写回落盘核对（真实 config.yml 输出）：apiKey 保留原值、白名单整组替换、非 llm 段字节保留；会话文件版本头落盘 `"cwd":"…"`（进程 cwd 继承语义）

## 二、真人验收清单 [真人]

前置：`cd desktop && npm start`（壳）或 `java -jar duo-harness-example/target/duo-harness-1.3.0.jar`（直跑）+ `~/.duo/config.yml` LLM 可用。

1. **composer 三选择器**：档位选「自动编辑」→ 标签跟随；模型选白名单另一项 → 下一轮对话用新模型；思考切「最高」→ 下一轮生效。三枚菜单互斥单开、点外部收起、菜单贴按钮弹出
2. **模型配置管理区**（状态面板）：改 baseUrl / 白名单 → 保存（armed 二次确认）→ 横幅「重启 duo 后生效」→ 重启后 `/model` 清单即新白名单；apiKey 留空提交 = 原值保留；非法值被端点点名、原文件不动
3. **工作区选择**：壳启动弹目录框（定位上次目录）→ 选 A → agent `ls` 操作根在 A；应用菜单「新建会话（选择工作区）」选 B → 新会话根 B、旧会话根 A；重启壳默认定位上次。纯浏览器：「＋ 新话题」输入条手输路径，不存在路径被点名不创建
4. **跨呈现位**：Web 切模型后 CLI 链不受影响（本呈现位独立——已知限制 #3 行为确认）

## 三、已知边界

见 [docs/limitations.md](../../docs/limitations.md) M38 段三条：模型/思考下一轮生效（进行中 turn 不变）、配置写回重启生效（热重建不做）、跨呈现位不同步（本呈现位独立裁定）。

## 四、smoke 扩展注记

- **同值切换不发事件**（后端「已是当前模型」短路、不落 model/intent）——smoke 选项按 `/api/llm-config` 的当前模型取**异值项**（选项逻辑与注释在 smokeSegmentSelectors）
- **plan 态不入程序化遍历**：plan/mode 事件语义为 entered/exited 非档名，选择器标签跟随不适用（计划态由计划横幅/交互卡表达）；smoke 只锁「菜单入口在册」。进计划态需退出流程，留真人项
- smoke 跑批切的是真实环境会话档位/模型（收尾已回 workspace-write；模型切过 deepseek-v4-pro——如需回原值在 Web 面点回即可）
