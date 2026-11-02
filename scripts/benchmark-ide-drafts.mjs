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
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const frontend = join(root, 'frontend');
const require = createRequire(join(frontend, 'package.json'));
const { build } = require('esbuild');
const directory = join(frontend, 'vendor/ide-benchmark/retained-drafts');
const output = join(frontend, 'test-results/ide-idle-cleanup');
await mkdir(directory, { recursive: true });
await mkdir(output, { recursive: true });
const files = {
  before: join(frontend, 'vendor/ide-benchmark/idle-cleanup-before/drafts.ts'),
  after: join(frontend, 'src/ide/drafts.ts'),
};
const hashes = {};
for (const [variant, file] of Object.entries(files)) {
  const source = await readFile(file, 'utf8');
  hashes[variant] = createHash('sha256').update(source).digest('hex');
  await writeFile(join(directory, `${variant}.ts`), source);
  await build({ stdin: { contents: source, loader: 'ts', resolveDir: dirname(file) }, bundle: true,
    platform: 'node', format: 'cjs', outfile: join(directory, `${variant}.cjs`), logLevel: 'silent' });
}
const runner = join(directory, 'runner.cjs');
await writeFile(runner, `
const assert = require('node:assert/strict');
const { trackSourceDrafts } = require(process.argv[2]);
const emitter = () => {
  const listeners = new Set();
  return { event: listener => { listeners.add(listener); return { dispose: () => listeners.delete(listener) }; },
    fire: value => { for (const listener of listeners) listener(value); } };
};
const opened = emitter(), changed = emitter(), saved = emitter();
const documents = [];
const storage = new Map();
global.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) };
const runtime = { workspace: { path: '/generated' },
  api: { workspace: { textDocuments: documents, onDidOpenTextDocument: opened.event, onDidChangeTextDocument: changed.event } },
  saves: { isDirty: () => false, onDidSave: saved.event } };
const tracker = trackSourceDrafts(runtime, message => { throw new Error(message); });
const collect = async openedCount => {
  await new Promise(resolve => setImmediate(resolve));
  for (let i = 0; i < 3; i++) global.gc();
  const memory = process.memoryUsage();
  return { openedCount, heapUsedBytes: memory.heapUsed, rssBytes: memory.rss, externalBytes: memory.external };
};
const browse = (start, count) => {
  for (let index = start; index < start + count; index++) {
    const bytes = Buffer.alloc(65536, 65 + index % 26);
    bytes.write(String(index).padStart(16, '0'));
    const text = bytes.toString('utf8');
    const document = { uri: { scheme: 'vscode-remote', path: '/generated/File' + index + '.java' }, getText: () => text };
    documents.push(document); opened.fire(document); documents.pop();
  }
};
(async () => {
  const samples = [await collect(0)];
  for (let batch = 0; batch < 4; batch++) { browse(batch * 250, 250); samples.push(await collect((batch + 1) * 250)); }
  assert.equal(documents.length, 0); assert.equal(storage.size, 0);
  const retainedHeapBytes = samples.at(-1).heapUsedBytes - samples[0].heapUsedBytes;
  const result = { retainedHeapBytes, samples, openDocuments: documents.length, persistedDrafts: storage.size };
  tracker.dispose();
  process.stdout.write(JSON.stringify(result));
})().catch(error => { console.error(error); process.exitCode = 1; });
`);
const reports = [];
for (let run = 1; run <= 3; run++) for (const variant of ['before', 'after']) {
  const report = JSON.parse(execFileSync(process.execPath, ['--expose-gc', runner, join(directory, `${variant}.cjs`)], { encoding: 'utf8' }));
  reports.push({ run, variant, ...report });
  console.log(JSON.stringify({ run, variant, retainedHeapMiB: report.retainedHeapBytes / 1024 ** 2 }));
}
assert.equal(reports.length, 6);
await writeFile(join(output, 'drafts.json'), JSON.stringify({
  scope: 'Diagnostic retained JavaScript heap of the production draft tracker, with synthetic VS Code document events. Forced GC; not native editor or whole-app RSS.',
  workload: { uniqueDocuments: 1000, textBytesPerDocument: 65536, batches: 4, openDocumentsAtEnd: 0, dirtyDocuments: 0, pairedTrials: 3 },
  sourceHashes: hashes, driverSha256: createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex'), reports,
}, null, 2));
