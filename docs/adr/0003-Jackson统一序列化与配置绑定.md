# 序列化与配置绑定统一用 Jackson

Status: 批准

配置树（yml）解析与插件 config 类型绑定统一用 Jackson 栈：jackson-databind + jackson-dataformat-yaml（传递引入 snakeyaml，不再单独声明）。插件声明 config record/POJO，内核负责绑定，绑定失败 = 插件加载失败（错误前移）。理由：M2 的 MCP Java SDK、M3 的 Web JSON 通信都建立在 Jackson 上，引入几乎必然；一栈同时解决 yml 解析、类型绑定、后续 JSON 协议。拒绝的选项：裸 Map 传 config（错误后移、API 后补要动）、手写反射绑定器（重复造轮子）。

## Consequences

- 运行时依赖从零起步即有 Jackson（首个外部运行时依赖）。
- config 类型是插件公开契约的一部分（record 字段增删即配置兼容性变更）。
