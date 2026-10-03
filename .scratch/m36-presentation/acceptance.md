# M36 端到端验收件（1.2.0，ADR-0038）

验收执行：**用户授权 agent 代验**（2026-10-03，「我不验了，你来验」）；本件按 duo-acceptance 判据成文，全部步骤由 agent 在隔离实例上亲跑并留证据（截图 `.scratch/m36-presentation/style-proof/`）。实际发版（1.2.0 落日期/tag）随用户指令。

## 环境与可复跑命令

```bash
# 1. 打包（含全部模块与新主题样板）
./mvnw -q -pl duo-harness-example -am package -DskipTests

# 2. 启动隔离实例（临时 DUO_HOME，不触碰真实 ~/.duo；auth: none 使警示行入镜）
cd duo-harness-example/target
java -Dduo.home=/tmp/m36-style/duo-home -jar duo-harness-1.1.0.jar /tmp/m36-style/screenshot.yml
# 夹具 /tmp/m36-style/screenshot.yml：tools/prompts/answers/commands/center + web(18080, auth none)

# 3. 主题包真装腿的包来源（工单 04 样板模块，包内容仅自有类——交货约定）
./mvnw -q -pl duo-harness-theme-light -am package -DskipTests
cp duo-harness-theme-light/target/duo-harness-theme-light-1.1.0.jar /tmp/m36-style/duo-home/plugins/

# 4. 浏览器打开 http://127.0.0.1:18080 按《八步核对表》逐项走
```

## 八步核对表（主题包真装全流程，全程不重启）——已自跑全过

| # | 步骤 | 预期 | 实测 | 证据 |
|---|---|---|---|---|
| 1 | jar 放插件目录 → 打开插件中心 | 待装区列出包（文件名 + 大小 + sha 前缀） | ✓ `duo-harness-theme-light-1.1.0.jar（5KB · sha af537816）` | 本件记录 |
| 2 | 点「安装」 | 行内装前点名安装单：入口类唯一候选只读 + 行 id 预填 + config 可空 | ✓ FQCN `dev.duo.harness.theme.light.LightThemePlugin`（只读）、id 预填 | 本件记录 |
| 3 | 填行 id `theme-light` → 确认安装 | 已装行卡出现 `theme-light ACTIVE`，操作写回装配文件（共 7 行 7 活跃） | ✓ | 本件记录 |
| 4 | 主题选择器（5s 节拍） | 出现「light:主题：亮色（内置样板）」 | ✓ | 本件记录 |
| 5 | 切换亮色 | 整页即时切亮色（body 精确命中值集 `rgb(238,240,243)`）+ localStorage `duo-theme=light` | ✓ 截图 `style-proof/11-real-install-light.png` | 归档 |
| 6 | 刷新页面 | 亮色保持（持久化恢复 + 值集重套 + 选择器回填） | ✓（工单 04 走查二同口径） | `style-proof/04` |
| 7 | 卸载：点「卸载」→ armed「确认卸载？」→ 再点执行 | 行消失、聚合端点 themes 空、**5s 内自动回落内置暗色**（body `rgb(11,15,22)`）+ localStorage 清空 + 选择器复位 | ✓ 全部命中 | 本件记录 |
| 8 | 全程重启次数 | 0 | ✓ 0 | — |

## 分项验收记录（各工单，均已自跑）

- **工单 01（事件结构化字段）**：五目标套件（Session 63/Tools 11/Auditing 5/ExitPlan 5/Headless 8）+ 全量回归；S3 字段断言与旧文案兼容锁绿。旧日志回放回落实测（工单 05 验收三同管道）。
- **工单 02（暗色精品 + token 体系）**：样式稿四轮用户目测裁定通过（2026-10-03，用户亲自参与——实底红块/全宽条两轮被裁定突兀后改状态区薄纱警示行 + 输入框暗底）；token 块外硬编码 grep 零命中；截图 `style-proof/01-02`。
- **工单 03（呈现贡献口 + 聚合端点）**：S1 声明闸门 + S2 HTTP 缝（无/错 token 403、auth:none 一致 200、快照 JSON 形态）；**Boot 审计时序头号风险实测排除**（主题行先于 web 行，指纹自愈注册，回归锁 `themeRowBeforeWebSelfHealsViaDependencyFingerprint`）。
- **工单 04（前端主题机制）**：S4 三态（切亮色/刷新保持/卸载回落，classpath 行形态）+ 真装腿（本件八步）。
- **工单 05（卡片注册表 + 魔法串退役）**：浏览器五项验收（声明驱动渲染/status 字段驱动/旧日志回落/声明摘除回落/审批 decision 字段）——`style-proof/10`。**改文案抽查的等效实证**：合成事件文案「用户拒绝了这个查询」「已拒绝（新的文案形态）」均无魔法串、页面判定全部正确（字段驱动即「改文案不误判」的结构性保证）。
- **工单 06（插件中心精修 + 响应式）**：四项验收（行卡徽标/改配置行内展开/卸载 armed/820px 纵排对话区 192→796px）截图 `style-proof/06-09`；prompt/confirm 全仓 grep 零残留。
- **工单 07（文档与存量账）**：文档站构建绿；扩展点清单两行兑现 + 呈现贡献口增补；已知限制 M36 两条；backlog 双销账（逐条核对交付物实体）。
- **全量回归**：15 模块 1159 例 0 失败 0 错误（工单 04 后基线；06/07 纯前端与文档无 Java 面变化，05 已含 app.js 改动后的全量轮）。

## 存量账复核（grep 实证）

- [x] backlog「工具名协议化渲染」「Web 页面全视觉 pass」两条已销账（交付物实体核对：97f78313/99d7e1ff/c273b313/6641edeb）
- [x] ADR-0029「协议化渲染留 1.0 后」/ 扩展点清单 M36 预登记 / WebRouteRegistry javadoc / 术语表内核独占面预埋——四处预登记全部兑现
- [x] CHANGELOG 未发布段六条目齐（01/02/03/04/05/06 各一）
- [x] 已知限制页 M36 节两条（主题改值不改构 / 展示卡新工具限定 + 交互卡独占）

## 缺陷与遗留

- 本轮验收零 P0/P1；过程中的实现期缺陷均在对应工单内修复并留回归锁（Boot 时序/快照活引用/Void config 严格绑定/输入框白底/横幅两层形态病根）。
- 遗留观察（不阻塞）：内置工具 paramSummary if 链未并入声明表（声明表服务开放面，内置摘要收敛作后续打磨）；CI 腿随分支推送触发（推送随用户指令）；实际发版（1.2.0 落日期/tag/Release）随用户指令走 duo-release-workflow。
