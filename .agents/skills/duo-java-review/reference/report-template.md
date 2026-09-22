# Java代码审查报告模板

## 审查概要

| 指标 | 数据 |
|------|------|
| 审查文件数 | N |
| 审查代码行数 | N |
| BLOCKER 违规 | X 项 |
| CRITICAL 违规 | Y 项 |
| MAJOR 违规 | Z 项 |
| 总违规数 | X+Y+Z 项 |

## 审查结论

- **通过 / 需修改后通过 / 不通过**

## 详细违规项

### [BLOCKER] 违规项示例

#### 1. 魔法值 - 常量定义 C-01
- **文件**：`src/main/java/com/example/service/OrderService.java:42`
- **等级**：BLOCKER
- **规范**：不允许任何魔法值（即未经预先定义的常量）直接出现在代码中
- **问题**：代码中直接使用了魔法值 `100`，未定义为常量
- **修复建议**：

```java
// 修改前
if (order.getStatus() == 100) {
    // ...
}

// 修改后
private static final int ORDER_STATUS_PAID = 100;

if (order.getStatus() == ORDER_STATUS_PAID) {
    // ...
}
```

---

#### 2. 线程池创建方式 - 并发处理 CON-04
- **文件**：`src/main/java/com/example/config/ThreadPoolConfig.java:15`
- **等级**：BLOCKER
- **规范**：线程池不允许使用 Executors 去创建，而是通过 ThreadPoolExecutor 的方式创建
- **问题**：使用 `Executors.newFixedThreadPool()` 创建线程池，可能导致 OOM
- **修复建议**：

```java
// 修改前
ExecutorService executor = Executors.newFixedThreadPool(10);

// 修改后
ExecutorService executor = new ThreadPoolExecutor(
    10,
    10,
    0L,
    TimeUnit.MILLISECONDS,
    new LinkedBlockingQueue<>(1000),
    new ThreadFactoryBuilder().setNameFormat("order-pool-%d").build(),
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

---

### [CRITICAL] 违规项示例

#### 3. 集合遍历方式 - 集合处理 COL-15
- **文件**：`src/main/java/com/example/util/MapUtil.java:28`
- **等级**：CRITICAL
- **规范**：使用 entrySet 遍历 Map 类集合 KV，而不是 keySet 方式遍历
- **问题**：使用 keySet 遍历 Map，每次需要额外查询 value，性能较差
- **修复建议**：

```java
// 修改前
for (String key : map.keySet()) {
    String value = map.get(key);
    System.out.println(key + "=" + value);
}

// 修改后
for (Map.Entry<String, String> entry : map.entrySet()) {
    System.out.println(entry.getKey() + "=" + entry.getValue());
}
```

---

### [MAJOR] 违规项示例

#### 4. 等号对齐 - 代码格式 F-11
- **文件**：`src/main/java/com/example/constant/AppConstants.java:10-15`
- **等级**：MAJOR
- **规范**：没有必要增加若干空格来使变量的赋值等号与上一行对应位置的等号对齐
- **问题**：变量赋值使用多余空格进行等号对齐
- **修复建议**：

```java
// 修改前
private static final String APP_NAME    = "myapp";
private static final String APP_VERSION = "1.0.0";

// 修改后
private static final String APP_NAME = "myapp";
private static final String APP_VERSION = "1.0.0";
```

---

## 优秀实践

（列出代码中符合规范的良好实践，给予正面反馈）

## 改进建议汇总

| 优先级 | 改进项 | 涉及文件 |
|--------|--------|----------|
| P0 | 修复线程池创建方式 | ThreadPoolConfig.java |
| P0 | 消除所有魔法值 | OrderService.java |
| P1 | 使用 entrySet 遍历 Map | MapUtil.java |
| P2 | 移除等号对齐多余空格 | AppConstants.java |
