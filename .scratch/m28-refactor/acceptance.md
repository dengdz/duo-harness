# M28 手动验收件（三件套）

> 里程碑级验收：重构「行为零变化」走查。命令自 [运行Demo.md](../../docs/01-入门/运行Demo.md) 原样复制（仅改端口做实例隔离与 mainClass）；预期对照按验收点分段。改动域覆盖映射：验收一 = 工单 03/04/06/07/08 + demo 基线，验收二 = 工单 03 spec 迁移，验收三 = 工单 05 host 改名。

## 准备（实例隔离——端口/会话锁不与你日常实例相撞）

```bash
# ① 生成隔离装配副本（端口 18081）；② 确认没有你之前在跑的 REPL/Web 实例（占端口与会话锁）
sed 's/18080/18081/' duo-harness-example/src/main/resources/agent-demo.yml > /tmp/m28-acceptance.yml
```

前置：`~/.duo/config.yml` 的 `llm:` 段照旧（与日常使用同一份）。

## 验收一：CLI + Web 双面走查（约 25 分钟，一条命令起）

```bash
mvn -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.DuoMain \
  -Dexec.args="/tmp/m28-acceptance.yml"
```

> ⚠️ 必须带 `-Dexec.mainClass`：缺省（无 mainClass）时 exec 跑的是 demo.yml 一条命令演示（M1/M2 叙述），**不会启动 CLI/Web**——2026-09-28 验收首版漏带被用户抓到，已自跑实测修正（横幅与 `你>` 提示符均出）。

启动预期：终端打印 `Web 面已启动（鉴权开启）: http://127.0.0.1:18081/?token=…`，随后 CLI `你>` 提示符。

### 一-A 终端 REPL（工单 03 装配链 + 04 命令注册 + 08 Cwd）

| # | 你做什么 | 应看到 |
|---|---|---|
| 1 | `读一下 pom.xml 的前 10 行` | `[调工具] read` → `[工具结果]` → 流式回答（agent 装配链走 AgentSpec） |
| 2 | `新建 hello-m28.txt 内容是验收` | write 免审批直接 `Created`（workspace-write 档；cwd 取 Cwd 单一源——文件落在启动目录） |
| 3 | `用 bash 跑 ls` | `[待审批]` → 回答 `y` 批准 → 输出 + `[exit code: 0]` |
| 4 | `/toolstats` | 报表含 read/write/bash 计数（M27 demo 基线不破） |
| 5 | `/permission` → `/permission read-only` → `写个测试文件` | 切档显示 → 写操作转 `[待审批]`（切档即时生效）→ `/permission workspace-write` 切回 |
| 6 | `/plan 用几句话调研项目结构` → 计划中 `用 bash 跑 ls` | 进入计划模式 → 只读 bash 放行（PlanBashGate 端口裁决）；再试 `用 bash 删 hello-m28.txt` → 拒（非只读）→ 模型 `exit_plan_mode` 呈交 → 批准 |
| 7 | `/new` → `/model` → `/effort` | 新会话 id 提示；模型白名单清单；思考等级当前档（registerCommands 分组对象化后全部命令活） |
| 8 | `/export` | 打印落盘路径 `duo-session-<id>.md`（Cwd 解析） |
| 9 | `/exit` | 终端退出、**Web 面不受影响**（浏览器仍可对话） |

### 一-B 浏览器 Web 面（工单 06 WebFace 拆分八域 + 07 fileRefs）

打开 `http://127.0.0.1:18081/?token=<启动打印的 token>`：

| # | 你做什么 | 应看到 |
|---|---|---|
| 10 | 打开页面 | 三区布局（侧栏/对话/状态面）——入口栅栏、静态资源、标签会话域全链路 |
| 11 | 输入框发 `总结一下这个项目` | 「思考中…」→ 流式回复 → 完成后 Markdown 渲染（对话端点 + SSE 事件投影） |
| 12 | `往 /tmp/duo-m28.txt 写点东西` | [待审批] 卡片 → 批准 ✓ 继续 / 再试一次拒绝 → ✗ 模型解释（HITL 回答端点） |
| 13 | 输入框敲 `@po` | **补全下拉出现**（fileRefs 服务化重点——经服务注册表惰性寻址；服务断链这里直接 503 提示）→ 选中发送 → 模型 read |
| 14 | 侧栏搜索框输入一个历史会话关键词回车 | 命中列表（标题/【】摘录）→ 点击切换到该会话 |
| 15 | 切到大 会话滚动到顶 | 「更早还有 N 条」→ 自动加载上一页、不跳屏 |
| 16 | 输入框敲 `/export` | 浏览器自动下载（Web 分流下载 URL） |
| 17 | 发消息执行中刷新页面 | 尾窗快照重建（最近 50 条）、执行中任务悬空审批卡保留可答 |
| 18 | 开第二个标签页 | 各自独立会话互不串（TabContext 域）；A 标签的审批卡不弹到 B |
| 19 | 关闭全部标签页 → CLI 让模型发起一个审批 | 无人在场 → fail-closed 拒绝（宽限期去抖后） |

## 验收二：headless --json（约 2 分钟，工单 03 spec 迁移）

```bash
mvn -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.DuoMain \
  -Dexec.args="--json 总结一下当前目录结构"
```

| 你看什么 | 应看到 |
|---|---|
| stdout 逐行 JSON | `session{sessionId,cwd}` 开场（cwd = 启动目录，Cwd 源）→ `status`/`tool_call`/`tool_result`/`text` → `final{text}` |
| `echo $?` | `0`（completed） |

## 验收三：子代理 host 改名（约 3 分钟，工单 05 重点）

```bash
mvn -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.subagent.SubagentDemoMain
```

| 你看什么 | 应看到 |
|---|---|
| 两段叙述 | `─── spawn：全新子代理 ───` 与 `─── fork：带着父对话背景派生 ───` 各自跑通 |
| 回流行 | `[回流] 父会话收到 subagent/completed`——**host 服务 inject 链真实跑通**（改名若断链，此处报「缺失服务 [host]」） |
| 侧栏/锁 | `[锁] 子会话已完成，锁已释放=true`、子会话事件链可回放 |

## 通过判据与记账

- 全部验收点与上表一致 → 十张工单 Status 置 done，M28 进入四轴审查
- 任一现象与预期不符 → 回报现象（贴日志/截图），回实现修复后重备重验——**你的实测优先于任何测试绿灯**
