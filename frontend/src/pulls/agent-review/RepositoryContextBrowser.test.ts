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

it('preserves explicit lesson ownership and discloses receipt-based context delivery with exact saved text', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-review-context-'));
  try {
    await build({ configFile: false, logLevel: 'silent', define: { 'process.env.NODE_ENV': '"production"' }, esbuild: { jsx: 'automatic', jsxDev: false },
      build: { outDir: directory, emptyOutDir: false, lib: { entry: resolve('src/pulls/agent-review/fixtures/repositoryContextBrowser.tsx'),
        name: 'RepositoryContextTests', formats: ['iife'], fileName: () => 'renderer.js' } } });
    await writeFile(join(directory, 'index.html'), '<!doctype html><meta charset="UTF-8"><link rel="stylesheet" href="style.css"><body><script src="renderer.js"></script>');
    await writeFile(join(directory, 'main.cjs'), `
      const { app, BrowserWindow } = require('electron');
      app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
      app.whenReady().then(async () => {
        const window = new BrowserWindow({ show: false, width: 1000, height: 900, webPreferences: { backgroundThrottling: false } });
        window.webContents.session.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (_details, callback) => callback({ cancel: true }));
        window.webContents.on('will-navigate', event => { event.preventDefault(); });
        window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
        try {
          await window.loadFile(${JSON.stringify(join(directory, 'index.html'))});
          const results = await window.webContents.executeJavaScript('RepositoryContextTests.runScenarios()');
          process.stdout.write(JSON.stringify(results) + '\\n'); app.exit(0);
        } catch (error) { console.error(error); app.exit(1); }
      });
    `);
    const environment = { ...process.env };
    delete environment.ELECTRON_RUN_AS_NODE;
    const executable = electron as unknown as string;
    const args = [join(directory, 'main.cjs')];
    const command = process.platform === 'linux' && !process.env.DISPLAY ? 'xvfb-run' : executable;
    const result = await promisify(execFile)(command, command === executable ? args : ['-a', executable, ...args], { env: environment, timeout: 30_000 });
    expect(JSON.parse(result.stdout.trim())).toHaveLength(6);
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, 40_000);
