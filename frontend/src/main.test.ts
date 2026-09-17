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
import { CI_RUN } from './agents/fixtures/ciAutofix';
import contracts from './agents/fixtures/agent-contracts-v1.json';
import { parseAgentTask } from './agents/contracts';
import type { ContextBuildViewDto, ContextDiscoveryDto, RepositoryNotebookDto } from './repository-context/contracts';
import type { AgentRuntimeStopImpactDto } from './types';

const desktop = vi.hoisted(() => ({
  listeners: new Map<string, () => Promise<void>>(),
  handlers: new Map<string, (...args: unknown[]) => Promise<unknown>>(),
}));

vi.mock('electron', () => ({
  app: {
    setName: vi.fn(), commandLine: { appendSwitch: vi.fn() }, requestSingleInstanceLock: () => true,
    on: (event: string, listener: () => Promise<void>) => desktop.listeners.set(event, listener),
    getAppPath: () => process.cwd(), getVersion: () => 'test', setAboutPanelOptions: vi.fn(),
  },
  ipcMain: { handle: (channel: string, handler: (...args: unknown[]) => Promise<unknown>) => desktop.handlers.set(channel, handler) },
  Menu: { setApplicationMenu: vi.fn(), buildFromTemplate: vi.fn() },
}));

vi.mock('./backendProcess', () => ({
  backendBase: () => 'http://127.0.0.1:9527', sessionToken: () => 'test-session', spawnBackend: vi.fn(),
  // Register the real IPC handlers, then stop startup before windows or background readers run.
  waitForBackendReady: async () => { throw new Error('End test startup'); },
}));

afterEach(() => { vi.unstubAllGlobals(); });

it('routes CI detail reads and replies to the authenticated CI API', async () => {
  await import('./main');
  const ready = desktop.listeners.get('ready');
  expect(ready).toBeDefined();
  await expect(ready?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify(CI_RUN), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch);
  const get = desktop.handlers.get('ci-autofix:get');
  const reply = desktop.handlers.get('ci-autofix:message');
  expect(get).toBeDefined();
  expect(reply).toBeDefined();
  await expect(get?.({}, 'run/one')).resolves.toEqual(CI_RUN);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/ci-autofix/runs/run%2Fone', expect.objectContaining({
    headers: { 'X-ByteQuay-Session': 'test-session' },
  }));
  await expect(reply?.({}, 'run/one', 'Use the compatibility fix', 'reply-key')).resolves.toEqual(CI_RUN);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/ci-autofix/runs/run%2Fone/messages', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ message: 'Use the compatibility fix' }),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session', 'Idempotency-Key': 'reply-key' },
  }));
  expect(fetch).toHaveBeenCalledTimes(2);
});

it('sends versioned local review actions and preserves the returned private review boundary', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  for (const action of ['start', 'sync', 'finish']) {
    const task = { ...contracts.task, localReviewHead: action === 'finish' ? null : 'a'.repeat(40) };
    const warning = 'Updated to the latest PR head. All local experiments were discarded because they conflicted with the update.';
    const response = action === 'sync' ? { task, warning } : task;
    const fetch = vi.fn(async () => new Response(JSON.stringify(response), { headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetch);
    const handler = desktop.handlers.get(`agent:task:local-review:${action}`);
    expect(handler).toBeDefined();
    await expect(handler?.({}, 'task/one', 'workspace/one', 7)).resolves.toEqual(action === 'sync' ? { task: parseAgentTask(task), warning } : parseAgentTask(task));
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(`http://127.0.0.1:9527/agent/tasks/task%2Fone/local-review/${action}?workspaceId=workspace%2Fone`,
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ expectedVersion: 7 }),
        headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  }
});

const emptyNotebook: RepositoryNotebookDto = { schemaVersion: 1, revision: 7, useInReviews: true, sources: [], activePreparation: null,
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, preparation: null, discoveryEvidenceRef: null }],
  entries: [], suppressedKnowledgeIds: [], sourceEvidence: [] };

it('routes notebook actions through authenticated workspace-scoped endpoints with exact revision and lesson bindings', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify(emptyNotebook), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context';
  await expect(desktop.handlers.get('repository-context:get')?.({}, 'workspace/one')).resolves.toEqual(emptyNotebook);
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  const actions: Array<{ action: string; path: string; method: string; request: Record<string, unknown> }> = [
    { action: 'configure', path: '/configuration', method: 'PUT', request: { expectedRevision: 7, useInReviews: false } },
    { action: 'edit', path: '/entries', method: 'PUT', request: { expectedRevision: 7, id: 'knowledge:project', scopeId: 'project',
      title: 'Project', summary: 'Summary', body: 'Corrected text', claims: [], sourceIds: [] } },
    { action: 'enabled', path: '/entries/enabled', method: 'PUT', request: { expectedRevision: 7, entryId: 'knowledge:project', enabled: false } },
    { action: 'remove', path: '/entries', method: 'DELETE', request: { expectedRevision: 7, entryId: 'knowledge:project' } },
    { action: 'reset', path: '/reset', method: 'POST', request: { expectedRevision: 7, scopeIds: ['project'] } },
    { action: 'lesson', path: '/lessons', method: 'POST', request: { expectedRevision: 7, scopeId: 'project',
      title: 'Lesson', summary: 'Summary', body: 'Conditional lesson', reviewId: 'review', runId: 'old-run', evidenceRefs: ['E2'], messageId: 'old-message' } },
  ];
  for (const { action, path, method, request } of actions) {
    await expect(desktop.handlers.get(`repository-context:${action}`)?.({}, 'workspace/one', request)).resolves.toEqual(emptyNotebook);
    expect(fetch).toHaveBeenLastCalledWith(base + path, expect.objectContaining({ method, body: JSON.stringify(request),
      headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  }
  expect(fetch).toHaveBeenCalledTimes(7);
});

it('rejects forged notebook mutation fields before sending and surfaces stale revisions without an automatic retry', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify({ message: 'Notebook changed; reload before saving.' }), { status: 409 }));
  vi.stubGlobal('fetch', fetch);
  const configure = desktop.handlers.get('repository-context:configure');
  expect(() => configure?.({}, 'workspace', { expectedRevision: 7, sourceEvidence: [] })).toThrow('unsupported request field');
  expect(fetch).not.toHaveBeenCalled();
  await expect(configure?.({}, 'workspace', { expectedRevision: 7, useInReviews: false })).rejects.toThrow('Notebook changed; reload before saving.');
  expect(fetch).toHaveBeenCalledTimes(1);
});

