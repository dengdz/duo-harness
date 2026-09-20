# 03: read_image 工具

## What to build

模型侧唯一附件写入口（DSH 同款）：读 workspace 本地图片文件 → 魔数嗅探与校验 → **先持久化到附件库再返回** → 返回"路径 + 附件引用"让模型知道看到了什么、引用在哪。归本地读类三档放行；vision=false 时执行前即拒（零 I/O）。

## Blocked by

01

## Status

in-progress

- 2026-09-20（实现轮）：ReadImageTool 落地（闸门零 I/O 先于路径解析、魔数嗅探、先持久化再返回引用）；归本地读类三档放行；FsToolsPlugin 可选依赖 attachments 在场才注册（DSH 同款）；视觉闸门暂恒 false 待工单 05 接线 llm.vision。tools 180 全绿、全仓 SUCCESS。
## Checklist

- [ ] tools 模块 `fs` 子包 `ReadImageTool`：单参数 `file_path`；`requiresApproval` 恒 false、归读类三档放行（档位矩阵测试钉住）
- [ ] 执行前闸门：`llm.vision=false` 即结构化拒绝（零 I/O，不做任何读取）
- [ ] 读文件 → 魔数嗅探（含无扩展名场景）→ 大小/格式校验 → `attachments.saveImage` 先持久化
- [ ] 返回：路径 + 附件引用（attachmentId/mediaType/bytes/宽高）；`isConcurrencySafe=true`
- [ ] 测试：闸门拒绝、嗅探、先持久化后返回（store 可查）、非图片文件拒绝、三档判定
