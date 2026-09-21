# 05: bash 输出三层与 spill

## What to build

bash 输出不再单层内存截断：inline 尾窗（默认 30k 字符）保最近输出；全量落盘为 spill 文件并回传 spillPath，模型可用 read 回读完整输出——大输出不丢信息。spill 帽 64MiB，超帽告警不静默（修 DSH「静默删 spill 不告警、SIGKILL 留残渣」两坑）；task-output 读的是 task-output 尾窗（默认 32k 字符）。三层预算 yml 可配（挂 fs-tools 行 config，沿用既有解析器先例：缺席=缺省、错误即插件 FAILED）。前台与后台任务共用同一输出分层机制。

决策依据：ADR-0025 决策二（输出三层预算段）；探测文档 DSH 本机执行工具族.md 的 OutputCollector/spill 语义与已知坑。spill 文件落 Duo home 临时区，进程退出清理。

## Blocked by

04（task-output 尾窗属 task 面输出路径）

## Status
ready-for-agent

## Checklist
- [ ] 输出分层机制：inline 尾窗 + spill 落盘 + spillPath 回传，前台后台共用
- [ ] 超帽告警不静默：64MiB 触顶显式告警文案，spill 文件退出清理（无残渣）
- [ ] task-output 尾窗 32k 生效
- [ ] yml 三预算可配（fs-tools 行 config），错误配置点名报错
- [ ] 测试（先例 FsBashToolTest seam）：分层边界、回读一致性、超帽告警、配置解析
- [ ] 工单级验收件：海量输出命令（如 concat 大文件）演示尾窗 + spillPath 回读，用户手动确认
- [ ] CHANGELOG 未发布段记账
