# 02: 壳工程骨架 + 拉起后端开窗——最小可用桌面

## What to build

桌面壳的 tracer bullet：仓库根新建 `desktop/` Node/TS 工程（Electron + TypeScript + vitest + electron-builder 配置骨架；依赖引入已获 ADR-0039 Q2 红线 4 同意）。壳 main 进程完成最小编排链：JDK 21 探测（login shell 环境探测，mac GUI 启动不继承 shell PATH——DSH 教训照抄；缺失/版本不符弹指引对话框）→ 试绑 0 端口取空闲值 → 以 `DUO_WEB_PORT` 环境变量 spawn 后端 fat-jar（**stdin 用 pipe 保持打开、从不写入**）→ 逐行扫 stdout 认 `duo:web-ready` 锚点 → 主窗直连 `http://127.0.0.1:<port>` 加载现有 Web UI。启动超时（60s 量级）或后端异常退出 → 失败对话框（含 stderr 尾部摘要，长度截断防凭据泄漏）。

端到端可验：命令行启动壳 → 弹出窗口即是一个完整可用的 duo（会话/审批/工具全功能，因为就是现有 Web UI）。**实现首日必验**：空 stdin 下 cli 行 REPL 阻塞等待而非 EOF 退出（M27 机制认知：cli apply 即 REPL 主循环）——实测留记录于本票 Comments，若 EOF 退出则调整 stdin 策略并记档。

## Blocked by

01

## Status

ready-for-agent

## Checklist

- [ ] `desktop/` 工程链就绪（package.json/tsconfig/vitest/electron-builder 骨架；npm install 可跑）
- [ ] JDK 21 探测 + 缺失指引对话框（login shell 环境探测）
- [ ] 端口探测 + spawn 编排（env 注入 + stdin pipe 策略）+ 锚点解析 + 窗口加载
- [ ] 启动超时/异常失败对话框（stderr 尾部诊断截断）
- [ ] 空 stdin REPL 行为首日实测记录（Comments 留档，策略依实测定稿）
- [ ] S3 壳编排缝 vitest：mock 子进程锁 spawn 参数/锚点解析/超时路径
- [ ] 真机冒烟：命令行起壳得到可用 duo（截图留档）
