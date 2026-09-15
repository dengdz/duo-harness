# 待办清单（backlog）

> 还不值得立里程碑、但确定要做的增强项。每条注明来源与建议时机；启动新里程碑时先来这里挑。

## 上下文治理（M9 衍生）

- [x] **Web 状态面显示 token 占用**：状态面加"上下文占用"一行，治理可见化。→ 进 M10（2026-09-14），升格为真实 usage 展示，见 `.scratch/m10-web-hardening/`
- [ ] **治理阈值 yml 化**：spill/修剪/压缩/窗口四阈值目前是 `ContextGovernance` 常量，改为 WebPlugin/装配 config 可配。来源：M9 spec Out of Scope 有意延后
- [x] **provider usage 捕获**：`stream_options: include_usage` 拿真实 token 数替代本地估算。→ 进 M10（2026-09-14），治理判定切真实值+估算兜底（ADR-0009），见 `.scratch/m10-web-hardening/`
- [ ] **/compact 手动压缩命令**：CLI 与 Web 各加一个手动触发入口。来源：DSH 参照（研究材料 45 行）

## Web 呈现域（M10 grill 衍生）

- [ ] **鉴权与局域网暴露**：loopback-only 维持。用户 2026-09-14 裁定"局域网多设备目前不需要"——要给别人用时重启（yml 静态访问令牌 + bind 配置 + "非回环绑定必须配令牌"的 fail-closed 联动，grill 已议定未落盘实施）。
- [ ] **会话内容恢复范式：尾部窗口快照 + 向上分页 + 切换无刷新**（DSH 范式，2026-09-14 研究确定）：刷新/重连/切会话统一取**尾部 N 条消息**快照（携 `hasMore` + 投影基线）整窗替换，更早历史按页向上加载（DSH `PAGE_MESSAGES=50`、跳跃 200 条参照）；同时消除会话切换的 `location.reload()`。**DSH 不持久化游标、无全量重放路径**——用"限制快照大小"替代"增量游标"，一套机制覆盖三场景。需服务端分页参数 + 前端向上加载 UI；新开 ADR（ADR-0010 只覆盖连接层游标，不可改）。来源：M10 工单 05 刷新策略裁定 + DSH 源码精查
- [ ] **代码语法高亮**：MD 渲染随 M10 落地（vendor marked.js + DOMPurify），highlight.js（约 100KB+）待界面稳定后评估。
- [ ] **计费口径统计**：cache 命中率、分桶明细——usage 已随 M10 落 assistant/message 日志，按需投影展示。
- [ ] **工具名协议化渲染**：前端散布 ask_user/exit_plan_mode 硬编码、后端"拒绝"魔法串判定审批语义，改元数据驱动。
- [ ] **状态面轮询统一**：5s setInterval 与 SSE 双通道并存，统一事件通道或论证保留轮询。

## 文档与呈现（M8.5 衍生）

- [ ] **视觉打磨**：原型评审时用户原话"可用非惊艳"——卡片视觉细节、动画过渡在界面稳定后统一打磨。来源：M8 工单 03 记录
- [ ] **03-高级章节**：技能编写指南、MCP 深入、多插件协同（组装第二个 agent）。来源：M8.5 spec Out of Scope

## 容器与测试基建（M12 衍生）

- [ ] **插件可选依赖**：`inject()` 目前全是硬依赖（缺失即 PENDING 挂起/启动失败），CLI 想"不带 fs 工具"的纯对话装配无法表达——需要声明可选服务名与降级语义（如 `/permission` 在无 workspace 服务时提示未挂载）。来源：M12-02 审查修复（/permission 接线引入 CLI→fs 硬依赖）
- [ ] **测试进程收割**：surefire fork 被强杀（或异常退出）时 MiniFileSystemServer 等 stdio 子进程不回收，机上是曾累计 150+ 僵尸进程； graceful dispose 路径正常。可考虑 McpClientPlugin 挂 shutdown hook 兜底杀子进程。来源：M12-02 回归排查（2026-09-15） 补充案例（M12-05）：cli 行的 REPL 线程非守护且阻塞在 System.in——单跑含 cli 行装配的测试类时测试本体已完成但 **JVM 退出挂起**（surefire forkedProcessTimeoutInSeconds=240 未兜住此形态，其只约束测试执行段）；修法=Boot 前 System.setIn 空流（ToolCatalogTest 已修），机制级收敛随 awaitStartup 超时语义一并评估。
- [ ] **awaitStartup 超时语义**：编程挂载 `Context.plugin(...).awaitStartup()` 缺依赖时无限等待且无日志，测试/演示易静默卡死（yml 路径有 Boot 校验点名报错）——评估加可配超时 + 点名缺失服务。来源：M12-02 回归排查（2026-09-15）

## 交互路由（M12-02 验收衍生）

- [ ] **审批/提问的呈现位路由（谁发起谁作答）**：双呈现位并存时按注册序固定路由（web 优先），CLI 发起的工具调用审批会跳到 Web 卡片、**终端零提示**——M12-02 验收第 6 步用户实际被绊住（误以为卡死）。改进方向：回答者按"发起呈现位"亲和路由（工具循环所属呈现位的回答者优先），或至少给终端补一行"审批已发往 Web，等待作答"。来源：M12-02 验收（2026-09-15）
- [ ] **Web 面斜杠命令缺口**：`/permission` 等 REPL 内置命令只在 CLI 呈现位拦截（ADR-0012 归 CLI），Web 聊天输入框不识别、透传给模型当普通消息——M12-03 验收第 8 步用户在浏览器输入即踩坑（模型自己排查出了根因）。改进方向：Web 输入框识别斜杠命令给提示（"该命令在终端 REPL 使用"），或给 Web 面做等效切档入口。来源：M12-03 验收（2026-09-15）
