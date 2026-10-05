/**
 * 桌面通知门控（M37 工单 05，ADR-0039 决策一「系统通知」+ spec 通知管线落钉）：
 * Electron 无关的纯判定——窗口可见中不扰（visibilityState 语义）+ 权限已授才发。
 * 消费方两处：preload 桥（document.visibilityState 注入）与 vitest 单测（S3 缝）；
 * 渲染层 app.js 的 desktopNotify 只做桥存在性判断与通知构造，不持有门控逻辑。
 */

/**
 * 是否应发桌面通知（BUG-20261005-01 修正语义：判「聚焦」不判「可见」——切应用后
 * duo 窗口多半仍在屏上（isVisible=true），按可见判会永远不发；「人在看 duo」的
 * 信号是窗口聚焦）。
 *
 * @param focused 主窗是否聚焦（main 经 win.isFocused() 注入）
 * @param permission 通知权限（Notification.permission：granted/default/denied）
 * @return true = 发通知（用户不在看 duo 且权限已授）
 */
export function shouldNotify(focused: boolean, permission: string): boolean {
  if (focused) {
    return false; // 聚焦中不扰——用户正看着 duo
  }
  return permission === 'granted';
}
