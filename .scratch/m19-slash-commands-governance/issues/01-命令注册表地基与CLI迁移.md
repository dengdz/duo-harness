# 01: 命令注册表地基 + CLI 迁移

**What to build:** agent 域立起 "commands" 命令注册表服务，CLI 的 /new、/permission、/plan、/exit 从 replLoop 硬编码迁移为注册调用——终端敲命令走注册表执行、会话日志见 command/run 与 command/done 两事件、模型历史无命令文本；agent 执行期间 busySafe 命令（/permission）立即生效，非 busySafe（/new）得到明确"执行中，需等待空闲"提示；未知 `/xxx` 报错附可用命令清单。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

语义权威：ADR-0020 决策 1-5；spec 见 `.scratch/m19-slash-commands-governance/spec.md`（命令注册表节）。

- [x] agent 域发布 "commands" 服务（保留裸名）：命令 = name + description + 适用呈现位（ANY/CLI/WEB，缺省 ANY）+ busySafe（缺省 false）+ handler；注册 API 沿 `register(registrant, definition)` 惯例，注册即注册方作用域 effect，插件停止自动摘除
- [x] handler 收统一命令上下文：参数文本、当前会话供给、回显通道、发起呈现位标记、请求结束回调；同步执行于呈现位进程内、返回文本结果；命令异常收敛为回显文本（不影响 agent 单飞与呈现位存活）
- [x] `command/run`（名 + 参数）与 `command/done`（结果）两事件落会话日志（先 run 后 done，崩溃断口可观测）
- [x] 投影排除：deriveMessages 忽略 command 事件（模型不可见由投影纯函数保证）；不参与 tool 配对算法、不占消息窗口计数（同批断言 M10/M13/M16 既有算法零牵动）
- [x] busySafe 分级：agent 单飞占用时 busySafe 命令立即执行回显；非 busySafe 回应"执行中，需等待空闲"
- [x] 入口顺序：命令注册表 → 技能直调（照旧进模型历史）→ 未知命令报错附可用命令清单；适用面不符提示"该命令仅在 X 可用"
- [x] CLI 四命令迁移（行为不变：/new 换绑、/permission 查看/切档含 workspace 缺席降级、/plan 进出与续接、/exit idle 语义）；/exit 经上下文"请求结束回调"表达
- [x] 测试走缝 1（agent 单元：注册/摘除/适用面/busySafe/异常收敛）+ 缝 2（投影排除与零牵动断言）+ 缝 3 CLI 面（脚本 REPL 驱动命令执行回显与事件落盘）；只断言外部行为
