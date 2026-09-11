# 05 — 端到端验收 Demo（filesystem server）

## What to build

M2 验收件（duo-acceptance 里程碑级）：demo.yml 扩展 + DemoMain 扩展 + 验收对照表。demo.yml 新增 MCP 配置行（filesystem server 指向临时目录）与审批策略配置；DemoMain 新增段落演示——MCP 工具自动出现在清单、经三段管线调用 `read_file` 读真实文件、审批策略拒绝演示、guard 治理演示、拔掉 MCP 配置行后工具消失。输出延续 plugin/status + demo/log 叙述风格，产出 M2 验收对照表（含预期日志原文快照，duo-acceptance 里程碑级范本）。完成的判据：一条命令（mvn -pl duo-harness-example -am package exec:java）跑通全部演示，用户按对照表逐行核对通过。

## Blocked by

02, 03, 04

## Status

done（2026-09-11 用户验收通过：一条命令全链路 BUILD SUCCESS，Demo 12 条对照全中）

## Checklist

- [x] 治理配置进 demo-m2.yml（审批 always-deny + 写保护插件）；MCP 连接行以运行时等价配置编程挂载（内核无"按 yml 行取 handle"的 API，编程挂载才能演示 dispose 拔除——等价 yml 形态在叙述行与 demo-m2.yml 注释中展示）
- [x] DemoMain 扩展 M2 段落：远端工具自动出现 / read_file 读真实文件 / guard 拦截涉密 / 审批拒绝写操作 / dispose 后工具消失
- [x] M2 验收对照表 `.scratch/m2-tools-mcp/acceptance.md`：运行命令 + 测试/演示两路径预期日志快照（§ 工单标记）+ 手动玩法
- [x] 文档同步：docs/01-入门/运行Demo.md 补 M2 段；limitations.md #5/#6 收敛；模块划分状态行 M2 全量落地
- [ ] 用户按对照表完成手动验收（done 的定义）

## Comments

### 自包含迷你 filesystem server

不用 npx filesystem server（依赖 node 与网络），example 模块内置
`MiniFileSystemServer`：SDK server 侧构建（真实 stdio 协议），`read_file` / `write_file`
两工具，根目录锁定在启动参数目录（路径归一化越界即拒）。由 MCP 连接作为子进程拉起，
写真实文件——"filesystem server 指向临时目录"的意图以自包含方式落地。

### 子进程 classpath 探测（exec:java 的坑）

`java.class.path` 在 exec:java（maven 同 JVM）下是 maven 自身的 classpath，不含项目类。
`subprocessClasspath()` 双 fallback：先试 `java.class.path`（IDEA / 裸 java 启动），
再从 context classloader 枚举 URL（maven ClassRealm 可枚举项目类与依赖 jar）。
实测 exec:java 下 fallback 生效，子进程正常拉起。

### 执行时序（demo 输出的三段治理证据）

- guard 拒绝涉密读取，署名（guard）——工单 04
- write_file 被治理插件声明 ask，always-deny 拒绝并署名策略——工单 03
- dispose 后 `mcp__files__read_file` 报"未注册"——注册即作用域 effect 的闭环——工单 02

### 套件叙述补齐（顺带）

mcp 模块 4 个测试类缺 `@BeforeAll` 套件叙述行（工单 01/02 的遗漏，
duo-acceptance 要求套件叙述必配）——补齐；EventsTest 叙述行的用例数漂移
（写 18 实跑 19）一并修正。

### 文档同步

- `docs/01-入门/运行Demo.md`：标题改 M1+M2，新增 M2 段叙述、机制表四行（MCP/审批/guard/连接即生命周期）、玩法（auto-approve 白名单放行写操作）、五个功能子包说明
- `docs/limitations.md`：#5 收敛为 guard 作用域语义限制 + 并发分类/scope 遮蔽待交付；#6 收敛为仅 Web/agent 循环未开始（M2 全量落地）
- `docs/04-架构/模块划分.md`：状态行 M2 全量落地
