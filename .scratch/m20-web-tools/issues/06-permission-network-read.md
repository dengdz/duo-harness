# 06: 权限"网络读"档位

## What to build

fetch/search 接入三档权限模型（ADR-0021 决策 8）：新设独立于本地读的网络读类别——`read-only` 档一律 ask（只读语义不出网），`workspace-write` 与 `danger-full-access` 档放行。默认档日常免审批、只读档越界必经确认。

## Blocked by

01

## Comments

- 2026-09-20（夜间自主实现）：网络读集合 + 三档判定落地，判定矩阵测试含未知工具保守 ask 回归。
- 2026-09-20（用户验收实测发现缺口）：read-only 档 web_fetch 未弹审批——根因：ApprovalGate 纪律为"声明归声明者、裁决归策略"，仅有裁决侧（WorkspacePolicy.decide）分支时闸门对未声明调用不介入（bash 有 ask 是因其工具级静态声明 requiresApproval，非闸门主动）。修复：两工具**档位感知声明** requiresApproval（read-only 声明交闸门 ask；其余档不声明——静态恒声明会因"审批即独占"让默认档退出并行池），workspace 经 WebToolsPlugin optionalInject 注入（ADR-0019）。新增 WebTierLinkageTest 四用例（read-only ask 且拒绝零网络请求 / 批准进工具本体 / workspace-write 与 danger 不声明直通）。ADR-0021 决策 8 已补记声明侧机制。模块 165 全绿。**待用户复验场景 6/7**。

- 2026-09-20：用户手动验收通过（含 read-only ask 复验、baeldung 正文修复复验、Tavily 搜索接力），工单收口。
## Checklist

- [ ] `WorkspacePolicy` 新增网络读集合与 decide 分支：read-only → ASK，workspace-write / danger → ALLOW
- [ ] 与工具 `requiresApproval` 声明联动按既有机制（bash 先例）：档位闸门前置短路，ask 委托审批管线
- [ ] 三档判定测试（先例 BashTierLinkageTest/WorkspacePolicyTest）：含"未知工具保守 ASK 不回归"
- [ ] `/permission` 运行时切档对 web 工具立即生效的联动验证（busySafe 切档既有语义）
