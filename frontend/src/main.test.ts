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
import type { ContextBuildViewDto, ContextSpecCheckDto } from './repository-context/contracts';
import type { AgentRuntimeStopImpactDto } from './types';
import type { TeamActivitySnapshot, TeamDirectory } from './teams/types';
import type { JiraIssuePage } from './jira/contracts';
import { MAX_ATTACHMENT_BYTES } from './agents/clipboardImage';

const desktop = vi.hoisted(() => ({
  listeners: new Map<string, () => Promise<void>>(),
  handlers: new Map<string, (...args: unknown[]) => Promise<unknown>>(),
  accountNotifications: [vi.fn(), vi.fn()],
  clipboardRead: vi.fn(),
  clipboardWriteText: vi.fn(),
  createImage: vi.fn(),
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
  clipboard: { read: desktop.clipboardRead, writeText: desktop.clipboardWriteText },
  nativeImage: { createFromBuffer: desktop.createImage },
}));

vi.mock('./backendProcess', () => ({
  backendBase: () => 'http://127.0.0.1:9527', sessionToken: () => 'test-session', spawnBackend: vi.fn(),
  // Register the real IPC handlers, then stop startup before windows or background readers run.
  waitForBackendReady: async () => { throw new Error('End test startup'); },
}));

afterEach(() => {
  desktop.accountNotifications.forEach(send => send.mockClear());
  desktop.clipboardRead.mockReset();
  desktop.clipboardWriteText.mockReset();
  desktop.createImage.mockReset();
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it.each(['pat:copy', 'workspaces:token:copy'])('reports %s only after the asynchronous clipboard write succeeds', async channel => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ value: 'test-token' }))));
  desktop.clipboardWriteText.mockResolvedValue(undefined);
  await expect(desktop.handlers.get(channel)?.({}, 'workspace')).resolves.toBe(true);
  expect(desktop.clipboardWriteText).toHaveBeenCalledWith('test-token');
  desktop.clipboardWriteText.mockRejectedValue(new Error('Clipboard unavailable'));
  await expect(desktop.handlers.get(channel)?.({}, 'workspace')).rejects.toThrow('Clipboard unavailable');
});

