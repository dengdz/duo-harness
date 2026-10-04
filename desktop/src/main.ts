/**
 * 桌面壳 Electron 主进程（M37 工单 02/03，ADR-0039）：薄壳——编排逻辑在
 * backend.ts（拉起链）与 window-control.ts（显隐状态机/菜单动作，可测），这里只
 * 接线：JDK 探测 → 端口探测 → 拉起后端 → 开窗加载锚点 URL；托盘常驻 + 应用菜单
 * （工单 03，mac 惯例：关窗=隐藏不退出、dock 点击复原、托盘左键切换右键菜单）。
 * 失败走原生指引对话框（--smoke 验收模式退化为 stderr 退出——模态框无人点会挂死
 * 自动化）。--smoke 序列：加载截屏 → 关窗（验证拦截隐藏）→ 托盘逻辑复原 → 复原
 * 截屏 → 退出。
 */
import { app, BrowserWindow, dialog, Menu, nativeImage, shell, Tray } from 'electron';
import * as fs from 'node:fs';
import * as os from 'node:os';
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
import {
  createTrayActions,
  shouldInterceptClose,
  toggleMainWindow,
  showMainWindow,
  type MainWindowLike,
  type WindowControlDeps,
} from './window-control';

let backend: BackendHandle | null = null;
let window: BrowserWindow | null = null;
let tray: Tray | null = null;
let quitting = false;

/** 关窗拦截只在 darwin 生效（mac 惯例关窗驻留）；其他平台关窗即退出（window-all-closed 兜底）。 */
const interceptHideOnClose = process.platform === 'darwin';

/** 数据目录解析（DUO_HOME > ~/.duo——与后端 DuoHome 环境级优先级对齐；JVM 内 sysprop 测试口对壳不可见）。 */
function dataDir(): string {
  return process.env.DUO_HOME || path.join(os.homedir(), '.duo');
}

/** 菜单/托盘点击回调兜底：编排抛错进对话框而非主进程 uncaughtException 崩壳。 */
function safely(action: () => void): () => void {
  return () => {
    try {
      action();
    } catch (err) {
      dialog.showErrorBox('duo 桌面壳', String(err));
    }
  };
}

const controlDeps: WindowControlDeps = {
  getWindow: () => window,
  createWindow: () => {
    if (!backend) {
      throw new Error('后端未就绪，无法重建主窗');
    }
    window = createWindow(backend.url); // 回写全局：销毁重建后 getWindow 取新窗（防窗体累积）
    return window;
  },
  quit: () => app.quit(),
  revealDir: (dir) => {
    void shell.openPath(dir).then((err) => {
      if (err) {
        dialog.showErrorBox('打开数据目录失败', err);
      }
    });
  },
  revealItemInFolder: (item) => shell.showItemInFolder(item),
  dataDir,
};

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function createWindow(url: string): BrowserWindow {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'duo-harness',
    show: false,
  });
  win.once('ready-to-show', () => win.show());
  // 关窗拦截（工单 03，仅 darwin）：非退出流程一律隐藏不退出（mac 惯例，dock/托盘保持）
  win.on('close', (event) => {
    if (interceptHideOnClose && shouldInterceptClose(quitting, win as MainWindowLike | null)) {
      event.preventDefault();
      win.hide();
    }
  });
  void win.loadURL(url);
  return win;
}

/** 托盘模板图标：运行时生成 16×16 圆点（BGRA 黑 + alpha，macOS 模板图随菜单栏明暗自适应）。 */
function trayIcon() {
  const size = 16;
  const bitmap = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const inside = Math.hypot(x - 7.5, y - 7.5) <= 6.5;
      const i = (y * size + x) * 4;
      bitmap[i] = 0; // B
      bitmap[i + 1] = 0; // G
      bitmap[i + 2] = 0; // R
      bitmap[i + 3] = inside ? 255 : 0; // A
    }
  }
  const image = nativeImage.createFromBitmap(bitmap, { width: size, height: size });
  image.setTemplateImage(true);
  return image;
}

function setupTray(): void {
  const actions = createTrayActions(controlDeps);
  tray = new Tray(trayIcon()); // 全局持有：Tray 局部变量会被 GC 致托盘消失（Electron 惯例坑）
  tray.setToolTip('duo-harness');
  const menu = Menu.buildFromTemplate([
    { label: '打开主窗', click: safely(actions.openMainWindow) },
    { label: '打开数据目录', click: safely(actions.openDataDir) },
    { type: 'separator' },
    { label: '退出 duo', click: safely(actions.quit) }, // 工单 04 换探活编排
  ]);
  // mac 双手势：左键=切换主窗显隐（票面「点击显隐」），右键=弹菜单三项——设了
  // context menu 后左键会被菜单吃掉，故不 setContextMenu 而手动 popUp
  tray.on('click', () => safely(() => toggleMainWindow(controlDeps))());
  tray.on('right-click', () => tray?.popUpContextMenu(menu));
}

function setupAppMenu(): void {
  const actions = createTrayActions(controlDeps);
  Menu.setApplicationMenu(
    Menu.buildFromTemplate([
      { role: 'appMenu' },
      { role: 'editMenu' },
      { role: 'viewMenu' },
      { role: 'windowMenu' },
      { label: '打开数据目录', click: safely(actions.openDataDir) },
    ]),
  );
}

async function captureTo(win: BrowserWindow, file: string): Promise<void> {
  const image = await win.webContents.capturePage();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, image.toPNG());
  console.log(`duo:smoke-shot ${file}`);
}

/** 冒烟断言：不符即非零退出（静默打印会让回归在 CI 假通过）。 */
function smokeAssert(condition: boolean, message: string): void {
  console.log(`duo:smoke ${message}`);
  if (!condition) {
    throw new Error(`smoke 断言失败：${message}`);
  }
}

/** --smoke 序列：首截屏 → 关窗（验证拦截隐藏）→ 托盘切换复原 → 复原截屏 → 退出。 */
async function smokeSequence(win: BrowserWindow): Promise<void> {
  const base = process.env.DUO_DESKTOP_SMOKE_OUT ?? path.join(process.cwd(), 'smoke-window.png');
  await delay(1500);
  await captureTo(win, base);
  win.close();
  await delay(500);
  smokeAssert(!win.isVisible(), `after-close visible=${win.isVisible()}（关窗拦截隐藏）`);
  toggleMainWindow(controlDeps);
  await delay(500);
  smokeAssert(win.isVisible(), `after-toggle visible=${win.isVisible()}（托盘切换复原）`);
  await captureTo(win, base.replace(/\.png$/, '-reopened.png'));
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
    window = createWindow(backend.url);
    setupTray();
    setupAppMenu();
    // dock 点击复原（mac 惯例：关窗后点 dock 图标回窗）
    app.on('activate', () => safely(() => showMainWindow(controlDeps))());
    if (smoke) {
      window.webContents.once('did-finish-load', () => {
        void smokeSequence(window!)
          .catch((err) => {
            console.error(`duo:smoke failed: ${err}`);
            app.exit(1);
          })
          .finally(() => app.quit());
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

app.on('before-quit', () => {
  quitting = true; // 放行 window close（关窗拦截让位真退出；工单 04 在此前置探活编排）
  backend?.stop();
});

// 关窗在 darwin 被拦截为隐藏，此事件只剩窗体真销毁的罕见态：mac 驻留（托盘可重建窗），其他平台关窗即走此退出
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
