/**
 * 桌面壳后端编排（M37 工单 02，ADR-0039 决策六）：Electron 无关的纯逻辑层——
 * JDK 探测、空闲端口探测、spawn Java 后端、stdout 锚点解析、启动超时。
 * 本模块不 import electron：编排逻辑经 vitest 单测锁定（S3 缝，spawnFn 可注入），
 * main.ts 只做薄壳接线。
 */
import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { createInterface } from 'node:readline';

/** 后端要求的 JDK 大版本（仓库编译目标 Java 21）。 */
export const REQUIRED_JAVA_MAJOR = 21;

/** 启动超时缺省（工单票 60s 量级）。 */
export const DEFAULT_START_TIMEOUT_MS = 60_000;

/** 失败对话框携带的 stderr 尾部上限（截断防凭据泄漏扩散——DSH 经验）。 */
export const STDERR_TAIL_LIMIT = 8 * 1024;

/** 锚点行格式（工单 01 契约面）：`duo:web-ready url=<启动URL>`。 */
export const ANCHOR_PREFIX = 'duo:web-ready url=';

/** 机器锚点行解析：命中返回 URL，人读行/空行返回 null——壳认锚点不认文案。 */
export function parseAnchorLine(line: string): string | null {
  const trimmed = line.trim();
  if (!trimmed.startsWith(ANCHOR_PREFIX)) {
    return null;
  }
  const url = trimmed.slice(ANCHOR_PREFIX.length).trim();
  return url.length > 0 ? url : null;
}

/** 锚点 URL → 状态端点 URL（同源同 token 查询段，路径换 /api/status）——退出探活用。 */
export function buildStatusUrl(anchorUrl: string): string {
  const url = new URL(anchorUrl);
  url.pathname = '/api/status';
  return url.toString();
}

/**
 * 状态载荷忙碌解析（工单 04 契约：turnActive 布尔，M37-04 后端新增）：缺失/非布尔
 * 一律按空闲（老后端兼容——旧版本无此字段，退出编排 fail-open 可退）。
 */
export function parseBusyStatus(jsonText: string): boolean {
  try {
    return JSON.parse(jsonText)?.turnActive === true;
  } catch {
    return false;
  }
}

/** 拉取状态并判忙（超时/网络错误抛出——由退出编排按可退处理）。 */
export async function fetchTurnActive(statusUrl: string, timeoutMs = 2_000): Promise<boolean> {
  const response = await fetch(statusUrl, { signal: AbortSignal.timeout(timeoutMs) });
  return parseBusyStatus(await response.text());
}

/** 从 `java -version` 输出解析大版本（输出形如 `openjdk version "21.0.5"`）。 */
export function parseJavaMajor(versionOutput: string): number | null {
  const match = /version "(\d+)/.exec(versionOutput);
  return match ? Number(match[1]) : null;
}

/** 空闲端口探测：试绑 0 端口取内核分配值后释放（竞态窗口理论存在，实践标准做法）。 */
export function findFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (address && typeof address === 'object') {
        const port = address.port;
        server.close(() => resolve(port));
      } else {
        server.close(() => reject(new Error('端口探测失败：非 TCP 地址')));
      }
    });
  });
}

export class JdkMissingError extends Error {
  constructor(detail: string) {
    super(detail);
    this.name = 'JdkMissingError';
  }
}

/**
 * 同步执行命令，聚合 stdout+stderr——`java -version` 退出 0 但版本串走 stderr，
 * execFileSync 成功路径只回 stdout 会把它丢掉（首跑冒烟实测：全候选误判「未命中」）；
 * spawnSync 成功/失败两路都带双流。命令不存在（ENOENT 等启动失败且无输出）返回 null。
 */
function tryExec(command: string, args: string[]): string | null {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    timeout: 10_000,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  if (result.error && output.length === 0) {
    return null;
  }
  return output.length > 0 ? output : null;
}

