# 07: Web 域·fileRefs 服务化（H-12）

## What to build

文件引用服务（fileRefs）走正门：装配期自建直传改 provide 发布 + 消费方 optionalInject——服务注册表里这一服务从此有常驻提供方，第三方可替换 / 补全文件引用实现（扫描册定性「病，修法轻」的那条）。

## Blocked by

06（消费面在拆分后的门面 / 端点形态上接线）。

## Status
in-progress（待手动验收，攒统一拍板）

## Checklist
- [x] 装配改 provide 发布（workspace 在场时构建即 `ctx.provide("fileRefs", …)`），服务注册表从此有常驻提供方
- [x] 消费方（补全端点）改 ctx 惰性寻址（WebServiceViews.FileRefs 视图，缺席 503 语义不变）；WebFace 直传字段与 setFileRefs 删除
- [x] 「发布 fileRefs 服务」这一挂载场景在服务注册表可查（不再旁路）；第三方可替换/补全实现
- [x] 既有文件引用行为不变（WebFileCompleteEndpointTest 改走服务注册表同路径，全绿）+ 全仓 `mvn verify` 绿（2026-09-28 exit 0）

## Comments

- 2026-09-28：**消费形态裁定**——扫描册原建议「消费方 optionalInject 服务化」，落地时发现旁路注释自述「自产自依赖会让内核 recheck 循环重跑 apply」（WebPlugin 自产 fileRefs 又自消费）——optionalInject 自声明会踩同一坑。落地为 provide + 端点惰性寻址（与 commands/skills 同款模式），recheck 规避且服务化目标（注册表有提供方、可替换）全达成。
- 待手动验收（攒统一拍板）。
