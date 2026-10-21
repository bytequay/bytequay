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
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { EventEmitter } from 'node:events';
import type { PullRequestDto } from './types';
import type { AppNotice } from './notices/contracts';
import { reviewFixture } from './pulls/agent-review/fixtures/review';

vi.mock('./notices/host', () => ({ NoticeHost: class {
    constructor(_window: unknown, read: () => Promise<unknown>, open: (notice: AppNotice) => Promise<void>) { desktop.readNotices = read; desktop.openNotice = open; }
    register = vi.fn(); attach = vi.fn(); start = vi.fn(); stop = vi.fn(); releaseWindow = vi.fn(); raise = vi.fn();
} }));

const desktop = vi.hoisted(() => ({
  listeners: new Map<string, () => Promise<void>>(),
  notifications: [] as EventEmitter[],
  constructNotification: vi.fn(),
  show: vi.fn(),
  send: vi.fn(),
  userData: '',
  readNotices: async (): Promise<unknown> => [],
  openNotice: async (_notice: AppNotice): Promise<void> => {},
  openExternal: vi.fn(async () => {}),
}));

vi.mock('electron', async () => {
  const { EventEmitter } = await import('node:events');
  return {
    shell: { openExternal: desktop.openExternal },
    nativeTheme: { on: vi.fn(), themeSource: 'system', shouldUseDarkColors: false },
    app: {
      setName: vi.fn(), commandLine: { appendSwitch: vi.fn() }, requestSingleInstanceLock: () => true,
      on: (event: string, listener: () => Promise<void>) => desktop.listeners.set(event, listener),
      getAppPath: () => process.cwd(), getPath: () => desktop.userData, getVersion: () => 'test', setAboutPanelOptions: vi.fn(),
    },
    BrowserWindow: class extends EventEmitter {
      webContents = Object.assign(new EventEmitter(), { send: desktop.send, setWindowOpenHandler: vi.fn(), focus: vi.fn(), isDestroyed: () => false });
      setWindowButtonVisibility = vi.fn();
      isDestroyed = () => false;
      loadURL = vi.fn();
    },
    Notification: class extends EventEmitter {
      static isSupported() { return true; }
      constructor() {
        super();
        desktop.constructNotification();
        desktop.notifications.push(this);
      }
      show() { desktop.show(this); }
      close = vi.fn();
    },
    protocol: { registerSchemesAsPrivileged: vi.fn() },
    ipcMain: { handle: vi.fn(), on: vi.fn() },
    Menu: { setApplicationMenu: vi.fn(), buildFromTemplate: vi.fn() },
    screen: { getPrimaryDisplay: () => ({ workAreaSize: { width: 1600, height: 1000 } }) },
  };
});

vi.mock('./backendProcess', () => ({
  backendBase: () => 'http://127.0.0.1:9527', sessionToken: () => 'test-session',
  spawnBackend: vi.fn(), waitForBackendReady: async () => true, killBackend: vi.fn(),
}));

const pr: PullRequestDto = {
  id: 1, repo: 'owner/repo', number: 42, title: 'A monitored PR', author: 'someone', htmlUrl: '',
  createdAt: '2026-09-06T10:00:00Z', updatedAt: '2026-09-06T10:01:00Z', origin: 'OTHER',
  labels: [], labelColors: null, draft: false, viewedAt: null, reviewedAt: null, handledAction: null,
  requestedReviewers: [], ciStatus: 'FAILING', additions: 0, deletions: 0, commentCount: 0,
  state: 'open', mergeable: false, mergeableState: 'unstable', headPushedAt: null, headRef: 'branch',
  monitoredAt: '2026-09-06T10:00:00Z', activityAt: '2026-09-06T10:01:00Z',
};
const prs = [pr, { ...pr, id: 2, number: 43 }];
const failure = 'The operation couldn’t be completed. (UNErrorDomain error 1.)';

