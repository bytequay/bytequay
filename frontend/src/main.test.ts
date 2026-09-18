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
  const task = { kind: 'task', taskId: 'task/one', workspaceId: 'workspace/one', repo: 'trinodb/trino', number: 30304 };
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

  await expect(handler('code:runs:output')(task, 'run/1', 12.7)).resolves.toEqual(output);
  expect(fetch).toHaveBeenLastCalledWith(
    'http://127.0.0.1:9527/agent/tasks/task%2Fone/code/runs/run%2F1/output?workspaceId=workspace%2Fone&repo=trinodb%2Ftrino&number=30304&after=12',
    expect.objectContaining({ headers: { 'X-ByteQuay-Session': 'test-session' } }));
  await expect(handler('code:runs:output')(review, 'run/1', 0)).resolves.toEqual(output);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/agent-review/review%2Fone/code/runs/run%2F1/output?after=0', expect.any(Object));

  fetch.mockResolvedValueOnce(json({ reason: 'No Maven wrapper (mvnw) in this working copy', action: 'OPEN_TERMINAL' }, 422));
  await expect(handler('code:runs:start')(task, 'core/src/test/java/io/x/TestAccess.java', null))
    .resolves.toEqual({ refusal: { reason: 'No Maven wrapper (mvnw) in this working copy', action: 'OPEN_TERMINAL' } });
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/agent/tasks/task%2Fone/code/runs?workspaceId=workspace%2Fone&repo=trinodb%2Ftrino&number=30304',
    expect.objectContaining({ method: 'POST', body: JSON.stringify({ path: 'core/src/test/java/io/x/TestAccess.java', method: null }) }));

  fetch.mockResolvedValueOnce(json({ currentHash: 'h2', currentContent: 'disk' }, 409));
  await expect(handler('code:save')(task, 'core/src/main/java/io/x/Access.java', 'draft', 'h1'))
    .resolves.toEqual({ conflict: { currentHash: 'h2', currentContent: 'disk' } });
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/agent/tasks/task%2Fone/code/file?workspaceId=workspace%2Fone&repo=trinodb%2Ftrino&number=30304',
    expect.objectContaining({ method: 'PUT', body: JSON.stringify({ path: 'core/src/main/java/io/x/Access.java', content: 'draft', expectedHash: 'h1' }) }));

  fetch.mockResolvedValueOnce(json({ message: "The task's worktree was removed" }, 409));
  await expect(handler('code:save')(task, 'a.java', 'draft', 'h1')).rejects.toThrow("The task's worktree was removed");

  const calls = fetch.mock.calls.length;
  await expect(handler('code:files')({ kind: 'task', taskId: 'task/one' })).rejects.toThrow('Malformed Code & Tests host');
  expect(fetch).toHaveBeenCalledTimes(calls);
});
