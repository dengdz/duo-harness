# M12 Spec：本机工具族与权限预设

> ADR-0012（2026-09-15 grill 裁定，六问全按推荐 + DSH 四域源码精查）为直接依据。基线 0.6.0 已发布；本里程碑把 agent 从"MCP 沙箱演示级文件能力"升级为"workspace 约束的真实项目操作能力"，并落地三档权限预设。目标版本 **0.7.0**（分支已切）。
>
> **过程记录**：grill 六问（模块归属 / workspace 绑定 / 命名与 MCP 行去留 / 预设形态 / bash 边界 / edit 语义）+ DSH 四域源码精查（fs/shell/sandbox/permission-presets）——采纳五设计点（三帽窗口纯函数、edit 归一匹配域、原子写、截断+回收结构、"一次 resolve 处处同根"）。理解关卡欠账按用户指示继续挂账（路线图留档）。

## Problem Statement

agent 的文件能力只有 MCP 沙箱演示级 files 工具（临时目录、单目录读写、无检索无编辑无命令执行）——无法真实操作用户项目，生产力存在最大缺口。同时写操作的安全边界缺失：没有 workspace 概念、没有路径约束、没有分档策略——"计划模式硬禁工具"（M7 limitation）与"bash 写范围不受限"都无处收敛。DSH 把 fs 五件套 + bash 列为默认启用的生产力核心，并配三档权限预设打包（策略语义非 OS 隔离）——差距明确且衔接路径清晰。

## Solution

`tools` 模块新增 `fs` 子包：`FsToolsPlugin`（Boot 插件）发布 `WorkspacePolicy` 服务并注册六个 DSH 短名工具——`read`（三帽窗口 + 精确总行数 + 自描述续读 footer + 二进制拒读）、`write`（原子替换 + 读前写闸门）、`edit`（LF 归一匹配域 + 四态结构化失败 + `replace_all`）、`glob` / `grep`（Java 自实现，跳 VCS 目录，截断回收）、`bash`（全新进程 + env 硬化 + 超时 clamp + 输出截断 + 非零退出 marker）。`WorkspacePolicy` 承载三档权限预设（read-only / workspace-write 默认 / danger-full-access）与路径包含性判定（canonicalize + 前缀比较——策略检查非内核边界，威胁模型显式声明）；REPL 内置 `/permission [档位]` 命令切运行时态（重启回 yml 缺省）。`agent-demo.yml` 移除 MCP files 行（本机工具取代，消除双写工具选型噪音；mcpfs 模块与 MCP 集成代码保留）。

## User Stories

1. As a CLI 使用者, I want agent 用 read 读取我项目里的文件（行窗口 + 续读指令）, so that 大文件按需分段消化不刷屏
2. As a CLI 使用者, I want agent 用 write 新建或整写文件, so that 生成物直接落盘
3. As a CLI 使用者, I want agent 用 edit 做精确字符串替换, so that 小改不必整写文件
4. As a CLI 使用者, I want agent 用 glob 按模式找文件、grep 按内容检索, so that 定位代码不靠猜
5. As a CLI 使用者, I want agent 用 bash 执行命令（构建/测试/git）, so that 真实开发闭环
6. As a CLI 使用者, I want 越过 workspace 的写与 bash 执行被审批拦截, so that 安全边界由我掌控
7. As a CLI 使用者, I want `/permission` 查看与切换三档, so that 风险等级随任务自调
8. As a Web 使用者, I want 同一套工具与审批在浏览器卡片呈现, so that 双入口能力对等
9. As a Web 使用者, I want read-only 档下写与 bash 的审批卡片明确标注, so that 我知道为什么被叫
10. As a 模型, I want read 的 footer 自带续读参数, so that 大文件分段读取无需猜测
11. As a 模型, I want edit 失败时得到匹配计数与补救指令, so that 自纠一次成功
12. As a 模型, I want bash 非零退出以 marker 呈现而非错误, so that 我能据退出码自行决策
13. As a 模型, I want 未读就改被拒并提示先 read, so that 不盲改我未见过的文件
14. As a 部署者, I want workspace 根经 yml 或启动参数配置, so that agent 操作我指定的项目
15. As a 部署者, I want 三档预设经 yml 配置缺省档, so that 风险基线部署时定
16. As a 框架维护者, I want 路径包含性判定单点（fs 与 bash 共用）, so that 两个执行面语义不漂移
17. As a 框架维护者, I want fs 工具不依赖 MCP 与网络, so that 零外部二进制/服务
18. As a 框架维护者, I want 工具目录对账测试覆盖新六件, so that 文档不漂移
19. As a CLI 使用者, I want MCP files 演示行移除后工具清单不再重复, so that 模型选型无噪音
20. As a 框架维护者, I want glob/grep 跳过 VCS 元数据目录, so that 检索结果干净且不误扫 .git

