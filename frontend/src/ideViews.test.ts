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
import type { BrowserWindow } from 'electron';
import { IdeViews } from './ideViews';
import type { LocalRunDto } from './pulls/code-tests/contracts';
import type { ExecutionRequest } from './ide/executionProtocol';

const desktop = vi.hoisted(() => ({
  handlers: new Map<string, (...args: unknown[]) => unknown>(),
  views: [] as MockView[],
}));
type MockView = {
  options: { webPreferences: Record<string, unknown> };
  setVisible: ReturnType<typeof vi.fn>;
  setBounds: ReturnType<typeof vi.fn>;
  webContents: {
    destroyed: boolean; mainFrame: object; isDestroyed: () => boolean;
    loadURL: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn>; send: ReturnType<typeof vi.fn>;
    emit: (name: string, ...args: unknown[]) => void;
    session: { protocol: { handle: ReturnType<typeof vi.fn> } };
  };
};
vi.mock('electron', async () => {
  const { EventEmitter } = await import('node:events');
  return {
    protocol: { registerSchemesAsPrivileged: vi.fn() },
    ipcMain: {
      handle: (key: string, fn: (...args: unknown[]) => unknown) => desktop.handlers.set(key, fn),
      on: (key: string, fn: (...args: unknown[]) => unknown) => desktop.handlers.set(key, fn),
    },
    net: { fetch: vi.fn(async () => new Response('asset')) },
    WebContentsView: class {
      webContents = Object.assign(new EventEmitter(), {
        destroyed: false, mainFrame: {},
        isDestroyed: () => this.webContents.destroyed,
        close: vi.fn(() => { this.webContents.destroyed = true; }),
        loadURL: vi.fn(async (): Promise<void> => undefined), send: vi.fn(), setWindowOpenHandler: vi.fn(),
        session: { protocol: { handle: vi.fn(), unhandle: vi.fn() }, setPermissionRequestHandler: vi.fn(),
          setPermissionCheckHandler: vi.fn(), webRequest: { onBeforeRequest: vi.fn() } },
      });
      setVisible = vi.fn(); setBounds = vi.fn();
      constructor(public options: MockView['options']) { desktop.views.push(this); }
    },
  };
});
vi.mock('node:fs/promises', () => ({ realpath: async (path: string) => path }));
vi.mock('./backendProcess', () => ({ backendBase: () => 'http://127.0.0.1:9527', sessionToken: () => 'desktop-only-token' }));
const connection = { taskId: 'task', sessionId: 'session', authority: '127.0.0.1:5210', root: '/task', bundleRoot: '/bundle',
  cacheRoot: '/cache', connectionToken: 'server-only-token', commit: 'a'.repeat(40) };
const host = { kind: 'task', workspaceId: 'workspace', taskId: 'task' };
const sender = { mainFrame: {}, send: vi.fn(), getZoomFactor: () => 1 };
const window = { webContents: sender, contentView: { addChildView: vi.fn(), removeChildView: vi.fn() },
  getContentBounds: () => ({ width: 1000, height: 800 }) } as unknown as BrowserWindow;
const fetchMock = vi.fn();
const parent = () => ({ sender, senderFrame: sender.mainFrame });
const child = () => ({ sender: desktop.views[0].webContents, senderFrame: desktop.views[0].webContents.mainFrame });
const invoke = (name: string, ...args: unknown[]) => desktop.handlers.get(name)?.(...args);
let views: IdeViews;
beforeEach(() => {
  vi.useFakeTimers(); desktop.views.length = 0; desktop.handlers.clear(); sender.send.mockClear();
  fetchMock.mockReset().mockImplementation(async (_url: string, request: RequestInit) => new Response(request.method === 'POST' ? JSON.stringify(connection) : ''));
  vi.stubGlobal('fetch', fetchMock);
  views = new IdeViews(() => window); views.register();
});
afterEach(() => { views.dispose(); vi.useRealTimers(); vi.unstubAllGlobals(); });

