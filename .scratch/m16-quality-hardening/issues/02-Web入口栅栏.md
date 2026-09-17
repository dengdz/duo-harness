# 02: Web 入口栅栏

## What to build

Web 面入站两级校验（术语：入口栅栏）：所有请求 Host 头白名单（127.0.0.1 / localhost / [::1] 带端口，封死 DNS rebinding）；POST 类写端点额外校验 Origin 为空（本地工具）或同源，非空且不同源 403。浏览器里任意网页无法跨站驱动本机 agent 或伪造审批作答；本地 curl 脚本与正常浏览器使用完全无感。不做配置化白名单——未来 bind 配置化时白名单随 bind 派生。

## Blocked by

07

## Status

done（2026-09-17 用户验收：curl 三对照 200/403/403 全符合预期；WebFaceTest 38 用例绿）

## Checklist

- [x] 全请求 Host 头白名单校验（127.0.0.1 / localhost / [::1] 带端口），违者 403——封死 DNS rebinding
- [x] POST 端点 Origin 空/同源校验；GET/SSE 不校验 Origin
- [x] WebFaceTest 放行/拒绝矩阵用例（raw socket 伪造 Host、跨站 Origin、无 Origin 放行、SSE 与静态资源不误伤）
- [x] 实测三对照：curl 无 Origin 放行（200）、curl 伪造 Origin 403、伪造 Host 403；浏览器同源全功能正常（截图留档）

## Comments

- 2026-09-17：实现落点为 `route()` 统一前置 `entryGate`（全部 11 个端点一次覆盖）；白名单按实际绑定端口生成（端口 0 测试形态同样成立），无配置开关。
- 2026-09-17 顺手修复：静态单页与 /web/ 资源响应补 `Cache-Control: no-cache`（此前无缓存头，浏览器启发式缓存曾致旧 app.js 渲染双计划卡——BUG-20260917-04 验收期实测）。
- curl 对照矩阵（真实运行演示取证）：①无 Origin 200 ②伪造 Origin POST 403 ③同源 Origin POST 202 ④伪造 Host 403 ⑤恶意 Origin GET 200。
- 实现期勘误：`java.net.http.HttpClient` 的 Host 头属受限头不可伪造，栅栏用例的伪造 Host 走 raw socket 发送（`rawGet` 夹具）。

### 用户复验对照表（可选）

```
# ① 本地 curl（无 Origin）→ 200
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:18080/api/status
# ② 伪造 Origin 的 POST → 403
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:18080/api/message \
  -H "Content-Type: application/json" -H "Origin: http://evil.com" -d '{"text":"hi"}'
# ③ 伪造 Host → 403
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:18080/api/status -H "Host: evil.com"
```

浏览器打开 http://127.0.0.1:18080 全功能正常即同源放行验证。
