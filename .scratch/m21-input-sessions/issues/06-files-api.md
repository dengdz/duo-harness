# 06: Files API 投递

## What to build

`imageDelivery: files` 时的图片投递优化（DeepSeek 形态端点）：上传换 file_id、本地索引去重、配额满回收最旧自有文件、file_id 失效重传一次、上传失败整体回退 inline。inline 缺省路径零感知。

## Blocked by

05

## Status

ready-for-agent

## Checklist

- [ ] `llm.imageDelivery: inline|files` 配置（缺省 inline）；files 时上传 `POST {base}/files` 换 file_id
- [ ] 本地索引文件去重（键 = scope+variantId，命中不重传）
- [ ] 配额满回收最旧自有文件后重试；file_id 失效重传一次；上传失败整体回退 inline data URI
- [ ] mock Files 端点测试：上传/命中索引不重传/回收/失效重传/失败回退 inline 全链
