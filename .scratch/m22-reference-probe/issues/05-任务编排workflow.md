# 05: T-09 任务编排 workflow

**What to build:** 编排 DSL 宿主 API（agent/parallel/pipeline/phase 等）、执行隔离（worker 线程/子进程）、协议桥、上限参数。两家各一份五段模板文档，落 docs/research/<别名>/子代理与编排/。

**对应关系：** DSH workflow+ptc-runtime/code-runtime ↔ ZCode dynamic-workflow(+runtime)

**Blocked by:** None (can start immediately)

**Status:** done（2026-09-21）

- [x] 两家文档落 `docs/research/<别名>/<域目录>/`（五段齐全：机制全貌/关键流程/接口参数要点/边界与坑/对 duo 的启示）
- [x] 锚点逐条给且抽查 3 处可对上（±3 行）；默认值与源码一致；未核实处明示
- [x] docs/research/index.md 登记两行
- [x] CHANGELOG 0.17.0（开发中）记账
- [x] 向用户汇报该批要点（含对 duo 的启示段摘要）
