# 05: 状态面板「模型配置」管理区——预设增改写回

## What to build

状态面板新增「模型配置」管理区（ADR-0040 决策四/Q5，主题选择器同区对称，M35 插件中心行内表单先例同交互）：列出 `llm.models` 预设（provider/baseUrl/apiKey 遮蔽/model/思考档）+ **编辑/新增预设**行内表单 → `PUT /api/llm-config`（02 交付）写回 config.yml → **「重启生效」横幅提示**（热重建不做是既定裁定）。密钥交互：GET 返回遮蔽值、PUT 空=保留原值（不回显不明文）。 armed 二次确认对齐插件中心先例（改配置影响下轮对话）。

验收标准：新增一个预设 → 写回 → 重启后该预设可被 /model 切换；编辑既有预设 baseUrl → 写回 → config.yml 非 llm 段零变化；非法值（provider 越界/缺 model）表单点名不写回。

## Blocked by

02

## Status

done

## Checklist

- [x] 状态面板「模型配置」区 UI（预设列表 + 行内编辑/新增表单；M35 插件中心行内表单交互先例）
- [x] PUT 写回接线（空 apiKey=保留原值语义；非法值表单点名不写回）
- [x] 「重启生效」提示横幅（写回成功后展示）
- [x] armed 二次确认（对齐插件中心改配置先例）
- [x] node --check 绿 + 浏览器手验（新增/编辑/写回/config.yml diff 核对截图留档）
- [x] CHANGELOG 记账（未发布段：模型配置管理区）