it('deduplicates worktree opens and keeps native credentials out of renderer URLs', async () => {
  const [first, second] = await Promise.all([invoke('ide:open', parent(), host), invoke('ide:open', parent(), host)]);
  expect(first).toBe(second); expect(desktop.views).toHaveLength(1);
  expect(desktop.views[0].options.webPreferences).toMatchObject({ sandbox: true, contextIsolation: true, nodeIntegration: false });
  expect(desktop.views[0].webContents.loadURL).toHaveBeenCalledWith('bytequay-ide://app/ide/index.html');
  expect(invoke('ide:bootstrap', child())).toEqual({ connection, context: null });
  expect(() => invoke('ide:bootstrap', parent())).toThrow('Unknown IDE view');
  expect(() => invoke('ide:present', { sender, senderFrame: {} }, first, null)).toThrow('Unknown IDE owner');
});

it('stops a hidden idle worktree even if the parent keeps reporting hidden bounds', async () => {
  const id = await invoke('ide:open', parent(), host);
  invoke('ide:present', parent(), id, { x: 0, y: 0, width: 900, height: 700 });
  await vi.advanceTimersByTimeAsync(150_000);
  expect(desktop.views[0].webContents.close).not.toHaveBeenCalled();
  invoke('ide:present', parent(), id, null);
  for (let i = 0; i < 9; i++) {
    invoke('ide:present', parent(), id, null);
    await vi.advanceTimersByTimeAsync(15_000);
  }
  expect(desktop.views[0].webContents.close).toHaveBeenCalledOnce();
  expect(fetchMock).toHaveBeenCalledWith('http://127.0.0.1:9527/agent/ide/task/session', expect.objectContaining({ method: 'DELETE' }));
  expect(sender.send).toHaveBeenCalledWith('ide:event', expect.objectContaining({ id, closed: true }));
});

it('keeps unsaved drafts, running tests and paused debugging alive while hidden', async () => {
  await invoke('ide:open', parent(), host);
  invoke('ide:report', child(), { dirty: 1, busy: false });
  await vi.advanceTimersByTimeAsync(150_000);
  expect(desktop.views[0].webContents.close).not.toHaveBeenCalled();
  invoke('ide:report', child(), { dirty: 0, busy: true });
  await vi.advanceTimersByTimeAsync(150_000);
  expect(desktop.views[0].webContents.close).not.toHaveBeenCalled();
  invoke('ide:report', child(), { dirty: 0, busy: false });
  await vi.advanceTimersByTimeAsync(15_000);
  expect(desktop.views[0].webContents.close).toHaveBeenCalledOnce();
});

it('retries failed process cleanup before acknowledging a closed editor', async () => {
  const id = await invoke('ide:open', parent(), host);
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ message: 'Process is still live' }), { status: 409 }));
  await expect(invoke('ide:close', parent(), id)).rejects.toThrow('Process is still live');
  expect(sender.send).not.toHaveBeenCalledWith('ide:event', expect.objectContaining({ closed: true }));
  await vi.advanceTimersByTimeAsync(15_000);
  expect(desktop.views[0].webContents.close).toHaveBeenCalledOnce();
  expect(sender.send).toHaveBeenCalledWith('ide:event', expect.objectContaining({ id, closed: true }));
});

it('binds encoded saves to the owning task and rejects escapes and malformed bytes', async () => {
  await invoke('ide:open', parent(), host);
  await invoke('ide:save', child(), 'src/main.go', 'eA==', 'a'.repeat(64));
  expect(fetchMock).toHaveBeenCalledWith('http://127.0.0.1:9527/agent/ide/tasks/workspace/task/file', expect.objectContaining({ method: 'PUT',
    body: JSON.stringify({ path: 'src/main.go', content: 'eA==', expectedHash: 'a'.repeat(64) }) }));
  for (const args of [['../outside', 'eA==', '-'], ['src/main.go', 'not-base64', '-'], ['src/main.go', 'eA==', 'bad-hash']]) {
    await expect(invoke('ide:save', child(), ...args)).rejects.toThrow('Invalid editor save');
  }
});


it('reads blame only for the current task and keeps backend credentials in main', async () => {
  const id = await invoke('ide:open', parent(), host);
  invoke('ide:update', parent(), id, { host, task: null, review: null, detail: null });
  await invoke('ide:request', child(), 'readCodeBlame', [host, 'src/測試.go'], false);
  expect(fetchMock).toHaveBeenCalledWith('http://127.0.0.1:9527/agent/ide/tasks/workspace/task/blame?path=src%2F%E6%B8%AC%E8%A9%A6.go', expect.objectContaining({ method: 'GET' }));
  expect(sender.send).not.toHaveBeenCalledWith('ide:request', expect.anything());
  expect(() => invoke('ide:request', child(), 'readCodeBlame', [{ ...host, taskId: 'other' }, 'src/main.go'], false)).toThrow('different task');
  for (const path of ['../secret', '/etc/passwd', '.git/config', 'src/../secret', 'src//file']) {
    expect(() => invoke('ide:request', child(), 'readCodeBlame', [host, path], false)).toThrow('workspace path');
  }
});


