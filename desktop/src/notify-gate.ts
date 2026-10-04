/**
 * 桌面通知门控（M37 工单 05，ADR-0039 决策一「系统通知」+ spec 通知管线落钉）：
 * Electron 无关的纯判定——窗口可见中不扰（visibilityState 语义）+ 权限已授才发。
 * 消费方两处：preload 桥（document.visibilityState 注入）与 vitest 单测（S3 缝）；
 * 渲染层 app.js 的 desktopNotify 只做桥存在性判断与通知构造，不持有门控逻辑。
 */

/**
 * 是否应发桌面通知。
 *
 * @param visibility 页面可见态（document.visibilityState：visible/hidden/…）
 * @param permission 通知权限（Notification.permission：granted/default/denied）
 * @return true = 发通知（后台触发且权限已授）
 */
export function shouldNotify(visibility: string, permission: string): boolean {
  if (visibility !== 'hidden') {
    return false; // 聚焦/可见中不扰——通知是「人不在场」的到达通道
  }
  return permission === 'granted';
}
