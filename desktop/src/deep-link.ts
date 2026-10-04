/**
 * 深链路由（M37 工单 06，ADR-0039 决策一 + Q11 仅 `duo://open`）：Electron 无关的
 * 纯解析——URL 判路与 argv 提取。路由面留白：除 open 外一律 ignored 静默忽略
 * （后续加 path 不动架构）；main.ts 持解析结果做聚焦，本模块不做任何 IO。
 */

const DEEP_LINK_SCHEME = 'duo';

/** 首个开放的路由（唤起/聚焦主窗）；多路由留白按需追加。 */
const OPEN_PATH = 'open';

export type DeepLinkRoute =
  | { kind: 'open'; raw: string }
  | { kind: 'ignored'; raw: string; reason: string };

/**
 * 深链判路：`duo://open`（含带查询串）→ open；未知 path / 非 duo scheme / 不可解析
 * → ignored（reason 记录形态，调用方静默）。host 大小写不敏感（URL 对非特殊 scheme
 * 不自动小写，防御性收口）。
 */
export function parseDeepLink(raw: string): DeepLinkRoute {
  if (!raw || !raw.toLowerCase().startsWith(DEEP_LINK_SCHEME + '://')) {
    return { kind: 'ignored', raw, reason: 'scheme' };
  }
  try {
    const url = new URL(raw);
    const host = url.host.toLowerCase();
    if (host === OPEN_PATH) {
      return { kind: 'open', raw };
    }
    return { kind: 'ignored', raw, reason: 'path:' + (host || '(empty)') };
  } catch {
    return { kind: 'ignored', raw, reason: 'parse' };
  }
}

/**
 * 从启动参数提取深链（win/linux 的 second-instance 形态；mac 走 open-url 不经此）：
 * 取最后一个 duo:// 形态参数（系统可能夹带应用路径等其他 argv），无则 null。
 */
export function extractUrlFromArgv(argv: string[]): string | null {
  const urls = argv.filter((arg) => arg && arg.toLowerCase().startsWith(DEEP_LINK_SCHEME + '://'));
  return urls.length > 0 ? urls[urls.length - 1] : null;
}