beforeEach(() => {
  vi.resetModules();
  vi.useFakeTimers();
  desktop.notifications.length = 0;
  desktop.constructNotification.mockReset();
  desktop.show.mockReset();
  desktop.send.mockClear();
  desktop.openExternal.mockClear();
  desktop.userData = fs.mkdtempSync(path.join(os.tmpdir(), 'bytequay-notifications-test-'));
  vi.stubGlobal('MAIN_WINDOW_VITE_DEV_SERVER_URL', 'http://127.0.0.1:5173');
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/prs/attention') ? prs : {}))));
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(async () => {
  await desktop.listeners.get('before-quit')?.();
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  fs.rmSync(desktop.userData, { recursive: true, force: true });
});

async function start(): Promise<void> {
  await import('./main');
  await desktop.listeners.get('ready')?.();
  await vi.advanceTimersByTimeAsync(0);
}

it('reads only task notices and opens the matching task', async () => {
  const task: AppNotice = { id: 'question', kind: 'QUESTION', taskId: 'task', workspaceId: 'workspace', turnId: 'turn', reviewId: null,
    repository: 'owner/repo', laneId: 'codex', title: 'A question', pullRequest: null, pullRequestTitle: null, changes: null, message: 'Please answer', occurredAt: '2026-10-12T00:00:00Z' };
  const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/agent/notices') ? [task] : [])));
  vi.stubGlobal('fetch', fetcher);
  await start();
  fetcher.mockClear();
  expect(await desktop.readNotices()).toEqual([task]);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(fetcher).toHaveBeenCalledWith('http://127.0.0.1:9527/agent/notices', expect.objectContaining({ headers: expect.any(Object) }));
  await desktop.openNotice(task);
  expect(fetcher).toHaveBeenCalledWith('http://127.0.0.1:9527/agent/tasks/task?workspaceId=workspace', expect.objectContaining({ headers: expect.any(Object) }));
  expect(desktop.send).toHaveBeenCalledWith('notices:open-task', { taskId: 'task', workspaceId: 'workspace', turnId: 'turn', reviewId: null, workItemId: null });
  expect(desktop.openExternal).not.toHaveBeenCalled();
  await desktop.openNotice({ ...task, id: 'next', kind: 'NEXT_WORK', turnId: null, workItemId: 'work-2' });
  expect(desktop.send).toHaveBeenLastCalledWith('notices:open-task', { taskId: 'task', workspaceId: 'workspace', turnId: null, reviewId: null, workItemId: 'work-2' });
});

it('opens completed reviews through their review route and checks their task and workspace', async () => {
  const review = reviewFixture();
  const notice: AppNotice = { id: 'review-finished', kind: 'TURN_FINISHED', taskId: review.task.id, workspaceId: review.workspaceId,
    turnId: 'turn', reviewId: review.id, repository: review.repo, laneId: 'codex', title: 'Review finished', pullRequest: 'owner/repo#42', pullRequestTitle: 'Improve recovery',
    changes: { update: false, commits: 2, files: 3, additions: 42, deletions: 12 }, message: 'Ready', occurredAt: '2026-10-12T00:00:00Z' };
  const fetcher = vi.fn(async () => new Response(JSON.stringify(review)));
  vi.stubGlobal('fetch', fetcher);
  await start();
  fetcher.mockClear(); desktop.send.mockClear();
  await desktop.openNotice(notice);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(fetcher).toHaveBeenCalledWith(`http://127.0.0.1:9527/api/agent-review/${review.id}`, expect.objectContaining({ headers: expect.any(Object) }));
  expect(desktop.send).toHaveBeenCalledWith('notices:open-task', { taskId: review.task.id, workspaceId: review.workspaceId, turnId: 'turn', reviewId: review.id,
    workItemId: null });
  expect(desktop.openExternal).not.toHaveBeenCalled();
  desktop.send.mockClear();
  for (const mismatch of [{ id: 'other' }, { workspaceId: 'other' }, { task: { ...review.task, id: 'other' } }]) {
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ ...review, ...mismatch })));
    await expect(desktop.openNotice(notice)).rejects.toThrow('no longer matches');
  }
  expect(desktop.send).not.toHaveBeenCalled();
});

it.each(['failed event', 'show throws', 'constructor throws'])('pauses native delivery after %s while attention updates keep polling', async mode => {
  if (mode === 'show throws') desktop.show.mockImplementation(() => { throw new Error(failure); });
  if (mode === 'constructor throws') desktop.constructNotification.mockImplementation(() => { throw new Error(failure); });
  await start();
  if (mode === 'failed event') {
    expect(desktop.notifications).toHaveLength(2);
    for (const notification of desktop.notifications) notification.emit('failed', {}, failure);
  }
  const attempts = desktop.constructNotification.mock.calls.length;
  expect(attempts).toBe(mode === 'failed event' ? 2 : 1);
  desktop.send.mockClear();
  vi.mocked(fetch).mockClear();

  await vi.advanceTimersByTimeAsync(60_000);

  expect(desktop.constructNotification).toHaveBeenCalledTimes(attempts);
  expect(console.warn).toHaveBeenCalledTimes(1);
  expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('paused until restart'), expect.anything());
  expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith('/prs/attention'))).toHaveLength(12);
  // Unchanged rows are not sent to the page again.
  expect(desktop.send.mock.calls.filter(([channel]) => channel === 'prs:attention-updated')).toHaveLength(0);
  expect(fs.existsSync(path.join(desktop.userData, 'pr-notifications.json'))).toBe(false);

  // A fresh app session retries undelivered activity and only persists a successful show.
  await desktop.listeners.get('before-quit')?.();
  vi.resetModules();
  desktop.constructNotification.mockReset();
  desktop.show.mockImplementation((notification: EventEmitter) => notification.emit('show'));
  await start();
  expect(desktop.constructNotification).toHaveBeenCalledTimes(2);
  const state = JSON.parse(fs.readFileSync(path.join(desktop.userData, 'pr-notifications.json'), 'utf8'));
  expect(Object.keys(state.delivered)).toHaveLength(2);
  await vi.advanceTimersByTimeAsync(10_000);
  expect(desktop.constructNotification).toHaveBeenCalledTimes(2);
});