it('bounds history queries to fixed read routes and validated revisions', async () => {
  const id = await invoke('ide:open', parent(), host);
  invoke('ide:update', parent(), id, { host, task: null, review: null, detail: null });
  const revision = 'a'.repeat(40);
  await invoke('ide:request', child(), 'readCodeHistory', [host, { revision, offset: 100, path: 'renamed 測試.go', token: 'unrecognized' }], false);
  const url = new URL(fetchMock.mock.calls.at(-1)?.[0] as string);
  expect(url.pathname).toBe('/agent/ide/tasks/workspace/task/history');
  expect(Object.fromEntries(url.searchParams)).toEqual({ revision, offset: '100', path: 'renamed 測試.go' });
  for (const [method, query] of [['readHistorySnapshot', { revision, path: '../secret' }], ['readHistoryCommit', { revision: '--all' }],
    ['readHistoryCommit', { revision, parent: 'HEAD' }], ['readCodeHistory', { offset: 1_000_001 }]]) {
    expect(() => invoke('ide:request', child(), method, [host, query], false)).toThrow();
  }
});


it('reuses a query-only view for the visible editor and binds replies to that child', async () => {
  const request = { id: 'query', connection, taskId: 'task', workspaceId: 'workspace', expiresAt: Date.now() + 300_000,
    query: { operation: 'REFERENCES', path: 'src/main.go', line: 1, column: 1, limit: 20 } } as const;
  const controller = new AbortController();
  const answer = views.query(request, controller.signal);
  await vi.advanceTimersByTimeAsync(0);
  expect(desktop.views).toHaveLength(1);
  expect(desktop.views[0].webContents.send).not.toHaveBeenCalledWith('ide:query', expect.anything());
  invoke('ide:query-ready', child());
  expect(desktop.views[0].webContents.send).toHaveBeenCalledWith('ide:query', request);
  await vi.advanceTimersByTimeAsync(150_000);
  expect(desktop.views[0].webContents.close).not.toHaveBeenCalled();
  const id = await invoke('ide:open', parent(), { kind: 'review', reviewId: 'review' });
  expect(desktop.views).toHaveLength(1);
  invoke('ide:update', parent(), id, { host: { kind: 'review', reviewId: 'review' }, review: null, task: null, detail: null });
  const result = { status: 'READY', items: [] as unknown[], truncated: false, message: 'No references' };
  expect(() => invoke('ide:query-reply', parent(), 'query', result)).toThrow('Unknown IDE view');
  invoke('ide:query-reply', child(), 'query', result);
  expect(await answer).toEqual({ connection, result });
  await vi.advanceTimersByTimeAsync(135_000);
  expect(desktop.views[0].webContents.close).toHaveBeenCalledOnce();
});

it('cancels semantic work when its caller stops and rejects malformed replies', async () => {
  const request = { id: 'query', connection, taskId: 'task', workspaceId: 'workspace', expiresAt: Date.now() + 60_000,
    query: { operation: 'DOCUMENT_SYMBOLS', path: 'src/main.go', limit: 20 } } as const;
  const controller = new AbortController();
  const answer = views.query(request, controller.signal);
  await vi.advanceTimersByTimeAsync(0);
  invoke('ide:query-ready', child()); controller.abort();
  expect((await answer).result.status).toBe('CANCELLED');
  expect(desktop.views[0].webContents.send).toHaveBeenCalledWith('ide:query', { ...request, cancel: true });
  const another = views.query({ ...request, id: 'another' }, new AbortController().signal);
  await vi.advanceTimersByTimeAsync(0);
  invoke('ide:query-reply', child(), 'another', { status: 'READY', items: [{ path: '../outside' }], truncated: false, message: '' });
  expect((await another).result.status).toBe('UNAVAILABLE');
  const crashController = new AbortController();
  const afterCrash = views.query({ ...request, id: 'after-crash' }, crashController.signal);
  await vi.advanceTimersByTimeAsync(0);
  desktop.views[0].webContents.destroyed = true;
  desktop.views[0].webContents.send.mockImplementation(() => { throw new Error('Object has been destroyed'); });
  crashController.abort();
  expect((await afterCrash).result.status).toBe('CANCELLED');
});


