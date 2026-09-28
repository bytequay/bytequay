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
import { parseAgentReview } from './pulls/agent-review/contracts';
import { runningReviewFixture } from './pulls/agent-review/fixtures/review';
import type { ReviewCodeFileDto } from './pulls/code-tests/contracts';
import type { ContextBuildViewDto, ContextDiscoveryDto, ContextUsageActivitiesDto, ContextUsageOverviewDto, RepositoryNotebookDto } from './repository-context/contracts';
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
  ipcMain: { handle: (channel: string, handler: (...args: unknown[]) => Promise<unknown>) => desktop.handlers.set(channel, handler), on: vi.fn() },
  Menu: { setApplicationMenu: vi.fn(), buildFromTemplate: vi.fn() },
}));

vi.mock('./backendProcess', () => ({
  backendBase: () => 'http://127.0.0.1:9527', sessionToken: () => 'test-session', spawnBackend: vi.fn(),
  // Register the real IPC handlers, then stop startup before windows or background readers run.
  waitForBackendReady: async () => { throw new Error('End test startup'); },
}));

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('routes an inline check log read by PR and job ID', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify({ text: 'test failed' }), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch);
  await expect(desktop.handlers.get('prs:checks:log')?.({}, 'owner/repo', 42, 301)).resolves.toEqual({ text: 'test failed' });
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch).toHaveBeenCalledWith(new URL('http://127.0.0.1:9527/prs/checks/log?repo=owner%2Frepo&number=42&jobId=301'), expect.any(Object));
});

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

it.each([
  { channel: 'review:start', args: ['workspace-1', 'trinodb/trino', 31102] },
  { channel: 'review:resume', args: ['review-1'] },
  { channel: 'review:update', args: ['review-1'] },
  { channel: 'review:code:sync', args: ['review-1', 'a'.repeat(40)] },
])('allows a slow Git synchronization to finish through $channel without retrying it', async ({ channel, args }) => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  vi.useFakeTimers();
  // Native AbortSignal timers do not use the fake clock. Keep real abort behavior with test-controlled time.
  vi.spyOn(AbortSignal, 'timeout').mockImplementation(milliseconds => {
    const controller = new AbortController();
    setTimeout(() => controller.abort(new DOMException('Request timed out', 'TimeoutError')), milliseconds);
    return controller.signal;
  });
  const review = runningReviewFixture();
  const fetch = vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(init.signal?.reason), { once: true });
    setTimeout(() => resolve(new Response(JSON.stringify(review), {
      headers: { 'Content-Type': 'application/json' },
    })), 65_000);
  }));
  vi.stubGlobal('fetch', fetch);
  const handler = desktop.handlers.get(channel);
  expect(handler).toBeDefined();
  const result = handler?.({}, ...args).then(value => ({ value }), error => ({ error }));

  await vi.advanceTimersByTimeAsync(65_000);

  expect(await result).toEqual({ value: parseAgentReview(review) });
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({ method: 'POST' }));
});

it('still reports a refused review update without retrying it', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify({ message: 'The review task is busy' }), {
    status: 409, headers: { 'Content-Type': 'application/json' },
  }));
  vi.stubGlobal('fetch', fetch);

  await expect(desktop.handlers.get('review:update')?.({}, 'review-1')).rejects.toThrow('The review task is busy');
  expect(fetch).toHaveBeenCalledTimes(1);
});

