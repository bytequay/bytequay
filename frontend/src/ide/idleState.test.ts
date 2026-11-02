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
  const state = { api: { workspace: { textDocuments: [{ isDirty: false, uri: {} }] }, debug: {} },
    saves: { pending: false, isDirty: () => false }, executions: { starting: false, run: null },
    terminals: { instances: [] as { hasChildProcesses: boolean }[] }, tests: { results: [] } };
  const runtime = state as unknown as IdeRuntime;
  expect(idleState(runtime)).toEqual({ dirty: 0, busy: false });
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
  expect(idleState(runtime).dirty).toBe(1);
});
