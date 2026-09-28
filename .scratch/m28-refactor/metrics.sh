#!/bin/sh
# M28 四项度量——可复跑。用法：sh .scratch/m28-refactor/metrics.sh
# 口径见同目录 metrics-baseline.md；重复代码组按立项清单做存在性检查（见 §④）。
cd "$(git rev-parse --show-toplevel)" || exit 1

echo "== ① 最大文件行数 top10（duo-harness-*/src/main/java）=="
find duo-harness-* -path "*/src/main/*" -name "*.java" -not -path "*/target/*" -exec wc -l {} + \
  | sort -rn | head -11 | tail -10

echo
echo "== ② 装配点参数个数 =="
echo "-- 已知装配点（精确值以工单 03/04/05/06 收口复核为准，此处列符号名行号）--"
grep -n "ChatAgent chatAgent(" duo-harness-agent/src/main/java/dev/duo/harness/agent/presenter/PresenterAssembly.java | tail -3
grep -n "void registerCommands(" duo-harness-cli/src/main/java/dev/duo/harness/cli/CliPlugin.java
grep -n "static WebFace start(" duo-harness-web/src/main/java/dev/duo/harness/web/WebFace.java | tail -2
grep -n "public ToolCallingAgent(" duo-harness-agent/src/main/java/dev/duo/harness/agent/internal/ToolCallingAgent.java | tail -2
echo "-- 全仓多参签名粗筛（单行签名 ≥8 逗号，多行签名需人工复核）--"
grep -rnE "^\s*(public|private|protected|static).*\(.*,.*,.*,.*,.*,.*,.*,.+\)\s*\{?\s*$" \
  --include="*.java" duo-harness-*/src/main/java | grep -v "/target/" | wc -l | xargs echo "命中行数:"

echo
echo "== ③ user.dir 同形计数 =="
grep -rn 'System.getProperty("user.dir")' --include="*.java" duo-harness-*/src/main/java | grep -v "/target/" | wc -l | xargs echo "总命中:"
grep -rn 'Path.of(System.getProperty("user.dir"))' --include="*.java" duo-harness-*/src/main/java | grep -v "/target/" | wc -l | xargs echo "精确同形:"

echo
echo "== ④ 重复代码组存在性检查（立项三组）=="
echo "-- 组A 接线三连抄（三处 apply 接线串）--"
grep -c "apply" duo-harness-cli/src/main/java/dev/duo/harness/cli/CliPlugin.java \
  duo-harness-web/src/main/java/dev/duo/harness/web/WebPlugin.java 2>/dev/null
ls duo-harness-example/src/main/java/dev/duo/harness/example/headless/HeadlessRunner.java 2>/dev/null || \
  find duo-harness-example -name "HeadlessRunner.java" -not -path "*/target/*"
echo "-- 组B 路径解析双实现（WorkspacePolicy 同文件两处同形 resolve）--"
grep -cn "isAbsolute() ? .*normalize() : .*resolve(.*)\.normalize()" duo-harness-tools/src/main/java/dev/duo/harness/tools/fs/WorkspacePolicy.java
echo "-- 组C 汉字判定三处（M26 收口已收敛为 QueryTokenizer.isHan 单一事实源，期望仅 1 处定义）--"
grep -rn "boolean isHan(" --include="*.java" duo-harness-*/src/main/java | grep -v "/target/"
