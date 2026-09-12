# 02: prompt 注册表

**What to build:** agent 域 `prompts` 服务：插件经 `register(registrant, fragment)` 贡献提示片段（source 名 + content），随注册作用域自动摘除；agent 每轮构造请求时按注册序动态组装最终 system 提示。yml 的 `llm.systemPrompt` 作为"用户指令片段"永远拼在最前；注册表与用户配置都为空才落内置缺省。这是 M7 技能指令段的挂载点。

**Blocked by:** None (can start immediately)

**Status:** implemented（2026-09-13，待用户验收）

## Checklist

- [x] `PromptFragment(source, content)` 与注册 API（与 tools / guard 注册同构，随作用域摘除）
- [x] 每轮 buildRequest 动态组装：注册序拼接、用户片段最前、全空落 `DEFAULT_SYSTEM_PROMPT`
- [x] 拔除贡献插件后片段从组装结果消失（作用域摘除语义）
- [x] 测试（ChatRequest 捕获 seam，ToolCallingAgentTest 先例）：组装顺序 / 用户片段置顶 / 全空缺省 / 摘除生效
- [x] 文档同步：CHANGELOG 未发布段记 prompts 服务

## 实现记录（2026-09-13）

- 契约：`PromptFragment`（source+content，空白内容拒绝）+ `PromptRegistry`（具体类，Session 同风格；register(Context, fragment) 随作用域摘除；compose() 每轮组装）
- `ToolCallingAgent` 构造演进：新增 PromptRegistry 形态（4 个构造器），旧 String 形态委托包装为用户指令片段；语义变化——空白 systemPrompt 从"构造报错"改为"落内置缺省"（spec 定案的拼接语义），构造校验测试同步更新
- AgentReplMain 切换 PromptRegistry 装配；ChatRepl 不动（直答路径）
- 测试：ToolCallingAgentTest 增组装顺序/作用域摘除/缺省回落 3 例；全量回归绿

## Comments

spec：[../spec.md](../spec.md)。设计要点（grill Q2 定案）：不分节——片段自带角色语义；注册表内容不变则最终串不变，DeepSeek 前缀缓存天然不受影响。
