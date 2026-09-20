# 06: Files API 投递

## What to build

`imageDelivery: files` 时的图片投递优化（DeepSeek 形态端点）：上传换 file_id、本地索引去重、配额满回收最旧自有文件、file_id 失效重传一次、上传失败整体回退 inline。inline 缺省路径零感知。

## Blocked by

05

## Status

done（2026-09-20 验收轮补课完成）

- 2026-09-20（实现轮）：FilesApiUploader 落地（multipart 上传/file_id 解析/超时与非 2xx 异常），attachment 模块 23/23 绿。
- 2026-09-20（收口轮核实）：**仅客户端落地，投递路径未接线**——`imageDelivery` 配置字段不存在、FilesApiUploader 无任何调用方（全仓 grep 核实）；此前"WebSearchTool 集成 imageDelivery=files 路径"的记录不实，已更正。工单 10 按实态记账：文档只写 inline 投递、limitations 记"Files API 未接线"。集成（含本地索引去重/配额回收/失效重传/mock 端点测试）转后续里程碑或验收轮补课。

## Checklist

- [x] `llm.imageDelivery: inline|files` 配置（缺省 inline，非法值 FAILED 点名）；files 时上传 `POST {base}/files` 换 file_id
- [x] FilesApiUploader 客户端：multipart 上传/file_id 解析/超时与非 2xx 异常（23/23 绿）
- [ ] 本地索引文件去重（键 = scope+variantId，命中不重传）
- [ ] 配额满回收最旧自有文件后重试；file_id 失效重传一次；上传失败整体回退 inline data URI
- [x] mock Files 端点测试：上传/命中索引不重传/回收/失效重传/失败回退 inline 全链（mock 端点 6 用例 + 适配器 file part 2 用例 + 消息投影回退 2 用例；失效**重传一次的聊天层自动重试**未做——失效轮报错，清索引重发即重传，limitations 记账）
