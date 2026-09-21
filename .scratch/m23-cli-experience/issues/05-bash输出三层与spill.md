# 05: bash 输出三层与 spill

## What to build

bash 输出不再单层内存截断：inline 尾窗（默认 30k 字符）保最近输出；全量落盘为 spill 文件并回传 spillPath，模型可用 read 回读完整输出——大输出不丢信息。spill 帽 64MiB，超帽告警不静默（修 DSH「静默删 spill 不告警、SIGKILL 留残渣」两坑）；task-output 读的是 task-output 尾窗（默认 32k 字符）。三层预算 yml 可配（挂 fs-tools 行 config，沿用既有解析器先例：缺席=缺省、错误即插件 FAILED）。前台与后台任务共用同一输出分层机制。

决策依据：ADR-0025 决策二（输出三层预算段）；探测文档 DSH 本机执行工具族.md 的 OutputCollector/spill 语义与已知坑。spill 文件落 Duo home 临时区，进程退出清理。

## Blocked by

04（task-output 尾窗属 task 面输出路径）

## Status
done

## Comments
- 2026-09-22：实现与双轴审查完成，全量 BUILD SUCCESS（tools 190 / agent 176 / web 74 / cli 34 / session 30 / example 14）。审查修复 6 项（见下报告）。待用户手动验收（海量输出 spill 演示）后转 done。
- 记档不修：cleanupSpillDir 删全目录在双进程共用 Duo home 时会互删在用 spill（个人工具单实例常态，多实例归 1.0 后）；spillMaxChars 按字符计口径已统一文档措辞（「64Mi 字符」，字符与字节在 UTF-8 中文下最多 3 倍差）。

## Checklist
- [x] 输出分层机制：inline 尾窗 + spill 落盘 + spillPath 回传，前台后台共用
- [x] 超帽告警不静默：触顶显式告警文案 + 落盘失败独立文案（回读不可用点名），spill 文件退出清理（无残渣）
- [x] task-output 尾窗 32k 生效
- [x] yml 三预算可配（fs-tools 行 config），错误配置点名报错
- [x] 测试（先例 FsBashToolTest seam）：分层边界、回读一致性、超帽告警、配置解析
- [x] 工单级验收件：海量输出命令（如 concat 大文件）演示尾窗 + spillPath 回读，用户手动确认（2026-09-22 通过）
- [x] CHANGELOG 未发布段记账

## 审查轮（2026-09-22，双轴，基点 efe0ee6）

**覆盖**：新增 1 + 改动 6 代码文件 + 1 测试文件全审（覆盖率 100% 全集口径）

### 阻断（已修）

- **finish() 后 writerOpened 残留 → 迟到 accept NPE 读线程静默死**（Standards 硬违规，正违「告警不静默」）——closed 标志停写，迟到 accept 只计丢弃。
- **后台任务从不调 finish() → BufferedWriter 8k 缓冲未刷，回读缺尾**（Spec 硬伤）——settle 与注册表关闭两处补 finish。
- **落盘 IO 失败静默折入超帽但文案报「已落盘可回读」指向残缺文件**（文案失真）——spillFailed 独立标记 + 「回读不可用」专属文案。
- **后台 output() 的 spilled 路径行拼中部 → task-output 32k 尾窗裁掉路径**（回传不保证）——指引行置尾。
- **CHANGELOG 未记账 + 工具目录 bash 条目旧 100k 截断语义**（红线 6/3）——已补。
- **超帽数字虚高**（trimmedTotal 含丢弃与失败量）——改 spilledChars 实落口径。

### 建议（处置）

- **spillMaxChars 字符/字节口径**——文档统一「字符」措辞（UTF-8 中文最多 3 倍差，上限偏保守方向安全）；ADR 已批准不改。
- **cleanupSpillDir 删全目录**双进程互删——个人工具单实例常态，记档。
- StreamCapture(int) 兼容构造删（无人用 + spillPath NPE 隐患）、(long) 冗余强转、FQN、output 段非对象静默改缺省、residue 用例 Files.list 关闭——已修。
- 前后台 spill 组装逻辑重复（BackgroundTask.output 与 appendSpillInfo）——形态不同（状态行 vs 退出码上下文），保持。

### 测试覆盖

- FsBashToolTest +5：spill 分层（路径回传+read 回读一致性）、超帽告警（丢弃量可见）、小输出无残渣、task-output 尾窗可配、config 解析（缺席缺省/字段生效/非法点名）
- 旧截断用例改造：defaultBudgetKeepsLargeOutputBoundedViaSpill（默认预算大输出=尾窗+spill 提示、返回体积有界）
