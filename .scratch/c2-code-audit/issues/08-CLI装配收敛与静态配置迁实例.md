# 08: CLI 装配收敛与静态配置迁实例

## What to build
CLI 呈现位的 apply 装配与 /new 换绑装配走同一装配工厂（agent 规格、能力集、会话装配步骤单点化）；LLM 配置四字段（含密钥）从 JVM 静态字段迁回实例态——多实例并存互不互踩，与 Web 呈现位的实例态模式一致，实例销毁后配置不残留。

证据锚点：审计报告 P2-B 族第 8 条（装配序列双份）+ P2-D 族第 17 条（static volatile 滞留）。

验收标准（用户可感）：CLI 双实例并存（同 JVM）各自配置独立生效；/new 换绑后能力面与初始一致。

## Status
in-progress（实现完成待手动验收，2026-09-28）

## Checklist
- [x] apply 与 /new 的装配序列收敛为共用工厂（buildAgent(chain, session, planGate)：CommandChain 上移到 agent 构建前，两处接线单点）
- [x] 四个静态配置字段（含密钥）迁回实例字段（static volatile 清零 grep 佐证；loadLlm 已是实例方法无阻碍）
- [x] 双实例并存（静态消除由结构保证；既有测试的两处 new CliPlugin 顺序运行从「后 apply 覆盖侥幸成立」变为天然独立——显式双 Fixture 用例成本高不补，记档）
- [x] 换绑行为等价回归（newCommandSwitchesToFreshSession 等 CliPluginTest 26 用例全绿）
- [x] CHANGELOG 记账

## Comments

- 2026-09-28 实现：/new 命令 lambda 闭包捕获 chain 参数直接传 buildAgent（CommandState 无需扩字段）。cli 41 用例全绿。

