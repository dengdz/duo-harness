# 常见违规代码示例与修复

## 1. 命名规约

### 1.1 POJO布尔属性加is前缀 [BLOCKER]

```java
// 违规：POJO布尔属性加了is前缀
public class User {
    private Boolean isDeleted;  // 序列化时字段名可能变为deleted，导致反向解析错误
}

// 正确：不加is前缀
public class User {
    private Boolean deleted;
}
```

### 1.2 抽象类命名不规范 [BLOCKER]

```java
// 违规
public class BaseService { }

// 正确
public abstract class AbstractBaseService { }
```

## 2. 常量定义

### 2.1 魔法值 [BLOCKER]

```java
// 违规
if (user.getType() == 2) {
    // ...
}

// 正确
private static final int USER_TYPE_VIP = 2;
if (user.getType() == USER_TYPE_VIP) {
    // ...
}
```

### 2.2 long赋值使用小写l [BLOCKER]

```java
// 违规：小写l容易和数字1混淆
long count = 10000l;

// 正确
long count = 10000L;
```

## 3. OOP规约

### 3.1 equals方法空指针风险 [BLOCKER]

```java
// 违规：param可能为null，调用equals会NPE
if (param.equals("success")) { }

// 正确：常量在前
if ("success".equals(param)) { }
```

### 3.2 包装类比较使用== [BLOCKER]

```java
// 违规：Integer超过127时==比较会失败
Integer a = 200;
Integer b = 200;
if (a == b) { }

// 正确
if (a.equals(b)) { }
// 或
if (Objects.equals(a, b)) { }
```

### 3.3 金额使用float/double [BLOCKER]

```java
// 违规：浮点数精度丢失
double total = 0.1 + 0.2;  // 结果为0.30000000000000004

// 正确：使用BigDecimal
BigDecimal total = new BigDecimal("0.1").add(new BigDecimal("0.2"));

// 或使用long以分为单位
long totalCents = 10L + 20L;  // 30分 = 0.30元
```

### 3.4 循环内字符串拼接 [BLOCKER]

```java
// 违规：每次循环都创建新的StringBuilder对象
String result = "";
for (String item : items) {
    result += item;
}

// 正确：使用StringBuilder
StringBuilder sb = new StringBuilder();
for (String item : items) {
    sb.append(item);
}
String result = sb.toString();
```

## 4. 集合处理

### 4.1 foreach中remove元素 [BLOCKER]

```java
// 违规：foreach中remove会抛ConcurrentModificationException
List<String> list = new ArrayList<>(Arrays.asList("a", "b", "c"));
for (String item : list) {
    if ("b".equals(item)) {
        list.remove(item);
    }
}

// 正确：使用Iterator
Iterator<String> iterator = list.iterator();
while (iterator.hasNext()) {
    if ("b".equals(iterator.next())) {
        iterator.remove();
    }
}

// 或使用Java8+ removeIf
list.removeIf("b"::equals);
```

### 4.2 集合转数组方式错误 [BLOCKER]

```java
// 违规
String[] arr = (String[]) list.toArray();

// 正确
String[] arr = list.toArray(new String[0]);
```

### 4.3 toMap未指定mergeFunction [BLOCKER]

```java
// 违规：key重复时抛IllegalStateException
Map<Long, String> map = userList.stream()
    .collect(Collectors.toMap(User::getId, User::getName));

// 正确：指定mergeFunction处理冲突
Map<Long, String> map = userList.stream()
    .collect(Collectors.toMap(
        User::getId,
        User::getName,
        (existing, replacement) -> existing
    ));
```

### 4.4 集合判空方式 [BLOCKER]

```java
// 违规
if (list.size() == 0) { }
if (list.size() != 0) { }

// 正确
if (list.isEmpty()) { }
if (!list.isEmpty()) { }

// 或使用Apache Commons / Guava
if (CollectionUtils.isEmpty(list)) { }
```

## 5. 并发处理

### 5.1 使用Executors创建线程池 [BLOCKER]

