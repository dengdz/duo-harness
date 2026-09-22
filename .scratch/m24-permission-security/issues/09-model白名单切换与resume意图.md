# 09: /model 白名单切换与 resume 意图

## What to build

yml 增 `llm.models` 白名单清单（缺席/空 = 不可切，/model 提示配置方法）；`/model` 无参列出清单 + 当前模型、带参仅准切清单内（清单外直接拒切——模型名决定成本面，防拼错烧钱）；切换落会话事件（保存意图）、下一 turn 生效（重建 adapter 完成执行绑定）；首期限同 provider（baseUrl 不变，跨 provider 路由留 1.0 后菜单）；resume 时投影暴露意图模型，≠ 当前配置模型则横幅提示（含 /model 建议）、不自动切——防成本意外（休假期换便宜模型后被静默换回）。

决策依据：ADR-0026 决策六；探测 docs/research/ZCode/Agent循环与会话/投影、恢复与分页.md（保存意图 vs 执行绑定分离）；backlog「/model 运行时切换」销账对象。

## Blocked by

08（provider 字段与装配选型先行）

## Status
ready-for-agent

## Checklist
- [ ] `llm.models` 解析 + `/model` 命令（无参列出 / 带参切换 / 清单外拒切）
- [ ] 切换会话事件 + 下一 turn 生效（adapter 重建）
- [ ] resume 意图投影 + 横幅提示不自动切
- [ ] 测试（先例 ChatAgent seam / CliPluginTest / BootTest）
- [ ] 工单级验收件：切模型下 turn 生效 + resume 提示演示，用户手动确认
- [ ] CHANGELOG 记账（0.19.0 段）