it.each([
  ['agent:attachments:paste-drafts', 'image/png'],
  ['agent:attachments:paste', 'image/png'],
  ['agent:attachments:paste-drafts', 'image/jpeg'],
  ['agent:attachments:paste', 'image/jpeg'],
])('reads and validates asynchronous clipboard images for %s (%s)', async (channel, mediaType) => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const bytes = Buffer.from('png');
  const attachment = { id: 'image', name: 'pasted-image.png', mediaType: 'image/png', byteSize: bytes.length, width: 1, height: 1 };
  const fetch = vi.fn(async () => new Response(JSON.stringify(attachment)));
  vi.stubGlobal('fetch', fetch);
  desktop.clipboardRead.mockResolvedValue([{ types: ['text/plain'] }]);
  await expect(desktop.handlers.get(channel)?.({}, 'task', 'workspace')).resolves.toEqual([]);
  expect(desktop.createImage).not.toHaveBeenCalled();
  expect(fetch).not.toHaveBeenCalled();

  const getType = vi.fn(async () => new Blob(['png'], { type: mediaType }));
  desktop.clipboardRead.mockResolvedValue([{ types: [mediaType], getType }]);
  desktop.createImage.mockReturnValue({ isEmpty: () => false, getSize: () => ({ width: 1, height: 1 }), toPNG: () => bytes });
  await expect(desktop.handlers.get(channel)?.({}, 'task', 'workspace')).resolves.toMatchObject([
    { name: attachment.name, mediaType: attachment.mediaType },
  ]);
  expect(getType).toHaveBeenCalledWith(mediaType);
  expect(desktop.createImage).toHaveBeenCalledWith(bytes);
  if (channel === 'agent:attachments:paste') {
    expect(fetch).toHaveBeenCalledWith(expect.any(URL), expect.objectContaining({ method: 'POST', body: new Uint8Array(bytes) }));
  }

  desktop.createImage.mockClear();
  fetch.mockClear();
  getType.mockResolvedValue(new Blob([new Uint8Array(MAX_ATTACHMENT_BYTES + 1)]));
  await expect(desktop.handlers.get(channel)?.({}, 'task', 'workspace')).rejects.toThrow('10 MiB');
  expect(desktop.createImage).not.toHaveBeenCalled();
  expect(fetch).not.toHaveBeenCalled();
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

it('routes spec check runs, their baseline and their recovery through the authenticated purpose-bound API', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const view: ContextBuildViewDto = { buildRequestSequence: 2, canStart: false, build: { kind: 'CHECK', requestKey: 'check-key', taskId: 'task', checkId: 'check-1',
    task: { id: 'task', workspaceId: 'workspace/one', state: 'OPEN', mutationClaim: 'TURN', worktreeRemovedAt: null }, activeTurn: { cancellationRequestedAt: null },
    pendingApprovals: 0, outcome: 'RUNNING', detail: null, activity: [{ at: '2026-10-02T00:00:00Z', text: 'Opened https://example.com/spec', tone: 'DONE' }] } };
  const fetch = vi.fn(async () => new Response(JSON.stringify(view)));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/build';
  await expect(desktop.handlers.get('repository-context:build:get')?.({}, 'workspace/one')).resolves.toEqual(view);
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  const cases = [
    { name: 'stop', path: '/stop', args: ['task'], body: { taskId: 'task' } },
    { name: 'stop-runtime', path: '/runtime/stop', args: ['task', 'generation'], body: { taskId: 'task', generation: 'generation' } },
    { name: 'fail', path: '/mark-failed', args: ['task', 'User ended the spec check.'], body: { taskId: 'task', reason: 'User ended the spec check.' } },
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
  const baseline = { branch: 'main', sha: 'a'.repeat(40) };
  fetch.mockImplementation(async () => new Response(JSON.stringify(baseline)));
  await expect(desktop.handlers.get('repository-context:build:baseline')?.({}, 'workspace/one')).resolves.toEqual(baseline);
  expect(fetch).toHaveBeenLastCalledWith(base + '/baseline', expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockImplementation(async () => new Response(JSON.stringify({ ...view, notebookRevision: 7, build: { ...view.build, outcome: 'SUBMITTED' } })));
  await expect(desktop.handlers.get('repository-context:build:get')?.({}, 'workspace/one')).rejects.toThrow('Malformed spec check: run outcome');
});

it('reads a directory’s spec sources and starts a check of it, refusing a forged request before sending it', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify({ sources: [{ url: 'https://iceberg.apache.org/spec/', kind: 'PAGE', links: 3, sections: ['manifests'] }] })));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/spec-checks';
  await expect(desktop.handlers.get('repository-context:spec-checks:sources')?.({}, 'workspace/one', 'plugin/trino-iceberg/'))
    .resolves.toEqual([{ url: 'https://iceberg.apache.org/spec/', kind: 'PAGE', links: 3, sections: ['manifests'] }]);
  expect(fetch).toHaveBeenLastCalledWith(base + '/sources?directory=plugin%2Ftrino-iceberg%2F', expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockImplementationOnce(async () => new Response(JSON.stringify({ modules: [
    { directory: 'plugin/trino-delta-lake/', page: 'https://github.com/delta-io/delta/blob/master/PROTOCOL.md', sections: 20, links: 25 },
    { directory: 'plugin/trino-iceberg/', page: null, sections: 0, links: 0 }] })));
  await expect(desktop.handlers.get('repository-context:spec-checks:modules')?.({}, 'workspace/one')).resolves.toEqual([
    { directory: 'plugin/trino-delta-lake/', page: 'https://github.com/delta-io/delta/blob/master/PROTOCOL.md', sections: 20, links: 25 },
    { directory: 'plugin/trino-iceberg/', page: null, sections: 0, links: 0 }]);
  expect(fetch).toHaveBeenLastCalledWith(base + '/modules', expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  fetch.mockClear();
  expect(() => desktop.handlers.get('repository-context:spec-checks:sources')?.({}, 'workspace/one', 'plugin/../core/')).toThrow('Malformed spec check: directory');
  const request = { requestKey: 'check-key', expectedBuildSequence: 2, directory: 'plugin/trino-iceberg/', specs: ['https://iceberg.apache.org/spec/'], maxReproductions: 3 };
  expect(() => desktop.handlers.get('repository-context:spec-checks:start')?.({}, 'workspace/one', { ...request, planId: 'plan' })).toThrow('unsupported request field');
  expect(() => desktop.handlers.get('repository-context:spec-checks:start')?.({}, 'workspace/one', { ...request, maxReproductions: 9 }))
    .toThrow('Malformed spec check: maxReproductions');
  expect(fetch).not.toHaveBeenCalled();
  const check: ContextSpecCheckDto = { id: 'check-1', directory: 'plugin/trino-iceberg/', baselineSha: 'a'.repeat(40), requestKey: 'check-key', specs: request.specs, checkedAgainst: [],
    labels: [], state: 'CHECKING', candidates: [], covered: [], next: [], reproductions: 0, maxReproductions: 3, detail: null, startedAt: '2026-10-02T00:00:00Z', endedAt: null };
  fetch.mockImplementation(async () => new Response(JSON.stringify({ check, build: { buildRequestSequence: 3, canStart: false, build: null } })));
  await expect(desktop.handlers.get('repository-context:spec-checks:start')?.({}, 'workspace/one', request))
    .resolves.toEqual({ check, build: { buildRequestSequence: 3, canStart: false, build: null } });
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ method: 'POST', body: JSON.stringify(request) }));
  // A directory whose code links no page still starts a check, which proposes the specification.
  await desktop.handlers.get('repository-context:spec-checks:start')?.({}, 'workspace/one', { ...request, specs: [] });
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ method: 'POST', body: JSON.stringify({ ...request, specs: [] }) }));
  fetch.mockImplementation(async () => new Response(JSON.stringify(check)));
  await expect(desktop.handlers.get('repository-context:spec-checks:dismiss')?.({}, 'workspace/one', 'check/1', 'c1', { reason: 'Filed on GitHub' })).resolves.toEqual(check);
  expect(fetch).toHaveBeenLastCalledWith(base + '/check%2F1/candidates/c1/dismiss', expect.objectContaining({ method: 'POST', body: JSON.stringify({ reason: 'Filed on GitHub' }) }));
});

