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
import { app, BrowserWindow, ipcMain, Menu, nativeImage, shell, WebContentsView } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import type {
  CherryPickExecutionRequest,
  CherryPickPreviewRequest,
  CommitHistoryRefDto,
  CommitHistorySourceDto,
} from './types';
import {
  BACKEND_BASE,
  killBackend,
  reportBackendFailure,
  sessionToken,
  spawnBackend,
  waitForBackendReady,
} from './backendProcess';

app.setName('ByteQuay');
app.commandLine.appendSwitch('js-flags', '--max-old-space-size=1024');

let mainWindow: BrowserWindow | null = null;
let browserView: WebContentsView | null = null;
let pendingWindow: Promise<void> | null = null;

if (!app.requestSingleInstanceLock()) {
  app.quit();
}
else {
  app.on('second-instance', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  });
}

function iconPath(): string | null {
  const roots = [
    path.join(__dirname, '..', '..', '..', 'build'),
    path.join(__dirname, '..', '..', 'build'),
    path.join(process.cwd(), 'build'),
    path.join(app.getAppPath(), 'build'),
  ];
  return roots.map(root => path.join(root, 'icon.icns')).find(fs.existsSync) ?? null;
}

function configureApplication(): void {
  const icon = iconPath();
  if (process.platform === 'darwin' && app.dock && icon) {
    const image = nativeImage.createFromPath(icon);
    if (!image.isEmpty()) app.dock.setIcon(image);
  }
  const about: Electron.AboutPanelOptionsOptions = {
    applicationName: 'ByteQuay',
    applicationVersion: app.getVersion(),
    copyright: '© 2026 Jian Chen — Apache License 2.0',
    website: 'https://github.com/bytequay/bytequay',
  };
  if (icon) about.iconPath = icon;
  app.setAboutPanelOptions(about);
  if (process.platform === 'darwin') {
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      {
        label: 'ByteQuay',
        submenu: [
          { role: 'about' },
          { type: 'separator' },
          { role: 'services' },
          { type: 'separator' },
          { role: 'hide' },
          { role: 'hideOthers' },
          { role: 'unhide' },
          { type: 'separator' },
          { role: 'quit' },
        ],
      },
      { role: 'editMenu' },
      { role: 'viewMenu' },
      { role: 'windowMenu' },
    ]));
  }
}

function normalizeDevServerUrl(value: string): string {
  const url = new URL(value);
  if (url.hostname === 'localhost') url.hostname = '127.0.0.1';
  return url.toString();
}

function requestBrowser(url: string): void {
  if (!/^https?:/i.test(url) || !mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send('inapp:open-request', { url });
}

function destroyBrowserView(): void {
  if (!browserView) return;
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.contentView.removeChildView(browserView);
  }
  if (!browserView.webContents.isDestroyed()) browserView.webContents.close();
  browserView = null;
}

function openPopup(url: string): void {
  if (!/^https?:/i.test(url)) return;
  const popup = new BrowserWindow({
    width: 1100,
    height: 760,
    backgroundColor: '#ffffff',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      partition: 'persist:inapp-browser',
    },
  });
  popup.webContents.setWindowOpenHandler(({ url: nested }) => {
    openPopup(nested);
    return { action: 'deny' };
  });
  void popup.loadURL(url);
}

function ensureWindow(): Promise<void> {
  if (mainWindow && !mainWindow.isDestroyed()) return Promise.resolve();
  pendingWindow ??= createWindow().finally(() => { pendingWindow = null; });
  return pendingWindow;
}

