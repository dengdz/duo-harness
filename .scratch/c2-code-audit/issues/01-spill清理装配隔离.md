# 01: spill 清理装配隔离（P1 止血）

## What to build
fs-tools 插件树停止时只清理本装配创建的 bash spill 临时文件，不再触碰共享目录里其他装配/进程的活文件。同进程多装配（子代理树高频启停、Web 多会话）与跨进程并存场景下，正在运行会话的"read 回读全文"承诺不因别的树停止而被破坏；spill 文件命名防跨进程碰撞。

证据锚点：审计报告 P1-1（`reviews/2026-09-28-全库补丁式代码审计.md`）。

验收标准（用户可感）：装配 A 运行中创建 spill 文件后，装配 B 启停任意次，A 的 spill 文件始终存在且可回读；B 停止后自身文件清空无残渣。

## Status
in-progress（实现完成待手动验收，2026-09-28）

## Checklist
- [x] spill 文件登记到创建方实例（SpillLedger 装配级登记簿，bash 前台与后台任务共用）
- [x] 文件命名防跨进程碰撞（进程号前缀 + 进程内序号）
- [x] 跨装配并存回归用例（SpillLedgerTest 3 用例：B 停止不误删 A 的活文件；B 停止后自身清理干净）
- [x] 删除失败不再静默吞（slf4j warn 留痕）
- [x] CHANGELOG 记账（用户可见：多会话/子代理并存下不再丢输出）

## Comments

- 2026-09-28 实现：新增 fs 包 `SpillLedger`（分配 `next(label)` 带进程号前缀 + 回收 `dispose()` 只删在册）；`FsBashTool`/`BackgroundTaskRegistry`/`BackgroundTask` 接线共享登记簿（registry 与 bash 既有便捷构造保留、内部自建登记簿——测试场景）；`FsToolsPlugin` 停止路径 `cleanupSpillDir()` 删除光共享目录 → `spillLedger.dispose()`。设计裁定：崩溃残留不主动清扫（误删活文件代价 >> 残留临时文件代价，原"删光全部"正是 P1 根因）；「进程退出无残渣」承诺由本装配无残渣承载，注释记档。测试：SpillLedgerTest 3（含跨装配 P1 回归锁）+ FsBashToolTest 18 + BackgroundTaskRegistryTest 6 全绿（27/27）。
