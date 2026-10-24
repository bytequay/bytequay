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

import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import electron from 'electron';
import { expect, it, vi } from 'vitest';
import { buildBrowserFixture, runBrowserFixture, deadline } from './browserFixture';
import { electronFixtureLifecycle } from './electronFixtureLifecycle';

it('returns results and exits when several fixtures finish together', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-fixture-concurrent-'));
  try {
    const entry = join(directory, 'renderer.js');
    await writeFile(entry, 'export function run() { return "completed"; }');
    const fixture = await buildBrowserFixture({
      prefix: 'bytequay-fixture-concurrent-app-', entry, global: 'Fixture', timeoutMs: 10_000,
    });
    try {
      const results = await Promise.allSettled(Array.from({ length: 6 }, () => fixture.run<string>()));
      expect(results).toEqual(Array(6).fill({ status: 'fulfilled', value: 'completed' }));
    }
    finally { await fixture.dispose(); }
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(20_000));

it.each(['parent exit', 'deadline'])('exits a real Electron fixture after %s', async (reason) => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-fixture-cleanup-'));
  const environment = { ...process.env };
  delete environment.ELECTRON_RUN_AS_NODE;
  const script = join(directory, 'main.cjs');
  const executable = electron as unknown as string;
  const command = process.platform === 'linux' && !process.env.DISPLAY ? 'xvfb-run' : executable;
  const args = command === executable ? [script] : ['-a', executable, script];
  await writeFile(script, `
    const { app, BrowserWindow } = require('electron');
    app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
    ${electronFixtureLifecycle(reason === 'deadline' ? 2_000 : 10_000)}
    app.whenReady().then(() => {
      new BrowserWindow({ show: false });
      process.stdout.write('READY ' + process.pid + '\\n');
    });
  `);
  // A disposable parent lets the test exercise a killed worker without killing Vitest.
  const parent = spawn(process.execPath, ['-e', `
    const child = require('node:child_process').spawn(${JSON.stringify(command)}, ${JSON.stringify(args)}, {
      stdio: ['pipe', 'inherit', 'inherit']
    });
    child.on('exit', code => process.exit(code ?? 1));
  `], { env: environment, stdio: ['ignore', 'pipe', 'pipe'] });
  const closed = once(parent, 'close');
  let fixturePid: number | undefined;
  let stdout = '';
  let stderr = '';
  parent.stdout.on('data', chunk => { stdout += chunk; });
  parent.stderr.on('data', chunk => { stderr += chunk; });
  try {
    await vi.waitFor(() => expect(stdout).toMatch(/READY \d+/), { timeout: 10_000 });
    fixturePid = Number(/READY (\d+)/.exec(stdout)?.[1]);
    if (reason === 'parent exit') parent.kill('SIGKILL');
    expect(await closed).toEqual(reason === 'parent exit' ? [null, 'SIGKILL'] : [1, null]);
    const pid = fixturePid;
    await vi.waitFor(() => expect(() => process.kill(pid, 0)).toThrowError(), { timeout: 5_000 });
    if (reason === 'deadline') expect(stderr).toContain('Electron fixture timed out');
  }
  finally {
    parent.kill('SIGKILL');
    if (fixturePid) forceExit(fixturePid);
    await rm(directory, { recursive: true, force: true });
  }
}, deadline(20_000));

it('force-closes a fixture whose main thread cannot handle graceful shutdown', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-fixture-stall-'));
  try {
    const entry = join(directory, 'renderer.js');
    await writeFile(entry, 'export function run() { return []; }');
    await expect(runBrowserFixture({
      prefix: 'bytequay-fixture-stall-app-', entry, global: 'Fixture', timeoutMs: 3_000,
      body: 'process.on("SIGTERM", () => {}); while (true) {}',
    })).rejects.toMatchObject({ killed: true, signal: 'SIGKILL' });
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(20_000));

function forceExit(pid: number): void {
  try { process.kill(pid, 'SIGKILL'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error; }
}
