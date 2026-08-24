# 07 — 示例插件 Demo 全链路

## What to build

duo-harness-example 模块：一组示例插件 + demo 配置 + 可执行入口，一条命令演示 M1 全部机制——服务提供者/消费者对（证明视图接口注入与依赖传导）、一个工具插件（注册后经三段管线执行）、一个管线拦截插件（pre-execute 否决或 post-execute 改写）、demo 配置中含一个禁用行（证明 disabled 卸载且保留行）。运行中打印各插件实例状态迁移（服务就绪启动/禁用停止），直观展示依赖驱动启停。完成的判据：`java -jar`（或 mvw exec）一条命令跑通全链路并输出可读的过程叙述（spec 用户故事 8，M1 的最终验收票，接缝 A 全覆盖）。

## Blocked by

05, 06

## Status

ready-for-agent

## Checklist

- [ ] duo-harness-example 模块：示例插件集 + demo plugins.yml + main 入口
- [ ] 服务提供者/消费者对：视图接口注入、依赖传导演示
- [ ] 工具插件 + 拦截插件：注册→三段管线→否决/改写演示
- [ ] 禁用行演示：disabled 卸载且依赖它的消费者随之停止
- [ ] 过程输出：插件实例状态迁移可读叙述（一条命令完整跑完）
- [ ] 文档同步：docs/ 架构章节补 example 使用说明（同一 diff）
