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

export type ScenarioResult = { name: string; error?: string };

export type BrowserFixture = {
  /** Temporary directory prefix, e.g. `bytequay-recent-loading-`. */
  prefix: string;
  /** Fixture entry module, relative to the frontend package root. */
  entry: string;
  /** Global the iife bundle exposes its scenarios on. */
  global: string;
  /** Link the stylesheet the lib build emits beside the bundle; layout assertions need it. */
  styles?: boolean;
  /** Use UTF-8 for bundled dependencies unless a fixture explicitly tests another encoding. */
  charset?: boolean;
  width?: number;
  height?: number;
  /** Preload source; the window gets it and `ipcMain` handlers can answer it from `setup`. */
  preload?: string;
  /** Main-process source that runs after the window exists and before the page loads. */
  setup?: string;
  /** Main-process source that assigns `results` after the page loads. */
  body?: string;
  blockNetwork?: boolean;
  timeoutMs?: number;
};

/** Builds a fixture as a page, runs it in a headless Electron window, and returns what it printed. */
export async function runBrowserFixture<T>(fixture: BrowserFixture): Promise<T>
{
  const directory = await mkdtemp(join(tmpdir(), fixture.prefix));
  try {
    await build({
      configFile: false, logLevel: 'silent', define: { 'process.env.NODE_ENV': '"production"' },
      esbuild: { jsx: 'automatic', jsxDev: false },
      build: { outDir: directory, emptyOutDir: false, lib: {
        entry: resolve(fixture.entry), name: fixture.global, formats: ['iife'], fileName: () => 'renderer.js',
      } },
    });
    const charset = fixture.charset !== false ? '<meta charset="UTF-8">' : '';
    const styles = fixture.styles === true ? '<link rel="stylesheet" href="style.css">' : '';
    await writeFile(join(directory, 'index.html'), `<!doctype html>${charset}${styles}<body><script src="renderer.js"></script>`);
    if (fixture.preload !== undefined) {
      await writeFile(join(directory, 'preload.cjs'), fixture.preload);
    }
    const size = fixture.width === undefined ? '' : `width: ${fixture.width}, height: ${fixture.height}, `;
    const preload = fixture.preload === undefined ? '' : `, preload: ${JSON.stringify(join(directory, 'preload.cjs'))}`;
    const network = fixture.blockNetwork === false ? ''
      : "window.webContents.session.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (_details, callback) => callback({ cancel: true }));";
    await writeFile(join(directory, 'main.cjs'), `
      const { app, BrowserWindow, ipcMain } = require('electron');
      app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
      app.whenReady().then(async () => {
        const window = new BrowserWindow({ show: false, ${size}webPreferences: { backgroundThrottling: false${preload} } });
        ${network}
        ${fixture.setup ?? ''}
        try {
          await window.loadFile(${JSON.stringify(join(directory, 'index.html'))});
          ${fixture.body ?? `const results = await window.webContents.executeJavaScript('${fixture.global}.run()');`}
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
    const result = await promisify(execFile)(command, command === executable ? args : ['-a', executable, ...args], { env: environment, timeout: fixture.timeoutMs ?? 20_000 });
    return JSON.parse(result.stdout.trim()) as T;
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}
