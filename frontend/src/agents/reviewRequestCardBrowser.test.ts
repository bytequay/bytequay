/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { execFile } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';
import electron from 'electron';
import { build } from 'vite';
import { expect, it } from 'vitest';

it('discloses complete review requests with keyboard and pointer without overflow or side effects in Chromium', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-review-requests-'));
  try {
    await build({
      configFile: false, logLevel: 'silent', define: { 'process.env.NODE_ENV': '"production"' },
      esbuild: { jsx: 'automatic', jsxDev: false },
      build: { outDir: directory, emptyOutDir: false, lib: {
        entry: resolve('src/agents/fixtures/reviewRequestCardBrowser.tsx'), name: 'ReviewRequestTests', formats: ['iife'], fileName: () => 'renderer.js',
      } },
    });
    await writeFile(join(directory, 'index.html'), '<!doctype html><meta charset="UTF-8"><link rel="stylesheet" href="style.css"><body><script src="renderer.js"></script>');
    await writeFile(join(directory, 'main.cjs'), `
      const { app, BrowserWindow } = require('electron');
      app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
      app.whenReady().then(async () => {
        const window = new BrowserWindow({ show: false, webPreferences: { backgroundThrottling: false } });
        window.webContents.session.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (_details, callback) => callback({ cancel: true }));
        let navigations = 0;
        window.webContents.on('will-navigate', event => { event.preventDefault(); navigations++; });
        window.webContents.setWindowOpenHandler(() => { navigations++; return { action: 'deny' }; });
        const run = code => window.webContents.executeJavaScript(code);
        const key = keyCode => {
          window.webContents.sendInputEvent({ type: 'keyDown', keyCode });
          window.webContents.sendInputEvent({ type: 'char', keyCode: keyCode === 'Enter' ? '\\r' : ' ' });
          window.webContents.sendInputEvent({ type: 'keyUp', keyCode });
        };
        try {
          await window.loadFile(${JSON.stringify(join(directory, 'index.html'))});
          const results = [];
          for (const width of [760, 320]) {
            window.setContentSize(width, 900);
            await run('ReviewRequestTests.mount()');
            await run('ReviewRequestTests.verify(false)');
            key('Enter');
            await run('ReviewRequestTests.verify(true)');
            key('Space');
            await run('ReviewRequestTests.verify(false)');
            await run('ReviewRequestTests.clickToggle()');
            await run('ReviewRequestTests.verify(true)');
            await run('ReviewRequestTests.retry()');
            await run('ReviewRequestTests.verify(true, 1)');
            await run('ReviewRequestTests.openReview()');
            results.push(await run('ReviewRequestTests.verify(true, 1, 1)'));
          }
          if (navigations !== 0) throw new Error('Disclosure must not navigate or open a window');
          process.stdout.write(JSON.stringify(results) + '\\n');
          app.exit(0);
        } catch (error) { console.error(error); app.exit(1); }
      });
    `);
    const environment = { ...process.env };
    delete environment.ELECTRON_RUN_AS_NODE;
    const executable = electron as unknown as string;
    const args = [join(directory, 'main.cjs')];
    const command = process.platform === 'linux' && !process.env.DISPLAY ? 'xvfb-run' : executable;
    const result = await promisify(execFile)(command, command === executable ? args : ['-a', executable, ...args], { env: environment, timeout: 20_000 });
    const results = JSON.parse(result.stdout.trim()) as { width: number; open: boolean }[];
    expect(results.map(result => result.width)).toEqual([760, 320]);
    expect(results.every(result => result.open)).toBe(true);
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, 30_000);
