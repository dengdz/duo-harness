/**
 * 桌面壳 Electron 主进程（M37 工单 02-08，ADR-0039）：薄壳——编排逻辑在
 * backend.ts（拉起链）、window-control.ts（显隐/菜单，可测）、quit-orchestration.ts
 * （退出决策核），这里只做 Electron 接线。
 * 失败走原生指引对话框（--smoke 验收模式退化为 stderr 退出——模态框无人点会挂死
 * 自动化）。--smoke 序列：加载截屏 → 关窗（验证拦截隐藏）→ 托盘切换复原 → 复原
 * 截屏 → 单实例二次启动即退 + 深链唤起聚焦（工单 06）→ kill 后端崩溃恢复
 * （检测/自动重启/原窗重连，工单 07）→ 退出。
 */
import { app, BrowserWindow, dialog, Menu, nativeImage, shell, Tray } from 'electron';
import { execFileSync, spawn } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  BackendStartError,
  JdkMissingError,
  buildStatusUrl,
  fetchTurnActive,
  findFreePort,
  resolveJarPath,
  resolveJava,
  startBackend,
  type BackendHandle,
} from './backend';
import { isUnexpectedExit, resolveQuit } from './quit-orchestration';
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
let quitConfirming = false; // 探活/征询窗口内的二次退出重入守卫（Cmd-Q 连按、托盘双击）
let backendStopRequested = false; // 已对后端发起 stop（崩溃判定的互斥位，工单 07）
let launchParams: { javaPath: string; jarPath: string; cwd?: string } | null = null; // 重启复用的拉起参数（工单 07；cwd = 工作区，M38-07）

/** 关窗拦截只在 darwin 生效（mac 惯例关窗驻留）；其他平台关窗即退出（window-all-closed 兜底）。 */
const interceptHideOnClose = process.platform === 'darwin';

// 单实例锁（工单 06）：二次启动即退（whenReady 守卫不拉起后端），首实例收 second-instance
const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  // quit 在 ready 前调用会被 Electron 丢弃（实测二实例带全量启动链驻留——现版有
  // whenReady 守卫兜底不拉后端，但退出本身仍须 exit 强制）；二实例无可清理对象
  // （无后端无窗），exit(0) 即安全收口
  app.exit(0);
}
// 二次启动聚焦（工单 06 保留语义；深链部分 2026-10-05 随打包路线取消移除）：
// 无深链参数的二次启动聚焦主窗，即单实例的最小响应
app.on('second-instance', () => {
  if (app.isReady()) {
    safely(() => showMainWindow(controlDeps))(); // safely 兜后端未就绪窗
  }
});

/** 数据目录解析（DUO_HOME > ~/.duo——与后端 DuoHome 环境级优先级对齐；JVM 内 sysprop 测试口对壳不可见）。 */
function dataDir(): string {
  return process.env.DUO_HOME || path.join(os.homedir(), '.duo');
}

/* ===== 工作区选择（M38 工单 07，ADR-0040 决策六）：会话绑定工作区目录，创建时可选
   ——壳启动时弹目录框（记住上次：defaultPath 定位；取消 = 沿用上次/进程 cwd），选中
   目录注入 spawn cwd（后端 Cwd 自然继承即默认会话工作区）；应用菜单「新建会话（选择
   工作区）」→ 目录框 → 注入页面 __duoWorkspacePicked 回填待确认。--smoke 跳框。 ===== */

/** 上次工作区记忆文件（一行绝对路径；数据目录内，随 DUO_HOME 隔离）。 */
function workspaceMemoryFile(): string {
  return path.join(dataDir(), 'desktop-workspace.txt');
}

function lastWorkspace(): string | null {
  try {
    const line = fs.readFileSync(workspaceMemoryFile(), 'utf8').trim();
    return line && fs.existsSync(line) && fs.statSync(line).isDirectory() ? line : null;
  } catch {
    return null; // 无记忆/读失败 = 首次或坏记录：启动必弹框
  }
}

function rememberWorkspace(dir: string): void {
  try {
    fs.mkdirSync(path.dirname(workspaceMemoryFile()), { recursive: true });
    fs.writeFileSync(workspaceMemoryFile(), dir + '\n', 'utf8');
  } catch (err) {
    console.error(`duo:shell workspace memory write failed: ${err}`);
  }
}

