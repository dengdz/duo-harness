# 04: 父级 steer——运行中消息注入

**What to build:** agent 执行长任务时，Web 发来的消息不再 409——进 agent 注入收件箱，send 循环在迭代边界排干：逐条落普通 `user/message`、下一轮请求可见（模型下一步就看到你的补充指示），不打断飞行中的工具组；Web 端返回"已注入，待当前步骤完成"轻提示。与子代理 send_message 的"下一轮生效"语义对称达成（ADR-0016 拒绝项的批评消除）。

**Blocked by:** None (can start immediately，独立线)

**Status:** ready-for-agent

语义权威：ADR-0020 决策 8/9；spec 实现决策"父级 steer（agent 域 + web 域）"节。

- [x] ToolCallingAgent 新增注入收件箱（并发队列）与 `injectUserMessage(text)`：busy 期间 WebFace 调用之（替代 409），返回 202 + "已注入"标记
- [x] send 循环在迭代边界排干收件箱：逐条落普通 `user/message` 事件再构造下一轮请求（不发明 steer 专属事件；多条注入照排）
- [x] 不打断飞行中工具组：注入只在迭代边界生效，当前步骤的工具组完整跑完
- [x] WebFace busy 分支重写：409 断言的并发测试同 diff 改为注入断言（"执行中 POST → 202 + 已注入 → 迭代边界落 user/message → 下一轮请求含该文本"）——**落地注记**：不支持 injectUserMessage 的 agent（测试桩/旧实现）保留 409 兜底，注入协议不静默漂移
- [x] 前端 toast 轻提示"已注入，待当前步骤完成"（执行中发送时）
- [x] 测试走缝 1（真实异步时序：慢 mock LLM + 并发注入，同步 mock 会让边界排干永不生效）+ 缝 3 WebFaceTest；同批断言日志"连续 user/message"形态的投影与回放兼容
- [x] CLI 不接运行中 steer（行缓冲天然排队）；机制留 agent 域，backlog 记"CLI 运行中 steer 入口"条目
