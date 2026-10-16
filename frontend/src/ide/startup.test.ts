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
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { IdeConnection } from './contracts';

const mocks = vi.hoisted(() => ({ start: vi.fn(), queries: vi.fn() }));
vi.mock('./runtime', () => ({ startRuntime: mocks.start }));
vi.mock('./semanticQueries', () => ({ installSemanticQueries: mocks.queries }));
const connection: IdeConnection = { taskId: 'task', sessionId: 'session', authority: '127.0.0.1:5210', root: '/task',
  bundleRoot: '/bundle', cacheRoot: '/cache', connectionToken: 'token', commit: 'a'.repeat(40) };

beforeEach(() => {
  vi.resetModules(); mocks.start.mockReset(); mocks.queries.mockReset();
  vi.stubGlobal('window', {}); vi.stubGlobal('document', { body: {} });
});
afterEach(() => { vi.unstubAllGlobals(); });

it('shares pending startup and retains the editor and query listener across remounts and module refreshes', async () => {
  const runtime = { workspace: { path: '/task' }, unsavedText: 'local draft' };
  let finish!: (value: typeof runtime) => void;
  mocks.start.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const { startWorkbench } = await import('./startup');
  const first = startWorkbench(connection);
  expect(startWorkbench(connection)).toBe(first);
  vi.resetModules();
  const refreshed = await import('./startup');
  expect(refreshed.startWorkbench(connection)).toBe(first);
  expect(mocks.start).toHaveBeenCalledOnce();
  finish(runtime);
  expect(await first).toEqual({ runtime, connection });
  vi.resetModules();
  expect(await (await import('./startup')).startWorkbench(connection)).toEqual({ runtime, connection });
  expect(mocks.start).toHaveBeenCalledOnce();
  expect(mocks.queries).toHaveBeenCalledOnce();
  expect(mocks.queries).toHaveBeenCalledWith(runtime.workspace);
});

it('retains the original startup failure instead of retrying partially initialized native services', async () => {
  mocks.start.mockRejectedValue(new Error('Project connection failed'));
  const { startWorkbench } = await import('./startup');
  await expect(startWorkbench(connection)).rejects.toThrow('Project connection failed');
  vi.resetModules();
  await expect((await import('./startup')).startWorkbench(connection)).rejects.toThrow('Project connection failed');
  expect(mocks.start).toHaveBeenCalledOnce();
  expect(mocks.queries).not.toHaveBeenCalled();
});
