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
import { electronFixtureLifecycle } from './electronFixtureLifecycle';

export type ScenarioResult = { name: string; error?: string };

/** Scales a deadline set on a developer Mac: suites take two to four times as long on a three-core CI runner. */
export const deadline = (milliseconds: number): number => milliseconds * (process.env.CI ? 3 : 1);

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
  /**
   * Main-process source that assigns `results` after the page loads. After resizing the window it awaits
   * `untilWidth(width)`: the page lays out at a new size only after `setSize` returns.
   */
  body?: string;
  blockNetwork?: boolean;
  timeoutMs?: number;
};

/** One bundle per suite; each run still gets its own Electron process and browser profile. */
export async function buildBrowserFixture(fixture: BrowserFixture) {
  const bundle = await mkdtemp(join(tmpdir(), fixture.prefix));
  const dispose = () => rm(bundle, { recursive: true, force: true });
  try {
    await build({
      configFile: false, logLevel: 'silent', define: { 'process.env.NODE_ENV': '"production"' },
      esbuild: { jsx: 'automatic', jsxDev: false },
      // Minified dependency names can collide with generated class-field helpers in these single-file bundles.
      build: { minify: false, outDir: bundle, emptyOutDir: false, lib: {
        entry: resolve(fixture.entry), name: fixture.global, formats: ['iife'], fileName: () => 'renderer.js',
      } },
    });
    return {
      run: <T>(options: Partial<Omit<BrowserFixture, 'prefix' | 'entry' | 'global'>> = {}) => runBuiltFixture<T>({ ...fixture, ...options }, bundle),
      dispose,
    };
  }
  catch (error) { await dispose(); throw error; }
}

/** Builds a one-off fixture and removes its bundle after the browser exits. */
export async function runBrowserFixture<T>(fixture: BrowserFixture): Promise<T> {
  const built = await buildBrowserFixture(fixture);
  try { return await built.run<T>(); }
  finally { await built.dispose(); }
}

async function runBuiltFixture<T>(fixture: BrowserFixture, bundle: string): Promise<T> {
  const directory = await mkdtemp(join(bundle, 'run-'));
  try {
    const timeout = deadline(fixture.timeoutMs ?? 20_000);
    const charset = fixture.charset !== false ? '<meta charset="UTF-8">' : '';
    const styles = fixture.styles === true ? '<link rel="stylesheet" href="../style.css">' : '';
    await writeFile(join(directory, 'index.html'), `<!doctype html>${charset}${styles}<body><script src="../renderer.js"></script>`);
    if (fixture.preload !== undefined) {
      await writeFile(join(directory, 'preload.cjs'), fixture.preload);
    }
    // The constructor shrinks a window to the screen, and a CI runner's display can be smaller than the fixture.
    const size = fixture.width === undefined ? '' : `window.setSize(${fixture.width}, ${fixture.height ?? 'window.getSize()[1]'});`;
    const preload = fixture.preload === undefined ? '' : `, preload: ${JSON.stringify(join(directory, 'preload.cjs'))}`;
    const network = fixture.blockNetwork === false ? ''
      : "window.webContents.session.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (_details, callback) => callback({ cancel: true }));";
    await writeFile(join(directory, 'main.cjs'), `
      const { app, BrowserWindow, ipcMain, nativeTheme } = require('electron');
      ${electronFixtureLifecycle(timeout)}
      // Fixtures render the same whatever the host's appearance and motion settings are.
      nativeTheme.themeSource = 'light';
      app.commandLine.appendSwitch('force-prefers-no-reduced-motion');
      app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
      app.whenReady().then(async () => {
        const window = new BrowserWindow({ show: false, webPreferences: { backgroundThrottling: false${preload} } });
        ${size}
        const untilWidth = width => window.webContents.executeJavaScript(
          'new Promise(resolve => { const check = () => innerWidth === ' + width + ' ? resolve() : requestAnimationFrame(check); check(); })');
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
    const result = await promisify(execFile)(command, command === executable ? args : ['-a', executable, ...args], { env: environment, timeout, killSignal: 'SIGKILL' });
    return JSON.parse(result.stdout.trim()) as T;
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}
