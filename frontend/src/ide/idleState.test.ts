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
import { afterEach, expect, it, vi } from 'vitest';
import type { IdeRuntime } from './runtime';
import { idleState } from './idleState';

afterEach(() => { vi.unstubAllGlobals(); });

it('keeps startup restoration, silent native shells, execution preparation and writes out of idle cleanup', () => {
  vi.stubGlobal('document', { querySelectorAll: () => [] });
  const state = { api: { workspace: { textDocuments: [{ isDirty: false, uri: { scheme: 'vscode-remote', path: '/task/src/main.go' } }] }, debug: {} },
    workspace: { path: '/task' }, saves: { pending: false, isDirty: () => false }, executions: { starting: false, run: null },
    terminals: { instances: [] as { hasChildProcesses: boolean }[] }, tests: { results: [] } };
  const runtime = state as unknown as IdeRuntime;
  expect(idleState(runtime)).toEqual({ unsaved: { files: [], drafts: 0 }, busy: false });
  expect(idleState(runtime, true).busy).toBe(true);
  state.terminals.instances.push({ hasChildProcesses: false });
  expect(idleState(runtime).busy).toBe(true);
  state.terminals.instances = [];
  state.executions.starting = true;
  expect(idleState(runtime).busy).toBe(true);
  state.executions.starting = false;
  state.saves.pending = true;
  expect(idleState(runtime).busy).toBe(true);
  state.saves.pending = false;
  state.api.workspace.textDocuments[0].isDirty = true;
  expect(idleState(runtime).unsaved.files).toEqual([]);
  state.saves.isDirty = () => true;
  expect(idleState(runtime).unsaved.files).toEqual(['src/main.go']);
  expect(idleState(runtime, false, () => false).unsaved.files).toEqual([]);
});

it('names changed documents separately from text drafts, including temporarily disabled drafts', () => {
  const textarea = (value: string, options: { editor?: boolean; readOnly?: boolean; disabled?: boolean } = {}) => ({
    value, readOnly: options.readOnly ?? false, disabled: options.disabled ?? false, classList: { contains: () => options.editor ?? false },
  });
  vi.stubGlobal('document', { querySelectorAll: () => [textarea('Comment'), textarea('Another draft'), textarea('  '),
    textarea('Editor contents', { editor: true }), textarea('Read only diff', { readOnly: true }), textarea('Disabled', { disabled: true })] });
  const runtime = { api: { workspace: { textDocuments: [
    { uri: { scheme: 'vscode-remote', path: '/task/src/main.go' } },
    { uri: { scheme: 'vscode-remote', path: '/task/src/main.go' } },
    { uri: { scheme: 'untitled', path: 'Untitled-1' } },
  ] }, debug: {} }, workspace: { path: '/task' }, saves: { pending: false, isDirty: () => true },
  executions: { starting: false, run: null }, terminals: { instances: [] }, tests: { results: [] } } as unknown as IdeRuntime;
  expect(idleState(runtime)).toEqual({ unsaved: { files: ['src/main.go', 'Untitled-1'], drafts: 3 }, busy: false });
});
