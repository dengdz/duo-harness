# 02: web 模块骨架——WebPlugin + HTTP + SSE + 静态页壳

**What to build:** 新模块 `duo-harness-web`（依赖 core + tools + session + agent）：`WebPlugin`（Boot yml 一行，config `{port}` 省略默认 8080，**只绑 127.0.0.1**，启动打印访问地址）；HttpServer 承载三个端点——静态单页（classpath 资源，双区布局壳）、`GET /api/status`（Context.snapshots + tools.list 的 JSON）、`GET /api/events`（SSE 流：订阅 Session 监听器，新事件转 `data:` 行推送；连接断开时注销监听器）。本期端点用假数据/空流即可（对话与状态面在后续工单填充），骨架的验收是"浏览器打开页面、SSE 连接可建立"。

**Blocked by:** 01（快照与监听器两个地基 API）

**Status:** ready-for-agent

## Checklist

- [ ] 新模块 `duo-harness-web`：pom（core/tools/session/agent 依赖）+ 根包 `dev.duo.web` + 根 POM 聚合
- [ ] `WebPlugin`（Boot yml 一行）：config `{port}` 可省默认 8080；HttpServer 只绑 127.0.0.1；起停挂插件生命周期（dispose 即停）
- [ ] 静态单页壳（classpath `/web/index.html`）：双区布局（左对话占位 / 右状态占位），亮色 DSH 风格 token 骨架（白/浅灰表面、deepseek 蓝、柔光阴影、大圆角、系统字体栈）
- [ ] `GET /api/status`：返回 `{plugins: [{id, state}], tools: [{name, description}]}` JSON
- [ ] `GET /api/events`：SSE 流——订阅 Session 监听器，新事件以 `data: {JSON}` 推送；客户端断开注销监听器
- [ ] 测试（HttpServer 先例 MockOpenAiServer）：起停、绑定 loopback、静态页 200、status JSON 形态、SSE 连接建立与首帧
- [ ] 文档同步：CHANGELOG 未发布段记 web 模块；根 README 模块表补 `duo-harness-web` 行

## Comments

spec：[../spec.md](../spec.md)。技术定案（grill Q1/Q3）：零新依赖（JDK HttpServer + 无构建静态单页，MockOpenAiServer 先例）、SSE 而非 WebSocket/轮询、loopback 无鉴权（M9+ 按需）。
