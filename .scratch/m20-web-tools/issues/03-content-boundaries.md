# 03: fetch 内容边界

## What to build

fetch 对"非理想内容"的行为全部定型（ADR-0021 决策 5）：不该取的不取、取不动的安全放弃、错误页照常给模型——垃圾与病态输入不进上下文、不挂死管线。

## Blocked by

01

## Status

ready-for-agent

## Checklist

- [ ] content-type 分类：`text/*`、`application/json`、`application/xml`、`+json`、`+xml` 透传为文本；PDF/图片/二进制/缺失 Content-Type 拒绝（结构化错误 + 改用其它方式指引）
- [ ] charset 按 Content-Type 头解码、缺省 UTF-8；声明了不认识的 charset 报错而非乱码
- [ ] 非 2xx 是正常结果：头行带状态码、错误页正文照常转换给出（测试锁定）
- [ ] 深度护栏：DOM 嵌套 >512 层返回固定占位符（先于转换执行）；转换异常同样返回占位符而非源码
- [ ] 三层限额精确语义：Content-Length 超 5MB 直接错误（无部分内容）；流式超限截断置 truncated；**恰好填满不算截断**；解码后 100k 字符 slice；输出 200k footer（01 已有基础，此处补齐语义边界）
- [ ] 错误路径 body 释放纪律：所有提前返回路径关闭/取消响应体，连接资源不泄漏
- [ ] 测试：各 content-type 用例 / 大响应（头超限与流式超限两形态）/ 深嵌套页面 / 非 2xx 错误页
