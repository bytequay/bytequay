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

it('preserves task reading position in Chromium while paging and following', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-history-scroll-'));
  try {
    await build({
      configFile: false,
      logLevel: 'silent',
      define: { 'process.env.NODE_ENV': '"production"' },
      esbuild: { jsx: 'automatic', jsxDev: false },
      build: {
        outDir: directory,
        emptyOutDir: false,
        lib: { entry: resolve('src/agents/fixtures/historyScrollBrowser.tsx'), name: 'HistoryScrollTests', formats: ['iife'], fileName: () => 'renderer.js' },
      },
    });
    await writeFile(join(directory, 'index.html'), '<!doctype html><link rel="stylesheet" href="style.css"><body><script src="renderer.js"></script>');
    await writeFile(join(directory, 'preload.cjs'), `const { contextBridge, ipcRenderer } = require('electron'); contextBridge.exposeInMainWorld('historyTestWheel', event => ipcRenderer.invoke('history-wheel', event));`);
    await writeFile(join(directory, 'main.cjs'), `
      const { app, BrowserWindow, ipcMain } = require('electron');
      app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
      app.whenReady().then(async () => {
        const window = new BrowserWindow({ show: false, width: 800, height: 600, webPreferences: { backgroundThrottling: false, preload: ${JSON.stringify(join(directory, 'preload.cjs'))} } });
        window.webContents.debugger.attach('1.3');
        ipcMain.handle('history-wheel', (_event, input) => window.webContents.debugger.sendCommand('Input.dispatchMouseEvent', { type: 'mouseWheel', deltaX: 0, ...input }));
        window.webContents.on('console-message', (_event, details) => { if (details.level === 'error') console.error(details.message); });
        try {
          await window.loadFile(${JSON.stringify(join(directory, 'index.html'))});
          const results = await window.webContents.executeJavaScript('HistoryScrollTests.run()');
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
    const results = JSON.parse(result.stdout.trim()) as { name: string; error?: string }[];
    expect(results).toHaveLength(10);
    expect(results.filter(result => result.error !== undefined)).toEqual([]);
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, 30_000);
