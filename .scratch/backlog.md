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
