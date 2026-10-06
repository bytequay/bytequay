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
import { expect, it, vi } from 'vitest';
import { runBrowserFixture } from '../fixtures/browserFixture';

// Exercise the real React lifecycle with the fixture's editor; code-preview creation is outside this regression.
vi.mock('vite', async importOriginal => {
  const vite = await importOriginal<typeof import('vite')>();
  return { ...vite, build: (config: import('vite').InlineConfig) => vite.build({ ...config, plugins: [{
    name: 'blame-editor-fixture',
    enforce: 'pre',
    resolveId: source => source === 'monaco-editor' || source.startsWith('@codingame/monaco-vscode-api/') ? '\0blame-editor-fixture' : undefined,
    load: id => id === '\0blame-editor-fixture' ? 'export const editor = {}, MouseTargetType = {}, EditorOption = {};' : undefined,
  }] }) };
});

it('keeps blame installed across cloned context updates and invalidates it for changed source', async () => {
  const result = await runBrowserFixture<{ contextUpdates: number; reads: number }>({
    prefix: 'bytequay-blame-', entry: 'src/ide/fixtures/editorNavigationBrowser.tsx', global: 'BlameTests',
  });
  expect(result).toEqual({ contextUpdates: 20, reads: 4 });
}, 30_000);
