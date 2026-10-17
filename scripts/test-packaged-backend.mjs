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
import { spawn } from 'node:child_process';
import { access, mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const app = resolve(process.argv[2] ?? join(root, 'frontend', 'out', `ByteQuay-${process.platform}-${process.arch}`, 'ByteQuay.app'));
const resources = join(app, 'Contents', 'Resources');
await access(join(resources, 'jre', 'legal', 'java.base', 'LICENSE'));
const temporary = await mkdtemp(join(tmpdir(), 'bytequay-packaged-backend-'));
const portFile = join(temporary, 'port');
const emptyPath = join(temporary, 'empty-path');
await mkdir(emptyPath);
const env = { ...process.env, PATH: emptyPath };
for (const key of ['JAVA_HOME', 'JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS']) delete env[key];

const started = Date.now();
const child = spawn(join(resources, 'jre', 'bin', 'java'), [
  '--enable-native-access=ALL-UNNAMED', `-Duser.home=${temporary}`,
  '-jar', join(resources, 'bytequay-backend.jar'), '--server.port=0',
  `--bytequay.backend.port-file=${portFile}`, '--bytequay.experiments.goals=false',
  '--bytequay.agent.codex-enabled=false', '--bytequay.agent.claude-enabled=false',
  '--bytequay.scheduling.enabled=false',
], { cwd: temporary, env, stdio: ['ignore', 'pipe', 'pipe'] });
let failure;
let output = '';
child.on('error', error => { failure = error; });
for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { output = (output + bytes).slice(-64000); });
const exited = new Promise(resolveExit => child.once('exit', resolveExit));
try {
  while (true) {
    if (failure) throw failure;
    if (child.exitCode !== null || child.signalCode !== null) throw new Error(`Packaged backend exited: ${output}`);
    if (Date.now() - started > 60000) throw new Error(`Packaged backend did not become ready: ${output}`);
    const port = await readFile(portFile, 'utf8').catch(error => {
      if (error.code !== 'ENOENT') throw error;
    });
    if (port) {
      assert.match(port.trim(), /^[1-9][0-9]{0,4}$/);
      const response = await fetch(`http://127.0.0.1:${port.trim()}/hello`, { signal: AbortSignal.timeout(5000) });
      assert.equal(response.status, 200);
      assert.equal(await response.text(), 'ByteQuay-backend ok');
      console.log(`Packaged backend ready in ${((Date.now() - started) / 1000).toFixed(1)}s without system Java`);
      break;
    }
    await setTimeout(250);
  }
} finally {
  if (child.pid && child.exitCode === null && child.signalCode === null) {
    child.kill('SIGTERM');
    await Promise.race([exited, setTimeout(5000, undefined, { ref: false })]);
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGKILL');
      await exited;
    }
  }
  await rm(temporary, { recursive: true, force: true });
}
