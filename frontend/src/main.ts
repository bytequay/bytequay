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
import { app, BrowserWindow, clipboard, dialog, ipcMain, Menu, nativeImage, shell, WebContentsView } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import {
  parseAgentApproval,
  parseAgentAttachment,
  parseAgentCompletion,
  parseAgentInputDelivery,
  parseAgentQueue,
  parseAgentQueuedInput,
  parseAgentRuntimeStopImpact,
  parseAgentTask,
  parseAgentTasks,
  parseAgentTurn,
  parseAgentTurnSettings,
} from './agents/contracts';
import { parseAgentEventSequence } from './agents/events';
import { AgentSubscriptions } from './agents/subscriptions';
import type {
  AgentApprovalDecision,
  AgentAttachmentDto,
  CherryPickExecutionRequest,
  CherryPickPreviewRequest,
  CommitHistoryRefDto,
  CommitHistorySourceDto,
  CreateAgentTaskRequest,
  GitHubCodeSnippetRequest,
  QueueAgentInputRequest,
  RecentDraftRequest,
  RecentFilterQuery,
  RecentTouchRequest,
  StartAgentTurnRequest,
  SteerAgentTurnRequest,
} from './types';
import {
  BACKEND_BASE,
  killBackend,
  reportBackendFailure,
  sessionToken,
  spawnBackend,
  waitForBackendReady,
} from './backendProcess';

// The app can outlive the terminal that launched it; a closed logging pipe
// must not crash the main process when Electron reports an IPC rejection.
const ignoreOutputError = (): void => undefined;
process.stdout.on('error', ignoreOutputError);
process.stderr.on('error', ignoreOutputError);

app.setName('ByteQuay');
app.commandLine.appendSwitch('js-flags', '--max-old-space-size=1024');

let mainWindow: BrowserWindow | null = null;

// The backend enforces the same bound; checking here spares reading a file that cannot be sent.
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

function imageMediaType(filePath: string): string {
  const extension = path.extname(filePath).toLowerCase();
  if (extension === '.png') return 'image/png';
  if (extension === '.gif') return 'image/gif';
  return 'image/jpeg';
}
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
  mainWindow.webContents.on('found-in-page', (_event, result) => {
    mainWindow?.webContents.send('find:result', {
      activeMatchOrdinal: result.activeMatchOrdinal,
      matches: result.matches,
    });
  });

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

/** The session secret never leaves main; the renderer only ever sees parsed results. */
function agentHeaders(json = false): Record<string, string> {
  const headers: Record<string, string> = { 'X-ByteQuay-Session': sessionToken() };
  if (json) headers['Content-Type'] = 'application/json';
  return headers;
}

const agentSubscriptions = new AgentSubscriptions((taskId, after, signal) => fetch(
  `${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/stream`
    + `?${new URLSearchParams({ after: String(after) })}`,
  { headers: { ...agentHeaders(), Accept: 'text/event-stream' }, signal },
));

