# 04: bash run_in_background 与 task 面

## What to build

模型对长命令（构建/测试/安装）传 `run_in_background` 参数：bash 立即返回 taskId 并转入内存任务注册表，命令继续在后台跑。模型经 task-output（支持 block/timeout 等待、读尾部窗口输出）与 task-stop（终止任务）两工具操作后台任务，不加 task-list（列表职责给 06 的呈现面）。任务完成通知必达：agent 空闲即开新轮消费；agent 正在跑则挂收件箱 next-turn 级、turn 收口多条到期通知合并为一条注入。每任务至多一条通知（first-wins）。超时语义维持 120s 杀树不变；暂停（02）不杀后台任务，进程退出注册表全灭。

决策依据：ADR-0025 决策二；ADR-0024 拒绝项（超时自动转后台不做）；探测文档 ZCode 本机执行工具族.md（后台生命周期/注册表/TaskOutput）与 DSH 任务管理.md（first-wins/通知注入）。

## Blocked by

01（完成通知 busy 挂 next-turn 收口合并消费，依赖两级收件箱）

## Status
done

## Comments
- 2026-09-22：实现与双轴审查完成，全量 BUILD SUCCESS（tools 190 / agent 176 / web 74 / cli 34 / session 30）。审查修复：双重 CAS 开轮竞争（通知回调与读者线程——判定上移调用方）、Registry 监听器异常隔离、shutdownAll 先摘通知路由（防拆树窗口通知进死呈现位）、awaitExit 中断假通知断言、KILLED 被 EXITED 覆盖竞态、ProcessBuilder 段去重、服务名对齐 camelCase 惯例（backgroundTasks）、工具目录补 task-output/task-stop 条目（防漂移闸门抓到）。
- 待用户手动验收（sleep 长命令后台跑 + task-output + 完成通知演示）后转 done。

## Checklist
- [x] bash 工具新增 run_in_background 参数：转入内存注册表返回 taskId，后台进程独立存活（注册表随进程 dispose）
- [x] task-output 工具：block/timeout 参数、读尾部窗口输出、任务不存在/已结束的明确结果
- [x] task-stop 工具：终止任务（复用杀树），幂等
- [x] 完成通知 first-wins：空闲开新轮、busy 挂 next-turn 收口多条合并为一条（测试覆盖两条路径）
- [x] 暂停不杀后台任务、通知不丢（与 02 集成验证）
- [x] 超时语义回归不变：前台 120s 杀树行为与既有测试全绿
- [x] 测试（先例 FsBashToolTest / SteerInjectionTest seam）：后台生命周期、两工具语义、通知路由
- [ ] 工单级验收件：sleep 长命令后台跑 + task-output 取输出 + 完成通知演示，用户手动确认
- [x] CHANGELOG 未发布段记账

## 审查轮（2026-09-22，双轴，基点 8a148da）

**覆盖**：新增 4 文件 + 改动 8 文件 + 4 测试文件全审（覆盖率 100% 全集口径）

### 阻断（已修）

- **通知路由双重 CAS**（双轴同点高优）：通知回调 CAS 占位后 startTurn 内部再 CAS——开轮必然失败、通知丢失。修复：busy 占位判定上移调用方（读者线程与通知线程各自 CAS 封口，竞争败北方回退插队/注入），startTurn 信任占位无条件执行。
- **shutdownAll 的 KILLED 通知进拆树窗口**（Standards 轴）：插件停止时杀树触发通知路由进已拆树中的呈现位。修复：dispose 先 clearListeners 再 shutdownAll。
- **Registry 监听器无异常隔离**（Standards 轴）：单路由方抛错饿死余者（与 Context.emit 约定不对齐）——已加 try-catch 隔离。
- **awaitExit 中断假通知**：被打断提前返回时 state 仍 RUNNING 的「[运行中]」通知会烧掉 first-wins——通知循环前补 isCompleted 断言。

### 建议（处置）

- **终态任务永驻注册表无上限**（量级风险，javadoc 已言 by design）——记档；上限策略归工单 05/06 评估。
- **通知必达竞态窗口**（Spec 轴）：turn 收口 drain 与 busy=false 之间注入的通知延至下次交互消费——不丢（必达=延迟语义），记档。
- 命名（interruptSeenInGroup）、注释服务名、测试叙述计数、文件尾换行——已顺手修。

### 测试覆盖

- BackgroundTaskRegistryTest 5：通知 first-wins、get/all 索引、stop 杀树幂等、shutdownAll 全杀、迟到监听器语义
- FsBashToolTest +3：后台端到端（taskId→task-output 等待→stop 幂等）、杀运行中任务、无注册表报错
- CliPluginTest +3：空闲通知自动开轮、busy 收口合并消费、**中断不杀后台+通知照达**（与 02 集成）
- WebFaceTest +1：空闲通知自动开轮 + busy 挂 next-turn 双路径
- 回归：前台 120s 杀树与既有用例全绿
