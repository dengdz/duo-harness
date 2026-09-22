# MySQL数据库与工程结构规则细则

> 本文件包含阿里巴巴Java开发手册中MySQL数据库、工程结构和服务器端规范的完整规则条目。
> 当审查代码涉及SQL语句、数据库操作、MyBatis映射、工程分层架构时加载本文件。

## 五、MySQL数据库

| 编号 | 等级 | 规则 |
|------|------|------|
| DB-01 | BLOCKER | 不要使用count(列名)或count(常量)来替代count(*)，count(*)是SQL92定义的标准统计行数的语法，跟数据库无关，跟NULL和非NULL都无关 |
| DB-02 | BLOCKER | count(distinct col) 计算该列除NULL之外的不重复行数，注意 count(distinct col1, col2) 如果其中一列全为NULL，那么即使另一列有不同的值，也返回为0 |
| DB-03 | BLOCKER | 当某一列的值全是NULL时，count(col)的返回结果为0，但sum(col)的返回结果为NULL，因此使用sum()时需注意NPE问题 |
| DB-04 | BLOCKER | 使用ISNULL()来判断是否为NULL值。NULL与任何值的直接比较都为NULL（NULL=NULL返回NULL而非true） |
| DB-05 | BLOCKER | 在代码中写分页查询逻辑时，若count为0应直接返回，避免执行后面的分页语句 |
| DB-06 | BLOCKER | 不得使用外键与级联，一切外键概念必须在应用层解决 |
| DB-07 | BLOCKER | 数据类型中decimal类型禁止使用，应该使用bigint类型替代，以分为单位存储金额 |
| DB-08 | BLOCKER | 如果存储的字符串长度几乎相等，使用char定长字符串类型 |
| DB-09 | BLOCKER | varchar是可变长字符串，不预先分配存储空间，长度不要超过5000，如果存储长度大于5000且可能会扩展，应该用text类型，并独立出一张表用主键来对应，避免影响其它字段索引效率 |
| DB-10 | BLOCKER | 表必备三字段：id, gmt_create, gmt_modified。id必为主键，类型为bigint unsigned、单表时自增、步长为1。gmt_create, gmt_modified的类型均为datetime类型 |
| DB-11 | BLOCKER | 在数据库中建立表时，一个表在5个索引以内为佳，不要超过5个索引 |
| DB-12 | BLOCKER | 业务上具有唯一特性的字段，即使是多个字段的组合，也必须建成唯一索引 |
| DB-13 | BLOCKER | 超过三个表禁止join。需要join的字段，数据类型必须绝对一致；多表关联查询时，保证被关联的字段需要有索引 |
| DB-14 | BLOCKER | 在varchar字段上建立索引时，必须指定索引长度，没必要对全字段建立索引 |
| DB-15 | BLOCKER | 页面搜索严禁左模糊或者全模糊，如果需要请使用搜索引擎 |
| DB-16 | BLOCKER | 如果有order by的场景，请注意利用索引的有序性。order by 最后的字段是组合索引的一部分，并且放在索引组合顺序的最后 |
| DB-17 | BLOCKER | 利用覆盖索引来进行查询操作，避免回表 |
| DB-18 | BLOCKER | 利用延迟关联或者子查询优化超多分页场景 |
| DB-19 | BLOCKER | SQL性能优化的目标：至少要达到range级别，要求是ref级别，如果可以是consts最好 |
| DB-20 | BLOCKER | 建组合索引的时候，区分度最高的在最左边 |
| DB-21 | BLOCKER | 防止因字段类型不同造成的隐式转换，导致索引失效 |
| DB-22 | BLOCKER | 避免在WHERE条件中使用函数操作（如DATE_FORMAT()、YEAR()等），会导致索引失效 |
| DB-23 | BLOCKER | 不要使用SELECT *，必须使用SELECT 字段列表，避免消耗更多CPU/IO/网络带宽 |

## 六、工程结构

| 编号 | 等级 | 规则 |
|------|------|------|
| PRJ-01 | BLOCKER | 应用分层：默认上层依赖于下层，箭头关系表示可直接依赖。开放接口层 -> 终端显示层 -> Web层 -> Manager层 -> Service层 -> DAO层 -> 数据库/缓存/文件系统/消息队列 |
| PRJ-02 | BLOCKER | 在DAO层只能进行与数据库相关的操作，不能有业务逻辑 |
| PRJ-03 | BLOCKER | Service层负责业务逻辑处理，可以调用DAO层和Manager层 |
| PRJ-04 | BLOCKER | Manager层负责通用业务处理，如与第三方服务对接、缓存处理、DAO层组合调用等 |
| PRJ-05 | BLOCKER | Web层负责向前端返回数据，进行参数校验、调用Service层 |
| PRJ-06 | CRITICAL | 二方库依赖：定义GAV（groupId, artifactId, version）时，version号后增加inforce标识（如SNAPSHOT/RELEASE） |
| PRJ-07 | BLOCKER | 二方库如果没有SOA服务提供方，可以引入二方库的jar包；如果已存在SOA服务提供方，不允许直接引入二方库的jar包，必须通过RPC服务接口访问 |
| PRJ-08 | BLOCKER | 应用分层中，DAO层不应该是直接操作数据库的最底层，应通过MyBatis/Hibernate等ORM框架进行操作 |
| PRJ-09 | CRITICAL | 所有的ORM框架的xml配置文件中与SQL有关的参数应使用占位符`#{}`，禁止使用`${}`（`${}`会导致SQL注入风险） |
| PRJ-10 | BLOCKER | 在生产环境代码中不允许出现`System.out`/`System.err`输出，必须使用日志框架 |
| PRJ-11 | BLOCKER | 禁止使用`e.printStackTrace()`输出异常堆栈，必须使用日志框架记录 |
| PRJ-12 | BLOCKER | 各层之间数据传递应使用DTO/VO等数据传输对象，禁止直接传递DAO层的Entity/DO对象到前端 |

## 服务器端规范补充

| 编号 | 等级 | 规则 |
|------|------|------|
| SRV-01 | BLOCKER | 不要在Java代码中硬编码配置信息（如数据库URL、密码、第三方API密钥等），应使用配置文件或配置中心管理 |
| SRV-02 | BLOCKER | 定时任务应具备幂等性，避免重复执行产生脏数据 |
| SRV-03 | BLOCKER | 对外提供API接口时，必须进行入参校验（使用@Valid、@Validated等注解或手动校验） |
| SRV-04 | BLOCKER | RESTful API应遵循HTTP语义：GET用于查询、POST用于创建、PUT用于全量更新、PATCH用于部分更新、DELETE用于删除 |
| SRV-05 | BLOCKER | 接口返回统一响应格式，包含code（状态码）、message（提示信息）、data（数据体）三个字段 |
| SRV-06 | BLOCKER | 分页查询接口必须限制最大查询条数，防止恶意大量数据查询导致OOM |
