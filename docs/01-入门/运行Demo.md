# 运行 M1 Demo

> 状态：门面可用。duo-harness-example 的 Demo 一条命令演示 M1 全部机制。

## 一条命令

```bash
mvn -pl duo-harness-example -am package exec:java
```

输出按时间顺序叙述：boot 状态迁移（`plugin/status` 事件）→ 服务注入（消费者经视图获得问候）→ 工具三段管线（正常执行 / 准入否决 / 结果治理）→ 运行时拔服务级联停止（消费者 UNLOADING 回 PENDING）→ 整树回滚。

## Demo 演示什么

| 机制 | 叙述中的体现 |
|---|---|
| 配置驱动 boot | `demo.yml` 一行一插件；`demo-disabled` 行在场而实例不装载 |
| 行序无加载语义 | `greeting-client` 行排在 `greeting` 前，先 PENDING 后被唤醒 |
| 服务注入 | GreetingClientPlugin `inject` 声明 + `ctx.as(视图)` 调用 |
| 工具三段管线 | echo 工具的 pre-execute 否决（敏感词）与 post-execute 结果后缀 |
| 依赖驱动生命周期 | 拔掉临时提供者 → 消费者 UNLOADING → PENDING（等待回归） |
| 整树回滚 | 收尾 root.dispose，每个插件 DISPOSED 逐一叙述 |

## 想改着玩

- 编辑 `duo-harness-example/src/main/resources/demo.yml`：翻转 `disabled`、改 config 前缀、增删行，重跑命令即见形态变化
- 示例插件源码在 `duo-harness-example` 的 `dev.duo.harness.example` 包——每个类演示一种扩展形态（服务提供者 / 消费者 / 工具插件 / 管线拦截者）