function refreshDashboardInBackground(): void {
  void backendVoid(`${BACKEND_BASE}/api/settings/sync/trigger`, {
    method: 'POST',
    signal: AbortSignal.timeout(15 * 60_000),
  }).then(() => {
    if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.webContents.isDestroyed()) {
      mainWindow.webContents.send('pull-requests:synced');
    }
  }).catch(() => {});
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
  ipcMain.handle('find:start', (_event, text: string, options: Electron.FindInPageOptions) => {
    mainWindow?.webContents.findInPage(text, options);
  });
  ipcMain.handle('find:stop', () => mainWindow?.webContents.stopFindInPage('clearSelection'));

  ipcMain.handle('agent:better-words', async (
    _event,
    text: string,
    repository: string,
    workspaceId?: string,
  ) => {
    const response = await backendJson<{ text: string }>(`${BACKEND_BASE}/agent/better-words`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-ByteQuay-Session': sessionToken(),
      },
      body: JSON.stringify({ text, repository, workspaceId }),
    });
    return response.text;
  });

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
  // The renderer has no environment of its own, so the one dev signal comes from here.
  ipcMain.handle('app:is-development', () => !app.isPackaged);
  ipcMain.handle('agent:tasks:list', (_event, workspaceId: string, limit?: number) => {
    const query = new URLSearchParams({ workspaceId });
    if (limit !== undefined) query.set('limit', String(limit));
    return backendJson(`${BACKEND_BASE}/agent/tasks?${query}`, { headers: agentHeaders() })
      .then(parseAgentTasks);
  });
  ipcMain.handle('agent:tasks:create', (_event, request: CreateAgentTaskRequest) =>
    backendJson(`${BACKEND_BASE}/agent/tasks`, {
      method: 'POST',
      headers: agentHeaders(true),
      body: JSON.stringify(request),
    }).then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:tasks:get', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:defaults', (
    _event,
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    defaults: unknown,
  ) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/defaults`
      + `?${new URLSearchParams({ workspaceId })}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...agentHeaders() },
      body: JSON.stringify({ expectedVersion, defaults }),
    }).then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:pull-requests', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/pull-requests`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() }));
  ipcMain.handle('agent:task:rename', (
    _event,
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    title: string,
  ) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}`
      + `?${new URLSearchParams({ workspaceId })}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', ...agentHeaders() },
      body: JSON.stringify({ expectedVersion, title }),
    }).then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:effective-settings', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/effective-settings`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((settings) => parseAgentTurnSettings(settings)));
  ipcMain.handle('agent:events:read', async (_event, taskId: string, after?: number, limit?: number) => {
    const query = new URLSearchParams();
    if (after !== undefined) query.set('after', String(after));
    if (limit !== undefined) query.set('limit', String(limit));
    const page = await backendJson<unknown>(
      `${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/events?${query}`,
      { headers: agentHeaders() },
    );
    // Validated in main so a payload the renderer cannot represent never reaches it.
    return parseAgentEventSequence(page, after ?? 0);
  });
  ipcMain.handle('agent:turns:start', (_event, request: StartAgentTurnRequest) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(request.taskId)}/turns`, {
      method: 'POST',
      headers: { ...agentHeaders(true), 'Idempotency-Key': request.idempotencyKey },
      body: JSON.stringify({
        message: request.message,
        expectedVersion: request.expectedTaskVersion,
        attachments: request.attachments ?? [],
      }),
    }).then((turn) => parseAgentTurn(turn)));
  ipcMain.handle('agent:turns:steer', (_event, request: SteerAgentTurnRequest) =>
    backendJson(`${BACKEND_BASE}/agent/turns/${encodeURIComponent(request.turnId)}/steer`, {
      method: 'POST',
      headers: { ...agentHeaders(true), 'Idempotency-Key': request.idempotencyKey },
      body: JSON.stringify({ message: request.message, attachments: request.attachments ?? [] }),
    }).then((delivery) => parseAgentInputDelivery(delivery)));
  ipcMain.handle('agent:queue:add', (_event, request: QueueAgentInputRequest) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(request.taskId)}/queue`
      + `?${new URLSearchParams({ workspaceId: request.workspaceId })}`, {
      method: 'POST',
      headers: { ...agentHeaders(true), 'Idempotency-Key': request.idempotencyKey },
      body: JSON.stringify({
        message: request.message,
        attachments: request.attachments ?? [],
        expectedVersion: request.expectedVersion,
      }),
    }).then((queued) => parseAgentQueuedInput(queued)));
  ipcMain.handle('agent:queue:list', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/queue`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((queue) => parseAgentQueue(queue)));
  ipcMain.handle('agent:queue:remove', (_event, taskId: string, workspaceId: string, queuedInputId: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/queue/${encodeURIComponent(queuedInputId)}`
      + `?${new URLSearchParams({ workspaceId })}`, { method: 'DELETE', headers: agentHeaders() })
      .then((queued) => parseAgentQueuedInput(queued)));
  ipcMain.handle('agent:queue:resume', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/queue/resume`
      + `?${new URLSearchParams({ workspaceId })}`, { method: 'POST', headers: agentHeaders() })
      .then((turn) => parseAgentTurn(turn)));
  // Main owns the file paths: it picks, reads and uploads; the renderer receives facts and references.
  ipcMain.handle('agent:attachments:pick', async (_event, taskId: string, workspaceId: string) => {
    if (mainWindow === null) return [];
    const picked = await dialog.showOpenDialog(mainWindow, {
      title: 'Attach images',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'gif'] }],
    });
    const stored: AgentAttachmentDto[] = [];
    for (const filePath of picked.filePaths) {
      const stat = await fs.promises.stat(filePath);
      if (stat.size > MAX_ATTACHMENT_BYTES) {
        throw new Error(`${path.basename(filePath)} is larger than 10 MiB`);
      }
      const bytes = await fs.promises.readFile(filePath);
      const url = new URL(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/attachments`);
      url.searchParams.set('workspaceId', workspaceId);
      stored.push(parseAgentAttachment(await backendJson(url, {
        method: 'POST',
        headers: {
          ...agentHeaders(),
          'Content-Type': imageMediaType(filePath),
          'X-Attachment-Name': encodeURIComponent(path.basename(filePath)),
        },
        body: bytes,
      })));
    }
    return stored;
  });
  ipcMain.handle('agent:attachments:read', async (_event, taskId: string, workspaceId: string, attachmentId: string) => {
    const url = new URL(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/attachments/${encodeURIComponent(attachmentId)}`);
    url.searchParams.set('workspaceId', workspaceId);
    const response = await fetch(url, { headers: agentHeaders(), signal: AbortSignal.timeout(60_000) });
    if (!response.ok) {
      throw new Error(response.status === 404 ? 'Attachment is no longer available' : `GET attachment returned ${response.status}`);
    }
    return {
      mediaType: response.headers.get('content-type') ?? 'application/octet-stream',
      bytes: new Uint8Array(await response.arrayBuffer()),
    };
  });
  ipcMain.handle('agent:attachments:remove', (_event, taskId: string, workspaceId: string, attachmentId: string) => {
    const url = new URL(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/attachments/${encodeURIComponent(attachmentId)}`);
    url.searchParams.set('workspaceId', workspaceId);
    return backendVoid(url, { method: 'DELETE', headers: agentHeaders() });
  });
  ipcMain.handle('agent:turns:get', (_event, turnId: string) =>
    backendJson(`${BACKEND_BASE}/agent/turns/${encodeURIComponent(turnId)}`, { headers: agentHeaders() })
      .then((turn) => parseAgentTurn(turn)));
  ipcMain.handle('agent:turns:cancel', (_event, turnId: string) =>
    backendJson(`${BACKEND_BASE}/agent/turns/${encodeURIComponent(turnId)}/cancel`, {
      method: 'POST',
      headers: agentHeaders(),
    }).then((turn) => parseAgentTurn(turn)));
  ipcMain.handle('agent:approvals:resolve', (
    _event,
    approvalId: string,
    decision: AgentApprovalDecision,
    scopedPermission?: string,
  ) =>
    backendJson(`${BACKEND_BASE}/agent/approvals/${encodeURIComponent(approvalId)}`, {
      method: 'POST',
      headers: agentHeaders(true),
      body: JSON.stringify({
        decision: decision === 'ALLOW_FOR_TASK' ? 'ALLOW' : decision,
        forTask: decision === 'ALLOW_FOR_TASK',
        scopedPermission: scopedPermission ?? null,
      }),
    }).then((approval) => parseAgentApproval(approval)));
  ipcMain.handle('agent:tasks:complete', (_event, taskId: string, workspaceId: string, notes?: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/complete`
      + `?${new URLSearchParams({ workspaceId })}`, {
      method: 'POST',
      headers: agentHeaders(true),
      body: JSON.stringify({ notes: notes ?? null }),
    }).then((completion) => parseAgentCompletion(completion)));
  ipcMain.handle('agent:task:mark-failed', (_event, taskId: string, workspaceId: string, reason: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/mark-failed`
      + `?${new URLSearchParams({ workspaceId })}`, {
      method: 'POST',
      headers: agentHeaders(true),
      body: JSON.stringify({ reason }),
    }).then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:stop-impact', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/runtime/stop-impact`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((impact) => parseAgentRuntimeStopImpact(impact)));
  ipcMain.handle('agent:task:stop', (_event, taskId: string, workspaceId: string, generation: string) =>
    backendJson(`${BACKEND_BASE}/agent/tasks/${encodeURIComponent(taskId)}/runtime/stop`
      + `?${new URLSearchParams({ workspaceId })}`, {
      method: 'POST',
      headers: agentHeaders(true),
      body: JSON.stringify({ generation }),
      // Stopping waits for the process tree to be verified gone, which can take a while.
      signal: AbortSignal.timeout(90_000),
    }).then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:stream:subscribe', (event, taskId: string, after: number) =>
    agentSubscriptions.start(event.sender, taskId, after));
  ipcMain.handle('agent:stream:unsubscribe', (_event, subscriptionId: string) => {
    agentSubscriptions.stop(subscriptionId);
  });
  ipcMain.handle('agent:providers:refresh', () =>
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
    remote?: string,
  ) => gitGet(workspaceId, 'commits', {
    source,
    branch,
    limit: String(limit),
    ...(remote ? { remote } : {}),
  }));
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
  ipcMain.handle('git:commit-refresh-status', (_event, workspaceId: string) =>
    gitGet(workspaceId, 'commits/refresh', {}));
  ipcMain.handle('git:refresh-history', (
    _event,
    workspaceId: string,
    refs: CommitHistoryRefDto[],
  ) => gitPost(workspaceId, 'commits/refresh', { refs }, 15 * 60_000));
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
    refreshDashboardInBackground();
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
  ipcMain.handle('pat:account', async () => {
    if (!await waitForBackendReady()) return { configured: false, maskedToken: null };
    try {
      return await backendJson(`${BACKEND_BASE}/api/auth/github/account`);
    }
    catch {
      return { configured: false, maskedToken: null };
    }
  });
  // The token value only ever transits main-process memory on its way to the
  // clipboard; the renderer sees a boolean.
  ipcMain.handle('pat:copy', async () => {
    const result = await backendJson<{ value: string }>(
      `${BACKEND_BASE}/api/auth/github/token/value`,
    );
    clipboard.writeText(result.value);
    return true;
  });
  ipcMain.handle('pat:delete', () =>
    backendVoid(`${BACKEND_BASE}/api/auth/github/token`, { method: 'DELETE' }));

  ipcMain.handle('settings:models', () => backendJson(`${BACKEND_BASE}/api/settings/models`));
  ipcMain.handle('settings:models:select', (
    _event,
    configId: string,
    model: string,
    effort: string | null,
  ) => backendJson(`${BACKEND_BASE}/api/settings/models/${encodeURIComponent(configId)}/selection`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, effort }),
  }));
  ipcMain.handle('settings:models:main', (_event, configId: string) =>
    backendJson(`${BACKEND_BASE}/api/settings/models/main`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ configId }),
    }));
  ipcMain.handle('settings:api-keys:add', (_event, provider: string, alias: string, key: string) =>
    backendJson(`${BACKEND_BASE}/api/settings/api-keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider, alias, key }),
    }));
  ipcMain.handle('settings:api-keys:remove', (_event, alias: string) =>
    backendJson(`${BACKEND_BASE}/api/settings/api-keys/${encodeURIComponent(alias)}`, {
      method: 'DELETE',
    }));
  ipcMain.handle('settings:quiet-hours', () =>
    backendJson(`${BACKEND_BASE}/api/settings/quiet-hours`));
  ipcMain.handle('settings:quiet-hours:save', (_event, quietHours: unknown) =>
    backendJson(`${BACKEND_BASE}/api/settings/quiet-hours`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(quietHours),
    }));

  ipcMain.handle('workspaces:settings', (_event, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/settings`));
  ipcMain.handle('workspaces:rename', (_event, workspaceId: string, name: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    }));
  ipcMain.handle('workspaces:remotes:add', (_event, workspaceId: string, name: string, url: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/remotes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, url }),
    }));
  ipcMain.handle('workspaces:remotes:remove', (_event, workspaceId: string, name: string) =>
    backendJson(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/remotes/${encodeURIComponent(name)}`,
      { method: 'DELETE' },
    ));
  ipcMain.handle('workspaces:agent-model:set', (
    _event,
    workspaceId: string,
    configId: string,
    model: string,
    effort: string | null,
  ) => backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/agent-model`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ configId, model, effort }),
  }));
  ipcMain.handle('workspaces:agent-model:clear', (_event, workspaceId: string) =>
    backendVoid(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/agent-model`, {
      method: 'DELETE',
    }));
  ipcMain.handle('workspaces:token:save', (_event, workspaceId: string, value: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/github-token`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }),
    }));
  ipcMain.handle('workspaces:token:delete', (_event, workspaceId: string) =>
    backendVoid(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/github-token`, {
      method: 'DELETE',
    }));
  ipcMain.handle('workspaces:token:copy', async (_event, workspaceId: string) => {
    const result = await backendJson<{ value: string }>(
      `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/github-token/value`,
    );
    clipboard.writeText(result.value);
    return true;
  });

  // Recent is local-only: every read answers from SQLite, so these stay
  // useful with the network off.
  ipcMain.handle('recent:list', (_event, query: RecentFilterQuery) => {
    const url = new URL(`${BACKEND_BASE}/recent`);
    if (query.from) url.searchParams.set('from', query.from);
    if (query.to) url.searchParams.set('to', query.to);
    if (query.types?.length) url.searchParams.set('types', query.types.join(','));
    if (query.action) url.searchParams.set('action', query.action);
    if (query.workspace) url.searchParams.set('workspace', query.workspace);
    if (query.changedSinceRead) url.searchParams.set('changedSinceRead', 'true');
    if (query.text) url.searchParams.set('text', query.text);
    if (query.cursor) url.searchParams.set('cursor', query.cursor);
    return backendJson(url);
  });
  ipcMain.handle('recent:search', (_event, query: string, limit: number) => {
    const url = new URL(`${BACKEND_BASE}/recent/search`);
    url.searchParams.set('q', query ?? '');
    url.searchParams.set('limit', String(limit));
    return backendJson(url);
  });
  ipcMain.handle('recent:words', (_event, query: string, limit: number) => {
    const url = new URL(`${BACKEND_BASE}/recent/words`);
    url.searchParams.set('q', query ?? '');
    url.searchParams.set('limit', String(limit));
    return backendJson(url);
  });
  ipcMain.handle('recent:changes', () => backendJson(`${BACKEND_BASE}/recent/changes`));
  ipcMain.handle('recent:touch', (_event, touch: RecentTouchRequest) =>
    backendVoid(`${BACKEND_BASE}/recent/touch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(touch),
    }));
  ipcMain.handle('recent:draft', (_event, draft: RecentDraftRequest) =>
    backendVoid(`${BACKEND_BASE}/recent/draft`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(draft),
    }));
  ipcMain.handle('recent:clear', () => backendVoid(`${BACKEND_BASE}/recent`, { method: 'DELETE' }));
  ipcMain.handle('recent:clear-row', (_event, id: string) => {
    // The id spans path segments because it carries the repository; encode each
    // segment and keep the separators.
    const path = id.split('/').map(encodeURIComponent).join('/');
    return backendVoid(`${BACKEND_BASE}/recent/${path}`, { method: 'DELETE' });
  });

  ipcMain.handle('prs:list', (_event, workspaceId: string | undefined, page: number, pageSize: number, search?: string) => {
    const url = new URL(workspaceId
      ? `/api/workspaces/${encodeURIComponent(workspaceId)}/pull-requests`
      : '/prs', BACKEND_BASE);
    url.searchParams.set('page', String(page));
    url.searchParams.set('size', String(pageSize));
    if (search?.trim()) url.searchParams.set('search', search.trim());
    return backendJson(url);
  });
  ipcMain.handle('prs:sync', (_event, workspaceId?: string) => backendVoid(workspaceId
    ? `${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/pull-requests/sync`
    : `${BACKEND_BASE}/api/settings/sync/trigger?listOnly=true`, {
      method: 'POST',
      signal: AbortSignal.timeout(15 * 60_000),
    }));
  ipcMain.handle('prs:detail', (_event, prId: number) =>
    backendJson(`${BACKEND_BASE}/prs/${encodeURIComponent(String(prId))}/detail`));
  ipcMain.handle('prs:detail:by-ref', (_event, repo: string, number: number) =>
    backendJson(prUrl('/prs/detail', repo, number)));
  ipcMain.handle('prs:users', (_event, repo: string, workspaceId?: string) => {
    const url = new URL('/prs/users', BACKEND_BASE);
    url.searchParams.set('repo', repo);
    if (workspaceId) url.searchParams.set('workspaceId', workspaceId);
    return backendJson(url);
  });
  ipcMain.handle('prs:labels', (_event, repo: string) => {
    const url = new URL('/prs/labels', BACKEND_BASE);
    url.searchParams.set('repo', repo);
    return backendJson(url);
  });
  ipcMain.handle('prs:metadata:update', (
    _event,
    repo: string,
    number: number,
    assignees: string[],
    labels: string[],
  ) => backendVoid(prUrl('/prs/metadata', repo, number), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ assignees, labels }),
  }));
  ipcMain.handle('prs:reviewer:update', (
    _event,
    repo: string,
    number: number,
    login: string,
    requested: boolean,
  ) => backendVoid(prUrl('/prs/reviewers', repo, number), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ login, requested }),
  }));
  ipcMain.handle('prs:merge-mode', (_event, repo: string, branch: string) => {
    const url = new URL('/prs/merge-mode', BACKEND_BASE);
    url.searchParams.set('repo', repo);
    url.searchParams.set('branch', branch);
    return backendJson(url);
  });
  ipcMain.handle('prs:detail:refresh:by-ref', (_event, repo: string, number: number) => {
    const url = prUrl('/prs/detail/refresh', repo, number);
    return backendJson(url, { method: 'POST' });
  });
  ipcMain.handle('prs:detail:refresh-all:by-ref', (_event, repo: string, number: number) => {
    const url = prUrl('/prs/detail/refresh-all', repo, number);
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
  ipcMain.handle('prs:diff:refresh', (
    _event,
    repo: string,
    number: number,
    baseSha?: string,
    headSha?: string,
  ) => {
    const url = prUrl('/prs/diffFiles/refresh', repo, number);
    if (baseSha && headSha) {
      url.searchParams.set('base', baseSha);
      url.searchParams.set('head', headSha);
    }
    return backendJson(url, { method: 'POST' });
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
  ipcMain.handle('prs:diff-content:refresh', (
    _event,
    repo: string,
    number: number,
    path: string,
    baseSha?: string,
    headSha?: string,
  ) => {
    const url = prUrl('/prs/diffContent/refresh', repo, number);
    url.searchParams.set('path', path);
    if (baseSha && headSha) {
      url.searchParams.set('base', baseSha);
      url.searchParams.set('head', headSha);
    }
    return backendJson(url, { method: 'POST' });
  });
  ipcMain.handle('prs:review-comment:create', (
    _event,
    repo: string,
    number: number,
    comment: import('./types').InlineReviewCommentDto,
  ) => backendJson(prUrl('/prs/review-comment', repo, number), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(comment),
  }));
  ipcMain.handle('prs:description:update', (_event, repo: string, number: number, body: string) =>
    backendVoid(prUrl('/prs/description', repo, number), {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    }));
  ipcMain.handle('prs:comment', (
    _event,
    repo: string,
    number: number,
    body: string,
  ) => {
    const url = prUrl('/prs/comment', repo, number);
    return backendJson(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
  });
  ipcMain.handle('prs:reaction:set', (
    _event,
    repo: string,
    number: number,
    target: import('./types').PullReactionTarget,
    commentId: number | null,
    content: import('./types').PullReactionContent,
    viewerHasReacted: boolean | null,
  ) => backendJson<{ viewerHasReacted: boolean }>(prUrl('/prs/reactions', repo, number), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ target, commentId, content, viewerHasReacted }),
  }));
  ipcMain.handle('prs:close', (_event, repo: string, number: number) =>
    backendVoid(prUrl('/prs/close', repo, number), { method: 'POST' }));
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
  ipcMain.handle('prs:checks:rerun-failed', (_event, repo: string, number: number) =>
    backendVoid(prUrl('/prs/checks/rerun-failed', repo, number), { method: 'POST' }));
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
    return backendJson(url, {
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
  ipcMain.handle('workspaces:issues', (_event, workspaceId: string, page: number, pageSize: number) => {
    const url = new URL(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues`);
    url.searchParams.set('page', String(page));
    url.searchParams.set('size', String(pageSize));
    return backendJson(url);
  });
  ipcMain.handle('workspaces:issue', (_event, workspaceId: string, issueNumber: number) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}`));
  ipcMain.handle('workspaces:issues:refresh', (_event, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/refresh`, {
      method: 'POST',
      signal: AbortSignal.timeout(15 * 60_000),
    }));
  ipcMain.handle('workspaces:issues:detail', (_event, workspaceId: string, issueNumber: number) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/detail`));
  ipcMain.handle('workspaces:issues:detail:refresh', (_event, workspaceId: string, issueNumber: number) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/detail/refresh`, {
      method: 'POST',
    }));
  ipcMain.handle('workspaces:issues:comment', (_event, workspaceId: string, issueNumber: number, body: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/comments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    }));
  ipcMain.handle('workspaces:issues:close', (_event, workspaceId: string, issueNumber: number) =>
    backendVoid(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/close`, {
      method: 'POST',
    }));
  ipcMain.handle('workspaces:issues:type:update', (_event, workspaceId: string, issueNumber: number, type: string | null) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/type`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type }),
    }));
  ipcMain.handle('workspaces:issues:metadata:update', (
    _event,
    workspaceId: string,
    issueNumber: number,
    assignees: string[],
    labels: string[],
  ) => backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/metadata`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ assignees, labels }),
  }));
  ipcMain.handle('workspaces:sync', (_event, workspaceId: string) =>
    backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/sync`, {
      method: 'POST',
    }));
  ipcMain.handle('workspaces:upstream', (
    _event,
    workspaceId: string,
    upstreamRepositoryId: number | null,
  ) => backendJson(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}/upstream`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ upstreamRepositoryId }),
  }));
  ipcMain.handle('workspaces:remove', (_event, workspaceId: string) =>
    backendVoid(`${BACKEND_BASE}/api/workspaces/${encodeURIComponent(workspaceId)}`, {
      method: 'DELETE',
    }));
  ipcMain.handle('repos:profile', () => backendJson(`${BACKEND_BASE}/api/profile`));
  ipcMain.handle('repos:user-profile', (_event, login: string) =>
    backendJson(`${BACKEND_BASE}/api/profile/${encodeURIComponent(login)}`));
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
  ipcMain.handle('repos:code-snippet', (_event, request: GitHubCodeSnippetRequest) => {
    const url = new URL(
      `${BACKEND_BASE}/api/repos/${encodeURIComponent(request.owner)}/${encodeURIComponent(request.repo)}/code-snippet`,
    );
    url.searchParams.set('commit', request.commit);
    url.searchParams.set('path', request.path);
    url.searchParams.set('startLine', String(request.startLine));
    url.searchParams.set('endLine', String(request.endLine));
    return backendJson(url);
  });
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
  refreshDashboardInBackground();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
app.on('before-quit', () => {
  // Streams read from the backend, so they stop before it does.
  agentSubscriptions.stopAll();
  killBackend();
});
app.on('activate', () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.focus();
  }
  else {
    void ensureWindow();
  }
});