it('refuses malformed notebook responses instead of returning a fabricated empty notebook', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ...emptyNotebook, useInReviews: 'false' }))));
  await expect(desktop.handlers.get('repository-context:get')?.({}, 'workspace')).rejects.toThrow('Malformed repository context');
});

it('routes explicit learning and recovery through the authenticated purpose-bound API', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const view: ContextBuildViewDto = { buildRequestSequence: 0, notebookRevision: 7, canStart: true, build: null };
  const fetch = vi.fn(async () => new Response(JSON.stringify(view)));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/build';
  await expect(desktop.handlers.get('repository-context:build:get')?.({}, 'workspace/one')).resolves.toEqual(view);
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  const request = { requestKey: 'learn-key', expectedRevision: 7, expectedBuildSequence: 0, scopeIds: ['project'], history: [{ kind: 'PR', number: 18 }] };
  const cases = [
    { name: 'start', path: '', args: [request], body: request },
    { name: 'stop', path: '/stop', args: ['task'], body: { taskId: 'task' } },
    { name: 'stop-runtime', path: '/runtime/stop', args: ['task', 'generation'], body: { taskId: 'task', generation: 'generation' } },
    { name: 'fail', path: '/mark-failed', args: ['task', 'User stopped learning'], body: { taskId: 'task', reason: 'User stopped learning' } },
    { name: 'cleanup', path: '/cleanup', args: ['task'], body: { taskId: 'task' } },
  ];
  for (const item of cases) {
    await expect(desktop.handlers.get(`repository-context:build:${item.name}`)?.({}, 'workspace/one', ...item.args)).resolves.toEqual(view);
    expect(fetch).toHaveBeenLastCalledWith(base + item.path, expect.objectContaining({ method: 'POST', body: JSON.stringify(item.body),
      headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  }
  const impact: AgentRuntimeStopImpactDto = { taskId: 'task/one', generation: 'generation', activeTurnId: null, liveProcess: true };
  fetch.mockImplementation(async () => new Response(JSON.stringify(impact)));
  await expect(desktop.handlers.get('repository-context:build:stop-impact')?.({}, 'workspace/one', 'task/one')).resolves.toEqual(impact);
  expect(fetch).toHaveBeenLastCalledWith(base + '/runtime/stop-impact?taskId=task%2Fone', expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockClear();
  expect(() => desktop.handlers.get('repository-context:build:start')?.({}, 'workspace/one', { ...request, prompt: 'forged' })).toThrow('unsupported request field');
  expect(fetch).not.toHaveBeenCalled();
});

it('routes explicit discovery and chosen suggestions without learning or forwarding configuration evidence', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const found: ContextDiscoveryDto = { workspaceId: 'workspace/one', notebookRevision: 7, baselineSha: 'a'.repeat(40), repositoryFingerprint: 'c'.repeat(64), query: 'Iceberg', scopes: [], sources: [], history: [], gaps: [],
    inspected: { directories: 0, files: 0, pullRequests: 0, issues: 0 } };
  const fetch = vi.fn(async () => new Response(JSON.stringify(found))); vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/discovery';
  const request = { expectedRevision: 7, scopeIds: ['project'], query: 'Iceberg', includeRemote: false };
  await expect(desktop.handlers.get('repository-context:discover')?.({}, 'workspace/one', request)).resolves.toEqual(found);
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ method: 'POST', body: JSON.stringify(request),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockImplementation(async () => new Response(JSON.stringify(emptyNotebook)));
  const apply = { expectedRevision: 7, baselineSha: found.baselineSha, repositoryFingerprint: found.repositoryFingerprint, query: 'Iceberg', scopeIds: ['component'], sourceIds: ['docs'] };
  await expect(desktop.handlers.get('repository-context:discovery:apply')?.({}, 'workspace/one', apply)).resolves.toEqual(emptyNotebook);
  expect(fetch).toHaveBeenLastCalledWith(base + '/apply', expect.objectContaining({ method: 'POST', body: JSON.stringify(apply),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(() => desktop.handlers.get('repository-context:discovery:apply')?.({}, 'workspace/one', { ...apply, sourceEvidence: [] })).toThrow('unsupported request field');
  expect(fetch).toHaveBeenCalledTimes(2);
});
