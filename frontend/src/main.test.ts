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

import { nativeTheme } from 'electron';
import { afterEach, expect, it, vi } from 'vitest';
import { MAX_ATTACHMENT_BYTES } from './agents/clipboardImage';
import type { TaskDiscussionDto } from './agents/discussions/contracts';
import { CI_RUN } from './agents/fixtures/ciAutofix';
import type { JiraIssuePage } from './jira/contracts';
import { parseAgentReview, type ReviewDraftDto } from './pulls/agent-review/contracts';
import { runningReviewFixture } from './pulls/agent-review/fixtures/review';
import type { ReviewCodeFileDto } from './pulls/code-tests/contracts';
import type { ContextBuildViewDto, ContextSpecCheckDto } from './repository-context/contracts';
import type { TeamActivitySnapshot, TeamDirectory } from './teams/types';
import type { AgentRuntimeStopImpactDto, ProviderUsageDto } from './types';

const desktop = vi.hoisted(() => ({
  listeners: new Map<string, () => Promise<void>>(),
  handlers: new Map<string, (...args: unknown[]) => Promise<unknown>>(),
  accountNotifications: [vi.fn(), vi.fn()],
  clipboardRead: vi.fn(),
  clipboardWriteText: vi.fn(),
  createImage: vi.fn(),
  spawnPty: vi.fn(),
}));

vi.mock('node-pty', () => ({ spawn: desktop.spawnPty }));

vi.mock('electron', () => ({
  nativeTheme: { on: vi.fn(), themeSource: 'system', shouldUseDarkColors: false },
  BrowserWindow: { getAllWindows: () => desktop.accountNotifications.map(send => ({ isDestroyed: () => false, webContents: { isDestroyed: () => false, send } })) },
  app: {
    setName: vi.fn(), commandLine: { appendSwitch: vi.fn() }, requestSingleInstanceLock: () => true,
    on: (event: string, listener: () => Promise<void>) => desktop.listeners.set(event, listener),
    getAppPath: () => process.cwd(), getPath: () => '/tmp/bytequay-main-test', getVersion: () => 'test', setAboutPanelOptions: vi.fn(),
  },
  protocol: { registerSchemesAsPrivileged: vi.fn() },
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
  desktop.spawnPty.mockReset();
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it.each([
  { kind: 'review', reviewId: 'work-review' },
  { kind: 'task', taskId: 'work-task', workspaceId: 'work-repo' },
])('starts a $kind terminal with the backend-selected Claude profile', async host => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify({ workingDirectory: '/managed/cork', claudeConfigDirectory: '/profiles/work' })));
  vi.stubGlobal('fetch', fetch);
  desktop.spawnPty.mockReturnValue({ onData: vi.fn(), onExit: vi.fn(), kill: vi.fn(), resize: vi.fn(), write: vi.fn() });
  const sender = { isDestroyed: () => false, on: vi.fn(), once: vi.fn(), send: vi.fn() };

  const result = await desktop.handlers.get('terminal:open')?.({ sender }, host, 100, 30);

  expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/code/terminal'), expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  expect(desktop.spawnPty).toHaveBeenCalledWith(expect.any(String), ['-l'], expect.objectContaining({
    cwd: '/managed/cork', cols: 100, rows: 30, env: expect.objectContaining({ CLAUDE_CONFIG_DIR: '/profiles/work' }),
  }));
  expect(result).toEqual({ terminalId: expect.any(String), scrollback: '' });
});

it.each(['confirm-not-posted', 'confirm-posted'])('settles an unconfirmed comment through the versioned local %s endpoint', async outcome => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const draft: ReviewDraftDto = { repo: 'owner/repo', number: 7, body: 'Saved review', event: 'COMMENT', items: [], reviewPublication: null, postedSummaryFindingIds: [], version: 5, updatedAt: '2026-09-07T00:00:00Z' };
  const fetch = vi.fn(async () => new Response(JSON.stringify(draft)));
  vi.stubGlobal('fetch', fetch);

  const result = await desktop.handlers.get(`review:draft:${outcome}`)?.({}, 'owner/repo', 7, 4, 'item/1');

  expect(result).toEqual(draft);
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch).toHaveBeenCalledWith(`http://127.0.0.1:9527/api/agent-review/drafts/items/item%2F1/${outcome}?repo=owner%2Frepo&number=7&expectedVersion=4`,
    expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
});

