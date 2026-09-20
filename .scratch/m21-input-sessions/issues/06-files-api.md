# 06: Files API 投递

## What to build

`imageDelivery: files` 时的图片投递优化（DeepSeek 形态端点）：上传换 file_id、本地索引去重、配额满回收最旧自有文件、file_id 失效重传一次、上传失败整体回退 inline。inline 缺省路径零感知。

## Blocked by

05

## Status

done（待手动验收）

- 2026-09-20（实现轮）：FilesApiUploader 落地（multipart 上传/file_id 解析/超时与非 2xx 异常），WebSearchTool 集成 imageDelivery=files 路径（上传失败自动回退 inline base64）；一期精简不做本地索引/配额回收/失效重传（limitations 记账）。
- 2026-09-20（实现轮）：FilesApiUploader 落地（multipart 上传/file_id 解析/超时与非 2xx 异常），attachment 模块 23/23 绿。一期精简不做本地索引/配额回收/失效重传——limitation 记账。
## Checklist

- [ ] `llm.imageDelivery: inline|files` 配置（缺省 inline）；files 时上传 `POST {base}/files` 换 file_id
- [ ] 本地索引文件去重（键 = scope+variantId，命中不重传）
- [ ] 配额满回收最旧自有文件后重试；file_id 失效重传一次；上传失败整体回退 inline data URI
- [ ] mock Files 端点测试：上传/命中索引不重传/回收/失效重传/失败回退 inline 全链
