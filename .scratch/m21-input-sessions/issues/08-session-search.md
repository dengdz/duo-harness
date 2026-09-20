# 08: 会话检索（新模块 duo-harness-session-query）

## What to build

会话全文检索：`session_query` 插件行 opt-in——内存倒排索引（懒构建首次搜索才扫 + 文件 mtime 增量）+ `SessionQuery` 服务接口（**后端无关**，后期可转 SQLite）+ `session_search` 单工具（模型侧）+ Web 侧栏搜索框（用户侧）。索引内容对齐 DSH 清单（消息文本/tool 调用名+参数/tool 结果/todo/turn 错误；reasoning 不入）；子代理会话不索引。

## Blocked by

无（与附件线零耦合，可立即开工）。

## Status

in-progress

## Checklist

- [ ] 新 Maven 模块 `duo-harness-session-query`（package-info + 模块 pom + 根 pom 注册），发布 "session-query" 服务
- [ ] 内容抽取：消息文本/tool 调用名+参数/tool 结果/todo/turn 错误入索引；reasoning 与未知事件不入（用例钉住）
- [ ] 内存倒排索引：懒构建（首次搜索才扫会话目录）+ 文件 mtime 增量刷新；分词 AND 匹配 + snippet 高亮
- [ ] 服务接口后端无关（memory 实现可替换；接口语义不绑倒排细节）
- [ ] `session_search` 工具（query → 命中会话 + 最强匹配事件 + snippet；分词 AND；工具目录对账）
- [ ] Web 侧栏搜索框（命中列表 → 点击打开会话）
- [ ] 测试：JSONL 夹具检索全链（命中/snippet/懒构建/mtime 增量/reasoning 不入/子代理会话不索引）
