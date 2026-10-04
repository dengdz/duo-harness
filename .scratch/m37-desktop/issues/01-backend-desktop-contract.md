# 01: 后端桌面对接两件——端口环境变量覆盖 + stdout 机器锚点行

## What to build

桌面壳要吃的两处后端契约面，一次锁死（ADR-0039 决策六；本票纯后端，壳归 02 起）。其一：`WebPlugin` 读端口改三级优先——系统属性 `duo.web.port`（测试注入口）> 环境变量 `DUO_WEB_PORT`（壳注入口）> web 行 `config.port`（缺省 8080 不变），与 DuoHome 解析优先级（sysprop > env > 缺省）同构；CLI/Web 直跑用户行为零变化。其二：后端在现有带 token URL 的人读打印处旁新增机器锚点行 `duo:web-ready url=<完整 URL 含 token>`——人读文案原样保留（1.x 兼容承诺内），壳只认锚点不认文案。

验收标准：S1 锚点缝与 S2 端口三态优先级缝回归锁全绿；本票为 M37 首笔提交，随附 CHANGELOG 记账（含预研产物：两家桌面端研究文档增量补扫、ADR-0039、术语表桌面壳词条——「独立研究增量随消费里程碑同 diff 记账」口径）。

## Blocked by

None (can start immediately)

## Status

ready-for-agent

## Checklist

- [ ] 端口三级优先解析落地（env 值非法/越界时回落 yml 值并日志点名，不启动失败）
- [ ] `duo:web-ready` 锚点行落地（人读文案原样保留，兼容锁断言两者同在）
- [ ] S1 锚点缝回归锁：拉真 JVM 进程断言锚点存在、URL 含 token 可解析
- [ ] S2 端口缝回归锁：sysprop / env / yml 三态各一锁（env 注入口按 DuoHome 先例落可测形态）
- [ ] 全量回归绿 + CHANGELOG 记账（M37 首笔提交随附预研产物入账）
