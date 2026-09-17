# 07: WebFace 拆分与结构化协议

## What to build

WebFace 从 777 行巨类收敛：注册方法退化为路由表、每个端点独立 handler 方法、响应写入统一 helper。`/api/answer` 切结构化协议——审批回答 `{decision: "approve"|"reject"}` 与自由文本答案分离，前端三件与后端同 diff 直接切换、不留兼容层；用户在提问卡自由输入"拒绝"二字按普通回答传递，不再被误判为审批拒绝（隐式字符串协议根治）。

## Blocked by

06

## Status

ready-for-agent

## Checklist

- [ ] 路由表 + 端点 handler + respond helper 拆分完成，注册方法不再内联业务
- [ ] `/api/answer` 请求体结构化（decision + answers 分离），后端只认新协议
- [ ] 前端审批/提问/计划三卡同 diff 改发结构化字段
- [ ] WebFaceTest 新协议判定用例 + "拒绝"误判根治用例
- [ ] 浏览器视觉验证：审批/提问/计划三卡回归（红线 5）
