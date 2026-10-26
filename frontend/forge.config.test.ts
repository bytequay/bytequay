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
import { execFileSync, execSync } from 'node:child_process';
import { join } from 'node:path';
import { expect, it, vi } from 'vitest';
import config from './forge.config';

vi.mock('node:child_process', async importOriginal => ({
  ...await importOriginal<typeof import('node:child_process')>(), execFileSync: vi.fn(), execSync: vi.fn(),
}));
vi.mock('node:fs', async importOriginal => {
  const fs = await importOriginal<typeof import('node:fs')>();
  return { ...fs, existsSync: (file: Parameters<typeof fs.existsSync>[0]) =>
    typeof file === 'string' && file.endsWith('bytequay-backend.jar') || fs.existsSync(file) };
});

it('builds the current backend even when a previous package left a JAR', async () => {
  const generate = config.hooks?.generateAssets;
  if (typeof generate !== 'function') throw new Error('Missing asset generation hook');
  await generate(config as Parameters<typeof generate>[0], 'darwin', 'arm64');
  expect(execSync).toHaveBeenCalledWith(expect.stringMatching(/^mvn .* clean package$/), {
    cwd: join(__dirname, '..', 'backend'), stdio: 'inherit',
  });
});

it('builds the dictation helper when it can, requires it in a package, and ships it with a mic usage string', async () => {
  const generate = config.hooks?.generateAssets;
  const prePackage = config.hooks?.prePackage;
  if (typeof generate !== 'function' || typeof prePackage !== 'function') throw new Error('Missing packaging hooks');
  const helper = (...flags: string[]) => [process.execPath, ['scripts/dictation-helper.mjs', 'darwin-arm64', ...flags],
    { cwd: join(__dirname, '..'), stdio: 'inherit' }];
  await generate(config as Parameters<typeof generate>[0], 'darwin', 'arm64');
  expect(execFileSync).toHaveBeenCalledWith(...helper('--optional'));
  vi.mocked(execFileSync).mockClear();
  await prePackage(config as Parameters<typeof prePackage>[0], 'darwin', 'arm64');
  expect(execFileSync).toHaveBeenCalledWith(...helper());
  expect(config.packagerConfig?.extraResource).toContain(join(__dirname, 'vendor', 'dictation'));
  expect(config.packagerConfig?.extendInfo).toMatchObject({ NSMicrophoneUsageDescription: expect.stringContaining('dictation') });
});
