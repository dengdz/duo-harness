#!/usr/bin/env node
// 应用图标生成（M37 工单 08，用户裁定：字标采用「哆」）：node 编排——拉起 electron
// 无头渲染（Canvas + PingFang SC：品牌蓝渐变圆角方 + 白色「哆」自动缩放；黑「哆」
// 托盘模板）→ sips 重采样 iconset → iconutil 出 icns。可重跑：node scripts/gen-icon.mjs
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RENDER = path.join(ROOT, 'scripts', 'icon-render.js');
const OUT = path.join(ROOT, 'resources');

// 1. electron 渲染出主图与托盘模板
fs.rmSync(path.join(OUT, 'icon-1024.png'), { force: true });
fs.rmSync(path.join(OUT, 'tray-template.png'), { force: true });
const electronBin = path.join(ROOT, 'node_modules', '.bin', 'electron');
const child = spawn(electronBin, [RENDER, OUT], { cwd: ROOT, stdio: 'inherit' });
child.on('error', (err) => {
  console.error('electron 拉起失败（依赖未装？先 npm install）:', err.message);
  process.exit(1);
});
const deadline = Date.now() + 60_000;
while (Date.now() < deadline) {
  if (fs.existsSync(path.join(OUT, 'tray-template.png')) && fs.existsSync(path.join(OUT, 'icon-1024.png'))) {
    break;
  }
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500);
}
try {
  child.kill();
} catch {
  // 已退出
}
if (!fs.existsSync(path.join(OUT, 'icon-1024.png')) || !fs.existsSync(path.join(OUT, 'tray-template.png'))) {
  console.error('渲染失败：resources/ 下未见 icon-1024.png / tray-template.png');
  process.exit(1);
}
console.log('rendered: resources/icon-1024.png + resources/tray-template.png');

// 2. iconset 重采样 + icns
const iconset = path.join(OUT, 'icon.iconset');
fs.rmSync(iconset, { recursive: true, force: true });
fs.mkdirSync(iconset, { recursive: true });
const master = path.join(OUT, 'icon-1024.png');
for (const [name, z] of [
  ['icon_16x16.png', 16], ['icon_16x16@2x.png', 32],
  ['icon_32x32.png', 32], ['icon_32x32@2x.png', 64],
  ['icon_128x128.png', 128], ['icon_128x128@2x.png', 256],
  ['icon_256x256.png', 256], ['icon_256x256@2x.png', 512],
  ['icon_512x512.png', 512], ['icon_512x512@2x.png', 1024],
]) {
  execFileSync('sips', ['-z', String(z), String(z), master, '--out', path.join(iconset, name)], { stdio: 'pipe' });
}
const icns = path.join(OUT, 'icon.icns');
try {
  execFileSync('iconutil', ['-c', 'icns', iconset, '-o', icns], { stdio: 'pipe' });
} finally {
  fs.rmSync(iconset, { recursive: true, force: true }); // 中间产物不入库（失败也清）
}
console.log('icns:', icns);
