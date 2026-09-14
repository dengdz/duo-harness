# 02: CliPlugin 模块与 REPL

## What to build

新模块 `duo-harness-cli`：`CliPlugin`（Boot 插件，对称 WebPlugin）——apply 装配完整执行链（经共享装配器；会话续接/新建取独占锁，占用即 FAILED 点名会话）+ 虚拟线程 REPL 循环（/new、/exit、/技能名直调、审批 y/n、ask 选项/自由输入、思考模型叙述，交互沿 AgentReplMain 既有形态全量搬迁）。`/exit` 或 EOF = **idle**：循环退出、会话锁释放、回答者摘除，插件保持挂载。`ConsoleAnswerer` 从 example 上移本模块。

## Blocked by

01（使用共享装配器）

## Status
ready-for-agent

## Checklist
- [ ] 模块骨架 duo-harness-cli（pom 依赖 core/tools/session/agent/llm）+ CliPlugin（默认构造 System.in/out；注入构造 in/out/sessionsDir 测试 seam）
- [ ] ConsoleAnswerer 从 example 上移（含其测试迁移）
- [ ] /技能名直调解析随 REPL 迁移（AgentReplMain.resolveSkillInvocation → CliPlugin，测试同步）
- [ ] REPL seam 测试：脚本输入驱动——会话事件序列、/new 落新会话、审批 y/n 呈现、/技能名注入、/exit 与 EOF 后会话文件可重新加锁（idle）
- [ ] 占用 seam 测试：会话被同进程实例持有时 CliPlugin 启动 FAILED 并点名会话
- [ ] 装配 seam 测试：纯 CLI yml Boot 后交互工具在册（对齐 WebPluginAssemblyTest 先例；依赖 ~/.duo/config.yml 先例披露）
