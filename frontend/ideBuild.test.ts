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
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { build } from 'vite';
import { expect, it } from 'vitest';
import { encodingDetector, textmateImports } from './ideBuild';

it('preserves the TextMate namespace through the Vite 5 production import transform', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-textmate-'));
  try {
    const source = join(directory, '@codingame/monaco-vscode-textmate-service-override');
    await mkdir(source, { recursive: true });
    await writeFile(join(directory, 'namespace.js'), 'export const main = { applyStateStackDiff: () => "tokenized", INITIAL: "initial" };');
    await writeFile(join(source, 'controller.js'), `
      export async function check() {
        const { applyStateStackDiff, INITIAL } = await import('@codingame/monaco-vscode-api/_virtual/main').then(function (n) { return n.main; });
        return [applyStateStackDiff(), INITIAL];
      }
    `);
    await build({
      configFile: false,
      root: directory,
      logLevel: 'silent',
      plugins: [textmateImports()],
      resolve: { alias: { '@codingame/monaco-vscode-api/_virtual/main': join(directory, 'namespace.js') } },
      build: {
        target: 'esnext',
        modulePreload: { resolveDependencies: () => [] },
        rollupOptions: {
          input: join(source, 'controller.js'),
          preserveEntrySignatures: 'strict',
          output: { entryFileNames: 'entry.mjs', chunkFileNames: '[name].mjs' },
        },
      },
    });
    // A fresh Node process executes Vite's output without Vitest transforming it a second time.
    const output = execFileSync(process.execPath, ['--input-type=module', '--eval',
      'const { check } = await import(process.argv[1]); console.log(JSON.stringify(await check()));',
      pathToFileURL(join(directory, 'dist/entry.mjs')).href], { encoding: 'utf8' });
    expect(JSON.parse(output)).toEqual(['tokenized', 'initial']);
  }
  finally {
    await rm(directory, { recursive: true, force: true });
  }
});

it('guesses file encodings through the pinned VS Code service in a production browser bundle', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-encoding-'));
  const api = join(__dirname, 'node_modules/@codingame/monaco-vscode-api/vscode/src/vs');
  try {
    await writeFile(join(directory, 'entry.js'), `
      import { VSBuffer } from ${JSON.stringify(join(api, 'base/common/buffer.js'))};
      import { detectEncodingFromBuffer } from ${JSON.stringify(join(api, 'workbench/services/textfile/common/encoding.js'))};
      export async function check() {
        const detect = (bytes, candidates) => detectEncodingFromBuffer(
          { buffer: VSBuffer.wrap(Uint8Array.from(bytes)), bytesRead: bytes.length }, true, candidates);
        const utf8 = new TextEncoder().encode('你好世界。こんにちは世界。café déjà vu.');
        return Promise.all([
          detect(utf8),
          detect(utf8, ['utf8']),
          detect([255, 254, 97, 0, 98, 0]),
          detect(Array.from('const result = 42;\u005cn', c => c.charCodeAt(0))),
          detect(utf8, ['cp866']),
          detect([]),
          detect([0, 1, 2, 3, 0, 4, 5])
        ]);
      }
    `);
    await build({
      configFile: false,
      root: directory,
      logLevel: 'silent',
      plugins: [encodingDetector()],
      build: {
        target: 'esnext',
        modulePreload: { resolveDependencies: () => [] },
        rollupOptions: {
          input: join(directory, 'entry.js'),
          preserveEntrySignatures: 'strict',
          output: { entryFileNames: 'entry.mjs', chunkFileNames: '[name].mjs' },
        },
      },
    });
    const output = execFileSync(process.execPath, ['--input-type=module', '--eval',
      'const { check } = await import(process.argv[1]); console.log(JSON.stringify(await check()));',
      pathToFileURL(join(directory, 'dist/entry.mjs')).href], { encoding: 'utf8' });
    expect(JSON.parse(output)).toEqual([
      { seemsBinary: false, encoding: 'utf8' },
      { seemsBinary: false, encoding: 'utf8' },
      { seemsBinary: false, encoding: 'utf16le' },
      { seemsBinary: false, encoding: null },
      { seemsBinary: false, encoding: null },
      { seemsBinary: false, encoding: null },
      { seemsBinary: true, encoding: null },
    ]);
  }
  finally {
    await rm(directory, { recursive: true, force: true });
  }
});
