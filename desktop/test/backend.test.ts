import { describe, expect, it, vi } from 'vitest';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import path from 'node:path';
import type { ChildProcess } from 'node:child_process';
import {
  ANCHOR_PREFIX,
  BackendStartError,
  JdkMissingError,
  parseAnchorLine,
  parseJavaMajor,
  pickNewestJar,
  resolveJarPath,
  resolveJava,
  startBackend,
} from '../src/backend';

/** 锚点/版本解析（纯函数） */
describe('parseAnchorLine', () => {
  it('命中锚点返回 URL', () => {
    expect(parseAnchorLine(`${ANCHOR_PREFIX}http://127.0.0.1:18080/?token=ab12`)).toBe(
      'http://127.0.0.1:18080/?token=ab12',
    );
  });

  it('人读行/空行/日志行返回 null（壳认锚点不认文案）', () => {
    expect(parseAnchorLine('Web 面已启动（鉴权开启）: http://127.0.0.1:8080/?token=ab12')).toBeNull();
    expect(parseAnchorLine('')).toBeNull();
    expect(parseAnchorLine('duo:web-ready url=')).toBeNull();
    expect(parseAnchorLine('2026-10-04 启动日志一行')).toBeNull();
  });
});

describe('parseJavaMajor', () => {
  it('解析发行版本串', () => {
    expect(parseJavaMajor('openjdk version "21.0.5" 2024-10-15')).toBe(21);
    expect(parseJavaMajor('java version "17.0.2"')).toBe(17);
    expect(parseJavaMajor('openjdk version "25" 2025-09-16')).toBe(25);
  });

  it('非版本串返回 null', () => {
    expect(parseJavaMajor('command not found: java')).toBeNull();
    expect(parseJavaMajor('')).toBeNull();
  });
});

describe('pickNewestJar', () => {
  it('按数值段比较取最新（1.10.0 > 1.9.0，非字典序）', () => {
    const jars = [
      '/x/duo-harness-1.9.0.jar',
      '/x/duo-harness-1.10.0.jar',
      '/x/duo-harness-1.2.0.jar',
    ];
    expect(pickNewestJar(jars)).toBe('/x/duo-harness-1.10.0.jar');
  });

  it('空集与非命中返回 null', () => {
    expect(pickNewestJar([])).toBeNull();
    expect(pickNewestJar(['/x/other.jar'])).toBeNull();
  });
});

describe('resolveJarPath', () => {
  it('env 显式指定优先', () => {
    expect(resolveJarPath({ DUO_DESKTOP_JAR: '/opt/duo.jar' }, '/anywhere/dist')).toBe('/opt/duo.jar');
  });

  it('打包态候选：Resources/backend 稳定名单文件优先（beforeBuild 钩子拷入形态，工单 08）', () => {
    const { mkdtempSync, mkdirSync, writeFileSync } = require('node:fs');
    const { tmpdir } = require('node:os');
    const resources = mkdtempSync(path.join(tmpdir(), 'm37-res-'));
    mkdirSync(path.join(resources, 'backend'), { recursive: true });
    writeFileSync(path.join(resources, 'backend', 'duo-harness.jar'), '');
    expect(resolveJarPath({}, '/anywhere/dist', resources)).toBe(
      path.join(resources, 'backend', 'duo-harness.jar'),
    );
  });

  it('target 目录无产物时报错并带指引', () => {
    expect(() => resolveJarPath({}, '/nonexistent-repo/desktop/dist')).toThrow(/DUO_DESKTOP_JAR/);
  });
});

