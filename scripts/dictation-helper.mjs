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
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// --optional lets `npm start` go on without the macOS 26 SDK; dictation is then unavailable in that build.
const optional = process.argv.includes('--optional');
const platform = process.argv.slice(2).find(arg => !arg.startsWith('--')) ?? `${process.platform}-${process.arch}`;
const arch = { 'darwin-arm64': 'arm64', 'darwin-x64': 'x86_64' }[platform];
if (arch === undefined) throw new Error(`The dictation helper builds only for macOS, not ${platform}`);

const xcrun = args => execFileSync('xcrun', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
let sdk;
try {
  sdk = xcrun(['--show-sdk-version']);
} catch {
  sdk = 'none';
}
if (!(Number.parseInt(sdk, 10) >= 26)) {
  const message = `Building the dictation helper requires the macOS 26 SDK (found ${sdk}); install Command Line Tools 26 or later`;
  if (!optional) {
    console.error(message);
    process.exit(1);
  }
  console.warn(`dictation-helper: skipped. ${message}`);
  process.exit(0);
}

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'frontend', 'dictation', 'Dictate.swift');
const options = ['-O', '-parse-as-library', '-swift-version', '6', '-target', `${arch}-apple-macos26.0`];
const marker = createHash('sha256')
  .update(JSON.stringify({ platform, sdk, swift: xcrun(['swiftc', '--version']), options, source: await readFile(source, 'utf8') }))
  .digest('hex');
const vendor = join(root, 'frontend', 'vendor');
const target = join(vendor, 'dictation');
const binary = join(target, 'bytequay-dictate');
const current = await readFile(join(target, '.manifest'), 'utf8').catch(error => {
  if (error.code !== 'ENOENT') throw error;
});
if (current === marker && existsSync(binary)) {
  console.log(`dictation-helper: ${platform} helper already built`);
  process.exit(0);
}

await mkdir(vendor, { recursive: true });
const staging = await mkdtemp(join(vendor, '.dictation-'));
try {
  const built = join(staging, 'dictation');
  await mkdir(built);
  execFileSync('xcrun', ['swiftc', ...options, source, '-o', join(built, 'bytequay-dictate')], { stdio: 'inherit' });
  await writeFile(join(built, '.manifest'), marker);
  await rm(target, { recursive: true, force: true });
  await rename(built, target);
  console.log(`dictation-helper: built ${platform}`);
} finally {
  await rm(staging, { recursive: true, force: true });
}
