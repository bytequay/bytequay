#!/usr/bin/env node
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
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { access, cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const app = resolve(process.argv[2] ?? join(root, 'frontend', 'out', `ByteQuay-${process.platform}-${process.arch}`, 'ByteQuay.app'));
const source = join(app, 'Contents', 'Resources', 'lab');
await access(join(source, 'python', 'lib', 'python3.14', 'LICENSE.txt'));
const temporary = await mkdtemp(join(tmpdir(), 'bytequay-packaged-lab-'));
let child;
try {
  const lab = join(temporary, 'relocated lab');
  await cp(source, lab, { recursive: true, verbatimSymlinks: true });
  const emptyPath = join(temporary, 'empty-path');
  const runtime = join(temporary, 'runtime');
  const results = join(temporary, 'results');
  const logs = join(temporary, 'logs');
  await mkdir(emptyPath);
  await mkdir(runtime);
  await mkdir(results);
  await mkdir(logs);
  await writeFile(join(logs, 'backend.log'), `${new Date().toISOString()} ERROR 12 --- [bytequay-backend] [main] c.b.Test : database is locked\n`);
  const env = { PATH: emptyPath, HOME: temporary, TMPDIR: temporary, BYTEQUAY_LAB_PARENT_PID: String(process.pid) };
  const python = join(lab, 'python', 'bin', 'python3');
  const database = join(temporary, 'fixture.db');
  // Reuse the test schema; the packaged refresh process still executes only bundled source.
  execFileSync(python, ['-E', '-s', '-B', '-c',
    'import sys; from pathlib import Path; sys.path.insert(0, sys.argv[1]); from test_desktop import database; database(Path(sys.argv[2]))',
    join(root, 'scripts', 'review-calibration'), database], { cwd: temporary, env, stdio: 'inherit' });
  const before = await readFile(database);
  execFileSync(python, ['-E', '-s', '-B', join(lab, 'app', 'desktop.py'), '--folder', results, '--runtime', runtime, '--database', database, '--logs', logs],
    { cwd: temporary, env, stdio: 'inherit' });
  for (const [file, rows] of [['turns.json', 'turns'], ['reviews.json', 'runs'], ['tuning.json', 'turns'], ['context.json', 'calls'], ['memory.json', 'memories']]) {
    const table = JSON.parse(await readFile(join(results, file), 'utf8'));
    assert.deepEqual(table[rows], [], `${file} must contain refreshed empty statistics`);
  }
  const health = JSON.parse(await readFile(join(results, 'app-health.json'), 'utf8'));
  assert.equal(health.errors, 1, 'Packaged Lab must analyze existing backend logs');
  assert.equal(health.groups[0].category, 'database');
  assert.deepEqual(await readFile(database), before, 'Lab must not change the application database');
  const listener = createServer();
  await new Promise((resolveListen, reject) => {
    listener.once('error', reject);
    listener.listen(0, '127.0.0.1', resolveListen);
  });
  const port = listener.address().port;
  await new Promise(resolveClose => listener.close(resolveClose));
  const basePath = `lab-${randomUUID()}`;
  const url = `http://127.0.0.1:${port}/${basePath}`;
  const started = Date.now();
  child = spawn(python, ['-E', '-s', '-B', join(lab, 'app', 'dashboard.py'), results,
    '--runtime', runtime, '--database', database, '--logs', logs, '--port', String(port), '--base-path', basePath], {
    cwd: temporary, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env,
  });
  let failure;
  let output = '';
  child.on('error', error => { failure = error; });
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { output = (output + bytes).slice(-64000); });
  while (true) {
    if (failure) throw failure;
    if (child.exitCode !== null || child.signalCode !== null) throw new Error(`Packaged Lab exited: ${output}`);
    if (Date.now() - started > 30000) throw new Error(`Packaged Lab did not become ready: ${output}`);
    const response = await fetch(`${url}/_stcore/health`, { signal: AbortSignal.timeout(1000) }).catch(() => undefined);
    if (response?.ok) {
      assert.equal(await response.text(), 'ok');
      assert.equal((await fetch(`${url}/`)).status, 200);
      console.log(`Relocated packaged Lab ready in ${((Date.now() - started) / 1000).toFixed(1)}s without system Python or uv`);
      break;
    }
    await setTimeout(100);
  }
} finally {
  if (child?.pid && child.exitCode === null && child.signalCode === null) {
    const exited = new Promise(resolveExit => child.once('exit', resolveExit));
    process.kill(-child.pid, 'SIGTERM');
    await Promise.race([exited, setTimeout(5000, undefined, { ref: false })]);
    if (child.exitCode === null && child.signalCode === null) {
      process.kill(-child.pid, 'SIGKILL');
      await exited;
    }
  }
  await rm(temporary, { recursive: true, force: true });
}
