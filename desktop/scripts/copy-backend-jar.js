// electron-builder beforeBuild 钩子（工单 08 审查修复）：target 下多历史 fat-jar
// 只拷最新版进包（extraResources 单文件源），防包体积随版本单调累积。
const { readdirSync, copyFileSync, mkdirSync } = require('node:fs');
const path = require('node:path');

module.exports = async function copyBackendJar() {
  // beforeBuild 上下文无 appOutDir（实测 undefined）——用脚本自身位置定位：
  // scripts/ → desktop → 仓库根
  const projectRoot = path.resolve(__dirname, '..', '..');
  const targetDir = path.join(projectRoot, 'duo-harness-example', 'target');
  const jars = readdirSync(targetDir)
    .filter((f) => /^duo-harness-\d+\.\d+\.\d+\.jar$/.test(f))
    .map((f) => {
      const [, a, b, c] = /duo-harness-(\d+)\.(\d+)\.(\d+)\.jar$/.exec(f);
      return { file: f, score: Number(a) * 1_000_000 + Number(b) * 1000 + Number(c) };
    })
    .sort((x, y) => y.score - x.score);
  if (jars.length === 0) {
    throw new Error(`找不到 fat-jar：${targetDir} 下无 duo-harness-<版本>.jar（先 mvn package）`);
  }
  const destDir = path.join(projectRoot, 'desktop', 'resources', 'backend');
  mkdirSync(destDir, { recursive: true });
  copyFileSync(path.join(targetDir, jars[0].file), path.join(destDir, 'duo-harness.jar'));
  console.log('copy-backend-jar:', jars[0].file, '→ desktop/resources/backend/duo-harness.jar');
};
