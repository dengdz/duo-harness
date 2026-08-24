# 07 — 示例插件 Demo 全链路

## What to build

duo-harness-example 模块：一组示例插件 + demo 配置 + 可执行入口，一条命令演示 M1 全部机制——服务提供者/消费者对（证明视图接口注入与依赖传导）、一个工具插件（注册后经三段管线执行）、一个管线拦截插件（pre-execute 否决或 post-execute 改写）、demo 配置中含一个禁用行（证明 disabled 卸载且保留行）。运行中打印各插件实例状态迁移（服务就绪启动/禁用停止），直观展示依赖驱动启停。完成的判据：`java -jar`（或 mvw exec）一条命令跑通全链路并输出可读的过程叙述（spec 用户故事 8，M1 的最终验收票，接缝 A 全覆盖）。

## Blocked by

05, 06

## Status

done

## Checklist

- [x] duo-harness-example 模块：示例插件集 + demo plugins.yml + main 入口
- [x] 服务提供者/消费者对：视图接口注入、依赖传导演示
- [x] 工具插件 + 拦截插件：注册→三段管线→否决/改写演示
- [x] 禁用行演示：disabled 卸载且依赖它的消费者随之停止
- [x] 过程输出：插件实例状态迁移可读叙述（一条命令完整跑完）
- [x] 文档同步：docs/ 架构章节补 example 使用说明（同一 diff）

## Comments

- 验收命令：`mvn -pl duo-harness-example -am package exec:java`（package 前缀是必须的：exec:java 为无生命周期 goal，reactor 兄弟需先产出构件；exec.skip 属性模式让其余模块跳过，3.1.0 的 required 参数校验先于 skip 故根 management 需占位 mainClass）。
- 级联停止的演示采用运行时 dispose 提供者（消费者 UNLOADING→PENDING 叙述）而非 boot 期 disabled 行触发——disabled 行若被依赖会导致 boot 审计 PENDING 失败，两机制分开演示更准确。
- core 增量：Boot 补 from(Path, onRootCreated) prepare 钩子（对齐 DSH prepare 语义）——demo 状态监听在树装载前挂载，boot 期迁移全程可见。
- demo.yml 特意把消费者行排在提供者前：状态叙述直接展示"行序无加载语义"。
- ocr 审查修复：prepare 参数 null 防御、echo 工具参数 schema 诚实化（声明 input 而非 NullNode）、example 的 slf4j-simple 显式 runtime（demo 运行期日志可见）；驳回一条（parent version 字面量系 Maven 解析必需）。
- DemoMainTest 冒烟断言输出叙述覆盖全部机制；77 用例（66 core + 10 tools + 1 demo）三连跑全绿。M1 收官。
