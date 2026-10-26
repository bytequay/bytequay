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
import { existsSync } from 'node:fs';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'bytequay-dictation-helper-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const script = join(root, 'scripts', 'dictation-helper.mjs');
  const source = join(root, 'frontend', 'dictation', 'Dictate.swift');
  const tools = join(root, 'tools with spaces');
  const target = join(root, 'frontend', 'vendor', 'dictation');
  await mkdir(join(root, 'scripts'));
  await mkdir(join(root, 'frontend', 'dictation'), { recursive: true });
  await mkdir(tools);
  await copyFile(new URL('./dictation-helper.mjs', import.meta.url), script);
  await writeFile(source, 'print("hello")\n');
  await writeFile(join(tools, 'xcrun'), `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
if (args[0] === '--show-sdk-version') { console.log(process.env.FAKE_SDK); process.exit(0); }
if (args[0] === 'swiftc' && args[1] === '--version') { console.log('Apple Swift version 6.3.3'); process.exit(0); }
const target = args[args.indexOf('-target') + 1];
require('node:assert/strict').equal(target, process.env.EXPECT_TARGET);
require('node:assert/strict').equal(args[args.indexOf('-swift-version') + 1], '6');
fs.appendFileSync(path.join(process.env.FAKE_TOOLS, 'calls'), target + '\\n');
if (process.env.FAIL_SWIFTC) process.exit(1);
fs.writeFileSync(args[args.indexOf('-o') + 1], 'helper', { mode: 0o755 });
`, { mode: 0o755 });
  const run = (args = [], env = {}) => spawnSync(process.execPath, [script, ...args], {
    env: {
      ...process.env, PATH: tools, FAKE_TOOLS: tools, FAKE_SDK: '26.5',
      EXPECT_TARGET: `${process.arch === 'arm64' ? 'arm64' : 'x86_64'}-apple-macos26.0`, ...env,
    },
    encoding: 'utf8',
  });
  const calls = () => readFile(join(tools, 'calls'), 'utf8').catch(() => '');
  return { root, source, target, run, calls };
}

test('builds, reuses the helper, and rebuilds after a source or SDK change', { skip: process.platform !== 'darwin' }, async t => {
  const { source, target, run, calls } = await fixture(t);
  const built = run();
  assert.equal(built.status, 0, built.stderr);
  assert.equal(await readFile(join(target, 'bytequay-dictate'), 'utf8'), 'helper');
  const cached = run();
  assert.equal(cached.status, 0, cached.stderr);
  assert.match(cached.stdout, /already built/);
  assert.equal((await calls()).split('\n').filter(Boolean).length, 1);
  await writeFile(source, 'print("changed")\n');
  assert.equal(run().status, 0);
  assert.equal(run([], { FAKE_SDK: '26.6' }).status, 0);
  assert.equal((await calls()).split('\n').filter(Boolean).length, 3);
});

test('targets the requested architecture', { skip: process.platform !== 'darwin' }, async t => {
  const { run, calls } = await fixture(t);
  const other = process.arch === 'arm64' ? 'x64' : 'arm64';
  const result = run([`darwin-${other}`], { EXPECT_TARGET: `${other === 'arm64' ? 'arm64' : 'x86_64'}-apple-macos26.0` });
  assert.equal(result.status, 0, result.stderr);
  assert.match(await calls(), new RegExp(`${other === 'arm64' ? 'arm64' : 'x86_64'}-apple-macos26\\.0`));
  const linux = run(['linux-x64']);
  assert.notEqual(linux.status, 0);
  assert.match(linux.stderr, /only for macOS/);
});

test('requires the macOS 26 SDK unless optional', { skip: process.platform !== 'darwin' }, async t => {
  const { target, run, calls } = await fixture(t);
  let result = run([], { FAKE_SDK: '15.5' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /requires the macOS 26 SDK \(found 15\.5\); install Command Line Tools 26 or later/);
  result = run(['--optional'], { FAKE_SDK: '15.5' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stderr, /skipped/);
  result = run(['--optional'], { PATH: '/nonexistent' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stderr, /found none/);
  assert.equal(await calls(), '');
  assert.equal(existsSync(target), false);
});

test('keeps the previous helper and removes staging files if compiling fails', { skip: process.platform !== 'darwin' }, async t => {
  const { root, source, target, run } = await fixture(t);
  assert.equal(run().status, 0);
  const marker = await readFile(join(target, '.manifest'), 'utf8');
  await writeFile(source, 'print("broken")\n');
  const failed = run(['--optional'], { FAIL_SWIFTC: '1' });
  assert.notEqual(failed.status, 0);
  assert.equal(await readFile(join(target, '.manifest'), 'utf8'), marker);
  assert.deepEqual(await readdir(join(root, 'frontend', 'vendor')), ['dictation']);
});
