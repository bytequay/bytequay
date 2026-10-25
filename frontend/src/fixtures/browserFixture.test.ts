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
import { access, mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import { expect, it } from 'vitest';
import { buildBrowserFixture, deadline, runBrowserFixture } from './browserFixture';

it('reuses a bundle while isolating browser state and cleaning up successful and failed runs', async () => {
  const source = await mkdtemp(join(tmpdir(), 'bytequay-fixture-source-'));
  let fixture: Awaited<ReturnType<typeof buildBrowserFixture>> | undefined;
  try {
    const entry = join(source, 'renderer.js');
    await writeFile(entry, `let runs = 0;
      export function run() {
        const previous = localStorage.getItem('previous');
        localStorage.setItem('previous', 'seen');
        return { runs: ++runs, previous };
      }`);
    fixture = await buildBrowserFixture({ prefix: 'bytequay-fixture-reuse-', entry, global: 'Fixture',
      body: `const results = { profile: app.getPath('userData'), value: await window.webContents.executeJavaScript('Fixture.run()') };` });
    // A second build would now fail; every run must use the already-built bundle.
    await rm(entry);
    const first = await fixture.run<{ profile: string; value: { runs: number; previous: string | null } }>();
    await expect(access(dirname(first.profile))).rejects.toMatchObject({ code: 'ENOENT' });
    await expect(fixture.run({ body: 'throw new Error("intentional fixture failure");' })).rejects.toThrow('intentional fixture failure');
    const second = await fixture.run<typeof first>();
    expect(first.value).toEqual({ runs: 1, previous: null });
    expect(second.value).toEqual(first.value);
    expect(second.profile).not.toBe(first.profile);
    const bundle = dirname(dirname(first.profile));
    expect((await readdir(bundle)).sort()).toEqual(['renderer.js']);
    await fixture.dispose();
    await expect(access(bundle)).rejects.toMatchObject({ code: 'ENOENT' });
  }
  finally {
    await fixture?.dispose();
    await rm(source, { recursive: true, force: true });
  }
}, deadline(20_000));

it('removes an incomplete bundle when compilation fails', async () => {
  const source = await mkdtemp(join(tmpdir(), 'bytequay-fixture-invalid-'));
  try {
    const entry = join(source, 'invalid.js');
    await writeFile(entry, 'export function broken(');
    await expect(buildBrowserFixture({ prefix: `${basename(source)}/bundle-`, entry, global: 'Fixture' })).rejects.toThrow();
    expect(await readdir(source)).toEqual(['invalid.js']);
  }
  finally { await rm(source, { recursive: true, force: true }); }
});

it('returns complete results larger than the stdout pipe buffer', async () => {
  const source = await mkdtemp(join(tmpdir(), 'bytequay-fixture-output-'));
  try {
    const entry = join(source, 'renderer.js');
    await writeFile(entry, 'export function run() { return "result".repeat(100_000); }');
    expect(await runBrowserFixture<string>({ prefix: 'bytequay-fixture-output-app-', entry, global: 'Fixture' })).toBe('result'.repeat(100_000));
  }
  finally { await rm(source, { recursive: true, force: true }); }
}, deadline(20_000));
