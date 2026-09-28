# 06: Web 域·WebFace 同包拆分（H-10）

## What to build

1812 行 WebFace 单体按职责同包拆五块：门面（生命周期 + 装配参数）、入口栅栏（Host/Origin/token 三级校验）、标签会话（TabContext 绑定与轮次线程局部）、SSE 与事件投影（客户端、心跳、游标、fail-closed 宽限）、端点处理器（17 端点 + route 声明表）。包私有协作，**行为零变化**——route 表声明式形态保留，无路由 / 校验 / 游标语义改动。WebFace.start 13 参 5 层重载顺带参数对象化。

步子红线：只做同类职责切分，不重新设计；端点扩展点（插件加 HTTP API）不做，归后续域。

## Blocked by

无（与装配链 03~05 逻辑独立，可并行）。

## Status
in-progress（待手动验收，攒统一拍板）

## Checklist
- [x] 拆分落位（8 件协作域）：门面 WebFace 365 + WebEntryGate 91 / TabContext 47 / WebSseHub 266 / WebTabs 207 / WebEndpoints 583 / WebSessionEndpoints 380 / WebHttp 135 / WebServiceViews 33——web 域最大文件 583 ≤600 行（度量①达标；WebFace 1812 → 门面 365 退出全仓 top10）
- [x] start 5 层重载保留公开签名（外部引用面 port/start/stop/removeClient/SseClient 等零变化），装配状态收协作类包私有字段
- [x] 17 端点 route 声明表形态保留（对话交互域与会话生命周期域分册），行为零变化
- [x] Web 域既有测试（10 类 97 用例，含行序契约 fail-fast）全绿 + 全仓 `mvn verify` 绿（2026-09-28 exit 0）

## Comments

- 2026-09-28：拆分中发现并修复一处重写遗漏（start 丢 bindTab 订阅——WebFaceTest.sseLiveFramesCarryEventIds 兜住，实时帧断链即刻暴露后补回）；过程三次 sed/perl 大段删除切坏语法，最终整读重写收口——**大文件手术禁用行号碎片操作，整文件重写为纪律**（入 review-log）。
- 待手动验收（攒统一拍板）。
