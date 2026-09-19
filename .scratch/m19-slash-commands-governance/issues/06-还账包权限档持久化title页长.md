# 06: 还账包——权限档持久化 + /title + 页长可配

**What to build:** 三件还账一起清：① `/permission` 切档落 `permission/mode` 会话事件，重开该会话恢复最后档位、新会话回 yml 缺省（M12#3 销账）；② `/title 新标题` 改名命令（busySafe=true），再 append title 事件即生效（M13#1 销账）；③ web 插件 config 可配首屏/每页消息数（缺省 50 不变，M13#2 销账）。

**Blocked by:** 01（/title 注册为命令；permission 持久化与页长本身不依赖，随包同行）

**Status:** ready-for-agent

语义权威：ADR-0020 决策 10/11/12；spec 实现决策"还账四件"节。

- [ ] 新增 `permission/mode` 会话事件（latest-wins 投影，plan/mode 同款先例）：/permission 切档时落事件；会话打开投影恢复最后档位（写回 WorkspacePolicy）；新会话/新部署回 yml 缺省（档位跟对话走，不跨会话惊吓）
- [ ] /title 命令（busySafe=true，纯事件写）：再 append `session/title` 即改名（latest-wins 投影现成）；双面可用；标题自动演进不做
- [ ] web 插件 config 增首屏/每页消息数（缺省 50 不变），WebFace 分页逻辑参数化；config 校验沿"未知字段/类型不符/越界一律异常点名"惯例
- [ ] 测试：缝 2（permission/mode latest-wins 投影）+ 缝 3（切档→落事件→重开恢复的 CliPluginTest 脚本用例；/title 改名后侧栏标题事件断言；WebFaceTest 页长 config 生效）
