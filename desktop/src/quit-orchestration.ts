/**
 * 退出编排决策核（M37 工单 04，ADR-0039 决策一「退出前探活确认」）：Electron 无关
 * 的纯决策状态机——探活忙 → 征询 → 按答退出/取消；探活失败（后端已死/无响应）按
 * 可退处理（fail-open，退出不应被一个已死后端拦住）。main.ts 注入真实探活与确认
 * 对话框，vitest 注入假体（S3 缝，backend.ts 分层先例）。
 */

export type QuitDecision = 'quit' | 'cancel';

export interface QuitDecisionDeps {
  /** 探活：后端是否有 agent 在飞（网络/超时错误抛出）。 */
  probeBusy(): Promise<boolean>;
  /** 忙时征询：true = 用户确认退出，false = 取消回常驻。 */
  confirmBusyQuit(): Promise<boolean>;
}

/**
 * 退出决策：探活空闲（或失败）→ quit；忙 → 用户裁定。三入口（Cmd-Q/应用菜单/
 * 托盘退出）经 before-quit 归一后都走这一处。
 */
export async function resolveQuit(deps: QuitDecisionDeps): Promise<QuitDecision> {
  try {
    if (await deps.probeBusy()) {
      return (await deps.confirmBusyQuit()) ? 'quit' : 'cancel';
    }
  } catch {
    // 探活失败按可退处理（ADR-0039 决策一）：退出不被已死/无响应的后端拦住
  }
  return 'quit';
}

/** 崩溃判定上下文（工单 07）：退出流程标志与后端停止请求标志。 */
export interface ExitContext {
  /** 真退出流程在途（before-quit 已放行，exit 属预期收口）。 */
  quitting: boolean;
  /** 已对后端发起过 stop（SIGTERM 在途/已收口，exit 属预期）。 */
  stopRequested: boolean;
}

/**
 * 意外退出判定（工单 07 崩溃恢复的互斥位）：退出流程在途或已主动 stop → 预期收口；
 * 两者皆否的 exit = 崩溃 → 恢复对话框。restart 后由调用方复位上下文。
 */
export function isUnexpectedExit(ctx: ExitContext): boolean {
  return !ctx.quitting && !ctx.stopRequested;
}
