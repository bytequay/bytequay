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
import { spawnSync } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'bytequay-java-runtime-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const script = join(root, 'scripts', 'java-runtime.mjs');
  const javaHome = join(root, 'jdk with spaces');
  const target = join(root, 'frontend', 'vendor', 'jre');
  await mkdir(join(root, 'scripts'));
  await mkdir(join(javaHome, 'bin'), { recursive: true });
  await copyFile(new URL('./java-runtime.mjs', import.meta.url), script);
  await writeFile(join(javaHome, 'bin', 'java'), `#!${process.execPath}
console.log('java.base@25\\njava.compiler@25\\njdk.compiler@25\\njdk.jlink@25\\njdk.jpackage@25');
`, { mode: 0o755 });
  await writeFile(join(javaHome, 'bin', 'jlink'), `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
require('node:assert/strict').equal(process.argv[process.argv.indexOf('--add-modules') + 1], 'java.base,java.compiler,jdk.compiler');
require('node:assert/strict').equal(process.argv[process.argv.indexOf('--compress') + 1], 'zip-6');
require('node:assert/strict').ok(!process.argv.includes('--module-path'));
fs.appendFileSync(path.join(process.env.JAVA_HOME, 'calls'), 'build\\n');
if (process.env.FAIL_JLINK) process.exit(1);
const output = process.argv[process.argv.indexOf('--output') + 1];
fs.mkdirSync(path.join(output, 'bin'), { recursive: true });
fs.mkdirSync(path.join(output, 'legal', 'java.base'), { recursive: true });
fs.writeFileSync(path.join(output, 'legal', 'java.base', 'LICENSE'), 'OpenJDK license');
fs.writeFileSync(path.join(output, 'bin', 'java'), '#!${process.execPath}\\n', { mode: 0o755 });
`, { mode: 0o755 });
  const release = (version, arch = process.arch === 'arm64' ? 'aarch64' : 'x86_64') =>
    writeFile(join(javaHome, 'release'), `JAVA_VERSION="${version}"\nOS_NAME="Darwin"\nOS_ARCH="${arch}"\n`);
  await release('25.0.1');
  const run = (args = [], env = {}) => spawnSync(process.execPath, [script, ...args], {
    env: { ...process.env, JAVA_HOME: javaHome, ...env }, encoding: 'utf8',
  });
  return { root, javaHome, target, release, run };
}

test('builds with licenses, reuses the runtime, and refreshes it after a JDK update', { skip: process.platform !== 'darwin' }, async t => {
  const { javaHome, target, release, run } = await fixture(t);
  const built = run();
  assert.equal(built.status, 0, built.stderr);
  assert.equal(await readFile(join(target, 'legal', 'java.base', 'LICENSE'), 'utf8'), 'OpenJDK license');
  const originalMarker = await readFile(join(target, '.manifest'), 'utf8');
  const cached = run();
  assert.equal(cached.status, 0, cached.stderr);
  assert.match(cached.stdout, /already present/);
  assert.equal(await readFile(join(javaHome, 'calls'), 'utf8'), 'build\n');
  await release('25.0.2');
  const updated = run();
  assert.equal(updated.status, 0, updated.stderr);
  assert.notEqual(await readFile(join(target, '.manifest'), 'utf8'), originalMarker);
  assert.equal(await readFile(join(javaHome, 'calls'), 'utf8'), 'build\nbuild\n');
});

test('rejects an incompatible JDK or cross-architecture package', { skip: process.platform !== 'darwin' }, async t => {
  const { release, run } = await fixture(t);
  await release('24.0.2');
  let result = run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Packaging requires .* JDK 25/);
  await release('25.0.1', process.arch === 'arm64' ? 'x86_64' : 'aarch64');
  result = run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Packaging requires .* JDK 25/);
  result = run([process.arch === 'arm64' ? 'darwin-x64' : 'darwin-arm64']);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Mac of the same architecture/);
});

test('preserves the previous runtime and removes staging files if linking fails', { skip: process.platform !== 'darwin' }, async t => {
  const { root, target, release, run } = await fixture(t);
  const built = run();
  assert.equal(built.status, 0, built.stderr);
  const originalMarker = await readFile(join(target, '.manifest'), 'utf8');
  await release('25.0.2');
  const failed = run([], { FAIL_JLINK: '1' });
  assert.notEqual(failed.status, 0);
  assert.equal(await readFile(join(target, '.manifest'), 'utf8'), originalMarker);
  assert.deepEqual(await readdir(join(root, 'frontend', 'vendor')), ['jre']);
});
