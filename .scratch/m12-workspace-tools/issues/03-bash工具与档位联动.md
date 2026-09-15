# 03: bash 工具与档位联动

## What to build

bash 工具（每次调用全新进程，工作目录固定 workspace 根）——env 硬化、超时 clamp、输出截断、非零退出 marker；与三档权限预设联动：danger 放行、其余档经审批 seam ask（终端 y/n / Web 卡片）。

## Blocked by

01（档位裁决经 WorkspacePolicy 服务）

## Status
ready-for-agent

## Checklist
- [ ] 全新进程执行（`bash -c`）+ env 硬化（NO_COLOR/TERM=dumb/PAGER=cat）+ 工作目录固定 workspace 根
- [ ] 超时 clamp（模型可传低值；缺省 120s、上限 600s）→ 超时 marker（`[timed out]`）+ 进程树终止
- [ ] 每流输出截断（完整输出提示走治理 spill 兜底）+ 非零退出 `[exit code: N]` marker 非 error
- [ ] requiresApproval = true（静态声明）+ 档位联动判定测试：danger 放行 / workspace-write 与 read-only 档 ask
- [ ] 呈现回归：终端叙述行与 Web 工具卡既有形态零特化验证
