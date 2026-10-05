/// <reference lib="dom" />
/**
 * 桌面壳 preload 桥（M37 工单 05）：contextIsolation 下的最小暴露面——渲染层
 * （app.js）经 window.duoDesktop 取通知门控与聚焦窗口能力，无 Node/文件面。
 * 门控判定走共享纯模块 notify-gate.ts（S3 单测锁定），此处只注入页面可见态。
 */
import { contextBridge, ipcRenderer } from 'electron';
import { shouldNotify } from './notify-gate';

contextBridge.exposeInMainWorld('duoDesktop', {
  /** 通知门控：主窗聚焦中不扰 / 权限未授不发。聚焦态取自主进程 win.isFocused()——
   * 渲染层 visibilityState 在 hide() 后不翻转（首跑冒烟实测）、且切应用后窗口仍在
   * 屏上（isVisible=true）会被误判「可见中不扰」致通知永不触发（BUG-20261005-01）；
   * 权限在渲染层读（Notification 归属页）。 */
  shouldNotify: (): boolean =>
    shouldNotify(
      ipcRenderer.sendSync('duo:window-focused') === true,
      typeof Notification !== 'undefined' ? Notification.permission : 'denied',
    ),
  /** 通知点击回主窗：经主进程聚焦（渲染层无窗体控制权）。 */
  focusWindow: (): void => {
    ipcRenderer.send('duo:focus-window');
  },
});