async function createWindow(): Promise<void> {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 1000,
    minHeight: 620,
    backgroundColor: '#ffffff',
    titleBarStyle: 'hiddenInset',
    vibrancy: process.platform === 'darwin' ? 'sidebar' : undefined,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  if (process.platform === 'darwin') mainWindow.setWindowButtonVisibility(false);
  mainWindow.on('closed', () => {
    destroyBrowserView();
    mainWindow = null;
  });
  const sendFullScreen = (isFullScreen: boolean) => {
    mainWindow?.webContents.send('window:fullscreen-state', { isFullScreen });
  };
  mainWindow.on('enter-full-screen', () => sendFullScreen(true));
  mainWindow.on('leave-full-screen', () => sendFullScreen(false));
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    requestBrowser(url);
    return { action: 'deny' };
  });
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!/^https?:/i.test(url)) return;
    const devOrigin = MAIN_WINDOW_VITE_DEV_SERVER_URL
      ? new URL(normalizeDevServerUrl(MAIN_WINDOW_VITE_DEV_SERVER_URL)).origin
      : null;
    if (devOrigin && url.startsWith(devOrigin)) return;
    event.preventDefault();
    requestBrowser(url);
  });
  mainWindow.webContents.on('did-start-loading', destroyBrowserView);

  if (MAIN_WINDOW_VITE_DEV_SERVER_URL) {
    await mainWindow.loadURL(normalizeDevServerUrl(MAIN_WINDOW_VITE_DEV_SERVER_URL));
  }
  else {
    await mainWindow.loadFile(
      path.join(__dirname, `../renderer/${MAIN_WINDOW_VITE_NAME}/index.html`),
    );
  }
}

function extractMessage(body: string): string {
  if (!body) return '';
  try {
    return (JSON.parse(body) as { message?: string }).message ?? body;
  }
  catch {
    return body;
  }
}

async function backendJson<T = unknown>(input: string | URL, init?: RequestInit): Promise<T> {
  const response = await fetch(input, { signal: AbortSignal.timeout(60_000), ...init });
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(extractMessage(body)
      || `${init?.method ?? 'GET'} ${input.toString()} returned ${response.status}`);
  }
  return response.json() as Promise<T>;
}

async function backendVoid(input: string | URL, init?: RequestInit): Promise<void> {
  const response = await fetch(input, { signal: AbortSignal.timeout(60_000), ...init });
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(extractMessage(body)
      || `${init?.method ?? 'GET'} ${input.toString()} returned ${response.status}`);
  }
}

function prUrl(pathname: string, repo: string, number: number): URL {
  const url = new URL(`${BACKEND_BASE}${pathname}`);
  url.searchParams.set('repo', repo);
  url.searchParams.set('number', String(number));
  return url;
}

