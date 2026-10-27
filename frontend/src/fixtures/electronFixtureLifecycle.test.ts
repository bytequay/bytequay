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

it.skipIf(process.platform !== 'darwin')('reaps native shutdown stalls only after a successful final quit callback', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-fixture-native-exit-'));
  try {
    const entry = join(directory, 'renderer.js');
    await writeFile(entry, 'export function run() { return "completed"; }');
    const fixture = await buildBrowserFixture({ prefix: 'bytequay-fixture-native-exit-app-', entry, global: 'Fixture', timeoutMs: 5000,
      body: 'const results = { pid: process.pid, value: await window.webContents.executeJavaScript("Fixture.run()") };',
    });
    // Simulate native teardown blocking after window destruction and the quit event have completed.
    const stalledExit = (quitCode: string) => `app.on('window-all-closed', () => {});
    Object.defineProperty(app, 'exit', { value: code => {
      for (const window of BrowserWindow.getAllWindows()) window.destroy();
      app.emit('quit', {}, ${quitCode});
      while (true) {}
    } });`;
    try {
      const result = await fixture.run<{ pid: number; value: string }>({ beforeReady: stalledExit('code') });
      expect(result.value).toBe('completed');
      expect(() => process.kill(result.pid, 0)).toThrowError();
      await expect(fixture.run({ beforeReady: stalledExit('1') }))
        .rejects.toMatchObject({ signal: 'SIGKILL', stdout: expect.stringContaining('completed') });
      await expect(fixture.run({ beforeReady: stalledExit('code'), body: 'throw new Error("failed before quit");' }))
        .rejects.toThrow('failed before quit');
      await expect(fixture.run({ beforeReady: `app.on('window-all-closed', () => {});
        Object.defineProperty(app, 'exit', { value: code => {
          app.emit('quit', {}, code);
          if (code === 0) throw new Error('failed after quit');
          while (true) {}
        } });` }))
        .rejects.toThrow('failed after quit');
      await expect(fixture.run({ beforeReady: stalledExit('code'), setup: 'app.on("quit", () => { throw new Error("quit handler failed"); });' }))
        .rejects.toThrow('quit handler failed');
    }
    finally { await fixture.dispose(); }
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(30_000));

it.skipIf(process.platform !== 'darwin')('rejects a stalled fixture with valid results but no quit acknowledgement', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-fixture-unfinished-exit-'));
  try {
    const entry = join(directory, 'renderer.js');
    await writeFile(entry, 'export function run() { return "completed"; }');
    await expect(runBrowserFixture({ prefix: 'bytequay-fixture-unfinished-exit-app-', entry, global: 'Fixture', timeoutMs: 3000,
      beforeReady: 'Object.defineProperty(app, "exit", { value: () => { while (true) {} } });',
    })).rejects.toMatchObject({ killed: true, signal: 'SIGKILL', stdout: '"completed"\n' });
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(20_000));

function forceExit(pid: number): void {
  try { process.kill(pid, 'SIGKILL'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error; }
}