it('routes memory reads and the user’s changes, refusing a forged memory id before sending it', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const memory: Record<string, unknown> = { id: 'm12', workspaceId: 'workspace/one', kind: 'RULE', title: 'Table property docs', text: 'Document every new table property.',
    scope: ['plugin/'],
    sources: [{ kind: 'PULL_REQUEST', url: 'https://github.com/trinodb/trino/pull/31201', quote: 'Docs, please.', at: '2026-10-02T00:00:00Z', section: null,
      pullRequest: { number: 31201, part: 'REVIEW', commentId: '7', author: 'alice', maintainer: true, merged: true, resolved: false, outdated: false } }],
    origin: { user: false, description: 'Learn from my PRs', taskId: 'task' }, version: 1, previous: null, delivered: 3, cited: 1, reports: [],
    createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z' };
  const page: Record<string, unknown> = { memories: [{ memory, missingScope: [] }], switches: { reviews: true, development: true }, guidelines: [], head: 'a'.repeat(40),
    harvest: { cursor: null, last: null } };
  const fetch = vi.fn(async () => new Response(JSON.stringify(page)));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/memory';
  const read = await desktop.handlers.get('memory:page')?.({}, 'workspace/one') as { memories: { id: string; sources: { pullRequest: { author: string } }[] }[] };
  expect(read.memories[0].id).toBe('m12');
  expect(read.memories[0].sources[0].pullRequest.author).toBe('alice');
  expect(fetch).toHaveBeenLastCalledWith(base, expect.anything());
  const cases = [
    { name: 'edit', args: ['m12', 1, 'New title', 'New text'], path: '/m12', method: 'PATCH', body: { expectedVersion: 1, title: 'New title', text: 'New text' } },
    { name: 'restore', args: ['m12', 2], path: '/m12/restore', method: 'POST', body: { expectedVersion: 2 } },
    { name: 'switches', args: [{ reviews: false, development: true }], path: '/switches', method: 'PUT', body: { reviews: false, development: true } },
  ];
  for (const item of cases) {
    await desktop.handlers.get(`memory:${item.name}`)?.({}, 'workspace/one', ...item.args);
    expect(fetch).toHaveBeenLastCalledWith(base + item.path, expect.objectContaining({ method: item.method, body: JSON.stringify(item.body) }));
  }
  for (const item of [{ name: 'keep', path: '/m12/keep', method: 'POST' }, { name: 'delete', path: '/m12', method: 'DELETE' },
    { name: 'undo-delete', path: '/m12/undo-delete', method: 'POST' }, { name: 'undo-remember', path: '/m12/undo-remember', method: 'POST' }]) {
    await desktop.handlers.get(`memory:${item.name}`)?.({}, 'workspace/one', 'm12');
    expect(fetch).toHaveBeenLastCalledWith(base + item.path, expect.objectContaining({ method: item.method }));
  }
  fetch.mockImplementation(async () => new Response(JSON.stringify({ started: true, pulls: 1, threads: 3, view: { buildRequestSequence: 5, canStart: false, build: null } })));
  const harvest = { requestKey: 'learn', expectedBuildSequence: 4, pull: 31240, repo: 'trinodb/trino' };
  await expect(desktop.handlers.get('memory:harvest')?.({}, 'workspace/one', harvest))
    .resolves.toEqual({ started: true, pulls: 1, threads: 3, view: { buildRequestSequence: 5, canStart: false, build: null } });
  expect(fetch).toHaveBeenLastCalledWith(base + '/harvest', expect.objectContaining({ method: 'POST', body: JSON.stringify(harvest) }));
  const pull = { number: 31354, title: 'Fix checkpoint write', closedAt: '2026-09-20T00:00:00Z', merged: true };
  fetch.mockImplementation(async () => new Response(JSON.stringify([pull])));
  await expect(desktop.handlers.get('memory:pulls')?.({}, 'workspace/one', 'checkpoint & null')).resolves.toEqual([pull]);
  expect(fetch).toHaveBeenLastCalledWith(base + '/pulls?q=checkpoint+%26+null', expect.anything());
  fetch.mockClear();
  expect(() => desktop.handlers.get('memory:pulls')?.({}, 'workspace/one', 'x'.repeat(201))).toThrow('Malformed pull request search');
  expect(() => desktop.handlers.get('memory:harvest')?.({}, 'workspace/one', { ...harvest, repo: null })).toThrow('Malformed harvest request');
  expect(() => desktop.handlers.get('memory:delete')?.({}, 'workspace/one', '../m12')).toThrow('Malformed memory id');
  expect(() => desktop.handlers.get('memory:edit')?.({}, 'workspace/one', 'm12', 0, 'Title', 'Text')).toThrow('Malformed memory version');
  expect(() => desktop.handlers.get('memory:edit')?.({}, 'workspace/one', 'm12', 1, ' ', 'Text')).toThrow('Malformed memory title');
  expect(() => desktop.handlers.get('memory:edit')?.({}, 'workspace/one', 'm12', 1, 'Title', '  ')).toThrow('Malformed memory text');
  expect(fetch).not.toHaveBeenCalled();
});

