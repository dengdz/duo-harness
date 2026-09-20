# 02: 请求变体管线

## What to build

发给视觉模型前的图片变体：variantId = sha256(attachmentId + 目标宽高/字节预算 + 编码版本)；按目标长边缩放（Thumbnailator）+ 字节预算适配；变体落盘缓存 `~/.duo/cache/attachments/`（variantId 寻址）；同变体并发单飞（共享一次计算）。

## Blocked by

01

## Status

ready-for-agent

## Checklist

- [ ] variantId 确定性：同附件 + 同目标 → 同 id；目标参数变 → id 变
- [ ] 长边缩放至目标 + 字节预算适配（质量阶梯压字节）
- [ ] 变体缓存落盘（寻址读写）；并发单飞（同 variantId 并发只算一次）
- [ ] 直调测试：确定性（同图同目标同输出字节）、预算适配生效、缓存命中不重算、并发单飞
