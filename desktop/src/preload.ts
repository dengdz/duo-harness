/// <reference lib="dom" />
/**
 * 桌面壳 preload 桥（M37 工单 05）：contextIsolation 下的最小暴露面——渲染层
 * （app.js）经 window.duoDesktop 取通知门控与聚焦窗口能力，无 Node/文件面。
 * 门控判定走共享纯模块 notify-gate.ts（S3 单测锁定），此处只注入页面可见态。
 */
import { contextBridge, ipcRenderer } from 'electron';
import { shouldNotify } from './notify-gate';

contextBridge.exposeInMainWorld('duoDesktop', {
  /** 通知门控：页面可见中不扰 / 权限未授不发。可见态取自主进程 win.isVisible()——
   * 本版 Electron 在 macOS 上 hide() 后 document.visibilityState 仍为 visible
   * （首跑冒烟实测），渲染层可见信号不可靠；权限在渲染层读（Notification 归属页）。 */
  shouldNotify: (): boolean =>
    shouldNotify(
      ipcRenderer.sendSync('duo:window-visible') ? 'visible' : 'hidden',
      typeof Notification !== 'undefined' ? Notification.permission : 'denied',
    ),
  /** 通知点击回主窗：经主进程聚焦（渲染层无窗体控制权）。 */
  focusWindow: (): void => {
    ipcRenderer.send('duo:focus-window');
  },
});
