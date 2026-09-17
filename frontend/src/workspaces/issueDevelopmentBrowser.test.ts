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

it('starts issue development through the existing composer and retains source navigation in Chromium', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-issue-development-'));
  try {
    await build({
      configFile: false, logLevel: 'silent', define: { 'process.env.NODE_ENV': '"production"' },
      esbuild: { jsx: 'automatic', jsxDev: false },
      build: { outDir: directory, emptyOutDir: false, lib: {
        entry: resolve('src/workspaces/fixtures/issueDevelopmentBrowser.tsx'), name: 'IssueDevelopmentTests', formats: ['iife'], fileName: () => 'renderer.js',
      } },
    });
    await writeFile(join(directory, 'index.html'), '<!doctype html><meta charset="UTF-8"><link rel="stylesheet" href="style.css"><body><script src="renderer.js"></script>');
    await writeFile(join(directory, 'main.cjs'), `
      const { app, BrowserWindow } = require('electron');
      app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
      app.whenReady().then(async () => {
        const window = new BrowserWindow({ show: false, webPreferences: { backgroundThrottling: false } });
        window.webContents.session.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (_details, callback) => callback({ cancel: true }));
        try {
          await window.loadFile(${JSON.stringify(join(directory, 'index.html'))});
          window.webContents.debugger.attach('1.3');
          await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
          const results = await window.webContents.executeJavaScript('IssueDevelopmentTests.run()');
          for (const width of [1060, 600]) {
            window.setSize(width, 820);
            const source = await window.webContents.executeJavaScript('IssueDevelopmentTests.mountIssueConversation()');
            await window.webContents.debugger.sendCommand('Input.dispatchMouseEvent', { type: 'mouseMoved', ...source });
            await window.webContents.executeJavaScript('IssueDevelopmentTests.verifyIssuePreview()');
            await window.webContents.debugger.sendCommand('Input.dispatchMouseEvent', { type: 'mouseMoved', x: width - 5, y: 5 });
            await window.webContents.executeJavaScript('IssueDevelopmentTests.verifyIssueKeyboard()');
            await window.webContents.executeJavaScript('IssueDevelopmentTests.unmountIssueConversation()');
            await window.webContents.executeJavaScript('IssueDevelopmentTests.mountIssueConversation(true)');
            await window.webContents.executeJavaScript('IssueDevelopmentTests.unmountIssueConversation()');
          }
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
    expect(results).toHaveLength(4);
    expect(results.filter(result => result.error !== undefined)).toEqual([]);
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, 30_000);