```java
// 违规
ExecutorService executor = Executors.newFixedThreadPool(10);
ExecutorService executor = Executors.newCachedThreadPool();

// 正确
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    10,                              // corePoolSize
    20,                              // maximumPoolSize
    60L,                             // keepAliveTime
    TimeUnit.SECONDS,                // unit
    new LinkedBlockingQueue<>(1000), // workQueue 必须有界
    new ThreadFactoryBuilder()
        .setNameFormat("biz-pool-%d")
        .build(),                    // threadFactory
    new ThreadPoolExecutor.CallerRunsPolicy()  // handler
);
```

### 5.2 显式创建线程 [BLOCKER]

```java
// 违规
new Thread(() -> { /* ... */ }).start();

// 正确：使用线程池
executor.submit(() -> { /* ... */ });
```

### 5.3 SimpleDateFormat线程不安全 [BLOCKER]

```java
// 违规：SimpleDateFormat非线程安全
private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

// 正确方式1：JDK8+ 使用DateTimeFormatter（线程安全）
private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

// 正确方式2：使用ThreadLocal
private static final ThreadLocal<SimpleDateFormat> sdfHolder =
    ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
```

### 5.4 ThreadLocal未清理 [BLOCKER]

```java
// 违规
ThreadLocal<UserContext> context = new ThreadLocal<>();
context.set(userContext);
// 忘记remove，线程池复用时内存泄漏

// 正确
ThreadLocal<UserContext> context = new ThreadLocal<>();
try {
    context.set(userContext);
    // 业务逻辑
} finally {
    context.remove();
}
```

### 5.5 锁的获取与释放位置错误 [BLOCKER]

```java
// 违规：lock()在try内部，如果lock()和try之间有异常，unlock会抛IllegalMonitorStateException
try {
    lock.lock();
    // 业务逻辑
} finally {
    lock.unlock();
}

// 正确：lock()在try外部，且lock()与try之间无其他代码
lock.lock();
try {
    // 业务逻辑
} finally {
    lock.unlock();
}
```

### 5.6 双重检查锁定未用volatile [CRITICAL]

```java
// 违规：instance未加volatile，可能因指令重排序返回未初始化的对象
private static Singleton instance;
public static Singleton getInstance() {
    if (instance == null) {
        synchronized (Singleton.class) {
            if (instance == null) {
                instance = new Singleton();
            }
        }
    }
    return instance;
}

// 正确：加volatile防止指令重排序
private static volatile Singleton instance;
```

## 6. 异常处理

### 6.1 吞掉异常 [BLOCKER]

```java
// 违规：异常被吞掉，问题无法排查
try {
    riskyOperation();
} catch (Exception e) {
    // 什么都不做
}

// 正确：记录日志并处理
try {
    riskyOperation();
} catch (BusinessException e) {
    log.error("操作失败，参数: {}", param, e);
    throw new ServiceException("操作失败", e);
}
```

### 6.2 e.printStackTrace() [BLOCKER]

```java
// 违规
try {
    riskyOperation();
} catch (Exception e) {
    e.printStackTrace();
}

// 正确
try {
    riskyOperation();
} catch (Exception e) {
    log.error("操作失败，参数: {}", param, e);
}
```

### 6.3 finally中return [BLOCKER]

```java
// 违规：finally中return会覆盖try中的return
public int getValue() {
    try {
        return 1;
    } finally {
        return 2;  // 实际返回2
    }
}

// 正确：finally中不要return
public int getValue() {
    int result = 1;
    try {
        // 业务逻辑
    } finally {
        // 清理资源，不return
    }
    return result;
}
```

### 6.4 资源未关闭 [BLOCKER]

```java
// 违规
FileInputStream fis = new FileInputStream("file.txt");
// 使用fis...
// 忘记关闭

// 正确方式1：try-with-resources
try (FileInputStream fis = new FileInputStream("file.txt")) {
    // 使用fis...
}

// 正确方式2：finally中关闭
FileInputStream fis = null;
try {
    fis = new FileInputStream("file.txt");
    // 使用fis...
} finally {
    if (fis != null) {
        try {
            fis.close();
        } catch (IOException e) {
            log.error("关闭流失败", e);
        }
    }
}
```

## 7. 日志规约

### 7.1 字符串拼接日志 [BLOCKER]