/** java 路径候选：PATH 直取优先，mac/linux 补 login shell 探测（GUI 启动不继承 shell PATH——DSH 教训）。同名去重（SHELL 常与 /bin/zsh 同值）。 */
function javaPathCandidates(env: NodeJS.ProcessEnv, platform: NodeJS.Platform, exec: ExecFn): string[] {
  const seen = new Set<string>();
  const candidates = ['java'];
  if (platform !== 'win32') {
    for (const shell of [env.SHELL, '/bin/zsh', '/bin/bash']) {
      if (!shell || seen.has(shell)) {
        continue;
      }
      seen.add(shell);
      const output = exec(shell, ['-ilc', 'command -v java']);
      const javaPath = output?.trim().split('\n').pop()?.trim();
      if (javaPath && !seen.has(javaPath)) {
        seen.add(javaPath);
        candidates.push(javaPath);
      }
    }
  }
  return candidates;
}

type ExecFn = (command: string, args: string[]) => string | null;

/**
 * JDK 探测：逐候选跑 `java -version` 解析大版本，首个 ≥ 21 即用。exec 可注入
 * （测试缝：探测走真实子进程，单测以假 exec 覆盖门控与消息分支）。
 *
 * @throws JdkMissingError 找不到 java 或大版本不足（指引文案随异常给出）
 */
export function resolveJava(
  env: NodeJS.ProcessEnv = process.env,
  platform: NodeJS.Platform = process.platform,
  exec: ExecFn = tryExec,
): { javaPath: string; major: number } {
  const found: Array<{ javaPath: string; major: number }> = [];
  for (const javaPath of javaPathCandidates(env, platform, exec)) {
    const major = parseJavaMajor(exec(javaPath, ['-version']) ?? '');
    if (major === null) {
      continue;
    }
    if (major >= REQUIRED_JAVA_MAJOR) {
      return { javaPath, major };
    }
    found.push({ javaPath, major });
  }
  const seen = found.map((f) => `java ${f.major}（${f.javaPath}）`).join('、');
  throw new JdkMissingError(
    found.length > 0
      ? `需要 JDK ${REQUIRED_JAVA_MAJOR}+，本机找到：${seen}。请安装 JDK ${REQUIRED_JAVA_MAJOR}+ 并确保 java 在 PATH（桌面版暂不内置运行时）。`
      : '找不到 java（PATH 与 login shell 均未命中）。请安装 JDK 21+。',
  );
}

/** 按 jar 文件名内嵌版本号取最新（数值段比较，1.10.0 > 1.9.0 非字典序）。 */
export function pickNewestJar(paths: string[]): string | null {
  const scoreOf = (p: string): number | null => {
    const match = /duo-harness-(\d+)\.(\d+)\.(\d+)\.jar$/.exec(p);
    return match ? Number(match[1]) * 1_000_000 + Number(match[2]) * 1000 + Number(match[3]) : null;
  };
  let best: { path: string; score: number } | null = null;
  for (const candidate of paths) {
    const score = scoreOf(candidate);
    if (score !== null && (best === null || score > best.score)) {
      best = { path: candidate, score };
    }
  }
  return best?.path ?? null;
}

/** fat-jar 定位（工单 08 增打包态）：env `DUO_DESKTOP_JAR` 显式指定 > 打包态
 * Resources/backend/（beforeBuild 钩子拷入的最新版单文件）> dev 形态仓库 target 目录取最新。 */
export function resolveJarPath(
  env: NodeJS.ProcessEnv = process.env,
  appRoot: string = __dirname,
  resourcesPath: string | undefined = (process as unknown as { resourcesPath?: string }).resourcesPath,
): string {
  const explicit = env.DUO_DESKTOP_JAR;
  if (explicit) {
    return explicit;
  }
  const dirs: string[] = [];
  if (resourcesPath) {
    dirs.push(path.join(resourcesPath, 'backend')); // 打包态（Electron 注入 Contents/Resources）
  }
  // dev 形态：appRoot = <repo>/desktop/dist → 上两级到仓库根
  dirs.push(path.join(path.resolve(appRoot, '..', '..'), 'duo-harness-example', 'target'));
  for (const dir of dirs) {
    try {
      // 钩子拷入的稳定名单文件优先（extraResources 单文件源）
      const stable = path.join(dir, 'duo-harness.jar');
      if (existsSync(stable)) {
        return stable;
      }
      const jars = readdirSync(dir)
        .filter((f) => /^duo-harness-\d+\.\d+\.\d+\.jar$/.test(f))
        .map((f) => path.join(dir, f));
      const newest = pickNewestJar(jars);
      if (newest) {
        return newest;
      }
    } catch {
      // 目录不存在 → 试下一候选
    }
  }
  throw new Error(
    `找不到 duo-harness fat-jar（已试: ${dirs.join('、')}）。请先 mvn package 或以 DUO_DESKTOP_JAR 指定。`,
  );
}

