# 待办清单（backlog）

> 还不值得立里程碑、但确定要做的增强项。每条注明来源与建议时机；启动新里程碑时先来这里挑。

## 上下文治理（M9 衍生）

- [ ] **Web 状态面显示 token 估算**：状态面加"上下文估算：N tokens / 阈值 M"一行，治理可见化（现状只在治理触发时打终端日志，短会话完全不可见）。来源：M9 收官用户反馈；建议随 M10 或 0.4.x patch
- [ ] **治理阈值 yml 化**：spill/修剪/压缩/窗口四阈值目前是 `ContextGovernance` 常量，改为 WebPlugin/装配 config 可配。来源：M9 spec Out of Scope 有意延后
- [ ] **provider usage 捕获**：`stream_options: include_usage` 拿真实 token 数替代本地估算（估算 ±10-20% 误差）。来源：M9 grill Q1 裁定的增强项
- [ ] **/compact 手动压缩命令**：CLI 与 Web 各加一个手动触发入口。来源：DSH 参照（研究材料 45 行）

## 文档与呈现（M8.5 衍生）

- [ ] **视觉打磨**：原型评审时用户原话"可用非惊艳"——卡片视觉细节、动画过渡在界面稳定后统一打磨。来源：M8 工单 03 记录
- [ ] **03-高级章节**：技能编写指南、MCP 深入、多插件协同（组装第二个 agent）。来源：M8.5 spec Out of Scope
