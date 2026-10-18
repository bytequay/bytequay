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
import { existsSync } from 'node:fs';
import { access, cp, mkdir, mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const app = resolve(process.argv[2] ?? join(root, 'frontend', 'out', `ByteQuay-${process.platform}-${process.arch}`, 'ByteQuay.app'));
const resources = join(app, 'Contents', 'Resources');
const manifest = JSON.parse(await readFile(join(resources, 'ide', 'runtimeManifest.json'), 'utf8'));
const extension = manifest.extensions.find(entry => entry.id === 'redhat.java');
assert.ok(extension, 'The Java extension must be bundled');
const extensionRoot = join(resources, 'ide', 'extensions', `${extension.id}-${extension.version}`);
assert.equal(existsSync(join(extensionRoot, 'jre')), false, 'The Java extension must share the application runtime');
const javaHome = join(resources, 'jre');
await access(join(javaHome, 'bin', 'javac'));
await access(join(javaHome, 'legal', 'java.base', 'LICENSE'));
const server = join(extensionRoot, 'server');
const launchers = (await readdir(join(server, 'plugins'))).filter(name => /^org\.eclipse\.equinox\.launcher_[^/]+\.jar$/.test(name));
assert.equal(launchers.length, 1, 'The Java language server must have one launcher');
const temporary = await mkdtemp(join(tmpdir(), 'bytequay-packaged-ide-'));
let child;
let timer;
try {
  const configuration = join(temporary, 'configuration');
  const workspace = join(temporary, 'workspace');
  const emptyPath = join(temporary, 'empty-path');
  await cp(join(server, 'config_mac'), configuration, { recursive: true });
  await mkdir(workspace);
  await mkdir(emptyPath);
  const started = Date.now();
  child = spawn(join(javaHome, 'bin', 'java'), [
    `-Duser.home=${temporary}`, '-Declipse.application=org.eclipse.jdt.ls.core.id1', '-Declipse.product=org.eclipse.jdt.ls.core.product',
    '-Dosgi.bundles.defaultStartLevel=4', '-Xmx512m', '--add-modules=ALL-SYSTEM', '--enable-native-access=ALL-UNNAMED',
    '--add-opens', 'java.base/java.util=ALL-UNNAMED', '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '-jar', join(server, 'plugins', launchers[0]), '-configuration', configuration, '-data', join(temporary, 'data'),
  ], { cwd: temporary, env: { PATH: emptyPath, HOME: temporary, TMPDIR: temporary }, stdio: ['pipe', 'pipe', 'pipe'] });
  let output = '';
  child.stderr.on('data', bytes => { output = (output + bytes).slice(-64000); });
  const send = message => {
    const body = JSON.stringify({ jsonrpc: '2.0', ...message });
    child.stdin.write(`Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
  };
  const initialized = new Promise((resolveReady, reject) => {
    let pending = Buffer.alloc(0);
    child.once('error', reject);
    child.stdin.on('error', reject);
    child.once('exit', (code, signal) => reject(new Error(`Java language server exited (${signal ?? code}): ${output}`)));
    timer = setTimeout(() => reject(new Error(`Java language server did not initialize: ${output}`)), 60000);
    child.stdout.on('data', bytes => {
      pending = Buffer.concat([pending, bytes]);
      try {
        while (true) {
          const end = pending.indexOf('\r\n\r\n');
          if (end < 0) return;
          const length = /^Content-Length: (\d+)$/mi.exec(pending.subarray(0, end).toString());
          assert.ok(length, 'The language server must send framed protocol messages');
          const bodyEnd = end + 4 + Number(length[1]);
          if (pending.length < bodyEnd) return;
          const message = JSON.parse(pending.subarray(end + 4, bodyEnd).toString());
          pending = pending.subarray(bodyEnd);
          if (message.id === 1 && !message.method) resolveReady(message);
          else if (message.id !== undefined && message.method) send({ id: message.id, result: null });
        }
      }
      catch (error) { reject(error); }
    });
  });
  send({ id: 1, method: 'initialize', params: {
    processId: process.pid, rootUri: pathToFileURL(workspace).href, capabilities: {},
    initializationOptions: { settings: { java: { autobuild: { enabled: false }, import: { maven: { enabled: false }, gradle: { enabled: false } } } } },
  } });
  const response = await initialized;
  assert.equal(response.error, undefined, JSON.stringify(response.error));
  assert.ok(response.result?.capabilities?.textDocumentSync, 'The shared runtime must initialize Java language support');
  send({ method: 'initialized', params: {} });
  console.log(`Packaged Java language server ready in ${((Date.now() - started) / 1000).toFixed(1)}s using shared Java without a system JDK`);
} finally {
  clearTimeout(timer);
  if (child?.pid && child.exitCode === null && child.signalCode === null) {
    const exited = new Promise(resolveExit => child.once('exit', resolveExit));
    child.kill('SIGTERM');
    await Promise.race([exited, delay(5000, undefined, { ref: false })]);
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGKILL');
      await exited;
    }
  }
  await rm(temporary, { recursive: true, force: true });
}
