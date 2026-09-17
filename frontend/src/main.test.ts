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
  ipcMain: { handle: (channel: string, handler: (...args: unknown[]) => Promise<unknown>) => desktop.handlers.set(channel, handler) },
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

it('starts a bounded CI repair through the desktop session and reads or stops it', async () => {
  await import('./main');
  await expect(desktop.listeners.get('ready')?.()).rejects.toThrow('End test startup');
  const fetch = vi.fn(async () => new Response(JSON.stringify(CI_RUN), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetch);
  await expect(desktop.handlers.get('ci-autofix:start')?.({}, 'workspace-1', 17, 'codex', 'repair-key')).resolves.toEqual(CI_RUN);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/ci-autofix/runs', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ workspaceId: 'workspace-1', pullRequestNumber: 17, laneId: 'codex', maxAttempts: 3,
      allowFailedJobReruns: true, allowHistoryRewrites: true, allowExactLeasePush: true }),
    headers: { 'Content-Type': 'application/json', 'X-ByteQuay-Session': 'test-session', 'Idempotency-Key': 'repair-key' },
  }));
  await expect(desktop.handlers.get('ci-autofix:cancel')?.({}, 'run/one')).resolves.toEqual(CI_RUN);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:9527/api/ci-autofix/runs/run%2Fone/cancel', expect.objectContaining({
    method: 'POST', headers: { 'X-ByteQuay-Session': 'test-session' },
  }));
  fetch.mockImplementationOnce(async () => new Response(JSON.stringify([CI_RUN])));
  await expect(desktop.handlers.get('ci-autofix:list')?.({})).resolves.toEqual([CI_RUN]);
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

it.each([
  { channel: 'review:start', args: ['workspace-1', 'trinodb/trino', 31102] },
  { channel: 'review:resume', args: ['review-1'] },
  { channel: 'review:update', args: ['review-1'] },
  { channel: 'agent:task:local-review:sync', args: ['task-1', 'workspace-1', 7] },
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
  const local = { task: { ...contracts.task, localReviewHead: 'a'.repeat(40) }, warning: 'Local experiments were discarded because they conflicted with the update.' };
  const isLocal = channel === 'agent:task:local-review:sync';
  const fetch = vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(init.signal?.reason), { once: true });
    setTimeout(() => resolve(new Response(JSON.stringify(isLocal ? local : review), {
      headers: { 'Content-Type': 'application/json' },
    })), 65_000);
  }));
  vi.stubGlobal('fetch', fetch);
  const handler = desktop.handlers.get(channel);
  expect(handler).toBeDefined();
  const result = handler?.({}, ...args).then(value => ({ value }), error => ({ error }));

  await vi.advanceTimersByTimeAsync(65_000);

  expect(await result).toEqual({ value: isLocal ? { task: parseAgentTask(local.task), warning: local.warning } : parseAgentReview(review) });
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