## Implementation Decisions

- **模块与包归属**：`tools` 模块新增 `fs` 子包（`dev.duo.harness.tools.fs`，package-info 齐备）——`FsToolsPlugin`（Boot 插件）+ 六工具 ToolDefinition + `WorkspacePolicy` 服务；权限预设语义内聚同一插件（服务与工具同源，避免跨插件服务时序）。`bash` 归同子包。
- **WorkspacePolicy 服务**（发布为 "workspace" 服务，FsToolsPlugin inject 自供）：承载 `mode`（read-only / workspace-write / danger-full-access，缺省 workspace-write）与 `root`（workspace 路径，缺省进程 cwd）；提供包含性判定（canonicalize → 词法前缀+分隔符；显式声明"策略检查非 OS 内核边界"）与档位查询。`/permission [档位]`（无参回显当前）改运行时态——重启回 yml 缺省；不持久化。
- **六工具参数与行为**（DSH 短名与语义，按本仓风格简化）：
  - `read`：`path` 必填、`offset`（1 起始行）、`limit`（行数，帽 2000）；单行 2000 字符截断、总量 50KiB 截断（footer 标注 + 续读参数）、扫全文件取精确总行数、二进制拒读（NUL 嗅探）、footer 三态（续读 / 未读尽 / 读尽含总行数）
  - `write`：`path` + `content`；原子替换（同目录临时文件 + `ATOMIC_MOVE`）；读前写闸门（覆盖已有文件须本会话已读——未读拒并提示"先 read"；新建豁免）
  - `edit`：`path` + `old_string` + `new_string` + `replace_all`（默认 false）；LF 归一匹配域（CRLF 写回恢复）；0 命中 / 多命中未 replace_all / 空串 / old==new 四态结构化失败（错误带匹配计数与补救指令）；成功不回显全文
  - `glob`：`pattern`（PathMatcher glob 语法）+ `path`（可选，默认 workspace 根）；`Files.walk` 遍历、跳 VCS 元数据目录（.git/.svn/.hg/.bzr/.jj）；按修改时间倒序；截断 100 条 + 未显示计数提示
  - `grep`：`pattern`（正则）+ `path`（可选）+ `include`（单 glob，拒绝取反与逗号列表）；逐文件逐行正则、命中输出 `路径:行号:文本`、截断 250 条 + 提示；二进制行跳过
  - `bash`：`command` 必填 + `timeoutMs`（可选，clamp 缺省 120s / 上限 600s）；每次调用全新进程（`bash -c`）+ env 硬化（`NO_COLOR/TERM=dumb/PAGER=cat`）；工作目录 = workspace 根；每流输出截断（完整输出提示走治理 spill 兜底）；**非零退出不是错误**——`[exit code: N]` marker 进正常结果