it.each([true, false])('settles review text with an unknown outcome through the versioned local endpoint, posted %s', async posted => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const draft: ReviewDraftDto = { repo: 'owner/repo', number: 7, body: '', event: 'COMMENT', items: [], reviewPublication: null, postedSummaryFindingIds: [], version: 5, updatedAt: '2026-09-07T00:00:00Z' };
  const fetch = vi.fn(async () => new Response(JSON.stringify(draft)));
  vi.stubGlobal('fetch', fetch);

  const result = await desktop.handlers.get('review:draft:confirm-review')?.({}, 'owner/repo', 7, 4, posted);

  expect(result).toEqual(draft);
  expect(fetch).toHaveBeenCalledWith(`http://127.0.0.1:9527/api/agent-review/drafts/review/confirm?repo=owner%2Frepo&number=7&expectedVersion=4&posted=${posted}`,
    expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
});

it('posts a single comment through the pending review under its comment box key', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response('{"number":7}'));
  vi.stubGlobal('fetch', fetch);
  const comment = { path: 'src/A.java', body: 'Rename this', line: 10, side: 'RIGHT', commitId: 'a'.repeat(40) };

  const result = await desktop.handlers.get('prs:review-comment:create')?.({}, 'owner/repo', 7, comment, '6f1c2e3a-5b7d-4c8e-9a0b-1c2d3e4f5a6b');

  expect(result).toEqual({ number: 7 });
  expect(fetch).toHaveBeenCalledWith('http://127.0.0.1:9527/api/agent-review/drafts/items/6f1c2e3a-5b7d-4c8e-9a0b-1c2d3e4f5a6b/post?repo=owner%2Frepo&number=7',
    expect.objectContaining({ method: 'POST', body: JSON.stringify(comment), headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
});

it('carries the displayed head commit through the merge request', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response('{}'));
  vi.stubGlobal('fetch', fetch);

  await desktop.handlers.get('prs:merge')?.({}, 12, 'owner/repo', 7, 'rebase', 'displayed-head');

  expect(fetch).toHaveBeenCalledWith(new URL('http://127.0.0.1:9527/prs/merge?repo=owner%2Frepo&number=7&id=12&strategy=rebase&headSha=displayed-head'),
    expect.objectContaining({ method: 'POST' }));
});

it.each([
  ['prs:diff', '/prs/diffFiles', undefined, []],
  ['prs:diff:refresh', '/prs/diffFiles/refresh', 'POST', []],
  ['prs:diff-content:refresh', '/prs/diffContent/refresh', 'POST', ['src/A B.ts']],
] as const)('preserves the method and complete revision pair for %s', async (channel, pathname, method, path) => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response('{}'));
  vi.stubGlobal('fetch', fetch);
  for (const [base, head, range] of [
    ['base', 'head', '&base=base&head=head'],
    [undefined, undefined, ''], ['base', undefined, ''], [undefined, 'head', ''], ['', 'head', ''], ['base', '', ''],
  ] as const) {
    await desktop.handlers.get(channel)?.({}, 'owner/repo', 7, ...path, base, head);
    const expected = new URL(`http://127.0.0.1:9527${pathname}?repo=owner%2Frepo&number=7${range}`);
    if (path.length > 0) expected.searchParams.set('path', path[0]);
    expect(fetch).toHaveBeenLastCalledWith(expected, expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
    expect(fetch.mock.calls.at(-1)?.[1]?.method).toBe(method);
  }
});

