# 06: 权限"网络读"档位

## What to build

fetch/search 接入三档权限模型（ADR-0021 决策 8）：新设独立于本地读的网络读类别——`read-only` 档一律 ask（只读语义不出网），`workspace-write` 与 `danger-full-access` 档放行。默认档日常免审批、只读档越界必经确认。

## Blocked by

01

## Status

ready-for-agent

## Checklist

- [ ] `WorkspacePolicy` 新增网络读集合与 decide 分支：read-only → ASK，workspace-write / danger → ALLOW
- [ ] 与工具 `requiresApproval` 声明联动按既有机制（bash 先例）：档位闸门前置短路，ask 委托审批管线
- [ ] 三档判定测试（先例 BashTierLinkageTest/WorkspacePolicyTest）：含"未知工具保守 ASK 不回归"
- [ ] `/permission` 运行时切档对 web 工具立即生效的联动验证（busySafe 切档既有语义）
