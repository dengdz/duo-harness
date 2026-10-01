# hooks:用外部脚本挂进工具管线

这篇讲怎么用 hooks 把**任意语言的外部脚本**挂进 agent 的工具执行管线——格式复用 Claude Code/Codex 的 hooks 配置，整文件粘贴即用。

## 能干什么

两个挂点，对应工具执行的前后：

- **PreToolUse**：工具执行**前**——可以阻断（deny）、放行（allow）、或转审批（ask）；
- **PostToolUse**：工具执行**后**——可以把结果改写为错误（不能假装撤销已执行的副作用）。

典型用法：写操作审计、敏感命令拦截、危险参数提醒——任何你喜欢的语言（bash/python/node…）都行。

## 配置在哪

`~/.duo/hooks.json`（用户级，全局生效）。装配里挂上 hooks 插件行后读取（opt-in，不装行零感知）：

```yaml
  - id: hooks
    name: dev.duo.harness.hooks.HooksPlugin
```

配置文件整体形态（matcher 组形态，与 Claude Code 同构）：

```json
{
  "hooks": {
    "PreToolUse": [
      {"matcher": "bash", "hooks": [{"type": "command", "command": "/path/to/guard.sh", "timeout": 30}]}
    ],
    "PostToolUse": [
      {"matcher": "Write|Edit", "hooks": [{"type": "command", "command": "/path/to/audit.sh"}]}
    ]
  }
}
```

也支持 Codex 的扁平形态（条目直接放 `command` 字段）。字段速查：

| 字段 | 说明 |
|---|---|
| `matcher` | 工具名过滤：精确名（`bash`）、多选（`Write\|Edit`）、正则皆可 |
| `type` | 只支持 `command` |
| `command` | 脚本路径（stdin 收载荷 JSON，见下） |
| `timeout` | 秒，缺省 600 |

## 脚本怎么写

脚本从 **stdin 收一个 JSON 载荷**（字段名与 Claude Code 同名）：

| 字段 | 说明 |
|---|---|
| `hook_event_name` | `PreToolUse` / `PostToolUse` |
| `tool_name` | 工具名 |
| `tool_input` | 工具参数 JSON |
| `cwd` | 当前工作目录 |
| `tool_response` | 仅 PostToolUse：工具结果 |
| `presenter_id` | 发起呈现位（`cli` / `web`） |

环境变量注入 `DUO_HOME`。

**裁定方式**（二选一）：

- **退出码**：`exit 2` = 阻断（stderr 内容回给模型作为拒绝理由）；`exit 0` = 放行；
- **stdout JSON**：`{"decision": "deny"}` / `{"decision": "allow"}` / `{"decision": "ask"}`——`ask` 交审批段走正常审批卡。

**失败语义是 fail-open**：钩子超时、崩溃一律放行并记警告——钩子是辅助栏，不是执法边界；「绝不放行」的硬闸门请用进程内 guard 或审批（见[多插件协同 §3.4](../03-高级/多插件协同.md)的分工讲解）。钩子与审批同段时先到先决、钩子只收不放——deny 占先可以省掉一次审批交互，但没有「跳过审批放行」的能力。

## 深读

- 全字段与边界：[插件配置参考 §hooks.json](../05-参考/插件配置参考.md)
- 决策记录：[ADR-0019](../adr/0019-扩展机制.md)
- hooks 与进程内插件监听的分工：[多插件协同 §3.4](../03-高级/多插件协同.md)