/** JDK 探测（exec 注入，hermetic） */
describe('resolveJava', () => {
  const ok21 = (command: string, args: string[]): string | null => {
    if (args[0] === '-version') {
      return 'openjdk version "21.0.5"';
    }
    if (args[0] === '-ilc') {
      return '/Library/Java/bin/java\n';
    }
    return command === 'java' ? 'openjdk version "21.0.5"' : null;
  };

  it('PATH 直取命中 21 返回 java 路径', () => {
    const { javaPath, major } = resolveJava({}, 'win32', ok21);
    expect(javaPath).toBe('java');
    expect(major).toBe(21);
  });

  it('PATH 无 java 时走 login shell 探测（mac GUI 不继承 shell PATH）', () => {
    const exec = (command: string, args: string[]): string | null => {
      if (args[0] === '-ilc') {
        return '/opt/homebrew/opt/openjdk@21/bin/java\n';
      }
      return command === 'java' ? null : 'openjdk version "21.0.5"';
    };
    const { javaPath } = resolveJava({ SHELL: '/bin/zsh' }, 'darwin', exec);
    expect(javaPath).toBe('/opt/homebrew/opt/openjdk@21/bin/java');
  });

  it('版本不足抛 JdkMissingError 且消息含已找到版本', () => {
    const exec = (command: string): string | null =>
      command === 'java' ? 'openjdk version "17.0.2"' : null;
    expect(() => resolveJava({}, 'win32', exec)).toThrow(JdkMissingError);
    try {
      resolveJava({}, 'win32', exec);
    } catch (err) {
      expect((err as Error).message).toContain('java 17');
      expect((err as Error).message).toContain('JDK 21+');
    }
  });

  it('完全找不到 java 报指引', () => {
    expect(() => resolveJava({}, 'win32', () => null)).toThrow(JdkMissingError);
  });
});

/** 后端拉起编排（spawnFn 注入） */
interface MockChild {
  child: ChildProcess;
  stdout: PassThrough;
  stderr: PassThrough;
  kills: string[];
}

function mockChild(): MockChild {
  const stdout = new PassThrough();
  const stderr = new PassThrough();
  const child = new EventEmitter() as ChildProcess & {
    stdout: PassThrough;
    stderr: PassThrough;
    kill: (signal?: string) => boolean;
  };
  child.stdout = stdout;
  child.stderr = stderr;
  const kills: string[] = [];
  child.kill = (signal?: string) => {
    kills.push(signal ?? 'SIGTERM');
    return true;
  };
  return { child, stdout, stderr, kills };
}

