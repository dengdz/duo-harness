/**
 * 桌面壳 Electron 主进程（M37 工单 02，ADR-0039）：薄壳——编排逻辑全在 backend.ts
 * （可测），这里只做接线：JDK 探测 → 端口探测 → 拉起后端 → 开窗加载锚点 URL。
 * 失败走原生指引对话框（JDK 缺失/后端启动失败携 stderr 尾部）。--smoke 为验收旗标：
 * 窗口加载完成后截屏存档并退出（真机冒烟证据，红线 5）。
 */
import { app, BrowserWindow, dialog } from 'electron';
import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  BackendStartError,
  JdkMissingError,
  findFreePort,
  resolveJarPath,
  resolveJava,
  startBackend,
  type BackendHandle,
} from './backend';

let backend: BackendHandle | null = null;

function createWindow(url: string): BrowserWindow {
  const window = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'duo-harness',
    show: false,
  });
  window.once('ready-to-show', () => window.show());
  void window.loadURL(url);
  return window;
}

/** --smoke：加载完成后截屏存档退出（真机冒烟证据，不进交互）。 */
async function smokeCapture(window: BrowserWindow): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 1500));
  const image = await window.webContents.capturePage();
  const out = process.env.DUO_DESKTOP_SMOKE_OUT ?? path.join(process.cwd(), 'smoke-window.png');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, image.toPNG());
  console.log(`duo:smoke-shot ${out}`);
}

app.whenReady().then(async () => {
  const smoke = process.argv.includes('--smoke');
  try {
    const { javaPath, major } = resolveJava();
    console.log(`duo:shell java=${javaPath} major=${major}`);
    const jarPath = resolveJarPath();
    console.log(`duo:shell jar=${jarPath}`);
    const port = await findFreePort();
    console.log(`duo:shell port=${port}`);
    backend = await startBackend({ jarPath, javaPath, port });
    console.log(`duo:shell ready url=${backend.url}`);
    const window = createWindow(backend.url);
    if (smoke) {
      window.webContents.once('did-finish-load', () => {
        void smokeCapture(window).finally(() => app.quit());
      });
    }
  } catch (err) {
    const detail =
      err instanceof JdkMissingError || err instanceof BackendStartError
        ? `${err.message}${err instanceof BackendStartError && err.stderrTail ? `\n\n—— 后端 stderr 尾部 ——\n${err.stderrTail}` : ''}`
        : String(err);
    console.error(`duo:shell failed: ${detail}`);
    if (smoke) {
      // 自动化冒烟不弹模态框（无人点会挂死验收）：stderr 明示后非零退出
      app.exit(1);
      return;
    }
    dialog.showErrorBox('duo 桌面壳启动失败', detail);
    app.quit();
  }
});

app.on('before-quit', () => backend?.stop());

// 关窗即退出（最小形态；工单 03 改托盘常驻隐藏语义）
app.on('window-all-closed', () => app.quit());
