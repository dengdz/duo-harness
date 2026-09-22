# Java代码审查快速检查清单

## 命名规约（BLOCKER）
- [ ] 命名不以 `_` 或 `$` 开始/结束
- [ ] 无拼音与英文混合命名
- [ ] 类名使用 UpperCamelCase
- [ ] 方法名/变量名使用 lowerCamelCase
- [ ] 常量全大写，下划线分隔
- [ ] 抽象类以 Abstract/Base 开头，异常类以 Exception 结尾
- [ ] 数组声明为 `int[] arr` 而非 `int arr[]`
- [ ] POJO布尔属性不加 is 前缀
- [ ] 包名全小写、单数形式
- [ ] 无不规范缩写

## 常量定义（BLOCKER）
- [ ] 无魔法值
- [ ] long/Long 赋值使用大写 L 后缀
- [ ] 常量按功能分类，非全部放一个类
- [ ] 固定范围变量使用 enum

## 代码格式（BLOCKER）
- [ ] 大括号使用规范（空块`{}`，非空块换行）
- [ ] 运算符两边有空格
- [ ] 4个空格缩进，无 Tab
- [ ] 单行不超过120字符
- [ ] 注释 `//` 后有一个空格
- [ ] 参数逗号后有空格
- [ ] UTF-8编码，Unix换行符

## OOP规约（BLOCKER）
- [ ] 静态成员通过类名访问，非对象引用
- [ ] 覆写方法加 @Override
- [ ] equals 使用常量或确定有值的对象调用
- [ ] 包装类比较使用 equals
- [ ] 金额计算使用 BigDecimal 或 long（分），禁止 float/double
- [ ] 循环内字符串拼接使用 StringBuilder
- [ ] POJO类有 toString()
- [ ] 构造方法中无业务逻辑
- [ ] 工具类无 public 构造方法

## 集合处理（BLOCKER）
- [ ] 重写 equals 同时重写 hashCode
- [ ] 判空使用 isEmpty() 而非 size()==0
- [ ] toMap() 指定 mergeFunction
- [ ] foreach 中无 remove/add 操作（使用 Iterator）
- [ ] 集合转数组使用 `toArray(new T[0])`
- [ ] Arrays.asList() 结果不可修改
- [ ] ArrayList.subList() 不可强转 ArrayList
- [ ] 集合初始化指定容量

## 并发处理（BLOCKER）
- [ ] 线程通过线程池创建，不显式 new Thread
- [ ] 线程池使用 ThreadPoolExecutor，不使用 Executors
- [ ] 线程池使用有界队列
- [ ] SimpleDateFormat 线程安全处理
- [ ] ThreadLocal 在 finally 中 remove
- [ ] 锁获取在 try 之外
- [ ] 双重检查锁定使用 volatile
- [ ] 高并发使用 ConcurrentHashMap 而非 HashMap
- [ ] 定时任务使用 ScheduledExecutorService 而非 Timer
- [ ] Random 使用 ThreadLocalRandom

## 控制语句（BLOCKER）
- [ ] switch 每个 case 有 break/return，含 default
- [ ] String 类型 switch 先判 null
- [ ] if/else/for/while 必须用大括号
- [ ] 三目运算符两表达式类型一致

## 异常处理（BLOCKER）
- [ ] 不用异常做流程控制
- [ ] try 范围最小化
- [ ] 异常被处理而非吞掉
- [ ] finally 中不 return
- [ ] 资源在 try-with-resources 或 finally 中关闭
- [ ] 不抛 RuntimeException/Exception/Throwable，用业务异常
- [ ] finally 块中关闭资源

## 日志规约（BLOCKER）
- [ ] 使用 SLF4J API，不直接用 Log4j/Logback
- [ ] 使用占位符 `{}` 而非字符串拼接
- [ ] 异常日志包含参数和完整堆栈
- [ ] debug 日志使用占位符
- [ ] 循环内不打日志

## 安全规约（BLOCKER）
- [ ] 用户页面/功能做权限校验
- [ ] 敏感数据脱敏
- [ ] SQL 使用参数绑定，无拼接
- [ ] 用户输入做有效性验证
- [ ] 密码加密存储（BCrypt），非明文
- [ ] 日志中无敏感信息
- [ ] 密码/密钥非明文存储

## 工程结构（BLOCKER）
- [ ] 分层架构清晰（Controller/Service/DAO/Manager）
- [ ] DAO 层无业务逻辑
- [ ] 无 System.out / System.err
- [ ] 无 e.printStackTrace()
- [ ] MyBatis 使用 `#{}` 而非 `${}`
- [ ] 层间传递使用 DTO/VO，非直接传递 DO
