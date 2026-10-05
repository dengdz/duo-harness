import { describe, expect, it } from 'vitest';
import { shouldNotify } from '../src/notify-gate';

/** 通知门控（M37-05，BUG-20261005-01 修正语义）：聚焦中不扰 + 权限已授才发 */
describe('shouldNotify', () => {
  it('聚焦中一律不扰（无论权限）——用户正看着 duo', () => {
    expect(shouldNotify(true, 'granted')).toBe(false);
    expect(shouldNotify(true, 'default')).toBe(false);
  });

  it('未聚焦（切应用/最小化/隐藏）+ 权限已授 → 发', () => {
    expect(shouldNotify(false, 'granted')).toBe(true);
  });

  it('未聚焦但权限未授 → 不发（default/denied 同规）', () => {
    expect(shouldNotify(false, 'default')).toBe(false);
    expect(shouldNotify(false, 'denied')).toBe(false);
  });
});