export type SpawnFn = typeof spawn;

export interface StartBackendOptions {
  jarPath: string;
  javaPath: string;
  port?: number;
  timeoutMs?: number;
  env?: NodeJS.ProcessEnv;
  spawnFn?: SpawnFn;
}

export interface BackendHandle {
  url: string;
  child: ChildProcess;
  /** SIGTERM 后端（退出编排的发动点；等真退用 {@link exited}）。 */
  stop(): void;
  /** 进程退出即决（无论何时退）——退出编排等干净收口 / SIGKILL 兜底用。 */
  exited: Promise<void>;
  /** stderr 尾部快照（持续滚动、上限截断）——崩溃恢复对话框的诊断源（工单 07）。 */
  stderrTail(): string;
}

export class BackendStartError extends Error {
  /** stderr 尾部（截断至上限），失败对话框展示用。 */
  readonly stderrTail: string;

  constructor(message: string, stderrTail: string) {
    super(message);
    this.name = 'BackendStartError';
    this.stderrTail = stderrTail;
  }
}

function tailOf(buffer: string): string {
  return buffer.length > STDERR_TAIL_LIMIT
    ? '…' + buffer.slice(buffer.length - STDERR_TAIL_LIMIT)
    : buffer;
}

/**
 * 拉起 Java 后端并等待就绪锚点（工单 01 契约面）：spawn `java -jar <fat-jar>`，
 * env 注入 `DUO_WEB_PORT`（壳选空闲端口，后端三级覆盖中属壳级注入）；**stdin 用
 * pipe 保持打开、从不写入也不 end**——装配 cli 行的 REPL 将阻塞在输入等待而非
 * EOF 退出（首日实测见工单 Comments）。stdout 逐行扫 `duo:web-ready` 锚点，命中
 * 即就绪；spawn 失败（ENOENT）/超时/进程先退 → BackendStartError（携 stderr 尾部）。
 */
export function startBackend(options: StartBackendOptions): Promise<BackendHandle> {
  const spawnFn = options.spawnFn ?? spawn;
  const timeoutMs = options.timeoutMs ?? DEFAULT_START_TIMEOUT_MS;
  return new Promise((resolve, reject) => {
    const port = options.port ?? 0;
    const child = spawnFn(options.javaPath, ['-jar', options.jarPath], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...options.env, DUO_WEB_PORT: String(port) },
    });
    let stderrTail = '';
    let settled = false;
    const exited = new Promise<void>((resolve) => {
      child.once('exit', () => resolve());
    });
    const settle = (fn: () => void) => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        fn();
      }
    };
    const timer = setTimeout(() => {
      settle(() => {
        child.kill('SIGTERM');
        reject(new BackendStartError(`后端启动超时（${timeoutMs}ms，端口 ${port}）`, tailOf(stderrTail)));
      });
    }, timeoutMs);

    child.stderr?.setEncoding('utf8');
    child.stderr?.on('data', (chunk: string) => {
      stderrTail += chunk;
      if (stderrTail.length > STDERR_TAIL_LIMIT) {
        stderrTail = stderrTail.slice(stderrTail.length - STDERR_TAIL_LIMIT);
      }
    });
    child.stdout?.setEncoding('utf8');
    const lines = createInterface({ input: child.stdout! });
    lines.on('line', (line) => {
      const url = parseAnchorLine(line);
      if (url === null) {
        return;
      }
      settle(() => {
        lines.close();
        resolve({
          url,
          child,
          stop: () => child.kill('SIGTERM'),
          exited,
          stderrTail: () => tailOf(stderrTail),
        });
      });
    });
    child.once('error', (err) => {
      settle(() => reject(new BackendStartError(`后端进程拉起失败：${err.message}`, tailOf(stderrTail))));
    });
    child.once('exit', (code) => {
      settle(() => reject(new BackendStartError(`后端进程提前退出（code=${code}）`, tailOf(stderrTail))));
    });
  });
}
