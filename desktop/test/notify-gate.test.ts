import { describe, expect, it } from 'vitest';
import { shouldNotify } from '../src/notify-gate';

/** 通知门控（M37-05）：可见中不扰 + 权限已授才发——三态矩阵全锁 */
describe('shouldNotify', () => {
  it('可见中一律不扰（无论权限）', () => {
    expect(shouldNotify('visible', 'granted')).toBe(false);
    expect(shouldNotify('visible', 'default')).toBe(false);
  });

  it('隐藏 + 权限已授 → 发', () => {
    expect(shouldNotify('hidden', 'granted')).toBe(true);
  });

  it('隐藏但权限未授 → 不发（default/denied 同规）', () => {
    expect(shouldNotify('hidden', 'default')).toBe(false);
    expect(shouldNotify('hidden', 'denied')).toBe(false);
  });

  it('未知可见态按可见处理（fail-closed 不扰）', () => {
    expect(shouldNotify('prerender', 'granted')).toBe(false);
  });
});
