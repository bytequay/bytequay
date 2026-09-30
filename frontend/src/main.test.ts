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
import type { ContextBuildViewDto, ContextMergedCandidateDto, ContextUsageOverviewDto, RepositoryNotebookDto } from './repository-context/contracts';
import type { AgentRuntimeStopImpactDto } from './types';
import type { TeamActivitySnapshot, TeamDirectory } from './teams/types';

const desktop = vi.hoisted(() => ({
  listeners: new Map<string, () => Promise<void>>(),
  handlers: new Map<string, (...args: unknown[]) => Promise<unknown>>(),
  accountNotifications: [vi.fn(), vi.fn()],
}));

vi.mock('electron', () => ({
  BrowserWindow: { getAllWindows: () => desktop.accountNotifications.map(send => ({ isDestroyed: () => false, webContents: { isDestroyed: () => false, send } })) },
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
  desktop.accountNotifications.forEach(send => send.mockClear());
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it.each(['pat:save', 'pat:delete', 'github:cli-import'])('invalidates every window after successful %s, preserving caches after failure', async channel => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch);
  await desktop.handlers.get(channel)?.({}, 'test-token');
  desktop.accountNotifications.forEach(send => {
    expect(send).toHaveBeenCalledWith('github:account-changed');
    send.mockClear();
  });
  fetch.mockImplementation(async () => new Response('Invalid credential', { status: 401 }));
  await expect(desktop.handlers.get(channel)?.({}, 'test-token')).rejects.toThrow();
  desktop.accountNotifications.forEach(send => expect(send).not.toHaveBeenCalled());
});

it('reads activity titles through the local stored-PR endpoint without an import IPC', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async (_url: string | URL) => new Response('null', { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch);

  expect(desktop.handlers.has('repos:pull')).toBe(false);
  await expect(desktop.handlers.get('prs:stored')?.({}, 'example/harbor', 32005)).resolves.toBeNull();
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(String(fetch.mock.calls[0][0])).toBe('http://127.0.0.1:9527/prs/stored?repo=example%2Fharbor&number=32005');
});

it('authenticates and validates Team IPC, with time for a bounded collection pass', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const directory: TeamDirectory = { accountLogin: 'viewer', circles: [] };
  const totals = { prsOpened: 0, authoredPrsMerged: 0, mergesPerformed: 0, prsReviewed: 0, issuesOpened: 0, issuesClosed: 0, comments: 0,
    directInteractions: 0, sharedPullRequests: 0, sharedIssues: 0 };
  const activity: TeamActivitySnapshot = {
    accountLogin: 'viewer', events: [], updates: 0, workItems: 0, people: [], totals, days: [{ date: '2026-09-29', updates: 0, collected: false }],
    coverage: { partial: true, observedSince: null, lastRefreshedAt: null, errors: [], memberCount: 0, membersScanned: 0, repositoryCount: 0, repositoriesScanned: 0 },
  };
  const contributors = [{ id: 42, login: 'alice', avatarUrl: '', bot: false }];
  const fetch = vi.fn(async (url: string | URL) => new Response(JSON.stringify(String(url).includes('/refresh?') ? activity
    : String(url).endsWith('/contributors') ? contributors : directory)));
  vi.stubGlobal('fetch', fetch);
  const member = { githubId: 42, login: 'alice' };
  await expect(desktop.handlers.get('teams:create')?.({}, { name: ' Internal ', description: ' Our reviewers ', avatar: { color: 'gray', shape: 'square' }, repositories: ['Owner/Repo'], members: [member] })).resolves.toEqual(directory);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/teams', expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ name: 'Internal', description: 'Our reviewers', avatar: { color: 'gray', shape: 'square' }, repositories: ['owner/repo'], members: [member] }),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' },
  }));
  await expect(desktop.handlers.get('teams:contributors')?.({})).resolves.toEqual(contributors);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/teams/contributors', expect.objectContaining({
    headers: { 'X-ByteQuay-Session': 'test-session' },
  }));
  await desktop.handlers.get('teams:members:add')?.({}, 'circle-1', member);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/teams/circle-1/members', expect.objectContaining({ method: 'POST',
    body: JSON.stringify(member), headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' },
  }));
  await desktop.handlers.get('teams:members:remove')?.({}, 'circle-1', 42);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/teams/circle-1/members/42', expect.objectContaining({
    method: 'DELETE', headers: { 'X-ByteQuay-Session': 'test-session' },
  }));
  const timeout = vi.spyOn(AbortSignal, 'timeout');
  await expect(desktop.handlers.get('teams:refresh')?.({}, { circleId: 'circle-1', memberId: 42, days: 30 })).resolves.toEqual(activity);
  expect(fetch).toHaveBeenLastCalledWith(new URL('http://127.0.0.1:9527/api/teams/refresh?circleId=circle-1&memberId=42&days=30&limit=100'),
    expect.objectContaining({ method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' } }));
  expect(timeout).toHaveBeenCalledWith(15 * 60_000);
  const calls = fetch.mock.calls.length;
  expect(() => desktop.handlers.get('teams:members:remove')?.({}, '../activity', 42)).toThrow('Invalid circle ID');
  expect(() => desktop.handlers.get('teams:members:add')?.({}, 'circle-1', { ...member, githubId: 0 })).toThrow('Invalid GitHub user ID');
  expect(() => desktop.handlers.get('teams:refresh')?.({}, { days: 91 })).toThrow('Invalid period');
  expect(fetch).toHaveBeenCalledTimes(calls);
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

const emptyNotebook: RepositoryNotebookDto = { schemaVersion: 1, revision: 7, useInReviews: true, useInDevelopment: true, sources: [],
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, discoveryEvidenceRef: null }],
  entries: [], replaced: [], sourceEvidence: [] };

it('reads bounded usage, save times and the monitored baseline through authenticated workspace endpoints', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const overview: ContextUsageOverviewDto = { trackingSince: null, window: '30d',
    summary: { eligibleDevelopmentTasks: 0, eligibleReviewRuns: 0, contextOffTasks: 0, developmentTasksFoundOrOpened: 0, reviewRunsFoundOrOpened: 0 },
    entries: [] };
  const fetch = vi.fn(async () => new Response(JSON.stringify(overview)));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/usage';
  await expect(desktop.handlers.get('repository-context:usage')?.({}, 'workspace/one', '30d')).resolves.toEqual(overview);
  expect(fetch).toHaveBeenLastCalledWith(base + '?window=30d',
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  const times = { entries: { '3:knowledge:project': { at: '2026-09-02T00:00:00Z', taskId: 'task' } } };
  fetch.mockImplementation(async () => new Response(JSON.stringify(times)));
  await expect(desktop.handlers.get('repository-context:times')?.({}, 'workspace/one')).resolves.toEqual(times);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/times',
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  const baseline = { branch: 'master', sha: 'a'.repeat(40) };
  fetch.mockImplementation(async () => new Response(JSON.stringify(baseline)));
  await expect(desktop.handlers.get('repository-context:build:baseline')?.({}, 'workspace/one')).resolves.toEqual(baseline);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/build/baseline',
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockClear();
  expect(() => desktop.handlers.get('repository-context:usage')?.({}, 'workspace/one', 'year')).toThrow('Malformed repository context');
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
    { action: 'restore', path: '/entries/restore', method: 'POST', request: { expectedRevision: 7, entryId: 'knowledge:project' } },
    { action: 'keep', path: '/entries/keep', method: 'POST', request: { expectedRevision: 7, entryId: 'knowledge:project' } },
    { action: 'run-notes:disable', path: '/runs/disable-notes', method: 'POST', request: { expectedRevision: 7, taskId: 'learn-task' } },
    { action: 'lesson', path: '/lessons', method: 'POST', request: { expectedRevision: 7, scopeId: 'project',
      title: 'Lesson', summary: 'Summary', body: 'Conditional lesson', reviewId: 'review', runId: 'old-run', evidenceRefs: ['E2'], messageId: 'old-message' } },
  ];
  for (const { action, path, method, request } of actions) {
    await expect(desktop.handlers.get(`repository-context:${action}`)?.({}, 'workspace/one', request)).resolves.toEqual(emptyNotebook);
    expect(fetch).toHaveBeenLastCalledWith(base + path, expect.objectContaining({ method, body: JSON.stringify(request),
      headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  }
  expect(fetch).toHaveBeenCalledTimes(9);
});

it('reads open reports and sends the user’s report of an exact version, showing the backend’s refusal', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify([])));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/reports';
  await expect(desktop.handlers.get('repository-context:reports')?.({}, 'workspace/one')).resolves.toEqual([]);
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  const request = { taskId: 'task/one', entryId: 'knowledge:project', entryVersion: 3, reason: 'Wrong path' };
  await expect(desktop.handlers.get('repository-context:report')?.({}, 'workspace/one', request)).resolves.toEqual([]);
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ method: 'POST', body: JSON.stringify(request),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  expect(() => desktop.handlers.get('repository-context:report')?.({}, 'workspace/one', { ...request, grade: 'HELPED' })).toThrow('unsupported request field');
  expect(fetch).toHaveBeenCalledTimes(2);
  fetch.mockImplementation(async () => new Response(JSON.stringify({ message: 'Report only a note version this task received or opened' }), { status: 400 }));
  await expect(desktop.handlers.get('repository-context:report')?.({}, 'workspace/one', request)).rejects.toThrow('Report only a note version this task received or opened');
  fetch.mockImplementation(async () => new Response(JSON.stringify([{ entryId: 'knowledge:project', entryVersion: 3, title: 'Project', userWritten: 'yes', grades: [] }])));
  await expect(desktop.handlers.get('repository-context:reports')?.({}, 'workspace/one')).rejects.toThrow('Malformed repository context');
  const disputes = { contested: { 'knowledge:project': { statement: 'Keep the condition.', version: 3 } }, settlements: {} };
  fetch.mockImplementation(async () => new Response(JSON.stringify(disputes)));
  await expect(desktop.handlers.get('repository-context:disputes')?.({}, 'workspace/one')).resolves.toEqual(disputes);
  expect(fetch).toHaveBeenLastCalledWith(base.replace(/reports$/, 'disputes'), expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
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
  const view: ContextBuildViewDto = { buildRequestSequence: 0, notebookRevision: 7, canStart: true, runs: [], build: null };
  const fetch = vi.fn(async () => new Response(JSON.stringify(view)));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/build';
  await expect(desktop.handlers.get('repository-context:build:get')?.({}, 'workspace/one')).resolves.toEqual(view);
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  const cases = [
    { name: 'discover', path: '/discover', args: [{ requestKey: 'discover-key', expectedRevision: 7, expectedBuildSequence: 0, continuation: true }],
      body: { requestKey: 'discover-key', expectedRevision: 7, expectedBuildSequence: 0, continuation: true } },
    { name: 'learn', path: '/learn', args: [{ requestKey: 'selected-key', expectedRevision: 7, expectedBuildSequence: 0,
      reportId: 'report-1', topicIds: ['topic:1'], excludedSourceIds: ['source:2'] }], body: { requestKey: 'selected-key', expectedRevision: 7,
      expectedBuildSequence: 0, reportId: 'report-1', topicIds: ['topic:1'], excludedSourceIds: ['source:2'] } },
    { name: 'continue', path: '/continue', args: [{ requestKey: 'continue-key', expectedRevision: 7, expectedBuildSequence: 0,
      planId: 'plan-1' }], body: { requestKey: 'continue-key', expectedRevision: 7, expectedBuildSequence: 0, planId: 'plan-1' } },
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
  fetch.mockImplementation(async () => new Response(JSON.stringify({ plan: null, next: null })));
  const discard = { planId: 'plan-1', expectedRevision: 7, expectedBuildSequence: 3 };
  await expect(desktop.handlers.get('repository-context:build:plan:discard')?.({}, 'workspace/one', discard)).resolves.toEqual({ plan: null, next: null });
  expect(fetch).toHaveBeenLastCalledWith(base + '/plan', expect.objectContaining({ method: 'DELETE', body: JSON.stringify(discard),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  const suggested = { planId: 'plan-1', expectedRevision: 7, expectedBuildSequence: 3, suggestedUrl: 'https://example.org/iceberg/', boundary: 'ROOT' };
  await expect(desktop.handlers.get('repository-context:build:plan:source')?.({}, 'workspace/one', suggested)).resolves.toEqual({ plan: null, next: null });
  expect(fetch).toHaveBeenLastCalledWith(base + '/plan/sources', expect.objectContaining({ method: 'POST', body: JSON.stringify(suggested),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  const decline = { planId: 'plan-1', suggestedUrl: suggested.suggestedUrl };
  await expect(desktop.handlers.get('repository-context:build:plan:decline')?.({}, 'workspace/one', decline)).resolves.toEqual({ plan: null, next: null });
  expect(fetch).toHaveBeenLastCalledWith(base + '/plan/sources', expect.objectContaining({ method: 'DELETE', body: JSON.stringify(decline),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  const impact: AgentRuntimeStopImpactDto = { taskId: 'task/one', generation: 'generation', activeTurnId: null, liveProcess: true };
  fetch.mockImplementation(async () => new Response(JSON.stringify(impact)));
  await expect(desktop.handlers.get('repository-context:build:stop-impact')?.({}, 'workspace/one', 'task/one')).resolves.toEqual(impact);
  expect(fetch).toHaveBeenLastCalledWith(base + '/runtime/stop-impact?taskId=task%2Fone', expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockClear();
  expect(() => desktop.handlers.get('repository-context:build:learn')?.({}, 'workspace/one', {
    requestKey: 'learn-key', expectedRevision: 7, expectedBuildSequence: 0, reportId: 'report-1', topicIds: ['topic:1'], excludedSourceIds: [], prompt: 'forged',
  })).toThrow('unsupported request field');
  expect(fetch).not.toHaveBeenCalled();
});

it('reads retained merged PR candidates without starting learning and fences explicit selection', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const candidate: ContextMergedCandidateDto = { id: 'pr:trinodb/trino#123', repo: 'trinodb/trino', number: 123, title: 'Refresh Iceberg metadata',
    mergedAt: '2026-09-01T00:00:00Z', taskTitle: null, taskResult: null, issue: null, review: null, checks: [] };
  const view: ContextBuildViewDto = { buildRequestSequence: 1, notebookRevision: 7, canStart: false, runs: [], build: null };
  const fetch = vi.fn(async () => new Response(JSON.stringify({ candidates: [candidate], gaps: [] })));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/candidates';
  await expect(desktop.handlers.get('repository-context:candidates')?.({}, 'workspace/one')).resolves.toEqual({ candidates: [candidate] });
  expect(fetch).toHaveBeenCalledOnce();
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockImplementation(async () => new Response(JSON.stringify(view)));
  const request = { candidateId: candidate.id, requestKey: 'candidate-key', expectedRevision: 7, expectedBuildSequence: 0 };
  await expect(desktop.handlers.get('repository-context:candidates:learn')?.({}, 'workspace/one', request)).resolves.toEqual(view);
  expect(fetch).toHaveBeenLastCalledWith(base + '/learn', expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ requestKey: request.requestKey, expectedRevision: request.expectedRevision,
      expectedBuildSequence: request.expectedBuildSequence, candidateId: request.candidateId }),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockClear();
  expect(() => desktop.handlers.get('repository-context:candidates:learn')?.({}, 'workspace/one', { ...request, prompt: 'forged' }))
    .toThrow('unsupported request field');
  expect(fetch).not.toHaveBeenCalled();
  const investigate = { entryId: 'knowledge:project', requestKey: 'investigate-key', expectedRevision: 7, expectedBuildSequence: 1 };
  await expect(desktop.handlers.get('repository-context:build:investigate')?.({}, 'workspace/one', investigate)).resolves.toEqual(view);
  expect(fetch).toHaveBeenLastCalledWith(base.replace(/candidates$/, 'build/investigate'), expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ requestKey: investigate.requestKey, expectedRevision: investigate.expectedRevision,
      expectedBuildSequence: investigate.expectedBuildSequence, entryId: investigate.entryId }) }));
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
