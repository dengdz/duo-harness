# 01: WorkspacePolicy 与权限预设插件

## What to build

路径感知的审批策略基座：`WorkspacePolicy` 服务（三档 + workspace 路径 + 包含性判定 + 判定方法）由新插件 `FsToolsPlugin`（tools 模块 fs 子包）发布；CliPlugin 注入服务并接线 REPL 内置命令 `/permission [档位]`（无参回显当前、未知档提示、切运行时态——重启回 yml 缺省）。判定矩阵测试锁定。

## Blocked by

无（可立即开工）

## Status
done（2026-09-15，随工单 02 一并验收覆盖）

## Checklist
- [x] WorkspacePolicy：mode 枚举（read-only / workspace-write / danger-full-access，缺省 workspace-write）+ root（缺省进程 cwd，yml `root` 可配）+ 包含性判定（canonicalize → 词法前缀+分隔符；符号链接 realpath；`..` 穿越拒绝）
- [x] 判定方法：工具名 + 参数 → allow / ask（write/edit 解析 `path` 字段；bash 无路径参数按档整体；解析失败保守 ask）
- [x] FsToolsPlugin（Boot 插件）：发布 "workspace" 服务 + 预留工具注册位（本单可空插件+服务形态）
- [x] CliPlugin：inject "workspace" 服务 + `/permission` 内置命令（内置命令优先于技能名解析）
- [x] 判定矩阵测试：三档 × {workspace 内写, 越界写, bash, 读类} 全组合；符号链接越界与 `../` 穿越用例

## Comments

- 交付随工单 02 落地：`WorkspacePolicy`（"workspace" 服务，`FsToolsPlugin` 发布）+ `WorkspacePolicyTest` 9 用例 + `WorkspaceGatePolicy`/`WorkspaceGatePolicyTest` 6 用例（档位裁决接审批管线）+ CLI `/permission` 查看与切换（`CliPluginTest.permissionCommandShowsAndSwitchesPreset`）。
- 验收覆盖（工单 02 的对照表步骤 1/4/6/7 + 验收记录）：`/permission` 回显与切换、workspace-write 档区内写短路放行（`审批决策：工具=write 结果=ALLOW 策略=workspace`）、越界写 ask 落 Web 卡片、read-only 档写一律 ask。验收期间发现并修复 BUG-20260915-01（档位裁决零调用方——判定基座未接管线），修复后重验通过。
