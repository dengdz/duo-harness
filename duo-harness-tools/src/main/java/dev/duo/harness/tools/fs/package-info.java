/**
 * 本机 fs 工具族与 workspace 策略（ADR-0012）：read / write / edit / glob / grep
 * 五个 DSH 短名工具（bash 同属本包，随工单 03 落地）+ {@link dev.duo.harness.tools.fs.WorkspacePolicy}
 * 三档权限预设与路径包含性判定 + 读前写闸门（{@link dev.duo.harness.tools.fs.ReadGate}）。
 *
 * <p>边界约定：路径包含性是可信代码内的策略检查，不是 OS 内核边界；工具级
 * 错误双轨——结构化失败返回自纠指令文本，基础设施故障（IO）上抛转 isError。</p>
 */
package dev.duo.harness.tools.fs;