- **权限预设与审批联动**：`write` / `edit` / `bash` 静态声明 `requiresApproval()`=true；审批裁决经 WorkspacePolicy 档位——`danger-full-access` 放行、`workspace-write` 按"目标路径是否在 workspace 内"放行或 ask（bash 无路径参数：非 danger 档一律 ask）、`read-only` 一律 ask；判定解析失败的参数保守按 ask。读类工具（read/glob/grep）不触发审批。
- **MCP files 行移除**：`agent-demo.yml` 删除 MCP 编程挂载与相关叙述（`mcpfs` 模块与 `MiniFileSystemServer` 保留在仓）；工具目录对账测试同步（六件新增、`mcp__files__*` 移除）。
- **错误双轨**：结构化失败反馈 = 稳定语义 + 自纠指令（未找到带路径、多命中带计数与 replace_all 提示、闸门带"先 read"动作、glob 模式非法带修正建议）；基础设施故障（IO 崩溃）才 isError。

## Testing Decisions

- **只测外部行为**：工具入参 → 输出/会话副作用/异常类型；Terminal 呈现与策略内部结构不自动化。
- **测试 seam（一个新 seam：WorkspacePolicy 注入；其余复用）**：
  - **fs 工具单元 seam**：`FsToolsPlugin` 工具实例 + TempDir workspace——read 三帽窗口与 footer 三态、write 原子与闸门（未读拒/读后放行/新建豁免）、edit 四态失败与 CRLF 归一、glob 排序与 VCS 跳过与截断、grep 命中行输出
  - **WorkspacePolicy seam**：三档 × 路径包含性判定矩阵（workspace 内写：write 档放行 / read-only 档 ask；越界写：两档都 ask；bash：非 danger 档 ask）；`/permission` 运行时切换后判定随之变化
  - **路径安全 seam**：workspace 内符号链接指向外部 → 包含性判定拒绝写（canonicalize 后判定）；`../` 穿越 → 拒绝
  - **装配 seam（对齐 CliPluginAssemblyTest 先例）**：FsToolsPlugin yml Boot 后六工具在册 + WorkspacePolicy 服务可解析
  - **回归**：工具目录对账测试（六件入册 + `mcp__files__*` 移除后清单更新）、CliPluginTest / AgentReplMainTest（demo 移除 MCP 后端到端形态变化同步断言）
- **测试环境注意**：无 LLM/网络依赖（纯文件与进程）；bash 用例依赖系统 `bash` 存在（macOS/Linux 开发机满足，Windows 未承诺）。

## Out of Scope

- OS 级沙箱（Seatbelt/Landlock/bwrap/ACL）——路径包含性是策略检查非隔离；bash 写范围不受 workspace 约束（文档明示）
- 预设切换的会话事件持久化（重启回 yml 缺省）；多 workspace 并行
- read_image / 附件；web_search / web_fetch；run_code (PTC)
- glob/grep 的 .gitignore 语义、上下文行参数（-A/-B/-C）、ripgrep 捆绑
- bash 后台执行（run_in_background / jobs 域）、多 shell（pwsh）
- edit 的 diff 视觉渲染（Web 卡片呈现现有形态）、语法高亮
- 工具并行调用、steering/inbox、会话查询/导出、session-title
- MCP files 集成本身（mcpfs 模块保留；仅 demo yml 移除其行）

## Further Notes

- ADR-0012 为架构依据；术语表新增 `workspace` / `权限预设` / `读前写闸门` 三条。
- DSH 差异留档：① DSH bash 在 read-only 档靠 OS 沙箱围栏"执行但拦写"，我们无 OS 沙箱 → 改为审批 ask（诚实语义）；② DSH grep/glob 捆绑 ripgrep，我们 Java 自实现（个人项目规模性能足够，引擎留替换点）；③ DSH 写有版本 token + 读前写闸门双层，我们本期只做闸门（版本 CAS 留演进）。
- 理解关卡欠账（M3-M11）按用户指示继续挂账——M12 涉及审批管线联动，建议 `/implement` 前至少补 M6（交互 seam）一门。
- 工具目录对账测试与运行Demo 的验收叙述随本票同步（M11-04 建立的收尾清单沿用）。
