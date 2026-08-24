# duo-harness

Java 实现的插件化 AI agent harness：内核是自研轻量插件容器，所有能力（工具 / MCP / 页面 / agent 循环）以插件形式组装。当前为 M1——插件容器内核、工具域骨架与示例 Demo 已落地（0.1.0）。

## 一条命令看它做什么

```bash
mvn -pl duo-harness-example -am package exec:java
```

输出 34 条状态/事件叙述：配置驱动 boot → 服务注入 → 工具三段管线（准入否决 / 结果治理）→ 拔服务级联停止 → 整树回滚。详见 [运行 Demo](docs/01-入门/运行Demo.md)。

## 模块

| 模块 | 职责 |
|---|---|
| `duo-harness-core` | 插件容器内核：生命周期（六态 + epoch 依赖指纹）、服务注入（视图接口寻址）、事件（五种分派模式）、配置驱动 boot |
| `duo-harness-tools` | 工具域：注册与三段执行管线 |
| `duo-harness-example` | 示例插件集与 Demo 入口 |

## 文档

入口在 [docs/index.md](docs/index.md)：入门（运行 Demo）、架构（模块划分与关键语义）、已知限制、术语表与 ADR。

构建要求：JDK 21、Maven 3.9+。
