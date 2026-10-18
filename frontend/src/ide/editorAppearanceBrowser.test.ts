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
import { createServer } from 'vite';
import { expect, it } from 'vitest';
import { electronFixtureLifecycle } from '../fixtures/electronFixtureLifecycle';

it('renders native syntax, folding, type icons, test colors, usages, search, private line comments, and change markers', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-editor-appearance-'));
  const server = await createServer({ configFile: resolve('vite.ide.config.ts'), logLevel: 'error',
    cacheDir: join(directory, 'node_modules/.vite'), server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'appearance-fixture', configureServer(server) {
      server.middlewares.use('/__appearance', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        response.end(await server.transformIndexHtml('/__appearance', '<!doctype html><meta charset="UTF-8"><body style="margin:0"><script>window.result = import("/src/ide/fixtures/appearanceBrowser.ts").then(module => module.run());</script>'));
      });
    } }],
    optimizeDeps: { entries: [resolve('src/ide/fixtures/appearanceBrowser.ts')] },
  });
  try {
    await server.listen();
    const address = server.httpServer?.address();
    if (!address || typeof address === 'string') throw new Error('Missing preview port');
    const script = join(directory, 'main.cjs');
    await writeFile(script, `
      const { app, BrowserWindow } = require('electron');
      ${electronFixtureLifecycle(90_000)}
      app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
      app.whenReady().then(async () => {
        const window = new BrowserWindow({ show: false, width: 1050, height: 720, webPreferences: { backgroundThrottling: false } });
        window.webContents.on('console-message', event => console.error(event.message));
        try {
          await window.loadURL('http://127.0.0.1:${address.port}/__appearance');
          const pending = window.webContents.executeJavaScript('window.result');
          let failure; pending.catch(error => { failure = error; });
          const modifier = process.platform === 'darwin' ? 'meta' : 'control';
          // Stay hidden: a shown window loses page focus whenever another app becomes active, and the user's clicks land in it.
          window.webContents.focus();
          const waitFor = async (expression, attempts = 100) => {
            for (let attempt = 0; attempt < attempts; attempt++) {
              if (failure) throw failure;
              if (await window.webContents.executeJavaScript(expression)) return;
              await new Promise(resolve => setTimeout(resolve, 50));
            }
            throw new Error('Shortcut did not reach the editor: ' + expression);
          };
          await waitFor('Boolean(window.usageClickPoint)', 600);
          const point = await window.webContents.executeJavaScript('window.usageClickPoint');
          window.webContents.sendInputEvent({ type: 'mouseMove', ...point, modifiers: [modifier] });
          window.webContents.sendInputEvent({ type: 'mouseDown', ...point, button: 'left', clickCount: 1, modifiers: [modifier] });
          window.webContents.sendInputEvent({ type: 'mouseUp', ...point, button: 'left', clickCount: 1, modifiers: [modifier] });
          for (const mode of ['dark', 'light', 'github', 'github-dark']) {
            await waitFor('window.searchCapture === ' + JSON.stringify(mode), 600);
            if (process.env.BYTEQUAY_SEARCH_CAPTURE) require('fs').writeFileSync(process.env.BYTEQUAY_SEARCH_CAPTURE + '-' + mode + '.png', (await window.webContents.capturePage()).toPNG());
            await window.webContents.executeJavaScript('window.searchCaptured = ' + JSON.stringify(mode));
          }
          for (const gesture of ['doubleShift', 'file', 'project']) {
            await waitFor('window.searchShortcut === ' + JSON.stringify(gesture), 600);
            const modifiers = gesture === 'project' ? [modifier, 'shift'] : gesture === 'file' ? [modifier] : [];
            for (let tap = 0; tap < (gesture === 'doubleShift' ? 2 : 1); tap++) {
              const keyCode = gesture === 'doubleShift' ? 'Shift' : 'F';
              window.webContents.sendInputEvent({ type: 'keyDown', keyCode, modifiers });
              window.webContents.sendInputEvent({ type: 'keyUp', keyCode, modifiers });
            }
          }
          for (const mode of ['dark', 'light']) {
            await waitFor('window.reviewCapture === ' + JSON.stringify(mode), 600);
            if (process.env.BYTEQUAY_REVIEW_CAPTURE) require('fs').writeFileSync(process.env.BYTEQUAY_REVIEW_CAPTURE + '-' + mode + '.png', (await window.webContents.capturePage()).toPNG());
            await window.webContents.executeJavaScript('window.reviewCaptured = ' + JSON.stringify(mode));
          }
          for (const mode of ['dark', 'light']) {
            await waitFor('window.lineChangesCapture === ' + JSON.stringify(mode), 600);
            if (process.env.BYTEQUAY_LINE_CHANGES_CAPTURE) require('fs').writeFileSync(process.env.BYTEQUAY_LINE_CHANGES_CAPTURE + '-' + mode + '.png', (await window.webContents.capturePage()).toPNG());
            await window.webContents.executeJavaScript('window.lineChangesCaptured = ' + JSON.stringify(mode));
          }
          const result = await pending;
          process.stdout.write(JSON.stringify(result) + '\\n');
          app.exit(0);
        } catch (error) { console.error(error); app.exit(1); }
      });
    `);
    const environment = { ...process.env }; delete environment.ELECTRON_RUN_AS_NODE;
    const executable = electron as unknown as string;
    const command = process.platform === 'linux' && !process.env.DISPLAY ? 'xvfb-run' : executable;
    const args = command === executable ? [script] : ['-a', executable, script];
    const result = await promisify(execFile)(command, args, { env: environment, timeout: 90_000, killSignal: 'SIGKILL', maxBuffer: 2 * 1024 * 1024 });
    const colors = JSON.parse(result.stdout.trim()) as Record<string, number>;
    expect(Object.keys(colors)).toHaveLength(12);
    expect(Object.values(colors).every(count => count >= 4)).toBe(true);
  } finally { await server.close(); await rm(directory, { recursive: true, force: true }); }
}, 120_000);
