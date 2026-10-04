import { describe, expect, it, vi } from 'vitest';
import {
  buildStatusUrl,
  parseAnchorLine,
  parseBusyStatus,
} from '../src/backend';
import { isUnexpectedExit, resolveQuit } from '../src/quit-orchestration';

/** 探活 URL 构造（工单 04）：锚点 URL → 同源同 token 的 /api/status */
describe('buildStatusUrl', () => {
  it('保留 origin 与 token 查询段，路径换 /api/status', () => {
    expect(buildStatusUrl('http://127.0.0.1:18999/?token=ab12')).toBe(
      'http://127.0.0.1:18999/api/status?token=ab12',
    );
  });
});

/** 状态忙碌解析：turnActive 契约（M37-04 后端字段，旧后端缺字段=空闲） */
describe('parseBusyStatus', () => {
  it('turnActive true/false 与缺字段三态', () => {
    expect(parseBusyStatus('{"turnActive":true}')).toBe(true);
    expect(parseBusyStatus('{"turnActive":false}')).toBe(false);
    expect(parseBusyStatus('{"auth":"token"}')).toBe(false); // 旧后端兼容：缺字段=空闲
  });

  it('坏 JSON 按空闲（fail-open 可退）', () => {
    expect(parseBusyStatus('not-json')).toBe(false);
  });
});

/** 退出决策状态机：三分支 + 忙时征询 */
describe('resolveQuit', () => {
  it('探活空闲 → 直接退出', async () => {
    const decision = await resolveQuit({ probeBusy: vi.fn(async () => false), confirmBusyQuit: vi.fn(async () => true) });
    expect(decision).toBe('quit');
  });

  it('探活忙 + 用户确认 → 退出', async () => {
    const confirm = vi.fn(async () => true);
    const decision = await resolveQuit({ probeBusy: vi.fn(async () => true), confirmBusyQuit: confirm });
    expect(confirm).toHaveBeenCalledOnce();
    expect(decision).toBe('quit');
  });

  it('探活忙 + 用户取消 → 回常驻（不碰后端）', async () => {
    const decision = await resolveQuit({ probeBusy: vi.fn(async () => true), confirmBusyQuit: vi.fn(async () => false) });
    expect(decision).toBe('cancel');
  });

  it('探活失败（后端已死/超时抛错）→ 按可退处理', async () => {
    const confirm = vi.fn(async () => true);
    const decision = await resolveQuit({
      probeBusy: vi.fn(async () => {
        throw new Error('fetch failed');
      }),
      confirmBusyQuit: confirm,
    });
    expect(confirm).not.toHaveBeenCalled(); // 失败不征询：没人可问，直接放行
    expect(decision).toBe('quit');
  });
});

/** 锚点解析回归（工单 02 契约面在退出链路上的复用） */
describe('parseAnchorLine 回归', () => {
  it('退出链路依赖的锚点解析仍为人读行免疫', () => {
    expect(parseAnchorLine('duo:web-ready url=http://127.0.0.1:1/?token=x')).toBe(
      'http://127.0.0.1:1/?token=x',
    );
    expect(parseAnchorLine('Web 面已启动: http://x')).toBeNull();
  });
});

/** 崩溃判定互斥位（工单 07）：退出流程/主动 stop 之外的 exit 才是崩溃 */
describe('isUnexpectedExit', () => {
  it('退出流程在途 → 预期（不弹恢复框）', () => {
    expect(isUnexpectedExit({ quitting: true, stopRequested: false })).toBe(false);
    expect(isUnexpectedExit({ quitting: true, stopRequested: true })).toBe(false);
  });

  it('已主动 stop（SIGTERM 在途）→ 预期', () => {
    expect(isUnexpectedExit({ quitting: false, stopRequested: true })).toBe(false);
  });

  it('两者皆否 → 意外退出（恢复对话框路径）', () => {
    expect(isUnexpectedExit({ quitting: false, stopRequested: false })).toBe(true);
  });
});
