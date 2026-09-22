# M23 验收 Demo 汇总（工单 11）

> 逐工单最小演示 + 里程碑串讲脚本。各工单验收件已逐张通过（记录在各工单文件 Checklist）；本文档是**汇总回放**与**串讲脚本**，供里程碑终验亲手跑一遍。
> 启动命令（除注明外通用）：`mvn -pl duo-harness-example -am package exec:java -DskipTests -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain`

## 串讲脚本：一条故事线跑过 M23 全部能力（约 5 分钟）

场景：你让 agent 后台跑一个慢任务，中途插话、临时暂停、改技能、最后用脚本消费结果。

| # | 动作 | 体现的工单 | 预期 |
|---|---|---|---|
| 1 | 对 agent 说「后台跑一条 `sleep 30 && echo done`」并批准 | 04+06 | 立即返回 bg-1，提示符出现 `[后台 1 个运行中]` |
| 2 | **任务执行期间**（后台任务在飞）直接键入「顺便看下 README.md 讲了什么」 | 01 | 回显「已插队」——执行期输入不再被吞，模型下一步边界即见 |
| 3 | 运行期按一次 **Ctrl+C** | 02 | 协作式中断：当前工作停下、已流出内容保留、会话可续接（再按才强退） |
| 4 | 续接后说「继续，读完 README 后台任务结果出来了也告诉我」 | 02+04 | 中断点续接；后台完成通知自动送达（busy 则收口合并） |
| 5 | 让 agent 读一个不存在的路径触发提问/审批 | 03 | 并发第二个审批卡不再被拒（FIFO 小队列），Esc 可拒最旧 |
| 6 | 新开一个终端改 `.duo/skills/` 下任意 SKILL.md（或新建技能） | 09 | **免重启**：`/技能名` 直调立即生效 |
| 7 | 让 agent 「用 glob 找 *.log、用 grep 搜关键词」 | 08 | `.gitignore` 忽略的目标在两处结果**同时消失**（口径一致） |
| 8 | 把某任务跑到接近迭代上限（或观察模型自发说「预算快用完我先收敛」） | 10 | 模型收到「剩余 2 轮」提醒后主动收敛交付，而非被硬掐 |

## 逐工单最小演示（验收回放）

### 工单 01+02+03：执行期插队 / 暂停续接 / 审批队列
见串讲 #2/#3/#5。单工单演示已在各工单文件 Checklist 记录通过（2026-09-21/22）。

### 工单 04+06：后台任务与可见化
见串讲 #1/#4。通知路径二选一观察：空闲期完成自动开新轮；busy 期完成挂收件箱、turn 收口以「[排队消息生效]」合并消费。

### 工单 05：bash 输出三层
让 agent 跑一条大输出命令（如 `seq 1 50000`），观察输出尾部窗口 + 续读提示；`task-output` 回读全量。yml 可配（`fs-tools` 行 config.output）。

### 工单 07：headless --json（脚本消费）
```bash
cd <任意工作目录>
mvn -f /Users/zhangyl/IdeaProjects/duo-harness/pom.xml -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.DuoMain \
  -Dexec.args="--json 用 bash 列出当前目录文件" 2>/dev/null | grep '"type":"final"'
echo $?   # 0
```
四步全过记录见工单 07 Comments（NDJSON 帧流/退出码 0/SIGTERM→0/--session-id 恢复）——**本项为 M23 唯一待用户确认的验收件**，跑通即工单 07 转 done。

### 工单 08：.gitignore 同口径
```bash
mkdir -p /tmp/duo-ig/demo && cd /tmp/duo-ig/demo
echo "secrets.txt" > .gitignore && echo "hidden" > secrets.txt && echo "ok" > visible.txt
mvn -f /Users/zhangyl/IdeaProjects/duo-harness/pom.xml -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.DuoMain \
  -Dexec.args="--json 用 glob 列出所有 txt，再用 grep 搜 hidden" 2>/dev/null | grep '"type":"final"'
```
预期：`secrets.txt` 从 glob 与 grep **同时消失**。已通过（2026-09-22）。

### 工单 09：技能热加载
REPL 运行中 `mkdir -p .duo/skills/demo && 写 SKILL.md` → `/demo` 直调即生效；删除后再调报未知。已通过（含「删除重建失聪」缺陷的根因修复复验，2026-09-22）。

### 工单 10：迭代上限感知
`agent-demo.yml` cli 行 `maxIterations: 2` → REPL 问「不要用工具直接回答」→ 追问「有没有 `<system-reminder>`」→ 模型原文复述「剩余 2 轮（含本轮）…」。已通过（2026-09-22，yml 已还原 30）。

## 终验清单

- [ ] 串讲脚本 8 步亲手跑过
- [ ] 工单 07 headless 四步演示补跑确认（M23 最后一个待确认验收件）
- [ ] 全量 `mvn test` 绿