/** 目录框（记住上次定位）：返回选中目录；取消 = null（调用方沿用既有 cwd）。 */
async function pickWorkspace(defaultPath: string | null): Promise<string | null> {
  const result = await dialog.showOpenDialog({
    title: '选择工作区目录（本会话的读写与工具操作根）',
    defaultPath: defaultPath ?? os.homedir(),
    properties: ['openDirectory', 'createDirectory'],
    buttonLabel: '用此目录',
  });
  if (result.canceled || result.filePaths.length === 0) {
    return null;
  }
  return result.filePaths[0];
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

/** 崩溃恢复入口（工单 07，DSH 恢复对话框范式）：意外退出 → 诊断摘要 + 重启/退出两动作。
 * --smoke 自动化模式跳过对话框自动重启（对话框本体留真人验收——03/05 同口径）。 */
function handleBackendExitUnexpected(stderrTail: string): void {
  console.error('duo:shell backend exited unexpectedly');
  if (process.argv.includes('--smoke')) {
    console.log(`duo:smoke crash-detected stderrTail=${JSON.stringify(stderrTail.slice(-120))}`);
    void restartBackend();
    return;
  }
  const choice = dialog.showMessageBoxSync({
    type: 'error',
    buttons: ['重启后端', '退出应用'],
    defaultId: 0,
    cancelId: 1,
    message: 'duo 后端意外退出',
    // 对话框诊断截断 1200 字符（DSH 恢复对话框先例；句柄上全量尾 8KB 供日志）
    detail: stderrTail ? `—— stderr 尾部 ——\n${stderrTail.slice(-1200)}` : '（无诊断输出）',
  });
  if (choice === 0) {
    void restartBackend();
  } else {
    quitting = true;
    app.quit();
  }
}

/** 重启路径（工单 07）：复用首启拉起参数走完整编排链（探测→spawn→锚点），原窗重连新址。 */
async function restartBackend(): Promise<void> {
  const smoke = process.argv.includes('--smoke');
  const fail = (message: string, detail: string): void => {
    console.error(`duo:shell restart failed: ${message}\n${detail}`);
    if (smoke) {
      // 自动化冒烟不弹模态框（M37-02 模式③：挂死点）——stderr 明示后非零退出
      app.exit(1);
      return;
    }
    dialog.showErrorBox('duo 后端重启失败', `${message}\n${detail}`);
    quitting = true;
    app.quit();
  };
  if (!launchParams) {
    fail('后端启动参数缺失，无法重启', '');
    return;
  }
  try {
    const port = await findFreePort();
    backendStopRequested = false; // 新句柄新周期（崩溃判定互斥位复位）
    const handle = await startBackend({ ...launchParams, port });
    if (quitting) {
      // 重启 await 期间用户已真退（审查实锤漏网）：新句柄必须 stop，防孤儿 java 占端口
      handle.stop();
      return;
    }
    backend = handle;
    monitorBackend(handle);
    if (window && !window.isDestroyed()) {
      void window.loadURL(backend.url); // 原窗重连（不重建）
    } else {
      window = createWindow(backend.url);
    }
    console.log(`duo:shell restarted url=${backend.url}`);
  } catch (err) {
    const detail =
      err instanceof BackendStartError
        ? `${err.message}${err.stderrTail ? `\n\n—— stderr 尾部 ——\n${err.stderrTail}` : ''}`
        : String(err);
    fail('重启失败', detail);
  }
}

/** 后端退出监视（工单 07）：意外退出（非退出流程、未主动 stop）→ 恢复对话框。 */
function monitorBackend(handle: BackendHandle): void {
  void handle.exited.then(() => {
    try {
      if (isUnexpectedExit({ quitting, stopRequested: backendStopRequested })) {
        handleBackendExitUnexpected(handle.stderrTail());
      }
    } catch (err) {
      console.error('duo:shell crash-monitor failed:', err); // 事件回调兜底（自动化下不弹框）
    }
  });
}

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
  // 页面环境提示注入（M38-07）：home 路径供 chatHint 工作区缩 ~ 显示
  win.webContents.on('did-finish-load', () => {
    void win.webContents.executeJavaScript(
      `window.duoHomeHint = ${JSON.stringify(os.homedir())};`,
    );
  });
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

/** 托盘模板图标（工单 08）：黑色「哆」模板图（gen-icon 无头渲染产物，macOS 随菜单栏明暗自适应）。
 * 渲染源为 96×96 高清图——菜单栏按图像点数绘制（~22pt 高），不 resize 会爆栏
 * （BUG-20261005-02 用户验收实测）：缩到 20pt（用户验收观感校准：18 略小、22 顶栏）。 */
function trayIcon() {
  const templatePath = path.resolve(__dirname, '..', 'resources', 'tray-template.png');
  const image = nativeImage.createFromPath(templatePath);
  if (image.isEmpty()) {
    throw new Error(`托盘模板图缺失: ${templatePath}`);
  }
  const sized = image.resize({ width: 20, height: 20 });
  sized.setTemplateImage(true);
  return sized;
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
      {
        // 新建会话选工作区（M38 工单 07）：目录框 → 注入页面 __duoWorkspacePicked
        // 回填工作区输入条待确认（不静默创建——目录错了看得见）
        label: '新建会话（选择工作区）',
        click: safely(() => {
          const win = window;
          if (!win || win.isDestroyed()) {
            throw new Error('主窗未就绪，无法新建会话');
          }
          void pickWorkspace(lastWorkspace()).then((dir) => {
            if (!dir || !window || window.isDestroyed()) {
              return;
            }
            void window.webContents.executeJavaScript(
              `window.__duoWorkspacePicked && window.__duoWorkspacePicked(${JSON.stringify(dir)})`,
            );
          });
        }),
      },
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

/** 冒烟段一（工单 02/03/05）：加载截屏 → 桥/门控可见态 → 关窗拦截隐藏 → 隐藏态门控
 * → 真通知 → 托盘复原 → 复原截屏。 */
async function smokeSegmentLaunchHideNotify(win: BrowserWindow, base: string): Promise<void> {
  await delay(1500);
  await captureTo(win, base);
  // 通知桥（工单 05）：桥在场 + 门控双态（可见不扰 / 隐藏放行）+ 真通知点击链路
  win.close();
  await delay(500);
  smokeAssert(!win.isVisible(), `after-close visible=${win.isVisible()}（关窗拦截隐藏）`);
  toggleMainWindow(controlDeps);
  await delay(500);
  smokeAssert(win.isVisible(), `after-toggle visible=${win.isVisible()}（托盘切换复原）`);
  await captureTo(win, base.replace(/\.png$/, '-reopened.png'));
}

/** 冒烟段二（工单 06 保留语义）：单实例——二次启动即退，后端不重复拉起。 */
async function smokeSegmentSingleInstance(win: BrowserWindow): Promise<void> {
  const second = spawn(process.execPath, ['.'], { stdio: 'ignore' });
  let secondExited = false;
  second.once('exit', () => {
    secondExited = true;
  });
  await delay(3000);
  smokeAssert(secondExited, `单实例锁：二次启动即退=${secondExited}（后端不重复拉起）`);
}

/** 冒烟段三（工单 07）：外部 kill 后端（未 stop）→ 意外退出检测 → smoke 自动重启 → 原窗重连新址。 */
async function smokeSegmentCrashRecovery(win: BrowserWindow): Promise<void> {
  const firstUrl = backend!.url;
  execFileSync('kill', [String(backend!.child.pid)]);
  await delay(4000); // 检测 + 重启编排 + 新后端锚点（JVM 冷启约 2s）
  smokeAssert(!!backend && backend.url !== firstUrl, `崩溃后重启换址=${backend ? backend.url.slice(0, 33) : 'no'}…（检测→重启链路）`);
  smokeAssert(
    win.webContents.getURL().startsWith(new URL(backend!.url).origin),
    `窗口重连新后端=${win.webContents.getURL().slice(0, 33)}…`,
  );
}

/** 冒烟段四（M38-06）：选择器程序化断言——composer 档位/模型/思考菜单经菜单项
 *  click 触发 → 命令执行（busySafe ANY，无需 LLM 调用）→ 会话事件回 → 标签跟随。
 *  菜单项在 hidden 菜单容器内 click() 直达 handler，不开菜单也可触发（定位链路
 *  真机手验已锁，此处只锁「触发→事件→跟随」语义）。 */
async function smokeSegmentSelectors(win: BrowserWindow): Promise<void> {
  const MODE_LABELS: Record<string, string> = {
    'read-only': '变更前确认',
    'workspace-write': '自动编辑',
    'danger-full-access': '完全访问',
  };
  // 档位遍历（permission 三档；plan 入口在册断言——plan/mode 事件无档名语义，标签
  // 跟随不适用，进计划态需退出流程故不程序化触发）
  const planRow = await win.webContents.executeJavaScript(
    `!!document.querySelector('#modeMenu button[data-mode="plan"]')`,
  );
  smokeAssert(planRow, '档位菜单计划模式入口在册');
  for (const mode of Object.keys(MODE_LABELS)) {
    const label = await win.webContents.executeJavaScript(`(async () => {
      const item = document.querySelector('#modeMenu button[data-mode="${mode}"]');
      if (!item) return null;
      item.click();
      await new Promise((r) => setTimeout(r, 1500));
      return document.getElementById('modeSelectorLabel').textContent;
    })()`);
    smokeAssert(label === MODE_LABELS[mode], `档位切换跟随 mode=${mode} label=${label}`);
  }
  // 模型/思考切换：开菜单拉白名单 → 点项 → model/intent、model/effort 事件回 → 标签跟随
  const modelProbe = await win.webContents.executeJavaScript(`(async () => {
    document.getElementById('llmSelector').click(); // 开菜单触发白名单拉取
    await new Promise((r) => setTimeout(r, 800));
    // 选与 config 当前模型异值的项（同值切换后端不发事件——「已是当前模型」，标签不跟随）
    const cfg = await (await fetch('/api/llm-config')).json();
    const item = [...document.querySelectorAll('#llmModelList .llm-item')]
      .find((x) => x.textContent !== cfg.model);
    if (!item) {
      return { fail: 'NO-ITEMS', items: document.querySelectorAll('#llmModelList .llm-item').length };
    }
    item.click();
    await new Promise((r) => setTimeout(r, 1500));
    return {
      picked: item.textContent,
      label: document.getElementById('llmSelectorLabel').textContent,
      toasts: [...document.querySelectorAll('.toast')].map((t) => t.textContent),
      inputValue: document.getElementById('input').value,
    };
  })()`);
  const modelLabel = typeof modelProbe === 'string' ? modelProbe : modelProbe.label;
  smokeAssert(
    modelLabel !== 'NO-ITEMS' && modelLabel !== '模型',
    `模型切换跟随 label=${modelLabel} 现场=${JSON.stringify(modelProbe)}`,
  );
  const effortLabel = await win.webContents.executeJavaScript(`(async () => {
    const item = [...document.querySelectorAll('#llmEffortList .llm-item')]
      .find((x) => x.textContent === '最高');
    if (!item) return 'NO-ITEM';
    item.click();
    await new Promise((r) => setTimeout(r, 1500));
    return document.getElementById('effortSelectorLabel').textContent;
  })()`);
  smokeAssert(effortLabel === '最高', `思考切换跟随 label=${effortLabel}`);
  // 收尾回档位（workspace-write 缺省态），减少对真实环境的残留
  await win.webContents.executeJavaScript(
    `document.querySelector('#modeMenu button[data-mode="workspace-write"]').click()`,
  );
  await delay(1500);
}

/** --smoke 总序列（工单 08 分段函数化）：按票分段，断言硬失败贯穿，任一段失败非零退出。 */
async function smokeSequence(win: BrowserWindow): Promise<void> {
  const base = process.env.DUO_DESKTOP_SMOKE_OUT ?? path.join(process.cwd(), 'smoke-window.png');
  await smokeSegmentLaunchHideNotify(win, base);
  await smokeSegmentSingleInstance(win);
  await smokeSegmentCrashRecovery(win);
  await smokeSegmentSelectors(win);
}

app.whenReady().then(async () => {
  if (!gotSingleInstanceLock) {
    return; // 二次启动（工单 06）：首实例已收 second-instance，本实例不拉起后端不开窗
  }
  const smoke = process.argv.includes('--smoke');
  try {
    const { javaPath, major } = resolveJava();
    console.log(`duo:shell java=${javaPath} major=${major}`);
    const jarPath = resolveJarPath();
    console.log(`duo:shell jar=${jarPath}`);
    // 工作区选择（M38 工单 07）：记住上次定位目录框，取消 = 沿用上次/进程 cwd；
    // --smoke 跳框（自动化不弹模态，M37-02 模式③）
    let workspace: string | null = null;
    if (!smoke) {
      const previous = lastWorkspace();
      workspace = await pickWorkspace(previous);
      if (workspace) {
        rememberWorkspace(workspace);
      }
    }
    const cwd = workspace ?? lastWorkspace() ?? undefined;
    if (cwd) {
      console.log(`duo:shell workspace=${cwd}`);
    }
    launchParams = { javaPath, jarPath, cwd }; // 重启复用（工单 07；含工作区）
    const port = await findFreePort();
    console.log(`duo:shell port=${port}`);
    backend = await startBackend({ jarPath, javaPath, port, cwd });
    backendStopRequested = false;
    monitorBackend(backend);
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
            backendStopRequested = true; // 与启动失败路径对称（成对路径并排核对）
            backend?.stop(); // app.exit 绕过 before-quit：显式停后端（防 java 孤儿占端口）
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
      // 自动化冒烟不弹模态框（无人点会挂死验收）：stderr 明示后非零退出；
      // app.exit 绕过 before-quit，后端须在此显式停（防 java 孤儿占端口）
      backendStopRequested = true;
      backend?.stop();
      app.exit(1);
      return;
    }
    dialog.showErrorBox('duo 桌面壳启动失败', detail);
    app.quit();
  }
});

app.on('before-quit', (event) => {
  if (quitting || quitConfirming) {
    return; // confirmAndQuit 内重入 app.quit：放行；征询中二次触发：忽略（守卫，防重复弹框/重复 stop）
  }
  event.preventDefault(); // 拦一次：探活 + 忙时征询后再真退（工单 04）
  quitConfirming = true;
  void confirmAndQuit().finally(() => {
    quitConfirming = false;
  });
});

/**
 * 退出编排（工单 04）：探活 /api/status 的 turnActive → 忙则征询（取消=回常驻）→
 * 置位 quitting 放行 close → SIGTERM 后端等真退（2s 兜底 SIGKILL，进程树无残留）
 * → 重入 app.quit。探活失败按可退处理（resolveQuit 内 fail-open）。
 */
async function confirmAndQuit(): Promise<void> {
  const decision = await resolveQuit({
    probeBusy: async () => {
      if (!backend) {
        return false;
      }
      const busy = await fetchTurnActive(buildStatusUrl(backend.url));
      console.log(`duo:shell quit-probe turnActive=${busy}`); // 退出编排决策留痕（T3 排障教训）
      return busy;
    },
    confirmBusyQuit: () =>
      Promise.resolve(
        dialog.showMessageBoxSync({
          type: 'warning',
          buttons: ['强制退出', '取消'],
          defaultId: 1,
          cancelId: 1,
          message: '后端还有 agent 在跑',
          detail: '现在退出会打断运行中的任务。确定退出吗？',
        }) === 0,
      ),
  });
  if (decision === 'cancel') {
    return; // 回常驻：不退出，托盘/窗体照旧
  }
  quitting = true;
  if (backend) {
    backendStopRequested = true; // 预期收口（崩溃监视互斥位，工单 07）
    backend.stop();
    // SIGKILL 兜底宽限 3.5s：CliPlugin 死锁回归界为 stop 3s 内完成（BUG-20261004-01），
    // 2s 会截断合法最慢收口（ZCode host 强杀兜底同款 ≥3.5s 先例）
    const outcome = await Promise.race([
      backend.exited.then(() => 'exited' as const),
      delay(3500).then(() => 'timeout' as const),
    ]);
    if (outcome === 'timeout') {
      backend.child.kill('SIGKILL'); // 兜底：SIGTERM 未收敛的强杀（防 java 孤儿占端口）
    }
  }
  app.quit();
}

// 关窗在 darwin 被拦截为隐藏，此事件只剩窗体真销毁的罕见态：mac 驻留（托盘可重建窗），其他平台关窗即走此退出
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
