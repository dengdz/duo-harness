# 01 — llm 模块骨架与 DuoHome 配置加载

## What to build

`duo-harness-llm` 模块骨架 + 配置加载机制。模块依赖仅 core + Jackson（零新依赖）。三件事：

1. **模块与包骨架**：契约（`LlmAdapter` 接口、`ChatRequest`/`ChatChunk` 类型——最小形态，字段按 OpenAI 兼容请求/流式响应所需）+ internal 包。`LlmAdapter` 的方法签名本票只立骨架（`stream(request) → chunk 回调`语义），实现留 02。
2. **DuoHome 解析**：`~/.duo` 默认、`DUO_HOME` 环境变量覆盖；目录不存在时按需创建（首次运行体验）。
3. **配置加载**：`~/.duo/config.yml` 读 `llm.baseUrl / llm.apiKey / llm.model`；`DUO_LLM_BASE_URL / DUO_LLM_API_KEY / DUO_LLM_MODEL` 环境变量逐项覆盖（env 优先于文件）；缺关键项（如 apiKey）时报错点名。加载逻辑封装为可复用机制（将来的会话/其他插件复用 DuoHome 与配置读取）。

## Blocked by

None

## Status

ready-for-agent

## Checklist

- [ ] 模块骨架：pom + 契约类型（LlmAdapter/ChatRequest/ChatChunk）+ internal 包
- [ ] DuoHome：`~/.duo` 默认 + `DUO_HOME` 覆盖 + 按需创建
- [ ] 配置加载：config.yml + `DUO_LLM_*` env 逐项覆盖 + 缺项点名报错
- [ ] 根 pom 注册模块；docs/04-架构/模块划分.md 补 llm 行
- [ ] 单元测试：env 覆盖优先级、缺项点名、DUO_HOME 重定向（@TempDir）
