import { readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';

const files = ['playwright.config.js'];
for (const directory of ['public', 'scripts', join('tests', 'browser')]) {
  for (const entry of await readdir(directory)) if (/\.(mjs|js)$/.test(entry)) files.push(join(directory, entry));
}
for (const file of files) {
  const result = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8', windowsHide: true });
  if (result.status !== 0) {
    console.error(result.stderr || `Syntax check failed: ${file}`);
    process.exitCode = 1;
    break;
  }
}
if (!process.exitCode) {
  console.log(`Syntax checked ${files.length} browser and development-tool modules.`);
  const result = spawnSync(process.execPath, [join('scripts', 'export-content.mjs'), '--check'], { stdio: 'inherit', windowsHide: true });
  if (result.status !== 0) process.exitCode = 1;
}