it('forwards PR opening freshness separately from periodic and checks-only refreshes', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response('{}'));
  vi.stubGlobal('fetch', fetch);
  const refresh = desktop.handlers.get('prs:detail:refresh:by-ref');

  await refresh?.({}, 'owner/repo', 7, false, true);
  expect(fetch).toHaveBeenLastCalledWith(new URL('http://127.0.0.1:9527/prs/detail/refresh?repo=owner%2Frepo&number=7&opened=true'),
    expect.objectContaining({ method: 'POST' }));
  await refresh?.({}, 'owner/repo', 7, false, false);
  expect(fetch).toHaveBeenLastCalledWith(new URL('http://127.0.0.1:9527/prs/detail/refresh?repo=owner%2Frepo&number=7'),
    expect.objectContaining({ method: 'POST' }));
  await refresh?.({}, 'owner/repo', 7, true);
  expect(fetch).toHaveBeenLastCalledWith(new URL('http://127.0.0.1:9527/prs/detail/refresh?repo=owner%2Frepo&number=7&checksOnly=true'),
    expect.objectContaining({ method: 'POST' }));
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
    headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }),
  }));
  await expect(desktop.handlers.get('teams:contributors')?.({})).resolves.toEqual(contributors);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/teams/contributors', expect.objectContaining({
    headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }),
  }));
  await desktop.handlers.get('teams:members:add')?.({}, 'circle-1', member);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/teams/circle-1/members', expect.objectContaining({ method: 'POST',
    body: JSON.stringify(member), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }),
  }));
  await desktop.handlers.get('teams:members:remove')?.({}, 'circle-1', 42);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/teams/circle-1/members/42', expect.objectContaining({
    method: 'DELETE', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }),
  }));
  const timeout = vi.spyOn(AbortSignal, 'timeout');
  await expect(desktop.handlers.get('teams:refresh')?.({}, { circleId: 'circle-1', memberId: 42, days: 30 })).resolves.toEqual(activity);
  expect(fetch).toHaveBeenLastCalledWith(new URL('http://127.0.0.1:9527/api/teams/refresh?circleId=circle-1&memberId=42&days=30&limit=100'),
    expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
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
    headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }),
  }));
  await expect(reply?.({}, 'run/one', 'Use the compatibility fix', 'reply-key')).resolves.toEqual(CI_RUN);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/ci-autofix/runs/run%2Fone/messages', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ message: 'Use the compatibility fix' }),
    headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session', 'Idempotency-Key': 'reply-key' }),
  }));
  await expect(reply?.({}, 'run/one', 'Docker is running', 'steer-key', 'ci-turn')).resolves.toEqual(CI_RUN);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/ci-autofix/runs/run%2Fone/messages', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ message: 'Docker is running', expectedTurnId: 'ci-turn' }),
    headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session', 'Idempotency-Key': 'steer-key' }),
  }));
  expect(fetch).toHaveBeenCalledTimes(3);
});

it.each([
  { channel: 'review:start', args: ['workspace-1', 'trinodb/trino', 31102] },
  { channel: 'review:worktree:prepare', args: ['workspace-1', 'trinodb/trino', 31102] },
  { channel: 'review:resume', args: ['review-1'] },
  { channel: 'review:update', args: ['review-1'] },
  { channel: 'review:explanation:refresh', args: ['review-1', 'run-1', 2] },
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

it('refreshes only the explanation using its displayed owner and revision', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const value = runningReviewFixture();
  const fetch = vi.fn(async () => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch);

  await expect(desktop.handlers.get('review:explanation:refresh')?.({}, 'review/one', 'run/one', 3)).resolves.toEqual(parseAgentReview(value));
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch).toHaveBeenCalledWith('http://127.0.0.1:9527/api/agent-review/review%2Fone/explanation/refresh', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ runId: 'run/one', expectedRevision: 3 }),
    headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }),
  }));
});