it('routes review replays through the agent review routes with the desktop session, refusing forged input before sending it', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const replay: Record<string, unknown> = { id: '6f1c2c1e-0000-4000-8000-000000000001', number: 31201, title: 'Add a property', url: 'https://github.com/trinodb/trino/pull/31201',
    head: 'a'.repeat(40), fallback: null, cutoff: '2026-09-19T10:00:00Z', reviewer: 'bo', excludedMemories: 0, createdAt: '2026-10-02T09:00:00Z', sides: [], comments: [] };
  const fetch = vi.fn(async () => new Response(JSON.stringify({ replays: [replay] })));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/agent-review/replays';
  const session = expect.objectContaining({ 'X-ByteQuay-Session': 'test-session' });
  await expect(desktop.handlers.get('replay:list')?.({}, 'workspace/one')).resolves.toHaveLength(1);
  expect(fetch).toHaveBeenLastCalledWith(base + '?workspaceId=workspace%2Fone', expect.objectContaining({ headers: session }));
  fetch.mockImplementation(async () => new Response(JSON.stringify(replay)));
  await desktop.handlers.get('replay:start')?.({}, 'workspace/one', { repo: 'trinodb/trino', number: 31201 });
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ method: 'POST', headers: session,
    body: JSON.stringify({ workspaceId: 'workspace/one', repo: 'trinodb/trino', number: 31201 }) }));
  await desktop.handlers.get('replay:mark')?.({}, 'workspace/one', replay.id, { commentId: 'c1', memory: false, raised: true });
  expect(fetch).toHaveBeenLastCalledWith(`${base}/${replay.id}/marks`, expect.objectContaining({ method: 'PUT', headers: session,
    body: JSON.stringify({ workspaceId: 'workspace/one', commentId: 'c1', memory: false, raised: true }) }));
  fetch.mockImplementation(async () => new Response(null, { status: 204 }));
  await desktop.handlers.get('replay:delete')?.({}, 'workspace/one', replay.id);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/${replay.id}?workspaceId=workspace%2Fone`, expect.objectContaining({ method: 'DELETE', headers: session }));
  fetch.mockClear();
  expect(() => desktop.handlers.get('replay:start')?.({}, 'workspace/one', { repo: 'trinodb/trino', number: 0 })).toThrow('Malformed replay request');
  expect(() => desktop.handlers.get('replay:delete')?.({}, 'workspace/one', '../replay')).toThrow('Malformed replay id');
  expect(fetch).not.toHaveBeenCalled();
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
  await desktop.handlers.get('jira:issues')?.({}, { ...location, boardId: 828, sprintId: null, mine: true, completed: true, nextPageToken: 'next/token + value' }, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/refresh?boardId=828&mine=true&completed=true&nextPageToken=next%2Ftoken+%2B+value`, expect.objectContaining({ method: 'POST' }));
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

it.each([false, true])('allows a Jira collection refresh beyond one minute (completed: %s)', async completed => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  vi.useFakeTimers();
  vi.spyOn(AbortSignal, 'timeout').mockImplementation(milliseconds => {
    const controller = new AbortController();
    setTimeout(() => controller.abort(new DOMException('Request timed out', 'TimeoutError')), milliseconds);
    return controller.signal;
  });
  const page: JiraIssuePage = { issues: [], fetchedAt: '2026-09-26T00:00:00Z', nextPageToken: 'next/token', error: null };
  const fetch = vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(init.signal?.reason), { once: true });
    setTimeout(() => resolve(new Response(JSON.stringify(page))), 125_000);
  }));
  vi.stubGlobal('fetch', fetch);
  const result = desktop.handlers.get('jira:issues')?.({}, {
    connectionId: 'connection', cloudId: 'cloud', boardId: 82, sprintId: null, mine: true, completed,
  }, true).then(value => ({ value }), error => ({ error }));

  await vi.advanceTimersByTimeAsync(125_000);

  expect(await result).toEqual({ value: page });
  expect(fetch).toHaveBeenCalledTimes(1);
});
