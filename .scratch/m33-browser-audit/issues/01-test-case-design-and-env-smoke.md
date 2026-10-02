# 01: 用例设计与环境 smoke——100 例清单落盘 + 隔离实例全通

## What to build

M33 首单，两件事：**①100 例清单落盘**——按 spec 配比矩阵（域① 12 / ② 12 / ③ 15 / ④ 10 / ⑤ 6 / ⑥ 8 / ⑦ 12 / ⑧ 8 / ⑨ 7 / ⑩ 10，跨域链 30 例）逐例写出：编号（`TC-<域码>-<序>`，域码 SESS/CYCLE/CARDS/HITL/ATTACH/STATUS/SSE/AUTH/INPUT/TOLER）、域、场景描述、走哪条缝（浏览器主缝 / HTTP 例外清单——仅协议负路径约 8 例）、行为判据（该出现的出现、不该出现的没出现，不逐字比对）、批次归属。清单落 `.scratch/m33-browser-audit/cases.md`。**②环境 smoke 全通**——隔离实例（独立 DUO_HOME + 随机端口）起得来、内置浏览器连得上、token 闭环（登录态到子资源）、三件套 fixture 就绪（微型 MCP echo server 一行命令可起、vision 图片夹具、真实 git 仓库工作区）。smoke 失败即回头改方案，不带病铺 100 例。spec 见 [spec.md](../spec.md)，裁定见 [ADR-0035](../../../docs/adr/0035-M33浏览器实测立项决策.md)。

## Blocked by

None (can start immediately)。

## Status

in-progress（2026-10-02 agent 执行完毕，**待用户抽查 smoke 证据后转 done**——批 1 开工不被阻塞）

## Checklist

- [x] cases.md 落盘：100 例逐例含编号/域/场景/缝/行为判据/批次归属，十域合计 100、跨域链 30，与 spec 矩阵一致（文内覆盖对账表自证）
- [x] 隔离实例 smoke：`DUO_HOME=/tmp/duo-m33/home` + 随机端口（实际 62980）启动成功，浏览器打开页面渲染三区，token 闭环到子资源（无 403）
- [x] 真实模型链路 smoke：「跑 ls -la」任务实测通过——用户消息上屏 → 终端卡（bash）→ 流式 markdown 回复 → 文件引用卡，全链呈现（anthropic 兼容 / deepseek-flash）
- [x] MCP fixture smoke：echo 型 stdio server 挂上——日志实证「已连接 + 4 个工具已注册」；状态面连接器区**受 BUG-20261002-01 影响呈空**（连接本体不受影响，批 4 以对话触发 ping 工具实证桥接）
- [x] vision 与 git 工作区夹具就绪：红/蓝 512 图 + 27MB 噪声大图（超限用例）；git 工作区含 .gitignore/被忽略文件/多目录结构 + greet 技能夹具；**deepseek-flash 图片支持未验证**——ATTACH-05/06 实测拒图则按阻塞记因
- [x] 装配核对：web 单呈现位装配（无 cli 行，行序预检不适用），启动无「服务解析失败」warn、无 FAILED
- [x] 批次切分定稿：5 批 × 20 归属表落 cases.md（轻环境先跑攒会话数据 → 重型 fixture 后置 → 栅栏/容错压轴）

## Comments

- 2026-10-02 smoke 执行记录：隔离环境 `/tmp/duo-m33/`（home/workspace/fix/mcp-classes/logs 三件套齐）；装配 `/tmp/duo-m33/m33-smoke.yml`（agent-demo.yml 裁剪：去 cli/tool-stats，加 echo MCP 行，port 0 → 实际 62980）；实例保持运行中供批 1-5 复用（后台任务 exec_c4c4c38b）。
- **执行口径裁定（全战役适用）**：IAB 内 Playwright 定位器点击/回车全线超时（节点稳定、无遮挡、按钮可见可点仍超时），**原生 evaluate 点击一发即中**——与 m8-web/issues/03 先例完全一致，重申为 M33 全程口径：填入用原生 setter + input 事件，发送用原生 click。
- **IAB 文件选择器不受支持**（skill 明示）——ATTACH 上传类用例批 3 执行时优先试 evaluate DataTransfer 粘贴路径，不通则按阻塞记因（环境限制）。
- **发现 BUG-20261002-01**：GET /api/status 空响应（静默断连），状态面五区块全「—」——已建档 + 台账索引；对域⑥ STATUS 八例的执行口径调整见档案尾注。修复归修复批，本期不修。
- smoke 证据：`.scratch/m33-browser-audit/evidence/BUG-20261002-01-状态面空-冒烟通过面.png`（对话面全通 + 状态面空同帧）。
