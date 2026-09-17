/**
 * subagent 功能演示（M15）：父 agent 经 spawn/fork 派生同进程内嵌子代理的全程可见演示。
 *
 * <p>入口三件：</p>
 * <ul>
 *   <li>{@code SubagentDemoMain}——脚本化 LLM 驱动全程（无外部调用、不烧 token），
 *       逐条打叙述行：模板装配、宿主机件发布、spawn 立即返回、子代理多轮工具循环
 *       （与主 agent 同构的迭代能力）、completed 回流父投影、侧栏排除、fork 播种
 *       与种子边界；</li>
 *   <li>{@code SubagentWebDemoMain}——Web 面子任务卡三态与右侧抽屉回放的浏览器
 *       视觉验证（预置会话事件，红线 5）；</li>
 *   <li>{@code subagent-demo.yml}（resources 下）——CLI 真 LLM 实测配置：
 *       挂 subagent 插件与两套模板，装配后主 agent 工具清单多出五件。</li>
 * </ul>
 */
package dev.duo.harness.example.subagent;
