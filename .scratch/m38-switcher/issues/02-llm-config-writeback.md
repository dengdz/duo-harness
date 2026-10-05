# 02: config.yml 写回契约 + llm-config 端点

## What to build

模型配置的读写契约面（ADR-0040 决策四：编辑写回 + 重启生效，热重建不做）。①`LlmConfig` 序列化写回：读现有 `~/.duo/config.yml` → 结构化修改 llm.models 等字段 → **原子写**（临时文件 + 原子改名，模式对齐 M35 plugins.yml 物化先例）；**非 llm 段原样保留**（yaml 结构化编辑，不整文件重写）。②端点：`GET /api/llm-config`（当前配置 + models 预设清单 + 当前 model/effort；**apiKey 遮蔽返回**）与 `PUT /api/llm-config`（写回，鉴权栅栏全覆盖——routeHandler 既有铁律：异常 500 + 日志，绝不裸关）。响应带 `restartRequired: true` 语义（热重建不做，重启生效是既定裁定）。

验收标准：读→改→写→重读往返一致；非 llm 段与其他字段零变化；原子写失败不留半文件；鉴权无 token 403 fail-closed。

## Blocked by

None (can start immediately)

## Status

ready-for-agent

## Checklist

- [ ] LlmConfig 序列化写回（llm.models 结构化编辑；yaml 非 llm 段原样保留；原子写 tmp+rename）
- [ ] `GET /api/llm-config`（apiKey 遮蔽；models 清单 + 当前 model/effort）+ `PUT`（写回，校验 provider 四值/字段合法性，非法值 400 点名）
- [ ] 鉴权栅栏全覆盖（无 token 403；异常 500 + 日志兜底——routeHandler 铁律）
- [ ] 回归锁：往返一致 / 原子写 / 非 llm 段保留 / 鉴权 403 / 非法值 400 点名
- [ ] CHANGELOG 记账（未发布段：/api/llm-config 端点 + config.yml 写回能力）
