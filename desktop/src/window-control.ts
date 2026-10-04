/**
 * 窗口形态编排（M37 工单 03，ADR-0039 决策一「原生桌面体验」）：Electron 无关的
 * 显隐状态机与菜单动作路由——关窗拦截隐藏（mac 惯例：关窗不退出）、托盘点击切换、
 * 托盘/应用菜单三动作。main.ts 注入真实 BrowserWindow/shell，vitest 注入假窗体
 * （S3 缝扩展，工单 02 的 backend.ts 分层先例）。
 */

/** 主窗所需的形态面（BrowserWindow 的子集，测试假体对齐）。 */
export interface MainWindowLike {
  isVisible(): boolean;
  isDestroyed(): boolean;
  show(): void;
  hide(): void;
  focus(): void;
}

export interface WindowControlDeps {
  /** 当前主窗（可能为 null 或已销毁——崩溃/异常路径，createWindow 重建）。 */
  getWindow(): MainWindowLike | null;
  /** 主窗重建工厂（销毁后托盘再开用；闭包持有后端 URL）。 */
  createWindow(): MainWindowLike;
  /** 真退出（工单 04 换探活编排；本票占位直通 app.quit）。 */
  quit(): void;
  /** 打开目录（mac Finder 新窗）。 */
  revealDir(dir: string): void;
  /** 在系统文件管理器中定位条目（父窗高亮——票面「Finder 定位」语义）。 */
  revealItemInFolder(item: string): void;
  /** 数据目录解析（DUO_HOME > ~/.duo，与后端 DuoHome 环境级优先级对齐）。 */
  dataDir(): string;
}

/** 托盘点击/打开主窗：可见则聚焦，隐藏则显示；窗体已销毁则经工厂重建。 */
export function showMainWindow(deps: WindowControlDeps): void {
  let win = deps.getWindow();
  if (!win || win.isDestroyed()) {
    win = deps.createWindow();
  }
  win.show();
  win.focus();
}

/** 托盘点击切换：可见→隐藏，隐藏→显示。返回切到后的形态。 */
export function toggleMainWindow(deps: WindowControlDeps): 'shown' | 'hidden' {
  const win = deps.getWindow();
  if (win && !win.isDestroyed() && win.isVisible()) {
    win.hide();
    return 'hidden';
  }
  showMainWindow(deps);
  return 'shown';
}

/**
 * 关窗拦截判定（window 'close' 事件）：非退出流程且窗体健在 → 拦截隐藏（mac 惯例
 * 关窗不退出，dock/托盘保持）；真退出（Cmd-Q/菜单退出触发 before-quit 置位）或窗体
 * 已销毁 → 放行 close。
 */
export function shouldInterceptClose(quitting: boolean, win: MainWindowLike | null): boolean {
  return !quitting && !!win && !win.isDestroyed();
}

/** 托盘/应用菜单的三动作路由（打开主窗 / 打开数据目录 / 退出——工单 04 接探活编排）。 */
export function createTrayActions(deps: WindowControlDeps): {
  openMainWindow: () => void;
  openDataDir: () => void;
  quit: () => void;
} {
  return {
    openMainWindow: () => showMainWindow(deps),
    openDataDir: () => deps.revealItemInFolder(deps.dataDir()),
    quit: () => deps.quit(),
  };
}
