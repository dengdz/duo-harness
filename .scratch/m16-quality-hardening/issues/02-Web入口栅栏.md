# 02: Web 入口栅栏

## What to build

Web 面入站两级校验（术语：入口栅栏）：所有请求 Host 头白名单（127.0.0.1 / localhost / [::1] 带端口，封死 DNS rebinding）；POST 类写端点额外校验 Origin 为空（本地工具）或同源，非空且不同源 403。浏览器里任意网页无法跨站驱动本机 agent 或伪造审批作答；本地 curl 脚本与正常浏览器使用完全无感。不做配置化白名单——未来 bind 配置化时白名单随 bind 派生。

## Blocked by

07

## Status

ready-for-agent

## Checklist

- [ ] 全请求 Host 头白名单校验，违者 403
- [ ] POST 端点 Origin 空/同源校验；GET/SSE 不校验 Origin
- [ ] WebFaceTest 放行/拒绝矩阵用例（Host 伪造、Origin 跨站、无 Origin 放行、SSE 与静态资源不误伤）
- [ ] 实测三对照：curl 无 Origin 放行、curl 伪造 Origin 403、浏览器全功能正常