```java
// 违规
log.debug("User info: " + user.getName() + ", age: " + user.getAge());

// 正确：使用占位符
log.debug("User info: {}, age: {}", user.getName(), user.getAge());
```

### 7.2 日志未输出异常堆栈 [BLOCKER]

```java
// 违规：仅输出message，丢失堆栈信息
try {
    riskyOperation();
} catch (Exception e) {
    log.error("操作失败: " + e.getMessage());
}

// 正确：输出完整堆栈
try {
    riskyOperation();
} catch (Exception e) {
    log.error("操作失败, 参数: {}", param, e);
}
```

### 7.3 直接使用日志框架API [BLOCKER]

```java
// 违规
import org.apache.log4j.Logger;
private static final Logger logger = Logger.getLogger(MyClass.class);

// 正确：使用SLF4J门面
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
private static final Logger logger = LoggerFactory.getLogger(MyClass.class);

// 或使用Lombok
@Slf4j
public class MyClass { }
```

## 8. 安全规约

### 8.1 SQL注入 [BLOCKER]

```java
// 违规：字符串拼接SQL
String sql = "SELECT * FROM user WHERE name = '" + name + "'";
jdbcTemplate.queryForList(sql);

// MyBatis XML 违规
// SELECT * FROM user WHERE name = '${name}'

// 正确：参数绑定
String sql = "SELECT * FROM user WHERE name = ?";
jdbcTemplate.queryForList(sql, name);

// MyBatis 正确
// SELECT * FROM user WHERE name = #{name}
```

### 8.2 敏感数据未脱敏 [BLOCKER]

```java
// 违规：直接返回手机号明文
public UserVO getUser(Long id) {
    User user = userDao.findById(id);
    UserVO vo = new UserVO();
    vo.setPhone(user.getPhone());  // 13812345678
    return vo;
}

// 正确：脱敏处理
public UserVO getUser(Long id) {
    User user = userDao.findById(id);
    UserVO vo = new UserVO();
    vo.setPhone(desensitize(user.getPhone()));  // 138****5678
    return vo;
}

private String desensitize(String phone) {
    if (phone == null || phone.length() < 7) {
        return phone;
    }
    return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
}
```

### 8.3 密码明文存储 [BLOCKER]

```java
// 违规：MD5存储密码
String password = DigestUtils.md5Hex(rawPassword);
user.setPassword(password);

// 正确：BCrypt加密
String password = passwordEncoder.encode(rawPassword);
user.setPassword(password);
```

## 9. 工程结构

### 9.1 System.out输出 [BLOCKER]

```java
// 违规
System.out.println("Processing order: " + orderId);
System.err.println("Error occurred");

// 正确
log.info("Processing order: {}", orderId);
log.error("Error occurred", e);
```

### 9.2 DAO层包含业务逻辑 [BLOCKER]

```java
// 违规：DAO层包含业务逻辑
@Repository
public class OrderDao {
    public void createOrder(Order order) {
        // 业务逻辑：计算折扣
        if (order.getAmount() > 1000) {
            order.setDiscount(order.getAmount() * 0.1);
        }
        // 持久化
        sqlSession.insert("insertOrder", order);
    }
}

// 正确：DAO层只做持久化
@Repository
public class OrderDao {
    public void insert(Order order) {
        sqlSession.insert("insertOrder", order);
    }
}

// 业务逻辑放在Service层
@Service
public class OrderService {
    public void createOrder(Order order) {
        if (order.getAmount() > 1000) {
            order.setDiscount(order.getAmount() * 0.1);
        }
        orderDao.insert(order);
    }
}
```

### 9.3 直接传递DO到前端 [BLOCKER]

```java
// 违规：直接返回DO对象
@GetMapping("/users/{id}")
public User getUser(@PathVariable Long id) {
    return userDao.findById(id);  // User是DO，包含敏感字段
}

// 正确：转换为VO
@GetMapping("/users/{id}")
public UserVO getUser(@PathVariable Long id) {
    User user = userDao.findById(id);
    UserVO vo = new UserVO();
    vo.setName(user.getName());
    vo.setEmail(maskEmail(user.getEmail()));
    return vo;
}
```
