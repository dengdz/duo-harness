# 01: WorkspacePolicy 与权限预设插件

## What to build

路径感知的审批策略基座：`WorkspacePolicy` 服务（三档 + workspace 路径 + 包含性判定 + 判定方法）由新插件 `FsToolsPlugin`（tools 模块 fs 子包）发布；CliPlugin 注入服务并接线 REPL 内置命令 `/permission [档位]`（无参回显当前、未知档提示、切运行时态——重启回 yml 缺省）。判定矩阵测试锁定。

## Blocked by

无（可立即开工）

## Status
ready-for-agent

## Checklist
- [ ] WorkspacePolicy：mode 枚举（read-only / workspace-write / danger-full-access，缺省 workspace-write）+ root（缺省进程 cwd，yml `root` 可配）+ 包含性判定（canonicalize → 词法前缀+分隔符；符号链接 realpath；`..` 穿越拒绝）
- [ ] 判定方法：工具名 + 参数 → allow / ask（write/edit 解析 `path` 字段；bash 无路径参数按档整体；解析失败保守 ask）
- [ ] FsToolsPlugin（Boot 插件）：发布 "workspace" 服务 + 预留工具注册位（本单可空插件+服务形态）
- [ ] CliPlugin：inject "workspace" 服务 + `/permission` 内置命令（内置命令优先于技能名解析）
- [ ] 判定矩阵测试：三档 × {workspace 内写, 越界写, bash, 读类} 全组合；符号链接越界与 `../` 穿越用例
