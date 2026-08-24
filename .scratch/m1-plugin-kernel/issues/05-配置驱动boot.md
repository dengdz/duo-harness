# 05 — 配置驱动 boot

## What to build

从 plugins.yml 到运行中的插件树：boot 读取单文件 yml 配置（行结构：id/name/config/disabled，id 必填），逐行经编程 API 加载插件；disabled 行卸载实例但保留行；boot 收尾审计——失败态插件点名重抛原始错误、永久等待态点名缺失服务列表、无实例且未禁用的行报加载失败；任何失败触发整树回滚后以带阶段标签的错误退出。完成的判据：改一行配置即可改变形态（启用/禁用/改配置重载）；启动失败得到点名式报错且不留半启动状态（spec 用户故事 1/5/6/24，接缝 A 首批）。

## Blocked by

04

## Status

done

## Checklist

- [x] yml 解析（Jackson dataformat-yaml）→ 配置行结构 → 编程 API 加载
- [x] disabled 语义：卸载实例保留行；配置变更触发重载（复用 04 的 restart 语义）
- [x] boot 审计：FAILED 重抛、PENDING 点名缺失服务、空行报加载失败
- [x] 失败整体回滚：整树 dispose 后抛带阶段标签的聚合错误
- [x] 测试（接缝 A）：boot 激活/禁用生效；缺依赖插件被点名；坏 config 定位到行；失败回滚无残留

## Comments

- Scope 裁决："配置变更触发重载"按一次性引导满足（改配置重启生效）——运行时行级热重载属 HMR（spec Out of Scope），DSH 的 Entry.update 事务对账模型留作 M2 参考。行序无加载语义已用例锁定（依赖方行在前、提供方在后照样激活——04 依赖驱动的红利）。
- 行结构对齐 spec 决策浓缩形状：id（必填，审计锚点）/ name（FQCN + 公共无参构造）/ config（JsonNode 透传绑定）/ disabled。杂散 "-" 空项按解析错误报（非 NPE，ocr 发现）。
- 审计点名三态：FAILED 经 awaitStartup 取回原始错误、PENDING 列缺失服务清单（Context.hasService 存在性查询，不破坏 inject 纪律）、类不可加载点名 FQCN。
- ocr 审查修复两项：YAML 列表 null 项的 NPE 改抛 BootException（PARSE_CONFIG 阶段）；rawConfig 冗余三元简化。
- 66 用例（54 + 12 boot）三连跑全绿。
