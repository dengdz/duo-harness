// 图标渲染器（M37 工单 08，electron 主进程无头跑）：Canvas 绘制 1024×1024 主图
// （品牌蓝渐变圆角方 + 白色「哆」，PingFang SC 自动缩放适配）与 96×96 黑「哆」
// 托盘模板，落 resources/。由 scripts/gen-icon.mjs 拉起，完成后自退。
const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const OUT = process.argv[2] || path.resolve(path.dirname(__filename), '..', 'resources');

app.whenReady().then(async () => {
  const win = new BrowserWindow({ show: false, width: 1200, height: 1200 });
  const html =
    '<!doctype html><html><body>' +
    '<canvas id="icon" width="1024" height="1024"></canvas>' +
    '<canvas id="tray" width="96" height="96"></canvas>' +
    '</body></html>';
  await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
  const payload = await win.webContents.executeJavaScript(
    `(function () {
        const icon = document.getElementById('icon').getContext('2d');
        const m = 24, r = 200;
        icon.beginPath();
        icon.roundRect(m, m, 1024 - 2 * m, 1024 - 2 * m, r);
        const g = icon.createLinearGradient(0, 0, 0, 1024);
        g.addColorStop(0, '#333A46'); // ZCode 样式深蓝黑阶（用户裁定 2026-10-04）
        g.addColorStop(1, '#14171E');
        icon.fillStyle = g;
        icon.fill();
        icon.fillStyle = '#ffffff';
        icon.textAlign = 'center';
        icon.textBaseline = 'middle';
        let px = 780;
        const font = (size) => '600 ' + size + 'px "PingFang SC", "Hiragino Sans GB", "Heiti SC", sans-serif';
        icon.font = font(px);
        const w = icon.measureText('哆').width;
        if (w > 680) {
          px = Math.floor(px * 680 / w);
          icon.font = font(px);
        }
        icon.fillText('哆', 512, 540);
        const tray = document.getElementById('tray').getContext('2d');
        tray.fillStyle = '#000000';
        tray.textAlign = 'center';
        tray.textBaseline = 'middle';
        tray.font = font(80);
        tray.fillText('哆', 48, 52);
        return JSON.stringify({
          icon: document.getElementById('icon').toDataURL('image/png'),
          tray: document.getElementById('tray').toDataURL('image/png'),
        });
      })()`,
  );
  const { icon, tray } = JSON.parse(payload);
  const write = (dataUrl, name) =>
    fs.writeFileSync(path.join(OUT, name), Buffer.from(dataUrl.split(',')[1], 'base64'));
  write(icon, 'icon-1024.png');
  write(tray, 'tray-template.png');
  console.log('duo:icon-render done');
  app.exit(0);
}) .catch((err) => {
  console.error('duo:icon-render failed:', err);
  app.exit(1);
});
