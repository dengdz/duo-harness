import { describe, expect, it, vi } from 'vitest';
import {
  createTrayActions,
  shouldInterceptClose,
  toggleMainWindow,
  type MainWindowLike,
  type WindowControlDeps,
} from '../src/window-control';

/** 假主窗：形态面 + 显隐记录。 */
function fakeWindow(initiallyVisible = false): MainWindowLike & { events: string[] } {
  const events: string[] = [];
  return {
    events,
    isVisible: () => initiallyVisible,
    isDestroyed: () => false,
    show: () => events.push('show'),
    hide: () => events.push('hide'),
    focus: () => events.push('focus'),
  };
}

function depsWith(win: MainWindowLike | null, overrides: Partial<WindowControlDeps> = {}): WindowControlDeps & {
  revealed: string[];
  readonly quits: number;
} {
  const revealed: string[] = [];
  let quitCount = 0;
  return {
    revealed,
    get quits() {
      return quitCount;
    },
    getWindow: () => win,
    createWindow: () => fakeWindow(true),
    quit: () => void (quitCount++),
    revealDir: (dir) => revealed.push(dir),
    revealItemInFolder: (item) => revealed.push(item),
    dataDir: () => '/Users/x/.duo',
    ...overrides,
  } as WindowControlDeps & { revealed: string[]; readonly quits: number };
}

describe('toggleMainWindow', () => {
  it('可见窗隐藏（托盘点击切换：shown → hidden）', () => {
    const win = fakeWindow(true);
    const deps = depsWith(win);
    expect(toggleMainWindow(deps)).toBe('hidden');
    expect(win.events).toEqual(['hide']);
  });

  it('隐藏窗显示并聚焦（hidden → shown）', () => {
    const win = fakeWindow(false);
    const deps = depsWith(win);
    expect(toggleMainWindow(deps)).toBe('shown');
    expect(win.events).toEqual(['show', 'focus']);
  });

  it('窗体销毁经工厂重建（崩溃后托盘再开路径）', () => {
    const destroyed = { ...fakeWindow(true), isDestroyed: () => true };
    const fresh = fakeWindow(true);
    const deps = depsWith(destroyed, { createWindow: () => fresh });
    expect(toggleMainWindow(deps)).toBe('shown');
    expect(fresh.events).toEqual(['show', 'focus']);
  });

  it('窗体为 null 时经工厂重建', () => {
    const fresh = fakeWindow(true);
    const deps = depsWith(null, { createWindow: () => fresh });
    expect(toggleMainWindow(deps)).toBe('shown');
    expect(fresh.events).toEqual(['show', 'focus']);
  });
});

describe('shouldInterceptClose', () => {
  it('常规关窗拦截（隐藏不退出——mac 惯例）', () => {
    expect(shouldInterceptClose(false, fakeWindow(true))).toBe(true);
  });

  it('真退出流程放行 close（before-quit 置位 quitting）', () => {
    expect(shouldInterceptClose(true, fakeWindow(true))).toBe(false);
  });

  it('窗体已销毁放行', () => {
    const destroyed = { ...fakeWindow(true), isDestroyed: () => true };
    expect(shouldInterceptClose(false, destroyed)).toBe(false);
    expect(shouldInterceptClose(false, null)).toBe(false);
  });
});

describe('createTrayActions', () => {
  it('openMainWindow 委托显示', () => {
    const win = fakeWindow(false);
    const deps = depsWith(win);
    createTrayActions(deps).openMainWindow();
    expect(win.events).toEqual(['show', 'focus']);
  });

  it('openDataDir 以解析后的数据目录调起 Finder', () => {
    const deps = depsWith(fakeWindow());
    createTrayActions(deps).openDataDir();
    expect(deps.revealed).toEqual(['/Users/x/.duo']);
  });

  it('quit 路由真退出（工单 04 换探活编排的占位点）', () => {
    const deps = depsWith(fakeWindow());
    createTrayActions(deps).quit();
    expect(deps.quits).toBe(1);
  });
});
