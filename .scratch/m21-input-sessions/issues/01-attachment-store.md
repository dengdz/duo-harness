# 01: 附件库与规范化存储（新模块 duo-harness-attachment）

## What to build

附件域的地基：内容寻址存储库——图片经准入校验（格式白名单 + 魔数嗅探 + 声明与解码比对 + 大小/像素/边长上限）→ 解码规范化（长边受限重编码、8-bit sRGB、字节预算内）→ 规范化字节 SHA-256 内容寻址（tmp fsync → 硬链接发布、EEXIST 比对去重 → 0400 只读）。**无 GC（永不自动删除）**。配置段齐备、非法值启动即 FAILED。全库零外部网络依赖，测试图全部代码生成。

## Blocked by

无（可立即开工，且是 02/03/04 的共同前置）。

## Status

in-progress

- 2026-09-20（实现轮）：模块/实现/测试完成——15/15 绿（去重、0400、规范化缩放、webp 夹具、拒绝矩阵、long 校验）。库级工单：随 04/05 里程碑演示一并手动验收。
## Checklist

- [ ] 新 Maven 模块 `duo-harness-attachment`（package-info + 模块 pom + 根 pom modules 注册），发布 "attachments" 服务
- [ ] 准入：规范 base64 校验、格式白名单 png/jpeg/gif/webp、魔数嗅探、声明与解码比对（不符点名）、单图/像素/边长/单消息总量上限
- [ ] 规范化：TwelveMonkeys 解码 → 长边受限重编码（8-bit sRGB、字节预算内）
- [ ] 内容寻址：规范化字节 SHA-256 → `tmp/` fsync → 硬链接 `objects/<2位>/<sha256>`（EEXIST 比对去重）→ 0400 只读
- [ ] 配置段（单图字节/像素/边长、单消息数与总量等）缺省对齐 DSH 量级；非法值启动 FAILED 点名
- [ ] 直调测试（`@TempDir` + 代码生成测试图）：去重（同图同 id）、规范化字节寻址、各拒绝路径、0400
