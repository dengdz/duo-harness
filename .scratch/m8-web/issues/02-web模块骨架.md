# 02: web 模块骨架——WebPlugin + HTTP + SSE + 静态页壳

**What to build:** 新模块 `duo-harness-web`（依赖 core + tools + session + agent）：`WebPlugin`（Boot yml 一行，config `{port}` 省略默认 8080，**只绑 127.0.0.1**，启动打印访问地址）；HttpServer 承载三个端点——静态单页（classpath 资源，双区布局壳）、`GET /api/status`（Context.snapshots + tools.list 的 JSON）、`GET /api/events`（SSE 流：订阅 Session 监听器，新事件转 `data:` 行推送；连接断开时注销监听器）。本期端点用假数据/空流即可（对话与状态面在后续工单填充），骨架的验收是"浏览器打开页面、SSE 连接可建立"。

**Blocked by:** 01（快照与监听器两个地基 API）

**Status:** implemented（2026-09-13，待用户验收）

## Checklist

- [x] 新模块 `duo-harness-web`：pom（core/tools/session/agent 依赖）+ 根包 `dev.duo.harness.web` + 根 POM 聚合
- [x] `WebPlugin`（Boot yml 一行）：config `{port}` 可省默认 8080；HttpServer 只绑 127.0.0.1；起停挂插件生命周期（dispose 即停）；装配自己的会话（续接/新建 ~/.duo/agent-sessions）
- [x] 静态单页壳（classpath `/web/index.html`）：双区布局 + 亮色 DSH token 骨架（deepseek 蓝 / 浅灰分层 / 柔光阴影 / 大圆角 / 系统字体栈）
- [x] `GET /api/status`：返回 `{plugins: [{name, state}], tools: [{name, description}]}` JSON
- [x] `GET /api/events`：SSE 流——连接帧 + 存量事件回放 + Session 监听器增量推送；客户端断开注销监听器
- [x] 测试：WebFaceTest 3 例（静态页 / status JSON / SSE 连接+回放+实时推送，读线程超时防护）
- [x] 文档同步：CHANGELOG 未发布段记 web 模块；根 README 模块表补 `duo-harness-web` 行

## 实现记录（2026-09-13）

- WebFace（构造注入 port/ctx/tools/session，可测：port 0 = 随机端口）；虚拟线程执行器（SSE 长连接不占平台线程，ADR-0002 同源）
- SSE 连接帧协议：`: connected` 注释帧 + 存量回放 + 增量 `data: {JSON}`；断开时 IOException → 注销监听 + 关连接
- WebPlugin 持有 face 引用，dispose = face::stop；provides 无（Web 面是呈现位不是服务提供者）
- 过程修正：Disposable 非 AutoCloseable（用 Disposable 类型 + dispose 包受检异常）；lambda 自引用用数组持有者；EventSource 兼容的 `data:` 前缀断言

## Comments

spec：[../spec.md](../spec.md)。技术定案（grill Q1/Q3）：零新依赖（JDK HttpServer + 无构建静态单页，MockOpenAiServer 先例）、SSE 而非 WebSocket/轮询、loopback 无鉴权（M9+ 按需）。