it('routes review passage and comment discussions through their owning review', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const discussion: TaskDiscussionDto = { id: 'discussion/one', taskId: 'task-1', turnId: 'turn-1', quote: 'releases the writer', occurrence: 1,
    resolved: false, pending: false, providerLane: 'codex', messages: [], version: 1, comment: null, replyDraft: null, replyDraftError: null,
    createdAt: '2026-09-06T16:00:00Z', updatedAt: '2026-09-06T16:00:00Z' };
  const open = { turnId: 'turn-1', quote: discussion.quote, occurrence: 1, question: 'How?' };
  const cases: { channel: string; args: unknown[]; path: string; method: string; body: unknown; response: unknown; expected: unknown }[] = [
    { channel: 'list', args: [], path: '', method: 'GET', body: undefined, response: [discussion], expected: [discussion] },
    { channel: 'open', args: [open], path: '', method: 'POST', body: open, response: discussion, expected: discussion },
    { channel: 'ask', args: [discussion.id, 'Again?'], path: '/discussion%2Fone/messages', method: 'POST',
      body: { question: 'Again?' }, response: discussion, expected: discussion },
    { channel: 'ask-comment', args: ['review_comment:1', 'Why?'], path: '/comments', method: 'POST',
      body: { key: 'review_comment:1', question: 'Why?' }, response: discussion, expected: discussion },
    { channel: 'resolve', args: [discussion.id, true, 1], path: '/discussion%2Fone', method: 'PATCH',
      body: { resolved: true, expectedVersion: 1 }, response: discussion, expected: discussion },
    { channel: 'retry', args: [discussion.id, 'message/one'], path: '/discussion%2Fone/messages/message%2Fone/retry', method: 'POST',
      body: {}, response: discussion, expected: discussion },
    { channel: 'draft-reply', args: [discussion.id], path: '/discussion%2Fone/reply-draft', method: 'POST',
      body: {}, response: { text: 'Draft reply' }, expected: 'Draft reply' },
  ];
  for (const entry of cases) {
    const fetch = vi.fn(async () => new Response(JSON.stringify(entry.response), { headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetch);
    const handler = desktop.handlers.get(`review:discussions:${entry.channel}`);
    expect(handler).toBeDefined();
    await expect(handler?.({}, 'review/one', ...entry.args)).resolves.toEqual(entry.expected);
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`http://127.0.0.1:9527/api/agent-review/review%2Fone/discussions${entry.path}`);
    expect(init.method ?? 'GET').toBe(entry.method);
    expect(init.body).toBe(entry.body === undefined ? undefined : JSON.stringify(entry.body));
    expect(new Headers(init.headers).get('X-ByteQuay-Session')).toBe('test-session');
  }
});

