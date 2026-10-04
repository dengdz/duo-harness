import { describe, expect, it } from 'vitest';
import { extractUrlFromArgv, parseDeepLink } from '../src/deep-link';

/** 深链判路（M37-06）：仅 duo://open 开放，其余静默 ignored（路由面留白） */
describe('parseDeepLink', () => {
  it('duo://open（含查询串）→ open', () => {
    expect(parseDeepLink('duo://open')).toEqual({ kind: 'open', raw: 'duo://open' });
    expect(parseDeepLink('duo://open?from=browser')).toEqual({
      kind: 'open',
      raw: 'duo://open?from=browser',
    });
  });

  it('未知 path 静默 ignored（reason 记形态）', () => {
    const route = parseDeepLink('duo://session/new?id=1');
    expect(route.kind).toBe('ignored');
    expect(route).toMatchObject({ reason: 'path:session' });
  });

  it('非 duo scheme / 空串 / 不可解析 → ignored', () => {
    expect(parseDeepLink('https://example.com').kind).toBe('ignored');
    expect(parseDeepLink('').kind).toBe('ignored');
    expect(parseDeepLink('duo:open').kind).toBe('ignored'); // 无 // authority 形态不认
  });

  it('host 大小写不敏感（URL 对非特殊 scheme 不自动小写，防御收口）', () => {
    expect(parseDeepLink('duo://OPEN').kind).toBe('open');
  });
});

/** argv 提取（second-instance 形态：系统夹带应用路径等参数时取最后一个深链） */
describe('extractUrlFromArgv', () => {
  it('取最后一个 duo:// 参数', () => {
    expect(extractUrlFromArgv(['/app/electron', '.', 'duo://open'])).toBe('duo://open');
    expect(extractUrlFromArgv(['duo://open', 'duo://open?x=1'])).toBe('duo://open?x=1');
  });

  it('无深链参数返回 null', () => {
    expect(extractUrlFromArgv(['/app/electron', '.'])).toBeNull();
    expect(extractUrlFromArgv([])).toBeNull();
  });
});
