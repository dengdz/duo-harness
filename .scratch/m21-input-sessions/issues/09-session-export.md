# 09: 会话导出

## What to build

`/export [markdown|json]`（缺省 markdown）双面命令：markdown 人读对话记录（角色/时间戳头部 + 工具摘要行 + 尾部附件引用清单）；json 为会话 JSONL 原样副本。附件字节不打包（引用清单 + 库内永不删除已覆盖信息）。读前 flush 持久化屏障 + fail-loud；只导当前会话；CLI 写盘 cwd / Web 下载流。

## Blocked by

无（与附件线零耦合，可立即开工）。

## Status

done

- 2026-09-20（实现轮）：实现完成、全量测试绿、隔离实例浏览器复验通过（/export 命令分发→SSE done 事件携 URL→前端自动下载；端点 Content-Disposition/非法格式 400）。

## Checklist

- [x] `/export [markdown|json]` 注册进命令注册表（双面、busySafe、`command/run`+`command/done` 审计白得；缺参缺省 markdown、非法参数点名）
- [x] markdown 渲染：角色/时间戳头部 + 消息文本 + 工具摘要行（调用名+参数摘要+结果摘要）+ 尾部附件引用清单
- [x] json 渲染：会话 JSONL 原样副本（逐行等价）
- [x] 读前 flush 持久化屏障（进行中会话先落盘再导出）；失败 fail-loud（不给截断文件）
- [x] 落位：CLI 写盘 cwd（`duo-session-<id>.md/.jsonl`）；Web 下载流（Content-Disposition）
- [x] 测试：markdown 快照断言 / JSONL 逐行等价 / 非法参数与失败路径 / 屏障（导出含最新消息）
