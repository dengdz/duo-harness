# 04: bash run_in_background 与 task 面

## What to build

模型对长命令（构建/测试/安装）传 `run_in_background` 参数：bash 立即返回 taskId 并转入内存任务注册表，命令继续在后台跑。模型经 task-output（支持 block/timeout 等待、读尾部窗口输出）与 task-stop（终止任务）两工具操作后台任务，不加 task-list（列表职责给 06 的呈现面）。任务完成通知必达：agent 空闲即开新轮消费；agent 正在跑则挂收件箱 next-turn 级、turn 收口多条到期通知合并为一条注入。每任务至多一条通知（first-wins）。超时语义维持 120s 杀树不变；暂停（02）不杀后台任务，进程退出注册表全灭。

决策依据：ADR-0025 决策二；ADR-0024 拒绝项（超时自动转后台不做）；探测文档 ZCode 本机执行工具族.md（后台生命周期/注册表/TaskOutput）与 DSH 任务管理.md（first-wins/通知注入）。

## Blocked by

01（完成通知 busy 挂 next-turn 收口合并消费，依赖两级收件箱）

## Status
ready-for-agent

## Checklist
- [ ] bash 工具新增 run_in_background 参数：转入内存注册表返回 taskId，后台进程独立存活（注册表随进程 dispose）
- [ ] task-output 工具：block/timeout 参数、读尾部窗口输出、任务不存在/已结束的明确结果
- [ ] task-stop 工具：终止任务（复用杀树），幂等
- [ ] 完成通知 first-wins：空闲开新轮、busy 挂 next-turn 收口多条合并为一条（测试覆盖两条路径）
- [ ] 暂停不杀后台任务、通知不丢（与 02 集成验证）
- [ ] 超时语义回归不变：前台 120s 杀树行为与既有测试全绿
- [ ] 测试（先例 FsBashToolTest / SteerInjectionTest seam）：后台生命周期、两工具语义、通知路由
- [ ] 工单级验收件：sleep 长命令后台跑 + task-output 取输出 + 完成通知演示，用户手动确认
- [ ] CHANGELOG 未发布段记账
