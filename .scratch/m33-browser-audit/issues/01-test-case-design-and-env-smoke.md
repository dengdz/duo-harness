# 01: 用例设计与环境 smoke——100 例清单落盘 + 隔离实例全通

## What to build

M33 首单，两件事：**①100 例清单落盘**——按 spec 配比矩阵（域① 12 / ② 12 / ③ 15 / ④ 10 / ⑤ 6 / ⑥ 8 / ⑦ 12 / ⑧ 8 / ⑨ 7 / ⑩ 10，跨域链 30 例）逐例写出：编号（`TC-<域码>-<序>`，域码 SESS/CYCLE/CARDS/HITL/ATTACH/STATUS/SSE/AUTH/INPUT/TOLER）、域、场景描述、走哪条缝（浏览器主缝 / HTTP 例外清单——仅协议负路径约 8 例）、行为判据（该出现的出现、不该出现的没出现，不逐字比对）、批次归属。清单落 `.scratch/m33-browser-audit/cases.md`。**②环境 smoke 全通**——隔离实例（独立 DUO_HOME + 随机端口）起得来、内置浏览器连得上、token 闭环（登录态到子资源）、三件套 fixture 就绪（微型 MCP echo server 一行命令可起、vision 图片夹具、真实 git 仓库工作区）。smoke 失败即回头改方案，不带病铺 100 例。spec 见 [spec.md](../spec.md)，裁定见 [ADR-0035](../../../docs/adr/0035-M33浏览器实测立项决策.md)。

## Blocked by

None (can start immediately)。

## Status

ready-for-agent

## Checklist

- [ ] cases.md 落盘：100 例逐例含编号/域/场景/缝/行为判据/批次归属，十域合计 100、跨域链 30，与 spec 矩阵一致
- [ ] 隔离实例 smoke：独立 DUO_HOME + 随机端口启动成功，浏览器打开页面渲染三区，token 闭环到子资源（无 403）
- [ ] 真实模型链路 smoke：发一条真实消息收到模型回复，SSE 实时呈现
- [ ] MCP fixture smoke：echo 型 stdio server 挂上，状态面连接器区可见
- [ ] vision 与 git 工作区夹具就绪：图片可上传，git 工作区文件可被读/改
- [ ] 装配核对：yml 中 web 行在 cli 行之前（DuoMain 预检红线），启动无「服务解析失败」warn
- [ ] 批次切分定稿：5 批 × 20 的例号归属表（环境形态相近同批、轻环境先跑攒会话数据）

## Comments

- 环境先例：隔离 DUO_HOME + 随机端口（M23-02）；「evaluate 原生点击」规避鼠标竞态（m8-web/issues/03）；web 行须在 cli 行前（DuoMain fail-fast）。
