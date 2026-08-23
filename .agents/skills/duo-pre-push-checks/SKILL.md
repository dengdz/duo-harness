---
name: duo-pre-push-checks
description: 推送、强推或"准备推送"前触发。选择覆盖当前 diff 的最小测试/编译/文档校验证据集，不全量跑整个测试套件。用户说"推一下"、"提交前检查"、"跑测试"、"我要 push 了"时触发。
---

# duo-harness 推送前检查

在 duo-harness 仓库推送前，用本技能**一次性**选择并运行相关的本地证据。核心原则：**每个行为变更都需要能因其回归而失败的最窄测试或针对性检查**；只对 diff 真正触及的面增加更宽的检查。本仓库没有 git hooks 和 CI 矩阵兜底，本地证据是唯一关口，因此选择要准、执行要真。

## 查看待推送变更

```sh
git status --short --branch
git rev-parse --show-toplevel
```

确认分支后，识别变更范围（对抗基线）：

```sh
git diff --stat @{push} 2>/dev/null || git diff --stat origin/main
git diff --name-only --cached   # 已暂存
```

合并了基线的最新提交后，重新评估合并后的范围会影响哪些行为，只重跑被合并失效的检查。

## 按变更类型选择证据

| 变更类型 | 必跑证据 |
|---------|---------|
| 某模块 `src/main/**` 的行为变更 | 拥有该行为的测试类：`mvn test -pl <module> -Dtest=XxxTest`；共享契约（跨模块的数据类型、接口）变化时加跑相邻测试 |
| 示例/演示模块 | `mvn compile -q`（示例无测试，编译即证据） |
| `docs/**`、`README.md` | 内部链接校验：抽查每个相对链接指向的文件存在 |
| `pom.xml`、依赖、构建配置 | `mvn clean test` 全量（构建变更影响一切） |
| 对外 API 签名变更 | 全量 `mvn clean test` + `grep -rn "旧方法名" --include="*.md" docs/ README.md`（文档同步证据） |
| 持久化格式/编解码变更 | 全量跑受影响模块的测试（序列化格式影响兼容） |

**不要**因为"接下来要提交/推送"就重复跑一遍刚通过的检查。测试选择与全量的分界：行为变更跨模块、或找不到更窄的可信集合时才全量；`TODO(测试套件成形后):` 补记测试基数与全量耗时，作为选择的参照。

## 单类聚焦测试

```sh
mvn test -pl <module> -Dtest='XxxTest'
mvn test -pl <module> -Dtest='XxxTest#shouldDoSomething*'
```

模块隔离注意：`-pl <module>` 单独编译会用本地仓库的旧依赖版本；跨模块一致性的验证必须从根 POM 跑（`mvn compile` 不带 `-pl`）。

## 保护改写历史的推送

rebase 后强推前，先获取远端当前分支并记录精确 OID，用租约发布：

```sh
git fetch origin <branch>
git rev-parse origin/<branch>                      # 记录观察到的 OID
git push --force-with-lease=<branch>:<观察到的OID>
```

裸 `--force` 永远不允许。强推后重新 fetch，重查远端引用与本地 `HEAD` 一致；重写前的 commit 哈希与行内评论锚点不再是有效证据。

## 失败处理

- 相关检查失败：**停下来修复或说明阻塞原因**，不要"先推了赌 CI 不一样"（本仓库没有 CI）。
- 失败疑似环境特定（网络、API Key、平台差异）：举证——记录确切命令、失败测试、平台差异点；跑通不受平台影响的其余证据；仅当用户明确同意时绕过，并如实报告绕过了什么、为何预期其他环境不同。
- 需要真实 API Key 的示例/演示类不是本地证据的一部分，不在推送前运行。

## 推送流程

1. 选定的相关检查跑一次，全绿。
2. 提交；检查 pre-commit 修复器改动的文件（如有）。
3. 推送（普通推送，或授权改写分支用精确租约）。
4. 验证远端引用与本地一致：

```sh
git rev-parse HEAD origin/$(git branch --show-current)
```

报告时如实区分：已验证通过 / 已跳过及原因 / 无法本地验证（如真实 API 行为）。
