# 02: prompt 注册表

**What to build:** agent 域 `prompts` 服务：插件经 `register(registrant, fragment)` 贡献提示片段（source 名 + content），随注册作用域自动摘除；agent 每轮构造请求时按注册序动态组装最终 system 提示。yml 的 `llm.systemPrompt` 作为"用户指令片段"永远拼在最前；注册表与用户配置都为空才落内置缺省。这是 M7 技能指令段的挂载点。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Checklist

- [ ] `PromptFragment(source, content)` 与注册 API（与 tools / guard 注册同构，随作用域摘除）
- [ ] 每轮 buildRequest 动态组装：注册序拼接、用户片段最前、全空落 `DEFAULT_SYSTEM_PROMPT`
- [ ] 拔除贡献插件后片段从组装结果消失（作用域摘除语义）
- [ ] 测试（ChatRequest 捕获 seam，ToolCallingAgentTest 先例）：组装顺序 / 用户片段置顶 / 全空缺省 / 摘除生效
- [ ] 文档同步：CHANGELOG 未发布段记 prompts 服务

## Comments

spec：[../spec.md](../spec.md)。设计要点（grill Q2 定案）：不分节——片段自带角色语义；注册表内容不变则最终串不变，DeepSeek 前缀缓存天然不受影响。