it('routes Tears of time reads and writes to the review or the development task that hosts the working copy', async () => {
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
    expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  fetch.mockResolvedValueOnce(json(codeFile));
  await expect(handler('code:file')(review, codeFile.path)).resolves.toEqual(codeFile);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/agent-review/review%2Fone/code/file?path=src%2FExample.java', expect.any(Object));
  fetch.mockResolvedValueOnce(json({ message: 'The working copy changed. Refresh its files.' }, 409));
  await expect(handler('code:file')(review, codeFile.path, 'a'.repeat(40))).rejects.toThrow('The working copy changed. Refresh its files.');

  await expect(handler('code:runs:output')(task, 'run/1', 12.7)).resolves.toEqual(output);
  expect(fetch).toHaveBeenLastCalledWith(
    'http://127.0.0.1:9527/agent/tasks/task%2Fone/code/runs/run%2F1/output?workspaceId=workspace%2Fone&after=12',
    expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await expect(handler('code:runs:output')(review, 'run/1', 0)).resolves.toEqual(output);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/agent-review/review%2Fone/code/runs/run%2F1/output?after=0', expect.any(Object));

  expect(desktop.handlers.has('code:runs:start')).toBe(false);

  const calls = fetch.mock.calls.length;
  await expect(handler('code:files')({ kind: 'task', taskId: 'task/one' })).rejects.toThrow('Malformed Tears of time host');
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
  expect(fetch).toHaveBeenLastCalledWith(base, expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  const taskBase = 'http://127.0.0.1:9527/agent/tasks/task';
  const cases = [
    { name: 'stop', url: base + '/stop', args: ['task'], body: { taskId: 'task' } },
    { name: 'stop-runtime', url: taskBase + '/runtime/stop?workspaceId=workspace%2Fone', args: ['task', 'generation'], body: { generation: 'generation' } },
    { name: 'fail', url: taskBase + '/mark-failed?workspaceId=workspace%2Fone', args: ['task', 'User ended the spec check.'], body: { reason: 'User ended the spec check.' } },
    { name: 'cleanup', url: taskBase + '/cleanup?workspaceId=workspace%2Fone', args: ['task'], body: undefined },
  ];
  for (const item of cases) {
    fetch.mockClear();
    await expect(desktop.handlers.get(`repository-context:build:${item.name}`)?.({}, 'workspace/one', ...item.args)).resolves.toEqual(view);
    expect(fetch).toHaveBeenNthCalledWith(1, item.url, expect.objectContaining({ method: 'POST',
      ...(item.body ? { body: JSON.stringify(item.body) } : {}), headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
    if (item.name !== 'stop') expect(fetch).toHaveBeenLastCalledWith(base, expect.anything());
  }
  const impact: AgentRuntimeStopImpactDto = { taskId: 'task/one', generation: 'generation', activeTurnId: null, liveProcess: true };
  fetch.mockImplementation(async () => new Response(JSON.stringify(impact)));
  await expect(desktop.handlers.get('repository-context:build:stop-impact')?.({}, 'workspace/one', 'task/one')).resolves.toEqual(impact);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/agent/tasks/task%2Fone/runtime/stop-impact?workspaceId=workspace%2Fone', expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  fetch.mockImplementation(async () => new Response(JSON.stringify({ ...view, notebookRevision: 7, build: { ...view.build, outcome: 'SUBMITTED' } })));
  await expect(desktop.handlers.get('repository-context:build:get')?.({}, 'workspace/one')).rejects.toThrow('Malformed run outcome');
});

it('reads a directory’s spec sources and starts a check of it, refusing a forged request before sending it', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify({ sources: [{ url: 'https://iceberg.apache.org/spec/', kind: 'PAGE', links: 3, sections: ['manifests'] }] })));
  vi.stubGlobal('fetch', fetch);
  const base = 'http://127.0.0.1:9527/api/workspaces/workspace%2Fone/repository-context/spec-checks';
  await expect(desktop.handlers.get('repository-context:spec-checks:sources')?.({}, 'workspace/one', 'plugin/trino-iceberg/'))
    .resolves.toEqual([{ url: 'https://iceberg.apache.org/spec/', kind: 'PAGE', links: 3, sections: ['manifests'] }]);
  expect(fetch).toHaveBeenLastCalledWith(base + '/sources?directory=plugin%2Ftrino-iceberg%2F', expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  fetch.mockImplementationOnce(async () => new Response(JSON.stringify({ modules: [
    { directory: 'plugin/trino-delta-lake/', page: 'https://github.com/delta-io/delta/blob/master/PROTOCOL.md', sections: 20, links: 25 },
    { directory: 'plugin/trino-iceberg/', page: null, sections: 0, links: 0 }] })));
  await expect(desktop.handlers.get('repository-context:spec-checks:modules')?.({}, 'workspace/one')).resolves.toEqual([
    { directory: 'plugin/trino-delta-lake/', page: 'https://github.com/delta-io/delta/blob/master/PROTOCOL.md', sections: 20, links: 25 },
    { directory: 'plugin/trino-iceberg/', page: null, sections: 0, links: 0 }]);
  expect(fetch).toHaveBeenLastCalledWith(base + '/modules', expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
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
  const session = withHeaders({ 'X-ByteQuay-Session': 'test-session' });
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
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/config', expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:connect')?.({});
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections', expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  const token = { siteUrl: 'https://example.atlassian.net', email: 'alex@example.com', token: 'fixture-token' };
  await desktop.handlers.get('jira:token:connect')?.({}, token);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections/token', expect.objectContaining({ method: 'POST', body: JSON.stringify(token), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:token:replace')?.({}, 'connection/one', token);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections/connection%2Fone/token', expect.objectContaining({ method: 'PUT', body: JSON.stringify(token), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:reconnect')?.({}, 'connection/one');
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections/connection%2Fone/reconnect', expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issues')?.({}, { ...location, boardId: 828, sprintId: 11, mine: true }, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/refresh?boardId=828&mine=true&sprintId=11`, expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issues')?.({}, { ...location, boardId: 828, sprintId: null, mine: false, completed: true }, false);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues?boardId=828&mine=false&completed=true`, expect.objectContaining({ method: 'GET', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issues')?.({}, { ...location, boardId: 828, sprintId: null, mine: true, completed: true, nextPageToken: 'next/token + value' }, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/refresh?boardId=828&mine=true&completed=true&nextPageToken=next%2Ftoken+%2B+value`, expect.objectContaining({ method: 'POST' }));
  await desktop.handlers.get('jira:issues')?.({}, { ...location, boardId: 828, sprintId: 11, mine: false, coassigned: true }, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/refresh?boardId=828&mine=false&sprintId=11&coassigned=true`, expect.objectContaining({ method: 'POST' }));
  await desktop.handlers.get('jira:issues')?.({}, { ...location, boardId: 828, sprintId: null, mine: true, coassigned: true, completed: true }, false);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues?boardId=828&mine=true&coassigned=true&completed=true`, expect.objectContaining({ method: 'GET' }));
  await desktop.handlers.get('jira:issue')?.({}, location, true, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/refresh?automatic=true`, expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issue')?.({}, location, true);
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/refresh`, expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:key:refresh')?.({}, 'connection/one', 'site/one', 'ENG-7');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/keys/ENG-7/refresh`, expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issue:update')?.({}, location, { labels: ['connector'] }, '2026-09-26T00:00:00Z');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7`, expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ fields: { labels: ['connector'] }, expectedUpdatedAt: '2026-09-26T00:00:00Z' }), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issue:comment')?.({}, location, 'Ready for review', 'operation-1');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/comments`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ body: 'Ready for review', operationId: 'operation-1', parentId: null }), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issue:comment')?.({}, location, 'Agreed', 'operation-2', '5');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/comments`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ body: 'Agreed', operationId: 'operation-2', parentId: '5' }) }));
  await desktop.handlers.get('jira:comment:edit')?.({}, location, '5', 'Updated', '2026-09-26T01:00:00.000+0000');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/comments/5`, expect.objectContaining({ method: 'PUT', body: JSON.stringify({ body: 'Updated', expectedUpdated: '2026-09-26T01:00:00.000+0000' }), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issue:workspace')?.({}, location, 'workspace-one');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/workspace`, expect.objectContaining({ method: 'PUT', body: JSON.stringify({ workspaceId: 'workspace-one' }), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }) }));
  await expect(desktop.handlers.get('jira:issue:local-child')?.({}, location, 'local-id', 'Add the flag', 'Context')).rejects.toThrow('Malformed work item');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/local-children`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ id: 'local-id', title: 'Add the flag', description: 'Context' }), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:link-types')?.({}, 'connection/one', 'site/one');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/link-types`, expect.objectContaining({ method: 'POST', headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:issue:link')?.({}, location, '10000', false, 'ENG-8');
  expect(fetch).toHaveBeenLastCalledWith(`${base}/issues/7/links`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ typeId: '10000', outward: false, key: 'ENG-8' }), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }) }));
  await desktop.handlers.get('jira:connections')?.({});
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/jira/connections', expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
});