it('routes Code & Tests reads and writes to the review or the development task that hosts the working copy', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const task = { kind: 'task', taskId: 'task/one', workspaceId: 'workspace/one' };
  const review = { kind: 'review', reviewId: 'review/one' };
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  const output = { text: 'ok', nextOffset: 2, skippedBytes: 0, complete: true };
  const fetch = vi.fn(async (_url: string, _init?: RequestInit) => json(output));
  vi.stubGlobal('fetch', fetch);
  const handler = (channel: string) => {
    const found = desktop.handlers.get(channel);
    expect(found).toBeDefined();
    // Electron turns a handler's synchronous throw into a rejected invoke, as this async wrapper does.
    return async (...args: unknown[]) => found?.({}, ...args);
  };

  const codeFile: ReviewCodeFileDto = { path: 'src/Example.java', content: 'class Example {}', contentHash: 'hash', editable: false,
    readOnlyReason: null, unavailableReason: null, prPatch: null, lineChanges: [] };
  fetch.mockResolvedValueOnce(json(codeFile));
  await expect(handler('code:file')(task, codeFile.path, 'a'.repeat(40))).resolves.toEqual(codeFile);
  expect(fetch).toHaveBeenLastCalledWith(`http://127.0.0.1:9527/agent/tasks/task%2Fone/code/file?workspaceId=workspace%2Fone&path=src%2FExample.java&expectedRevision=${'a'.repeat(40)}`,
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockResolvedValueOnce(json(codeFile));
  await expect(handler('code:file')(review, codeFile.path)).resolves.toEqual(codeFile);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/agent-review/review%2Fone/code/file?path=src%2FExample.java', expect.any(Object));
  fetch.mockResolvedValueOnce(json({ message: 'The working copy changed. Refresh its files.' }, 409));
  await expect(handler('code:file')(review, codeFile.path, 'a'.repeat(40))).rejects.toThrow('The working copy changed. Refresh its files.');

  await expect(handler('code:runs:output')(task, 'run/1', 12.7)).resolves.toEqual(output);
  expect(fetch).toHaveBeenLastCalledWith(
    'http://127.0.0.1:9527/agent/tasks/task%2Fone/code/runs/run%2F1/output?workspaceId=workspace%2Fone&after=12',
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await expect(handler('code:runs:output')(review, 'run/1', 0)).resolves.toEqual(output);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/agent-review/review%2Fone/code/runs/run%2F1/output?after=0', expect.any(Object));

  fetch.mockResolvedValueOnce(json({ reason: 'No Maven wrapper (mvnw) in this working copy', action: 'OPEN_TERMINAL' }, 422));
  await expect(handler('code:runs:start')(task, 'core/src/test/java/io/x/TestAccess.java', null))
    .resolves.toEqual({ refusal: { reason: 'No Maven wrapper (mvnw) in this working copy', action: 'OPEN_TERMINAL' } });
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/agent/tasks/task%2Fone/code/runs?workspaceId=workspace%2Fone',
    expect.objectContaining({ method: 'POST', body: JSON.stringify({ path: 'core/src/test/java/io/x/TestAccess.java', method: null }) }));

  fetch.mockResolvedValueOnce(json({ currentHash: 'h2', currentContent: 'disk' }, 409));
  await expect(handler('code:save')(task, 'core/src/main/java/io/x/Access.java', 'draft', 'h1'))
    .resolves.toEqual({ conflict: { currentHash: 'h2', currentContent: 'disk' } });
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/agent/tasks/task%2Fone/code/file?workspaceId=workspace%2Fone',
    expect.objectContaining({ method: 'PUT', body: JSON.stringify({ path: 'core/src/main/java/io/x/Access.java', content: 'draft', expectedHash: 'h1' }) }));

  fetch.mockResolvedValueOnce(json({ message: "The task's worktree was removed" }, 409));
  await expect(handler('code:save')(task, 'a.java', 'draft', 'h1')).rejects.toThrow("The task's worktree was removed");

  const syncedReview = runningReviewFixture();
  fetch.mockResolvedValueOnce(json(syncedReview));
  await expect(handler('review:code:sync')('review/one', 'a'.repeat(40))).resolves.toEqual(parseAgentReview(syncedReview));
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/agent-review/review%2Fone/code/sync',
    expect.objectContaining({ method: 'POST', body: JSON.stringify({ expectedHead: 'a'.repeat(40) }),
      headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));

  const calls = fetch.mock.calls.length;
  await expect(handler('code:files')({ kind: 'task', taskId: 'task/one' })).rejects.toThrow('Malformed Code & Tests host');
  expect(fetch).toHaveBeenCalledTimes(calls);
});

const emptyNotebook: RepositoryNotebookDto = { schemaVersion: 1, revision: 7, useInReviews: true, useInDevelopment: true, sources: [], activePreparation: null,
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, preparation: null, discoveryEvidenceRef: null }],
  entries: [], pendingDrafts: [], suppressedKnowledgeIds: [], sourceEvidence: [] };

