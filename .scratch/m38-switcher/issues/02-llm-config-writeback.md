# 02: config.yml 写回契约 + llm-config 端点

## What to build

模型配置的读写契约面（ADR-0040 决策四：编辑写回 + 重启生效，热重建不做）。①`LlmConfig` 序列化写回：读现有 `~/.duo/config.yml` → 结构化修改 llm.models 等字段 → **原子写**（临时文件 + 原子改名，模式对齐 M35 plugins.yml 物化先例）；**非 llm 段原样保留**（yaml 结构化编辑，不整文件重写）。②端点：`GET /api/llm-config`（当前配置 + models 预设清单 + 当前 model/effort；**apiKey 遮蔽返回**）与 `PUT /api/llm-config`（写回，鉴权栅栏全覆盖——routeHandler 既有铁律：异常 500 + 日志，绝不裸关）。响应带 `restartRequired: true` 语义（热重建不做，重启生效是既定裁定）。

验收标准：读→改→写→重读往返一致；非 llm 段与其他字段零变化；原子写失败不留半文件；鉴权无 token 403 fail-closed。

## Blocked by

None (can start immediately)

## Status

in-progress（2026-10-05 实现完成、四轴审查修复毕、web 模块全量绿；done 判据 = 用户验收或授权代验）

## Checklist

- [x] LlmConfig 序列化写回（llm 段文本手术 + 非 llm 段字节级保留；临时文件 load 全规则校验 + 原子改名——LlmConfigFile）
- [x] `GET /api/llm-config`（apiKey 零回显只回已配置布尔；models 清单 + provider/model/effortNote）+ `PUT`（五字段可写面，白名单外字段 400 点名——永不静默）
- [x] 鉴权栅栏全覆盖（GET/PUT 无 token 403；畸形 JSON 就近 400；IOException 500 + 日志兜底——routeHandler 铁律）
- [x] 回归锁：往返一致 / 非 llm 段字节保留 / 鉴权 403 / 非法 provider 400 原文件不动 / apiKey 空=保留 / 坏条目 400 / 白名单外字段 400 / 畸形 JSON 400（8 例）
- [x] CHANGELOG 记账（未发布段）

## Comments

- **契约收敛记档（工单措辞与实现偏差，如实）**：①「预设（含独立 baseUrl/key）」→ 收敛为「单一连接 + models 模型名白名单」——LlmConfig.models 是纯字符串名单（无按模型独立连接），「新增模型」= 白名单加名；②「GET 含当前 model/effort」→ GET 回文件面缺省值与 effortNote/effortLevels，运行时当前值由前端经 SSE 事件跟随（工单 04 消费）；③「思考档写回」→ effort 不在 yml 解析面，写回面排除 effort（PUT 收 effort 400 点名——永不静默）。
- **验证**：web 模块全量 0 失败（新套件 8 例）。

## 审查轮（2026-10-05 · 四轴合并轴）

**覆盖**：3 文件 = 100%

### 最重三件全修
- **[轴三/Java E-03·已修] PUT 畸形 JSON 误回 500**：readTree 异常逃逸 routeHandler——就近 catch 回 400（客户端错误不进 500 日志）。
- **[轴二·已修] 白名单外字段静默丢弃回 200**：写回面五字段白名单，patch 含未管理字段 400 点名（永不静默）。
- **[轴三·已修] 固定名 tmp 并发竞态**：Files.createTempFile 请求唯一名（「未校验内容被搬入正文件」窗口消除）。

### 已修 4 / 记档 4
- **[已修]** 测试 DuoHome.PROP_OVERRIDE 泄漏清理 / CHANGELOG 记账缺口 / 错误 JSON 统一 + 405 空体 / web pom 补 llm 直依赖。
- **[记档]** WebEndpoints 740 行临界（域拆分留后续观察）；splice 边缘形态（零缩进条目/CRLF——load 校验兜 fail-closed）；GET 无文件形态（空串=未配置，工单 05 消费）；move 回落 catch 稍宽（行为等价于 BootLoader 先例）。

**测试收口**：审查修复后 web 模块全量 0 失败。四轴报告全文见本轮对话记录，本节为合并处置版。