function registerIpc(): void {
  ipcMain.handle('window:fullscreen', () => mainWindow?.isFullScreen() ?? false);
  ipcMain.handle('window:control', (_event, action: string) => {
    if (!mainWindow) return;
    if (action === 'close') mainWindow.close();
    if (action === 'minimize') mainWindow.minimize();
    if (action === 'zoom') mainWindow.setFullScreen(!mainWindow.isFullScreen());
  });
  ipcMain.handle('app:version', () => ({ version: app.getVersion() }));

  // Cherry-pick endpoints sit behind the desktop session filter, and the token
  // stays here: the renderer never sees it, so a page in the embedded GitHub
  // view cannot reach them.
  ipcMain.handle('cherrypick:preview', (_event, request: CherryPickPreviewRequest) => {
    const { workspaceId, ...body } = request;
    return backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/cherry-pick/preview`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-ByteQuay-Session': sessionToken(),
      },
      body: JSON.stringify(body),
      // A preview fetches the source remote in full; on a repository the size
      // of Trino that outlasts the default request timeout.
      signal: AbortSignal.timeout(15 * 60_000),
    });
  });
  ipcMain.handle('cherrypick:preview:get', (_event, previewId: string) =>
    backendJson(`${BACKEND_BASE}/api/cherry-pick-ranges/previews/${encodeURIComponent(previewId)}`, {
      headers: { 'X-ByteQuay-Session': sessionToken() },
    }));
  ipcMain.handle('cherrypick:providers', () =>
    backendJson(`${BACKEND_BASE}/agent/providers/status`, {
      headers: { 'X-ByteQuay-Session': sessionToken() },
    }));
  ipcMain.handle('cherrypick:execution:start', (_event, request: CherryPickExecutionRequest) => {
    const { workspaceId, ...body } = request;
    return backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/cherry-pick/executions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-ByteQuay-Session': sessionToken(),
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15 * 60_000),
    });
  });
  ipcMain.handle('cherrypick:execution:get', (_event, executionId: string) =>
    backendJson(`${BACKEND_BASE}/api/cherry-pick-ranges/executions/${encodeURIComponent(executionId)}`, {
      headers: { 'X-ByteQuay-Session': sessionToken() },
    }));
  ipcMain.handle('cherrypick:execution:message', (_event, executionId: string, message: string) =>
    backendJson(`${BACKEND_BASE}/api/cherry-pick-ranges/executions/${encodeURIComponent(executionId)}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-ByteQuay-Session': sessionToken(),
      },
      body: JSON.stringify({ message }),
    }));
  ipcMain.handle('cherrypick:execution:cancel', (_event, workspaceId: string, executionId: string) => {
    const url = `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/cherry-pick/executions/${encodeURIComponent(executionId)}/cancel`;
    return backendJson(url, {
      method: 'POST',
      headers: { 'X-ByteQuay-Session': sessionToken() },
    });
  });
  const deliveryUrl = (workspaceId: string, executionId: string, action: string) =>
    `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`
      + `/cherry-pick/executions/${encodeURIComponent(executionId)}/${action}`;
  ipcMain.handle('cherrypick:delivery:get', (_event, workspaceId: string, executionId: string) =>
    backendJson(deliveryUrl(workspaceId, executionId, 'delivery'), {
      headers: { 'X-ByteQuay-Session': sessionToken() },
    }));
  ipcMain.handle('cherrypick:delivery:push', (_event, workspaceId: string, executionId: string) =>
    backendJson(deliveryUrl(workspaceId, executionId, 'push'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-ByteQuay-Session': sessionToken(),
      },
      body: JSON.stringify({ confirmed: true }),
      signal: AbortSignal.timeout(15 * 60_000),
    }));
  ipcMain.handle('cherrypick:delivery:pull-request', (
    _event,
    workspaceId: string,
    executionId: string,
  ) => backendJson(deliveryUrl(workspaceId, executionId, 'pull-request'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-ByteQuay-Session': sessionToken(),
    },
    body: JSON.stringify({ confirmed: true }),
  }));
  ipcMain.handle('cherrypick:execution:list', (_event, limit: number) => {
    const url = new URL(`${BACKEND_BASE}/api/cherry-pick-ranges/executions`);
    url.searchParams.set('limit', String(limit));
    return backendJson(url, { headers: { 'X-ByteQuay-Session': sessionToken() } });
  });
  // Local checkout browsing for the workspace Commits tab. Only the workspace
  // ID crosses IPC; the backend owns and resolves the managed checkout path.
  const gitGet = (workspaceId: string, endpoint: string, params: Record<string, string>) => {
    const url = new URL(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/git/${endpoint}`,
    );
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
    return backendJson(url, { headers: { 'X-ByteQuay-Session': sessionToken() } });
  };
  const gitPost = (workspaceId: string, endpoint: string, body: unknown, timeoutMs = 120_000) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/git/${endpoint}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-ByteQuay-Session': sessionToken(),
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  ipcMain.handle('git:overview', (_event, workspaceId: string) =>
    gitGet(workspaceId, 'overview', {}));
  ipcMain.handle('git:branches', (_event, workspaceId: string, remote: string) =>
    gitGet(workspaceId, 'branches', { remote }));
  ipcMain.handle('git:log', (
    _event,
    workspaceId: string,
    source: CommitHistorySourceDto,
    branch: string,
    limit: number,
  ) => gitGet(workspaceId, 'commits', { source, branch, limit: String(limit) }));
  ipcMain.handle('git:diff', (_event, workspaceId: string, base: string, head: string) =>
    gitGet(workspaceId, 'diff', { base, head }));
  ipcMain.handle('git:diff-content', (
    _event,
    workspaceId: string,
    base: string,
    head: string,
    path: string,
  ) => gitGet(workspaceId, 'diff-content', { base, head, path }));
  ipcMain.handle('git:status', (_event, workspaceId: string) =>
    gitGet(workspaceId, 'status', {}));
  ipcMain.handle('git:refresh-history', (
    _event,
    workspaceId: string,
    refs: CommitHistoryRefDto[],
  ) => gitPost(workspaceId, 'commits/refresh', { refs }));
  ipcMain.handle('git:commit', (_event, workspaceId: string, summary: string, description: string) =>
    gitPost(workspaceId, 'commit', { summary, description }));
  ipcMain.handle('git:reword', (_event, workspaceId: string, branch: string, sha: string, message: string) =>
    gitPost(workspaceId, 'reword', { branch, sha, message }));
  ipcMain.handle('git:squash', (
    _event,
    workspaceId: string,
    branch: string,
    baseSha: string,
    tipSha: string,
    message: string,
  ) => gitPost(workspaceId, 'squash', { branch, baseSha, tipSha, message }));

  ipcMain.handle('pat:save', async (_event, pat: string) => {
    if (!pat?.trim()) throw new Error('PAT must not be empty');
    await backendJson(`${BACKEND_BASE}/api/auth/github/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value: pat.trim() }),
    });
    void fetch(`${BACKEND_BASE}/api/settings/sync/trigger`, { method: 'POST' });
    return true;
  });
  ipcMain.handle('pat:has', async () => {
    if (!await waitForBackendReady()) return false;
    try {
      const result = await backendJson<{ configured: boolean }>(
        `${BACKEND_BASE}/api/auth/github/configured`,
      );
      return result.configured;
    }
    catch {
      return false;
    }
  });

  ipcMain.handle('prs:list', (_event, workspaceId?: string) => backendJson(workspaceId
    ? `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/pull-requests`
    : `${BACKEND_BASE}/prs`));
  ipcMain.handle('prs:sync', (_event, workspaceId?: string) => backendVoid(workspaceId
    ? `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/pull-requests/sync`
    : `${BACKEND_BASE}/api/settings/sync/trigger`, { method: 'POST' }));
  ipcMain.handle('prs:detail', (_event, repo: string, number: number) =>
    backendJson(prUrl('/prs/detail', repo, number)));
  ipcMain.handle('prs:users', (_event, repo: string, workspaceId?: string) => {
    if (workspaceId) {
      return backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/contributors`);
    }
    const url = new URL('/prs/users', BACKEND_BASE);
    url.searchParams.set('repo', repo);
    return backendJson(url);
  });
  ipcMain.handle('prs:merge-mode', (_event, repo: string, branch: string) => {
    const url = new URL('/prs/merge-mode', BACKEND_BASE);
    url.searchParams.set('repo', repo);
    url.searchParams.set('branch', branch);
    return backendJson(url);
  });
  ipcMain.handle('prs:detail:refresh', (_event, repo: string, number: number) => {
    const url = prUrl('/prs/detail/refresh', repo, number);
    return backendJson(url, { method: 'POST' });
  });
  ipcMain.handle('prs:diff', (
    _event,
    repo: string,
    number: number,
    baseSha?: string,
    headSha?: string,
  ) => {
    const url = prUrl('/prs/diffFiles', repo, number);
    if (baseSha && headSha) {
      url.searchParams.set('base', baseSha);
      url.searchParams.set('head', headSha);
    }
    return backendJson(url);
  });
  ipcMain.handle('prs:diff-commits', (_event, repo: string, number: number) =>
    backendJson(prUrl('/prs/diffCommits', repo, number)));
  ipcMain.handle('prs:diff-content', (
    _event,
    repo: string,
    number: number,
    path: string,
    baseSha?: string,
    headSha?: string,
  ) => {
    const url = prUrl('/prs/diffContent', repo, number);
    url.searchParams.set('path', path);
    if (baseSha && headSha) {
      url.searchParams.set('base', baseSha);
      url.searchParams.set('head', headSha);
    }
    return backendJson(url);
  });
  ipcMain.handle('prs:review-comment:create', (
    _event,
    repo: string,
    number: number,
    comment: import('./types').InlineReviewCommentDto,
  ) => backendVoid(prUrl('/prs/review-comment', repo, number), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(comment),
  }));
  ipcMain.handle('prs:comment', (
    _event,
    repo: string,
    number: number,
    body: string,
  ) => {
    const url = prUrl('/prs/comment', repo, number);
    return backendVoid(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
  });
  ipcMain.handle('prs:review-comment:reply', (
    _event,
    repo: string,
    number: number,
    commentId: number,
    body: string,
  ) => {
    const url = prUrl(`/prs/review-comments/${commentId}/replies`, repo, number);
    return backendVoid(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
  });
  ipcMain.handle('prs:review-thread:resolution', (
    _event,
    repo: string,
    number: number,
    commentId: number,
    resolved: boolean,
  ) => {
    const url = prUrl(`/prs/review-comments/${commentId}/resolution`, repo, number);
    return backendVoid(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolved }),
    });
  });
  ipcMain.handle('prs:approve', (_event, prId: number, repo: string, number: number) => {
    const url = prUrl('/prs/approve', repo, number);
    url.searchParams.set('id', String(prId));
    return backendVoid(url, { method: 'POST' });
  });
  ipcMain.handle('prs:review', (
    _event,
    prId: number,
    repo: string,
    number: number,
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
    body: string,
    comments?: import('./types').InlineReviewCommentDto[],
  ) => {
    const url = prUrl('/prs/review', repo, number);
    url.searchParams.set('id', String(prId));
    return backendVoid(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event, body, comments }),
    });
  });
  ipcMain.handle('prs:merge', (
    _event,
    prId: number,
    repo: string,
    number: number,
    strategy: string,
  ) => {
    const url = prUrl('/prs/merge', repo, number);
    url.searchParams.set('id', String(prId));
    url.searchParams.set('strategy', strategy);
    return backendVoid(url, { method: 'POST' });
  });

  ipcMain.handle('repos:list', () => backendJson(`${BACKEND_BASE}/api/repos`));
  ipcMain.handle('repos:remove', (_event, owner: string, repo: string) =>
    backendVoid(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`,
      { method: 'DELETE' },
    ));
  ipcMain.handle('workspaces:search', (_event, query: string, limit: number) => {
    const url = new URL(`${BACKEND_BASE}/api/repository-search`);
    url.searchParams.set('q', query);
    url.searchParams.set('limit', String(limit));
    return backendJson(url);
  });
  ipcMain.handle('workspaces:preview', (_event, selectedRepositoryId: number) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/watch-preview`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ selectedRepositoryId }),
    }));
  ipcMain.handle('workspaces:watch', (_event, command: {
    selectedRepositoryId: number;
    previewFingerprint: string;
    name?: string;
  }) => backendJson(`${BACKEND_BASE}/api/workspaces`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(command),
  }));
  ipcMain.handle('workspaces:list', () => backendJson(`${BACKEND_BASE}/api/workspaces`));
  ipcMain.handle('workspaces:get', (_event, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`));
  ipcMain.handle('workspaces:metadata', (_event, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/metadata`));
  ipcMain.handle('workspaces:issues', (_event, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues`));
  ipcMain.handle('workspaces:issues:sync', (_event, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/sync`, {
      method: 'POST',
    }));
  ipcMain.handle('workspaces:sync', (_event, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/sync`, {
      method: 'POST',
    }));
  ipcMain.handle('workspaces:upstream', (
    _event,
    workspaceId: string,
    upstreamWorkspaceId: string | null,
  ) => backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/upstream`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ upstreamWorkspaceId }),
  }));
  ipcMain.handle('workspaces:remove', (_event, workspaceId: string) =>
    backendVoid(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`, {
      method: 'DELETE',
    }));
  ipcMain.handle('repos:profile', () => backendJson(`${BACKEND_BASE}/api/profile`));
  ipcMain.handle('repos:contribution', (_event, login: string) => {
    const url = new URL(`${BACKEND_BASE}/api/contribution-graph`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });
  ipcMain.handle('repos:contribution-day', (_event, login: string, date: string) => {
    const url = new URL(`${BACKEND_BASE}/api/contribution-graph/day`);
    url.searchParams.set('login', login);
    url.searchParams.set('date', date);
    return backendJson(url);
  });
  ipcMain.handle('repos:pull', (_event, owner: string, repo: string, number: number) =>
    backendJson(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pulls/${number}`,
    ));
  ipcMain.handle('repos:meta', (_event, owner: string, repo: string) =>
    backendJson(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/meta`,
    ));
  ipcMain.handle('repos:activity', (_event, login: string) => {
    const url = new URL(`${BACKEND_BASE}/api/activity/recent`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });
  ipcMain.handle('repos:following', (_event, login: string) => {
    const url = new URL(`${BACKEND_BASE}/api/activity/following`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });

  ipcMain.handle('github:cli-available', () =>
    backendJson(`${BACKEND_BASE}/api/auth/github/cli`));
  ipcMain.handle('github:cli-import', () =>
    backendJson(`${BACKEND_BASE}/api/auth/github/cli/import`, { method: 'POST' }));

  ipcMain.handle('external:open', async (_event, url: string) => {
    if (/^https?:/i.test(url)) await shell.openExternal(url);
  });
  ipcMain.handle('inapp:open', (_event, url: string) => requestBrowser(url));
  type Bounds = { x: number; y: number; width: number; height: number };
  const bounds = (value: Bounds): Bounds => ({
    x: Math.round(value.x),
    y: Math.round(value.y),
    width: Math.max(0, Math.round(value.width)),
    height: Math.max(0, Math.round(value.height)),
  });
  ipcMain.handle('inapp:mount', async (_event, url: string, target: Bounds) => {
    if (!/^https?:/i.test(url) || !mainWindow) return;
    destroyBrowserView();
    const view = new WebContentsView({
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        partition: 'persist:inapp-browser',
      },
    });
    view.setBounds(bounds(target));
    view.setBackgroundColor('#ffffff');
    mainWindow.contentView.addChildView(view);
    browserView = view;
    const pushState = () => {
      if (!browserView || !mainWindow) return;
      const contents = browserView.webContents;
      mainWindow.webContents.send('inapp:nav-state', {
        url: contents.getURL(),
        title: contents.getTitle(),
        canGoBack: contents.navigationHistory.canGoBack(),
        canGoForward: contents.navigationHistory.canGoForward(),
        loading: contents.isLoadingMainFrame(),
      });
    };
    view.webContents.on('did-navigate', pushState);
    view.webContents.on('did-navigate-in-page', pushState);
    view.webContents.on('did-start-loading', pushState);
    view.webContents.on('did-stop-loading', pushState);
    view.webContents.on('page-title-updated', pushState);
    view.webContents.setWindowOpenHandler(({ url: next }) => {
      if (/^https?:/i.test(next)) void view.webContents.loadURL(next);
      return { action: 'deny' };
    });
    await view.webContents.loadURL(url);
  });
  ipcMain.handle('inapp:bounds', (_event, value: Bounds) => browserView?.setBounds(bounds(value)));
  ipcMain.handle('inapp:unmount', destroyBrowserView);
  ipcMain.handle('inapp:back', () => {
    if (browserView?.webContents.navigationHistory.canGoBack()) {
      browserView.webContents.navigationHistory.goBack();
    }
  });
  ipcMain.handle('inapp:forward', () => {
    if (browserView?.webContents.navigationHistory.canGoForward()) {
      browserView.webContents.navigationHistory.goForward();
    }
  });
  ipcMain.handle('inapp:reload', () => browserView?.webContents.reload());
  ipcMain.handle('inapp:popout', (_event, url: string) => openPopup(url));
}

app.on('ready', async () => {
  configureApplication();
  registerIpc();
  spawnBackend();
  if (app.isPackaged && !await waitForBackendReady()) reportBackendFailure();
  await ensureWindow();
  void fetch(`${BACKEND_BASE}/api/settings/sync/trigger`, { method: 'POST' });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
app.on('before-quit', killBackend);
app.on('activate', () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.focus();
  }
  else {
    void ensureWindow();
  }
});