it('reads bounded usage and a selected note’s activities through authenticated workspace endpoints', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const overview: ContextUsageOverviewDto = { trackingSince: null, window: '30d', coverage: 'RETAINED_TASKS_AND_REVIEWS',
    summary: { eligibleDevelopmentTasks: 0, eligibleReviewRuns: 0, contextOffTasks: 0, tasksProvidedOrOpened: 0,
      activeNotesWithoutConfirmedDeliveryOrOpen: 0, scopesDemandedWhileMissing: 0, scopesStillMissingAfterDemand: 0,
      searchesWithNoMatchingScope: 0, searchesWithScopeButNoNote: 0 },
    byTaskAndNoteKind: [], entries: [], scopeGaps: [], hasMoreEntries: false, hasMoreScopeGaps: false };
  const activities: ContextUsageActivitiesDto = { entryId: 'knowledge:path:plugin/trino-iceberg/', activities: [{ taskId: 'task', reviewRunId: null, turnId: 'turn',
    taskKind: 'DEVELOPMENT', entryVersion: 2, stage: 'OPENED', delivery: 'CONFIRMED', occurredAt: '2026-09-02T00:00:00Z',
    receiptRef: 'context-read:1' }], hasMore: false };
  const fetch = vi.fn(async () => new Response(JSON.stringify(overview)));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/usage';
  await expect(desktop.handlers.get('repository-context:usage')?.({}, 'workspace/one', '30d', 0)).resolves.toEqual(overview);
  expect(fetch).toHaveBeenLastCalledWith(base + '?window=30d&page=0',
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockImplementation(async () => new Response(JSON.stringify(activities)));
  await expect(desktop.handlers.get('repository-context:usage:entry')?.({}, 'workspace/one', activities.entryId, '30d', 1)).resolves.toEqual(activities);
  expect(fetch).toHaveBeenLastCalledWith(base + '/activities?entryId=knowledge%3Apath%3Aplugin%2Ftrino-iceberg%2F&window=30d&page=1',
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockClear();
  expect(() => desktop.handlers.get('repository-context:usage')?.({}, 'workspace/one', 'year', 0)).toThrow('Malformed repository context');
  expect(() => desktop.handlers.get('repository-context:usage:entry')?.({}, 'workspace/one', 'knowledge:project', '30d', -1)).toThrow('Malformed repository context');
  expect(fetch).not.toHaveBeenCalled();
  fetch.mockImplementation(async () => new Response(JSON.stringify({ ...overview, window: 'all' })));
  await expect(desktop.handlers.get('repository-context:usage')?.({}, 'workspace/one', '30d', 0)).rejects.toThrow('Malformed repository context');
});

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
    { action: 'configure', path: '/configuration', method: 'PUT', request: { expectedRevision: 7, useInDevelopment: false } },
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
  expect(fetch).toHaveBeenCalledTimes(8);
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

it('routes Jira reads and versioned writes through the authenticated native API', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify({ issue: { key: 'ENG-7' } }), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch);
  const location = { connectionId: 'connection/one', cloudId: 'site/one', issueId: '7' };
  const base = 'http://127.0.0.1:9527/api/jira/connections/connection%2Fone/sites/site%2Fone';
  await desktop.handlers.get('jira:config')?.({});
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/config', expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:connect')?.({});
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections', expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  const token = { siteUrl: 'https://example.atlassian.net', email: 'alex@example.com', token: 'fixture-token' };
  await desktop.handlers.get('jira:token:connect')?.({}, token);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections/token', expect.objectContaining({ method: 'POST', body: JSON.stringify(token), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:token:replace')?.({}, 'connection/one', token);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections/connection%2Fone/token', expect.objectContaining({ method: 'PUT', body: JSON.stringify(token), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:reconnect')?.({}, 'connection/one');
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections/connection%2Fone/reconnect', expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issues')?.({}, { ...location, boardId: 828, sprintId: 11, mine: true }, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/refresh?boardId=828&mine=true&sprintId=11`, expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issues')?.({}, { ...location, boardId: 828, sprintId: null, mine: false, completed: true }, false);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues?boardId=828&mine=false&completed=true`, expect.objectContaining({ method: 'GET', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue')?.({}, location, true, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/refresh?automatic=true`, expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue')?.({}, location, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/refresh`, expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:key:refresh')?.({}, 'connection/one', 'site/one', 'ENG-7');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/keys/ENG-7/refresh`, expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue:update')?.({}, location, { labels: ['connector'] }, '2026-09-26T00:00:00Z');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7`, expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ fields: { labels: ['connector'] }, expectedUpdatedAt: '2026-09-26T00:00:00Z' }), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue:comment')?.({}, location, 'Ready for review', 'operation-1');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/comments`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ body: 'Ready for review', operationId: 'operation-1', parentId: null }), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue:comment')?.({}, location, 'Agreed', 'operation-2', '5');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/comments`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ body: 'Agreed', operationId: 'operation-2', parentId: '5' }) }));
  await desktop.handlers.get('jira:comment:edit')?.({}, location, '5', 'Updated', '2026-09-26T01:00:00.000+0000');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/comments/5`, expect.objectContaining({ method: 'PUT', body: JSON.stringify({ body: 'Updated', expectedUpdated: '2026-09-26T01:00:00.000+0000' }), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue:workspace')?.({}, location, 'workspace-one');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/workspace`, expect.objectContaining({ method: 'PUT', body: JSON.stringify({ workspaceId: 'workspace-one' }), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue:child-types')?.({}, location);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/child-types`, expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue:child')?.({}, location, '10003', 'Add the flag');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/children`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ issueTypeId: '10003', summary: 'Add the flag' }), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:link-types')?.({}, 'connection/one', 'site/one');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/link-types`, expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:issue:link')?.({}, location, '10000', false, 'ENG-8');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/links`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ typeId: '10000', outward: false, key: 'ENG-8' }), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  await desktop.handlers.get('jira:connections')?.({});
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections', expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
});
