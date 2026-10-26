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
import { access, constants, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const app = resolve(process.argv[2] ?? join(root, 'frontend', 'out', `ByteQuay-${process.platform}-${process.arch}`, 'ByteQuay.app'));
const helper = join(app, 'Contents', 'Resources', 'dictation', 'bytequay-dictate');
const run = (file, args, options = {}) => execFileSync(file, args, { encoding: 'utf8', ...options }).trim();

await access(helper, constants.X_OK);
assert.equal(run('/usr/bin/lipo', ['-archs', helper]), process.arch === 'arm64' ? 'arm64' : 'x86_64');
assert.match(run('/usr/bin/vtool', ['-show-build', helper]), /platform MACOS\s+minos 26\.0/);
assert.match(run('/usr/bin/plutil', ['-extract', 'NSMicrophoneUsageDescription', 'raw', join(app, 'Contents', 'Info.plist')]), /dictation/);

const macos = run('/usr/bin/sw_vers', ['-productVersion']);
if (Number.parseInt(macos, 10) >= 26) {
  const empty = await mkdtemp(join(tmpdir(), 'bytequay-packaged-dictation-'));
  try {
    const check = JSON.parse(run(helper, ['check'], { env: { PATH: empty, HOME: empty } }));
    assert.equal(check.type, 'check');
    assert.equal(typeof check.available, 'boolean');
    console.log(`Packaged dictation helper runs on macOS ${macos}: ${JSON.stringify(check)}`);
  } finally {
    await rm(empty, { recursive: true, force: true });
  }
} else {
  console.log(`Packaged dictation helper is in place; macOS ${macos} cannot run it, so its check was skipped`);
}