it('delivers backend queries without a parent renderer and cancels removed queue entries', async () => {
  const request = { id: 'queued', connection, taskId: 'task', workspaceId: 'workspace', expiresAt: Date.now() + 60_000,
    query: { operation: 'DOCUMENT_SYMBOLS', path: 'src/main.go', limit: 20 } } as const;
  let poll: ((response: Response) => void) | undefined;
  let first = true;
  fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
    if (url.includes('/queries?after=')) {
      if (first) { first = false; return new Response(JSON.stringify({ version: 1, requests: [request] })); }
      return new Promise<Response>((resolve, reject) => {
        poll = resolve; init.signal?.addEventListener('abort', () => reject(new Error('Stopped')), { once: true });
      });
    }
    if (url.endsWith('/queries/queued')) return new Response('');
    return new Response('');
  });
  views.startQueries();
  await vi.advanceTimersByTimeAsync(0);
  invoke('ide:query-ready', child());
  const result = { status: 'READY', items: [] as unknown[], truncated: false, message: 'Imported project' };
  invoke('ide:query-reply', child(), 'queued', result);
  await vi.advanceTimersByTimeAsync(0);
  expect(fetchMock).toHaveBeenCalledWith('http://127.0.0.1:9527/agent/ide/queries/queued', expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ taskId: 'task', sessionId: 'session', result }) }));
  expect(sender.send).not.toHaveBeenCalledWith('ide:request', expect.anything());
  poll?.(new Response(JSON.stringify({ version: 2, requests: [] })));
  await vi.advanceTimersByTimeAsync(0);
});


it('binds execution metadata to the owning task and keeps cleanup-pending runs alive while hidden', async () => {
  const id = await invoke('ide:open', parent(), host);
  const start: ExecutionRequest = { operation: 'start', request: { scope: 'DEBUG', label: 'Example', path: null } };
  await expect(invoke('ide:execution', child(), start)).rejects.toThrow('Open Code & Tests');
  invoke('ide:update', parent(), id, { host, task: null, review: null, detail: null });
  const run: LocalRunDto = { id: 'run-1', ordinal: 1, initiator: 'USER', scope: 'DEBUG', path: null, testClass: null, testMethod: null, module: null,
    command: 'Example', sourceHeadSha: 'a'.repeat(40), localPaths: [], sourceFingerprint: 'source', status: 'RUNNING',
    startedAt: '2026-01-01T00:00:00Z', endedAt: null, exitCode: null, tests: null, failures: null, errors: null, skipped: null,
    outputTruncated: false, omittedBytes: null, treeUnverified: false, results: [] };
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(run)));
  await invoke('ide:execution', child(), start);
  expect(fetchMock).toHaveBeenLastCalledWith('http://127.0.0.1:9527/agent/ide/tasks/workspace/task/executions', expect.objectContaining({
    body: JSON.stringify({ scope: 'DEBUG', label: 'Example', path: null, sessionId: 'session' }),
  }));
  await expect(invoke('ide:execution', child(), start)).rejects.toThrow('already using');
  await expect(invoke('ide:execution', child(), { operation: 'stop', id: 'another-run' })).rejects.toThrow('does not belong');
  const finish: ExecutionRequest = { operation: 'finish', id: run.id, request: { cancelled: false, failed: false, incomplete: false, exitCode: 0, tests: [] } };
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ ...run, status: 'COMPLETED', treeUnverified: true })));
  await invoke('ide:execution', child(), finish);
  await vi.advanceTimersByTimeAsync(150_000);
  expect(desktop.views[0].webContents.close).not.toHaveBeenCalled();
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ ...run, status: 'COMPLETED', treeUnverified: false })));
  await invoke('ide:execution', child(), finish);
  await vi.advanceTimersByTimeAsync(15_000);
  expect(desktop.views[0].webContents.close).toHaveBeenCalledOnce();
});
