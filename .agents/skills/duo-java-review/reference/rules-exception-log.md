# 异常日志规则细则

> 本文件包含阿里巴巴Java开发手册中异常处理和日志规约的完整规则条目。
> 当审查代码涉及try-catch、异常抛出、日志输出时加载本文件。

## 2.1 异常处理

| 编号 | 等级 | 规则 |
|------|------|------|
| E-01 | BLOCKER | Java类库中定义的一类RuntimeException可以通过预先检查进行规避，而不应该通过catch来处理（如IndexOutOfBoundsException、NullPointerException等） |
| E-02 | BLOCKER | 异常不要用来做流程控制、条件控制，因为异常的处理效率比条件分支方式低 |
| E-03 | BLOCKER | 对大段代码进行try-catch，这是不负责任的表现。catch时请分清稳定代码和非稳定代码，稳定代码指的是无论如何不会出错的代码。对于非稳定代码的catch尽可能进行区分异常类型，再做对应的异常处理 |
| E-04 | BLOCKER | 捕获异常是为了处理它，不要捕获了却最后把它抛出去或者忽略掉。如果不想处理它，请将该异常抛给它的调用者。最外层的业务使用者，必须处理异常，将其转化为用户可以理解的内容 |
| E-05 | BLOCKER | 在try块内部必须确保所有资源（如文件流、数据库连接、网络连接等）被正确关闭。使用try-with-resources语法或在finally块中关闭 |
| E-06 | BLOCKER | 不能在finally块中使用return，finally块中的return返回后方法结束执行，不会再执行try块中的return语句 |
| E-07 | BLOCKER | 捕获异常与抛异常，必须是完全匹配，或者捕获异常是抛异常的父类 |
| E-08 | BLOCKER | 在方法定义中throws声明的异常必须是方法内可能抛出的异常或其父类异常 |
| E-09 | BLOCKER | NPE产生的场景：返回类型为基本数据类型，return包装类数据时自动拆箱有可能产生NPE；数据库查询结果为null，调用其方法可能产生NPE；远程调用返回对象时，不进行null判断可能产生NPE；集合中的元素可能为null，使用时可能产生NPE。使用Optional避免NPE |
| E-10 | BLOCKER | 定义时区分unchecked/checked异常，避免直接抛出new RuntimeException()，更不允许抛出Exception或者Throwable，应使用有意义的业务异常类 |
| E-11 | CRITICAL | 对于公司外的HTTP/API开放接口必须使用"错误码"机制；应用内部推荐使用抛异常的方式跨接口传递异常 |
| E-12 | CRITICAL | 金融场景中，异常不能直接抛给用户（前端），需要catch后转化为友好提示 |

## 2.2 日志规约

| 编号 | 等级 | 规则 |
|------|------|------|
| L-01 | BLOCKER | 应用中不可直接使用日志系统（Log4j、Logback）中的API，而应依赖使用日志框架SLF4J中的API，使用门面模式的日志框架，有利于维护和各个类的日志处理方式统一 |
| L-02 | BLOCKER | 日志文件推荐至少保存15天，有些异常行为以周频率发生 |
| L-03 | BLOCKER | 应用中的扩展日志（如打点、临时监控、访问日志等）命名方式：appName_logType_logName.log。logType:日志类型，推荐分类有stats/desc/monitor/visit等；logName:日志描述 |
| L-04 | BLOCKER | 对trace/debug/info级别的日志输出，必须使用占位符进行参数化输出（如`log.info("id: {}", id)`），不要进行字符串拼接 |
| L-05 | BLOCKER | 避免重复打印日志，浪费磁盘空间，务必在logback.xml中设置additivity=false |
| L-06 | BLOCKER | 异常信息应该包括两个参数：参数信息和异常堆栈（如`log.error("input params: {}", params, e)`），不要仅输出e.getMessage() |
| L-07 | BLOCKER | 谨慎地记录日志。生产环境禁止输出debug日志；有选择地输出info日志；如果使用warn来记录刚上线时的业务行为信息，一定要注意日志输出量的问题 |
| L-08 | BLOCKER | 可以使用warn日志级别来记录用户输入参数异常等行为（非业务异常），不要使用error级别，error级别只记录系统逻辑错误、异常或重要的违规操作 |
| L-09 | CRITICAL | 日志输出必须使用参数化方式（占位符），禁止使用字符串拼接 |
| L-10 | CRITICAL | 在循环中不要打印日志，即使日志级别是debug。如需打印，在循环外收集后统一打印 |