it('records a Jira visit without parsing the empty response and preserves HTTP errors', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  const location = { connectionId: 'connection/one', cloudId: 'site/one', issueId: '7' };
  await expect(desktop.handlers.get('jira:issue:recent')?.({}, location, 20)).resolves.toBeNull();
  expect(fetch).toHaveBeenLastCalledWith(
    'http://127.0.0.1:9527/api/jira/connections/connection%2Fone/sites/site%2Fone/issues/7/recent',
    expect.objectContaining({ method: 'POST', body: JSON.stringify({ dwellSeconds: 20 }), headers: withHeaders({
      'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session',
    }) }),
  );
  fetch.mockImplementation(async () => new Response(JSON.stringify({ message: 'Ticket unavailable' }), { status: 404 }));
  await expect(desktop.handlers.get('jira:issue:recent')?.({}, location, 20)).rejects.toThrow('Ticket unavailable');
});

it('returns an ordinary cache miss from the workspace issue lookup', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  vi.stubGlobal('fetch', vi.fn(async () => new Response('null')));
  await expect(desktop.handlers.get('workspaces:issue')?.({}, 'workspace', 7)).resolves.toBeNull();
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

it('applies only saved appearance and keeps the current theme when saving fails', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const save = desktop.handlers.get('settings:app:save');
  for (const [theme, source] of [['DARK', 'dark'], ['LIGHT', 'light'], ['SYSTEM', 'system']]) {
    const preferences = { theme, taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30 };
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(preferences))));
    await expect(save?.({}, preferences)).resolves.toEqual(preferences);
    expect(nativeTheme.themeSource).toBe(source);
  }
  vi.stubGlobal('fetch', vi.fn(async () => new Response('Cannot save', { status: 500 })));
  await expect(save?.({}, { theme: 'DARK' })).rejects.toThrow('Cannot save');
  expect(nativeTheme.themeSource).toBe('system');
});