describe('startBackend', () => {
  const base = { jarPath: '/jars/duo-harness-1.2.0.jar', javaPath: 'java' };

  it('spawn 参数正确：-jar + env 注入 DUO_WEB_PORT + stdio 全 pipe', async () => {
    const { child } = mockChild();
    const spawnFn = vi.fn(() => child);
    const pending = startBackend({ ...base, port: 18973, spawnFn });
    child.stdout!.write('boot log\n');
    child.stderr!.write('warn line\n'); // stderr 尾部持续滚动（handle.stderrTail 诊断源）
    child.stdout!.write(`${ANCHOR_PREFIX}http://127.0.0.1:18973/?token=ff\n`);
    const handle = await pending;
    expect(spawnFn).toHaveBeenCalledWith(
      'java',
      ['-jar', '/jars/duo-harness-1.2.0.jar'],
      expect.objectContaining({
        stdio: ['pipe', 'pipe', 'pipe'],
        env: expect.objectContaining({ DUO_WEB_PORT: '18973' }),
      }),
    );
    expect(handle.url).toBe('http://127.0.0.1:18973/?token=ff');
    expect(handle.stderrTail()).toContain('warn line'); // 崩溃诊断源（工单 07）：句柄上可取 stderr 尾部
  });

  it('cwd 选项透传 spawn（M38-07 工作区注入）；缺省不设 cwd 继承壳进程', async () => {
    const { child } = mockChild();
    const spawnFn = vi.fn(() => child);
    const pending = startBackend({ ...base, port: 18974, cwd: '/tmp/ws-a', spawnFn });
    child.stdout!.write(`${ANCHOR_PREFIX}http://127.0.0.1:18974/?token=ff\n`);
    await pending;
    expect(spawnFn).toHaveBeenCalledWith(
      'java',
      ['-jar', '/jars/duo-harness-1.2.0.jar'],
      expect.objectContaining({ cwd: '/tmp/ws-a' }),
    );
    // 缺省形态：spawn options 无 cwd 键（undefined 时展开零属性——继承壳进程 cwd）
    const { child: child2 } = mockChild();
    const spawnFn2 = vi.fn(() => child2);
    const pending2 = startBackend({ ...base, port: 18975, spawnFn: spawnFn2 });
    child2.stdout!.write(`${ANCHOR_PREFIX}http://127.0.0.1:18975/?token=ff\n`);
    await pending2;
    const opts = spawnFn2.mock.calls[0][2] as Record<string, unknown>;
    expect('cwd' in opts).toBe(false);
  });

  it('锚点前的所有人读行被忽略（壳认锚点不认文案）', async () => {
    const { child } = mockChild();
    const pending = startBackend({
      ...base,
      spawnFn: () => child,
    });
    child.stdout!.write('Web 面已启动（鉴权开启）: http://127.0.0.1:8080/?token=noise\n');
    child.stdout!.write('duo:web-ready url=http://127.0.0.1:18971/?token=real\n');
    const handle = await pending;
    expect(handle.url).toBe('http://127.0.0.1:18971/?token=real');
  });

  it('进程先退抛 BackendStartError 携 stderr 尾部', async () => {
    const { child, stderr } = mockChild();
    const pending = startBackend({ ...base, spawnFn: () => child });
    stderr.write('Error: LLM 未配置\n');
    child.emit('exit', 1);
    await expect(pending).rejects.toThrow(BackendStartError);
    await pending.catch((err: BackendStartError) => {
      expect(err.stderrTail).toContain('LLM 未配置');
      expect(err.message).toContain('code=1');
    });
  });

  it('spawn 失败（ENOENT 形态 error 事件）即拒不干等超时', async () => {
    const { child } = mockChild();
    const pending = startBackend({ ...base, spawnFn: () => child });
    child.emit('error', new Error('spawn java ENOENT'));
    await expect(pending).rejects.toThrow(/拉起失败/);
  });

  it('超时 kill SIGTERM 并抛错（stderr 尾部随行）', async () => {
    const { child, stderr, kills } = mockChild();
    const pending = startBackend({ ...base, timeoutMs: 50, spawnFn: () => child });
    stderr.write('still booting\n');
    await expect(pending).rejects.toThrow(/启动超时/);
    expect(kills).toEqual(['SIGTERM']);
    await pending.catch((err: BackendStartError) => {
      expect(err.stderrTail).toContain('still booting');
    });
  });

  it('handle.stop 走 SIGTERM（正常停机语义，工单 04 扩展编排）', async () => {
    const { child, kills } = mockChild();
    const pending = startBackend({ ...base, spawnFn: () => child });
    child.stdout!.write(`${ANCHOR_PREFIX}http://127.0.0.1:18970\n`);
    const handle = await pending;
    handle.stop();
    expect(kills).toEqual(['SIGTERM']);
  });

  it('handle.exited 在进程退出即决（退出编排等干净收口的依据）', async () => {
    const { child } = mockChild();
    const pending = startBackend({ ...base, spawnFn: () => child });
    child.stdout!.write(`${ANCHOR_PREFIX}http://127.0.0.1:18969\n`);
    const handle = await pending;
    let exited = false;
    void handle.exited.then(() => {
      exited = true;
    });
    await vi.waitFor(() => {}); // 微任务清空
    expect(exited).toBe(false); // 未退未决
    child.emit('exit', 0);
    await handle.exited;
    expect(exited).toBe(true);
  });

  it('stderr 尾部超上限被截断（防凭据泄漏扩散）', async () => {
    const { child, stderr } = mockChild();
    const pending = startBackend({ ...base, spawnFn: () => child });
    stderr.write('x'.repeat(100_000) + 'END-MARK');
    child.emit('exit', 2);
    await pending.catch((err: BackendStartError) => {
      expect(err.stderrTail.length).toBeLessThanOrEqual(8 * 1024 + 1);
      expect(err.stderrTail.endsWith('END-MARK')).toBe(true);
    });
  });
});
