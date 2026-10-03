# M35 端到端验收件

Status: 已通过（2026-10-03，agent 按本件自跑八步全过 + 逮出三处缺陷修复后，用户确认验收通过——M35 收口，工单 09 转 done）

裁定与口径见 [spec.md](spec.md) 与 [ADR-0037](../../docs/adr/0037-M35M36插件生态化立项决策.md)。实现证据：工单 01-08 各 Comments + 分支 `1.1.0` 提交链（aa5c2ec7 → 本件前最后一笔 22e1691a）。

## 端到端验收叙事（可复跑）

> 场景：拿到一个第三方 fat-jar → 放目录 → 页面点名 → 装 → 热生效 → 换审批策略 → 卸载干净，**全程不重启**。

```bash
# 0) 构建产物（一次性）
./mvnw -pl duo-harness-example -am package -DskipTests
./mvnw -pl duo-harness-stats package -DskipTests

# 1) 准备临时部署（独立 DUO_HOME，不碰真实数据）。
#    关键：预写 DUO_HOME/plugins.yml（缺省物化路径的账本）——插件中心的一切
#    操作写回这份文件，装配即页面所见。勿用显式 yml 参数启动（与账本脱节）
export M35=/tmp/m35-accept
rm -rf $M35 && mkdir -p $M35/duo-home/plugins
cat > $M35/duo-home/config.yml <<'EOF'
llm:
  baseUrl: https://placeholder.local
  apiKey: accept-key
  model: accept-model
EOF
cat > $M35/duo-home/plugins.yml <<'EOF'
plugins:
  - id: tools
    name: dev.duo.harness.tools.ToolsPlugin
  - id: prompts
    name: dev.duo.harness.agent.prompt.PromptPlugin
    config:
      systemPrompt: "验收用。"
  - id: answers
    name: dev.duo.harness.tools.InteractionPlugin
  - id: commands
    name: dev.duo.harness.agent.commands.CommandsPlugin
    config: {}
  - id: approval
    name: dev.duo.harness.tools.ApprovalPlugin
    config: {}
  - id: plugin-center
    name: dev.duo.harness.center.PluginCenterPlugin
  - id: web
    name: dev.duo.harness.web.WebPlugin
    config:
      port: 18090
      auth: none
EOF
# 2) 第三方插件包进目录（样板即交货形态：常规模块 jar）
cp duo-harness-stats/target/duo-harness-stats-1.0.1.jar $M35/duo-home/plugins/

# 3) 启动（无 yml 参数 = 缺省物化路径：plugins.yml 在位即直接装载）。
#    保持前台；另开终端做浏览器与 curl 步骤
DUO_HOME=$M35/duo-home java -jar duo-harness-example/target/duo-harness-1.0.1.jar
```

浏览器开 `http://127.0.0.1:18090`（auth:none 有常驻横幅属预期），逐步核对：

| 步 | 操作 | 预期 |
|---|---|---|
| 1 | 状态区点「插件中心」 | 抽屉滑出；已装区 7 行（含 approval），plugin-center 行标「需重启生效」且无按钮 |
| 2 | 待装区 | 列出 `duo-harness-stats-1.0.1.jar`（大小 + sha 前 8 位）|
| 3 | 点「安装」→ 确认入口类（唯一候选自动预填）→ 行 id 填 `tool-stats` → config `{}` | toast「安装完成」；行表新增 `tool-stats ACTIVE`；待装区清空；**状态面工具表出现 `tool_stats`**（5 秒内刷新）|
| 4 | `tool-stats` 行点「停用」 | 行态变「已停用」；`tool_stats` 从状态面消失 |
| 5 | 「启用」 | 复活，`tool_stats` 回归 |
| 6 | 换审批策略：`approval` 行「卸载」（二次确认）→ 「挂类路径插件」表单填 id `approval` / FQCN `dev.duo.harness.tools.ApprovalPlugin` / config `{}` → 「挂载」（二次确认） | 换实现热生效；装配文件写回可见 |
| 7 | `tool-stats` 行「卸载」 | 行消失、待装区重新出现同包 |
| 8 | `grep jar: $M35/duo-home/plugins.yml` | 只剩第 6 步挂载的 approval 行（结构操作全落盘）|

## 浏览器实测记录（S4 批，2026-10-03 已执行）

- 形态：真实 fat-jar + 临时 DUO_HOME + stats 样板包（与本件同构，端口 18080）。
- 覆盖：抽屉滑出与四区渲染、六行操作按钮矩阵、`plugin-center` 行「需重启生效」标注（不可拔清单生效）、待装点名卡（sha 前 8 位）、端点驱动安装后七行渲染与待装清空、`tool_stats` 入状态面（/api/status 实证）、鉴权横幅 auth:none 形态。
- 证据：`evidence/drawer-before-install.png` / `evidence/drawer-after-install.png`（装前/装后双截图）。
- 已知自动化边界：安装流中的浏览器原生 prompt 对话框在 IAB 自动化下未能驱动（页 prompt 流人工可走，本件第 3 步即人工路径）；安装动作的等价验证经同一桥端点完成（WebBridgeTest 4 用例 + 端点实装）。
- 视觉债务：页面视觉「功能及格、视觉不及格」（用户 2026-10-03 原话裁定）——经选 B 并入 M36 主题 token 期做全页视觉 pass，backlog「Web 呈现视觉债务」条目在案。本批不因视觉阻断功能验收。

## 回归与存量账证据（2026-10-03 实测）

- 全仓回归：`./mvnw test` exit 0，**14 模块 1142 例，0 失败 0 错误**（含 PluginRowsTest 9 + JarRowTest 6 + UserAssemblyTest 4 + PluginCenterTest 6 + WebBridgeTest 4 + PackageSmokeTest 2 新回归锁）。
- 存量账 grep 实证：`grep -c "已销账（2026-10-03，M35 工单 02/05" docs/limitations.md` → 1（#4 销账）；`grep -c "已部分销账，M35" docs/limitations.md` → 1（#1 收窄）；CHANGELOG 未发布段五条目在册（行级控制/插件包装载/可写事实源/贡献口/插件中心页+交货样板）。
- 文档站：vitepress build 绿；《插件中心》《插件包交货指南》两新页产物在册、nav 已登记。

## 发版两查预演（发布时执行，M32 经验档口径）

1. `releases/tags/v1.1.0` API/UI 实查 Release 存在且 fat-jar + sha256 资产齐（tag ≠ 发布完成）；
2. main 分支 CI 末次 run 结论实查绿——分支绿不算数。

CI 说明：本分支（`1.1.0`）推送即触发分支 CI；main 合并随发版流（红线 7）。