it('reads API-key usage by stable ID while retaining the alias and desktop authentication', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const accountId = 'api-key-11111111-1111-4111-8111-111111111111';
  const account = { id: accountId, label: 'DeepSeek · ds-001' };
  const usage: ProviderUsageDto = { accountId, plan: null, checkedAt: '2026-01-01T00:00:00Z', windows: [], message: 'Usage limits aren’t available for this API-key account.' };
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify([account]))).mockResolvedValueOnce(new Response(JSON.stringify(usage)));
  vi.stubGlobal('fetch', fetch);
  await expect(desktop.handlers.get('usage:accounts')?.({})).resolves.toEqual([account]);
  await expect(desktop.handlers.get('usage:read')?.({}, accountId)).resolves.toMatchObject(usage);
  expect(fetch).toHaveBeenLastCalledWith(`http://127.0.0.1:9527/agent/usage/${accountId}`,
    expect.objectContaining({ headers: withHeaders({ 'X-ByteQuay-Session': 'test-session' }) }));
  expect(() => desktop.handlers.get('usage:read')?.({}, 'api-key-../codex')).toThrow('Invalid usage account');
  expect(fetch).toHaveBeenCalledTimes(2);
});

it('authenticates Google account, mail and Gemini connector requests without exposing the session token', async () => {
  const { GeminiSetup } = await import('./google/geminiSetup');
  vi.spyOn(GeminiSetup.prototype, 'status').mockResolvedValue({ configured: true, installed: true });
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetcher = vi.fn(async () => new Response('{}'));
  vi.stubGlobal('fetch', fetcher);
  const profile = '00000000-0000-0000-0000-000000000001';
  const requests: [string, string, unknown[]][] = [
    ['google:accounts:add', '/accounts', []],
    ['google:account:select', '/account', [profile]],
    ['google:archive', '/archive', [profile, 'message']],
    ['google:send', '/send', [{ profile, to: 'to@example.com', subject: 'Hello', body: 'Text', replyId: '' }]],
  ];
  for (const [channel, endpoint, args] of requests) {
    await desktop.handlers.get(channel)?.({}, ...args);
    expect(fetcher).toHaveBeenLastCalledWith(`http://127.0.0.1:9527/api/google${endpoint}`, expect.objectContaining({
      headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }),
    }));
  }
  // Reject before credentials or a provider process are touched; inspect the real native chat command request.
  fetcher.mockImplementation(async () => new Response('Account unavailable', { status: 403 }));
  await desktop.handlers.get('google-chat:prompt')?.({}, profile, 'Read mail');
  expect(fetcher).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/google/chat-command', expect.objectContaining({
    headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }), body: JSON.stringify({ profile }),
  }));
  await desktop.handlers.get('google-chat:clear')?.({}, profile);
});

it.each([
  ['review:draft:edit', ['item-1', 'Edited body', true], 'PUT', { expectedVersion: 4, body: 'Edited body', included: true }],
  ['review:draft:add-finding', ['review-1', 'finding-1'], 'POST', { expectedVersion: 4, reviewId: 'review-1', findingId: 'finding-1' }],
  ['review:draft:save', ['Review body', 'COMMENT'], 'PUT', { expectedVersion: 4, body: 'Review body', event: 'COMMENT' }],
])('preserves the JSON body and session header for %s', async (channel, args, method, body) => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const draft: ReviewDraftDto = { repo: 'owner/repo', number: 7, body: '', event: 'COMMENT', items: [], reviewPublication: null, postedSummaryFindingIds: [], version: 5, updatedAt: '2026-09-07T00:00:00Z' };
  const fetch = vi.fn(async () => new Response(JSON.stringify(draft)));
  vi.stubGlobal('fetch', fetch);
  await desktop.handlers.get(channel)?.({}, 'owner/repo', 7, 4, ...args);
  expect(fetch).toHaveBeenCalledOnce();
  expect(fetch).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
    method, body: JSON.stringify(body), headers: withHeaders({ 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session' }),
  }));
});

function withHeaders(expected: Record<string, string>) {
  return { asymmetricMatch: (value: unknown) => value instanceof Headers && Object.entries(expected).every(([key, value_]) => value.get(key) === value_) };
}
