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
import { app, BrowserWindow, clipboard, dialog, ipcMain, Menu, nativeImage, Notification, shell, WebContentsView } from 'electron';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { notificationVersion, parsePrNotificationState, pendingPrNotifications, type PrNotificationState } from './prNotifications';
import {
  parseAgentApproval,
  parseAgentAttachment,
  parseAgentCompletion,
  parseAgentDevelopmentFacts,
  parseCiAutofixRun,
  parseAgentInputDelivery,
  parseAgentProviderStatuses,
  parseAgentProviderUnavailable,
  parseAgentQueue,
  parseAgentQueuedInput,
  parseAgentRuntimeStopImpact,
  parseAgentSkillInput,
  parseAgentSkills,
  parseAgentTask,
  parseAgentTasks,
  parseAgentTurn,
  parseAgentTurnSettings,
  parseDevelopmentAutomation,
  parseModelSettings,
} from './agents/contracts';
import { parseAgentEventSequence, parseAgentHistoryPage, parseAgentPendingApprovals } from './agents/events';
import {
  parseAgentReview,
  parseAgentReviews,
  parseReviewDraft,
  parseReviewIneligible,
  parseReviewPublishResult,
} from './pulls/agent-review/contracts';
import { AgentSubscriptions } from './agents/subscriptions';
import { clipboardImagePng, MAX_ATTACHMENT_BYTES } from './agents/clipboardImage';
import type {
  AgentApprovalDecision,
  AgentAttachmentDto,
  AgentDraftAttachmentDto,
  AgentProviderUnavailableDto,
  CherryPickExecutionRequest,
  CherryPickPreviewRequest,
  CommitHistoryRefDto,
  CommitHistorySourceDto,
  CreateAgentTaskRequest,
  GitHubCodeSnippetRequest,
  QueueAgentInputRequest,
  PullRequestDto,
  RecentDraftRequest,
  RecentFilterQuery,
  RecentTouchRequest,
  StartAgentTurnRequest,
  SteerAgentTurnRequest,
  WatchRepositoryCommand,
} from './types';
import {
  backendBase,
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
let prNotificationState: PrNotificationState | null = null;
let prNotificationTimer: ReturnType<typeof setInterval> | undefined;
let pollingPrNotifications = false;
let repollPrNotifications = false;
let prNotificationGeneration = 0;
let prMonitorMutations = 0;
let pendingPrNotification: { repo: string; number: number } | null = null;
const showingPrNotifications = new Map<number, Notification>();

function notificationState(): PrNotificationState {
  if (prNotificationState === null) {
    try {
      prNotificationState = parsePrNotificationState(JSON.parse(fs.readFileSync(path.join(app.getPath('userData'), 'pr-notifications.json'), 'utf8')));
    }
    catch {
      prNotificationState = { handled: {}, delivered: {} };
    }
  }
  return prNotificationState;
}

function saveNotificationState(): void {
  const file = path.join(app.getPath('userData'), 'pr-notifications.json');
  try {
    fs.writeFileSync(`${file}.tmp`, JSON.stringify(notificationState()));
    fs.renameSync(`${file}.tmp`, file);
  }
  catch (error) {
    console.warn('Unable to save PR notification state', error);
  }
}

async function pollPrNotifications(): Promise<void> {
  if (pollingPrNotifications || prMonitorMutations > 0) {
    repollPrNotifications = true;
    return;
  }
  pollingPrNotifications = true;
  repollPrNotifications = false;
  const generation = prNotificationGeneration;
  try {
    const prs = await backendJson<PullRequestDto[]>(`${backendBase()}/prs/attention`);
    if (generation !== prNotificationGeneration || prMonitorMutations > 0) return;
    mainWindow?.webContents.send('prs:attention-updated', prs);
    const state = notificationState();
    if (!Notification.isSupported()) return;
    for (const item of pendingPrNotifications(prs, state, Date.now())) {
      const pr = item.pr;
      if (pr === null || showingPrNotifications.has(pr.id)) continue;
      const version = notificationVersion(item);
      const notification = new Notification({
        title: `PR updated · ${item.repo}#${item.number}`,
        body: item.title,
      });
      showingPrNotifications.set(pr.id, notification);
      const release = () => {
        if (showingPrNotifications.get(pr.id) === notification) showingPrNotifications.delete(pr.id);
      };
      notification.on('click', () => {
        pendingPrNotification = { repo: item.repo, number: item.number };
        void ensureWindow().then(() => {
          mainWindow?.show();
          mainWindow?.focus();
          mainWindow?.webContents.send('prs:notification-open');
        }).catch(error => console.warn('Unable to open PR notification', error));
      });
      notification.on('show', () => {
        if (showingPrNotifications.get(pr.id) !== notification) return;
        release();
        if (generation !== prNotificationGeneration || prMonitorMutations > 0
          || pendingPrNotifications([pr], state, Date.now()).length === 0) {
          notification.close();
          return;
        }
        state.delivered[item.key] = version;
        saveNotificationState();
      });
      notification.on('failed', (_event, error) => {
        release();
        console.warn('Unable to display PR notification', error);
      });
      notification.on('close', release);
      try {
        notification.show();
      }
      catch (error) {
        release();
        console.warn('Unable to display PR notification', error);
      }
    }
  }
  catch { /* Keep the last delivery marks while the sidecar or GitHub account is unavailable. */ }
  finally {
    pollingPrNotifications = false;
    if (repollPrNotifications) void pollPrNotifications();
  }
}

const MAX_DRAFT_ATTACHMENTS = 8;
const draftAttachments = new Map<string, AgentDraftAttachmentDto>();

function retainDraftImages(images: Omit<AgentDraftAttachmentDto, 'id'>[]): AgentDraftAttachmentDto[] {
  if (draftAttachments.size + images.length > MAX_DRAFT_ATTACHMENTS) throw new Error('Attach up to 8 images');
  const drafts = images.map((image) => ({ ...image, id: randomUUID() }));
  for (const draft of drafts) draftAttachments.set(draft.id, draft);
  return drafts;
}

async function uploadAgentImage(taskId: string, workspaceId: string, name: string, mediaType: string, bytes: Buffer): Promise<AgentAttachmentDto> {
  if (bytes.length > MAX_ATTACHMENT_BYTES) throw new Error(`${name} is larger than 10 MiB`);
  const url = new URL(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/attachments`);
  url.searchParams.set('workspaceId', workspaceId);
  return parseAgentAttachment(await backendJson(url, {
    method: 'POST',
    headers: {
      ...agentHeaders(),
      'Content-Type': mediaType,
      'X-Attachment-Name': encodeURIComponent(name),
    },
    body: new Uint8Array(bytes),
  }));
}

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
  mainWindow.webContents.on('dom-ready', () => draftAttachments.clear());
  mainWindow.on('closed', () => {
    draftAttachments.clear();
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

class AgentProviderUnavailableError extends Error {
  constructor(readonly detail: AgentProviderUnavailableDto) {
    // Electron preserves an Error's message across IPC. The renderer validates the tagged JSON
    // again before treating this as a known-unsent preflight refusal.
    super(`AGENT_PROVIDER_UNAVAILABLE:${JSON.stringify(detail)}`);
  }
}

async function backendJson<T = unknown>(input: string | URL, init?: RequestInit): Promise<T> {
  const response = await fetch(input, { signal: AbortSignal.timeout(60_000), ...init });
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    if (response.status === 503 && body !== '') {
      try {
        const decoded: unknown = JSON.parse(body);
        if (typeof decoded === 'object' && decoded !== null
          && (decoded as { code?: unknown }).code === 'AGENT_PROVIDER_UNAVAILABLE') {
          throw new AgentProviderUnavailableError(parseAgentProviderUnavailable(decoded));
        }
      }
      catch (failure) {
        if (failure instanceof AgentProviderUnavailableError) throw failure;
        // A malformed typed error is handled as the ordinary HTTP failure below.
      }
    }
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

/** Always declares a JSON body, so a FormData or raw-bytes upload must build its own init. */
function jsonInit(method: string, body: unknown, headers: Record<string, string> = {}): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  };
}

const reviewSubscriptions = new AgentSubscriptions(
  (reviewId, after, signal) => fetch(
    `${backendBase()}/api/agent-review/${encodeURIComponent(reviewId)}/stream`
      + `?${new URLSearchParams({ after: String(after) })}`,
    { headers: { ...agentHeaders(), Accept: 'text/event-stream' }, signal },
  ),
  'review:stream:message',
);

const agentSubscriptions = new AgentSubscriptions((taskId, after, signal) => fetch(
  `${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/stream`
    + `?${new URLSearchParams({ after: String(after) })}`,
  { headers: { ...agentHeaders(), Accept: 'text/event-stream' }, signal },
));

function refreshDashboardInBackground(): void {
  void Promise.resolve().then(() => backendVoid(`${backendBase()}/api/settings/sync/trigger`, {
    method: 'POST',
    signal: AbortSignal.timeout(15 * 60_000),
  })).then(() => {
    if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.webContents.isDestroyed()) {
      mainWindow.webContents.send('pull-requests:synced');
    }
  }).catch(() => {});
}

function prUrl(pathname: string, repo: string, number: number): URL {
  const url = new URL(`${backendBase()}${pathname}`);
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
    const response = await backendJson<{ text: string }>(
      `${backendBase()}/agent/better-words`,
      jsonInit('POST', { text, repository, workspaceId }, agentHeaders()),
    );
    return response.text;
  });

  // Cherry-pick endpoints sit behind the desktop session filter, and the token
  // stays here: the renderer never sees it, so a page in the embedded GitHub
  // view cannot reach them.
  ipcMain.handle('cherrypick:preview', (_event, request: CherryPickPreviewRequest) => {
    const { workspaceId, ...body } = request;
    return backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/cherry-pick/preview`, {
      ...jsonInit('POST', body, agentHeaders()),
      // A preview fetches the source remote in full; on a repository the size
      // of Trino that outlasts the default request timeout.
      signal: AbortSignal.timeout(15 * 60_000),
    });
  });
  ipcMain.handle('agent:tasks:list', (_event, workspaceId: string, limit?: number) => {
    const query = new URLSearchParams({ workspaceId });
    if (limit !== undefined) query.set('limit', String(limit));
    return backendJson(`${backendBase()}/agent/tasks?${query}`, { headers: agentHeaders() })
      .then(parseAgentTasks);
  });
  ipcMain.handle('agent:tasks:create', async (_event, request: CreateAgentTaskRequest) => {
    const { draftAttachmentIds = [], ...metadata } = request;
    if (draftAttachmentIds.length === 0) {
      return parseAgentTask(await backendJson(`${backendBase()}/agent/tasks`,
        jsonInit('POST', metadata, agentHeaders())));
    }
    if (draftAttachmentIds.length > MAX_DRAFT_ATTACHMENTS) throw new Error('Attach up to 8 images');
    const body = new FormData();
    body.append('request', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    for (const id of draftAttachmentIds) {
      const draft = draftAttachments.get(id);
      if (draft === undefined) throw new Error('An image draft is no longer available. Add the image again.');
      body.append('images', new Blob([new Uint8Array(draft.bytes)], { type: draft.mediaType }), draft.name);
    }
    return parseAgentTask(await backendJson(`${backendBase()}/agent/tasks`, {
      method: 'POST', headers: agentHeaders(), body,
    }));
  });
  ipcMain.handle('agent:tasks:get', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:defaults', (
    _event,
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    defaults: unknown,
  ) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/defaults`
      + `?${new URLSearchParams({ workspaceId })}`,
    jsonInit('PUT', { expectedVersion, defaults }, agentHeaders()))
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:pull-requests', (_event, taskId: string, workspaceId: string, refresh?: boolean) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/pull-requests`
      + `${refresh === true ? '/sync' : ''}?${new URLSearchParams({ workspaceId })}`,
    { method: refresh === true ? 'POST' : 'GET', headers: agentHeaders() }));
  ipcMain.handle('agent:task:development-facts', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/development-facts`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((facts) => parseAgentDevelopmentFacts(facts)));
  ipcMain.handle('ci-autofix:get', (_event, runId: string) =>
    backendJson(`${backendBase()}/ci-autofix/runs/${encodeURIComponent(runId)}`, { headers: agentHeaders() }).then(parseCiAutofixRun));
  ipcMain.handle('ci-autofix:message', (_event, runId: string, message: string, idempotencyKey: string) =>
    backendJson(`${backendBase()}/ci-autofix/runs/${encodeURIComponent(runId)}/messages`,
      jsonInit('POST', { message }, { ...agentHeaders(), 'Idempotency-Key': idempotencyKey })).then(parseCiAutofixRun));
  ipcMain.handle('agent:task:rename', (
    _event,
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    title: string,
  ) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}`
      + `?${new URLSearchParams({ workspaceId })}`,
    jsonInit('PATCH', { expectedVersion, title }, agentHeaders()))
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:archive', (
    _event,
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    archived: boolean,
  ) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}`
      + `?${new URLSearchParams({ workspaceId })}`,
    jsonInit('PATCH', { expectedVersion, archived }, agentHeaders()))
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:delete', (_event, taskId: string, workspaceId: string) => {
    const url = new URL(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}`);
    url.searchParams.set('workspaceId', workspaceId);
    return backendVoid(url, { method: 'DELETE', headers: agentHeaders() });
  });
  ipcMain.handle('agent:task:effective-settings', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/effective-settings`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((settings) => parseAgentTurnSettings(settings)));
  ipcMain.handle('agent:events:read', async (_event, taskId: string, after?: number, limit?: number) => {
    const query = new URLSearchParams();
    if (after !== undefined) query.set('after', String(after));
    if (limit !== undefined) query.set('limit', String(limit));
    const page = await backendJson<unknown>(
      `${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/events?${query}`,
      { headers: agentHeaders() },
    );
    // Validated in main so a payload the renderer cannot represent never reaches it.
    return parseAgentEventSequence(page, after ?? 0);
  });
  ipcMain.handle('agent:history:read', async (_event, taskId: string, before?: string, after?: string) => {
    const query = new URLSearchParams();
    if (before !== undefined) query.set('before', before);
    if (after !== undefined) query.set('after', after);
    const page = await backendJson<unknown>(
      `${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/history?${query}`,
      { headers: agentHeaders() },
    );
    return parseAgentHistoryPage(page, taskId);
  });
  ipcMain.handle('agent:approvals:pending', async (_event, taskId: string, turnId: string) => {
    const approvals = await backendJson<unknown>(
      `${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/pending-approvals?${new URLSearchParams({ turnId })}`,
      { headers: agentHeaders() },
    );
    return parseAgentPendingApprovals(approvals, taskId, turnId);
  });
  ipcMain.handle('agent:turns:start', (_event, request: StartAgentTurnRequest) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(request.taskId)}/turns`, {
      method: 'POST',
      headers: { ...agentHeaders(true), 'Idempotency-Key': request.idempotencyKey },
      body: JSON.stringify({
        message: request.message,
        expectedVersion: request.expectedTaskVersion,
        developmentAutomation: request.developmentAutomation,
        attachments: request.attachments ?? [],
      }),
    }).then((turn) => parseAgentTurn(turn)));
  ipcMain.handle('agent:turns:steer', (_event, request: SteerAgentTurnRequest) =>
    backendJson(`${backendBase()}/agent/turns/${encodeURIComponent(request.turnId)}/steer`, {
      method: 'POST',
      headers: { ...agentHeaders(true), 'Idempotency-Key': request.idempotencyKey },
      body: JSON.stringify({ message: request.message, attachments: request.attachments ?? [] }),
    }).then((delivery) => parseAgentInputDelivery(delivery)));
  ipcMain.handle('agent:queue:add', (_event, request: QueueAgentInputRequest) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(request.taskId)}/queue`
      + `?${new URLSearchParams({ workspaceId: request.workspaceId })}`, {
      method: 'POST',
      headers: { ...agentHeaders(true), 'Idempotency-Key': request.idempotencyKey },
      body: JSON.stringify({
        message: request.message,
        attachments: request.attachments ?? [],
        expectedVersion: request.expectedVersion,
        developmentAutomation: request.developmentAutomation,
      }),
    }).then((queued) => parseAgentQueuedInput(queued)));
  ipcMain.handle('agent:queue:list', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/queue`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((queue) => parseAgentQueue(queue)));
  ipcMain.handle('agent:queue:remove', (_event, taskId: string, workspaceId: string, queuedInputId: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/queue/${encodeURIComponent(queuedInputId)}`
      + `?${new URLSearchParams({ workspaceId })}`, { method: 'DELETE', headers: agentHeaders() })
      .then((queued) => parseAgentQueuedInput(queued)));
  ipcMain.handle('agent:queue:resume', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/queue/resume`
      + `?${new URLSearchParams({ workspaceId })}`, { method: 'POST', headers: agentHeaders() })
      .then((turn) => parseAgentTurn(turn)));
  ipcMain.handle('agent:attachments:pick-drafts', async () => {
    if (mainWindow === null) return [];
    const picked = await dialog.showOpenDialog(mainWindow, {
      title: 'Attach images',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'gif'] }],
    });
    if (draftAttachments.size + picked.filePaths.length > MAX_DRAFT_ATTACHMENTS) throw new Error('Attach up to 8 images');
    const drafts: Omit<AgentDraftAttachmentDto, 'id'>[] = [];
    for (const filePath of picked.filePaths) {
      const stat = await fs.promises.stat(filePath);
      if (!stat.isFile() || stat.size > MAX_ATTACHMENT_BYTES) throw new Error(`${path.basename(filePath)} must be an image under 10 MiB`);
      const bytes = await fs.promises.readFile(filePath);
      if (bytes.length > MAX_ATTACHMENT_BYTES) throw new Error(`${path.basename(filePath)} is larger than 10 MiB`);
      drafts.push({ name: path.basename(filePath), mediaType: imageMediaType(filePath), bytes });
    }
    return retainDraftImages(drafts);
  });
  ipcMain.handle('agent:attachments:paste-drafts', () => {
    const bytes = clipboardImagePng(clipboard.readImage());
    return bytes === null ? [] : retainDraftImages([{ name: 'pasted-image.png', mediaType: 'image/png', bytes }]);
  });
  ipcMain.handle('agent:attachments:paste', async (_event, taskId: string, workspaceId: string) => {
    const bytes = clipboardImagePng(clipboard.readImage());
    return bytes === null ? [] : [await uploadAgentImage(taskId, workspaceId, 'pasted-image.png', 'image/png', bytes)];
  });
  ipcMain.handle('agent:attachments:discard-drafts', (_event, attachmentIds: string[]) => {
    for (const id of attachmentIds) draftAttachments.delete(id);
  });
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
      stored.push(await uploadAgentImage(taskId, workspaceId, path.basename(filePath), imageMediaType(filePath), bytes));
    }
    return stored;
  });
  ipcMain.handle('agent:attachments:read', async (_event, taskId: string, workspaceId: string, attachmentId: string) => {
    const url = new URL(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/attachments/${encodeURIComponent(attachmentId)}`);
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
    const url = new URL(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/attachments/${encodeURIComponent(attachmentId)}`);
    url.searchParams.set('workspaceId', workspaceId);
    return backendVoid(url, { method: 'DELETE', headers: agentHeaders() });
  });
  ipcMain.handle('agent:turns:get', (_event, turnId: string) =>
    backendJson(`${backendBase()}/agent/turns/${encodeURIComponent(turnId)}`, { headers: agentHeaders() })
      .then((turn) => parseAgentTurn(turn)));
  ipcMain.handle('agent:turns:cancel', (_event, turnId: string) =>
    backendJson(`${backendBase()}/agent/turns/${encodeURIComponent(turnId)}/cancel`, {
      method: 'POST',
      headers: agentHeaders(),
    }).then((turn) => parseAgentTurn(turn)));
  ipcMain.handle('agent:approvals:resolve', (
    _event,
    approvalId: string,
    decision: AgentApprovalDecision,
    scopedPermission?: string,
  ) =>
    backendJson(`${backendBase()}/agent/approvals/${encodeURIComponent(approvalId)}`, jsonInit('POST', {
      decision: decision === 'ALLOW_FOR_TASK' ? 'ALLOW' : decision,
      forTask: decision === 'ALLOW_FOR_TASK',
      scopedPermission: scopedPermission ?? null,
    }, agentHeaders())).then((approval) => parseAgentApproval(approval)));
  ipcMain.handle('agent:tasks:complete', (_event, taskId: string, workspaceId: string, notes?: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/complete`
      + `?${new URLSearchParams({ workspaceId })}`,
    jsonInit('POST', { notes: notes ?? null }, agentHeaders()))
      .then((completion) => parseAgentCompletion(completion)));
  ipcMain.handle('agent:task:cleanup', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/cleanup`
      + `?${new URLSearchParams({ workspaceId })}`, {
      method: 'POST',
      headers: agentHeaders(),
    }).then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:mark-failed', (_event, taskId: string, workspaceId: string, reason: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/mark-failed`
      + `?${new URLSearchParams({ workspaceId })}`,
    jsonInit('POST', { reason }, agentHeaders()))
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:stop-impact', (_event, taskId: string, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/runtime/stop-impact`
      + `?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then((impact) => parseAgentRuntimeStopImpact(impact)));
  ipcMain.handle('agent:task:stop', (_event, taskId: string, workspaceId: string, generation: string) =>
    backendJson(`${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}/runtime/stop`
      + `?${new URLSearchParams({ workspaceId })}`, {
      ...jsonInit('POST', { generation }, agentHeaders()),
      // Stopping waits for the process tree to be verified gone, which can take a while.
      signal: AbortSignal.timeout(90_000),
    }).then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:stream:subscribe', (event, taskId: string, after: number, statusOnly?: boolean) =>
    agentSubscriptions.start(event.sender, taskId, after, statusOnly === true));
  ipcMain.handle('agent:stream:unsubscribe', (_event, subscriptionId: string) => {
    agentSubscriptions.stop(subscriptionId);
  });

  const reviewUrl = (path: string, query: Record<string, string> = {}): string => {
    const url = new URL(`${backendBase()}/api/agent-review${path}`);
    Object.entries(query).forEach(([key, value]) => url.searchParams.set(key, value));
    return url.toString();
  };
  const review = (path: string, query: Record<string, string> = {}, init?: RequestInit) =>
    backendJson(reviewUrl(path, query), { headers: agentHeaders(init?.body !== undefined), ...init })
      .then(parseAgentReview);
  const draft = (path: string, query: Record<string, string> = {}, init?: RequestInit) =>
    backendJson(reviewUrl(path, query), { headers: agentHeaders(init?.body !== undefined), ...init })
      .then(parseReviewDraft);

  ipcMain.handle('review:list', (_event, workspaceId: string) =>
    backendJson(reviewUrl('', { workspaceId }), { headers: agentHeaders() }).then(parseAgentReviews));
  ipcMain.handle('review:find', async (_event, workspaceId: string, repo: string, number: number) => {
    const response = await fetch(reviewUrl('', { workspaceId, repo, number: String(number) }), { headers: agentHeaders() });
    if (response.status === 204) return null;
    if (!response.ok) throw new Error(extractMessage(await response.text().catch(() => '')) || `Review lookup returned ${response.status}`);
    return parseAgentReview(await response.json());
  });
  ipcMain.handle('review:start', (_event, workspaceId: string, repo: string, number: number) =>
    review('', {}, { method: 'POST', body: JSON.stringify({ workspaceId, repo, number }) }));
  ipcMain.handle('review:get', (_event, reviewId: string) => review(`/${encodeURIComponent(reviewId)}`));
  ipcMain.handle('review:stop', (_event, reviewId: string) => review(`/${encodeURIComponent(reviewId)}/stop`, {}, { method: 'POST' }));
  ipcMain.handle('review:runtime:stop-impact', (_event, reviewId: string) =>
    backendJson(reviewUrl(`/${encodeURIComponent(reviewId)}/runtime/stop-impact`), { headers: agentHeaders() })
      .then(parseAgentRuntimeStopImpact));
  ipcMain.handle('review:runtime:stop', (_event, reviewId: string, generation: string) =>
    review(`/${encodeURIComponent(reviewId)}/runtime/stop`, {}, {
      method: 'POST', body: JSON.stringify({ generation }), signal: AbortSignal.timeout(90_000),
    }));
  ipcMain.handle('review:resume', (_event, reviewId: string) => review(`/${encodeURIComponent(reviewId)}/resume`, {}, { method: 'POST' }));
  ipcMain.handle('review:update', (_event, reviewId: string) => review(`/${encodeURIComponent(reviewId)}/update`, {}, { method: 'POST' }));
  ipcMain.handle('review:end', (_event, reviewId: string) => review(`/${encodeURIComponent(reviewId)}/end`, {}, { method: 'POST' }));
  ipcMain.handle('review:ask', (_event, reviewId: string, findingId: string | null, targetRevision: number, message: string) =>
    review(`/${encodeURIComponent(reviewId)}/questions`, {}, {
      method: 'POST',
      body: JSON.stringify({ findingId, targetRevision, message }),
    }));
  ipcMain.handle('review:message', (_event, reviewId: string, message: string, delivery: string, idempotencyKey: string) =>
    backendJson(reviewUrl(`/${encodeURIComponent(reviewId)}/messages`), {
      method: 'POST',
      headers: { ...agentHeaders(true), 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ message, delivery }),
    }).then(parseAgentReview));
  ipcMain.handle('review:queue:remove', (_event, reviewId: string, queuedInputId: string) =>
    review(`/${encodeURIComponent(reviewId)}/queue/${encodeURIComponent(queuedInputId)}`, {}, { method: 'DELETE' }));
  ipcMain.handle('review:finding:resolve', (_event, reviewId: string, findingId: string, expectedVersion: number, reason: string) =>
    review(`/${encodeURIComponent(reviewId)}/findings/${encodeURIComponent(findingId)}/resolve`, {}, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion, reason }),
    }));
  ipcMain.handle('review:finding:reopen', (_event, reviewId: string, findingId: string, expectedVersion: number) =>
    review(`/${encodeURIComponent(reviewId)}/findings/${encodeURIComponent(findingId)}/reopen`, {}, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion }),
    }));
  ipcMain.handle('review:events:read', async (_event, reviewId: string, after?: number, limit?: number) => {
    const query: Record<string, string> = {};
    if (after !== undefined) query.after = String(after);
    if (limit !== undefined) query.limit = String(limit);
    const page = await backendJson<unknown>(reviewUrl(`/${encodeURIComponent(reviewId)}/events`, query), { headers: agentHeaders() });
    return parseAgentEventSequence(page, after ?? 0);
  });
  ipcMain.handle('review:history:read', async (_event, reviewId: string, taskId: string, before?: string, after?: string) => {
    const query: Record<string, string> = {};
    if (before !== undefined) query.before = String(before);
    if (after !== undefined) query.after = String(after);
    const page = await backendJson<unknown>(reviewUrl(`/${encodeURIComponent(reviewId)}/history`, query), { headers: agentHeaders() });
    return parseAgentHistoryPage(page, taskId);
  });
  ipcMain.handle('review:approvals:pending', async (_event, reviewId: string, taskId: string, turnId: string) => {
    const approvals = await backendJson<unknown>(reviewUrl(`/${encodeURIComponent(reviewId)}/pending-approvals`), { headers: agentHeaders() });
    return parseAgentPendingApprovals(approvals, taskId, turnId);
  });
  ipcMain.handle('review:approvals:resolve', (_event, reviewId: string, approvalId: string, decision: string) =>
    backendJson(reviewUrl(`/${encodeURIComponent(reviewId)}/approvals/${encodeURIComponent(approvalId)}`),
      jsonInit('POST', { decision }, agentHeaders()))
      .then(parseAgentApproval));
  ipcMain.handle('review:stream:subscribe', (event, reviewId: string, after: number, statusOnly?: boolean) =>
    reviewSubscriptions.start(event.sender, reviewId, after, statusOnly === true));
  ipcMain.handle('review:stream:unsubscribe', (_event, subscriptionId: string) => {
    reviewSubscriptions.stop(subscriptionId);
  });

  ipcMain.handle('review:draft:get', (_event, repo: string, number: number) =>
    draft('/drafts', { repo, number: String(number) }));
  ipcMain.handle('review:draft:add-comment', (_event, repo: string, number: number, expectedVersion: number, comment: unknown) =>
    draft('/drafts/items', { repo, number: String(number) }, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion, ...(comment as Record<string, unknown>) }),
    }));
  ipcMain.handle('review:draft:add-finding', (_event, repo: string, number: number, expectedVersion: number, reviewId: string, findingId: string) =>
    draft('/drafts/findings', { repo, number: String(number) }, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion, reviewId, findingId }),
    }));
  ipcMain.handle('review:draft:edit', (_event, repo: string, number: number, expectedVersion: number, itemId: string, body: string, included: boolean) =>
    draft(`/drafts/items/${encodeURIComponent(itemId)}`, { repo, number: String(number) }, {
      method: 'PUT',
      body: JSON.stringify({ expectedVersion, body, included }),
    }));
  ipcMain.handle('review:draft:remove', (_event, repo: string, number: number, expectedVersion: number, itemId: string) =>
    draft(`/drafts/items/${encodeURIComponent(itemId)}`, { repo, number: String(number), expectedVersion: String(expectedVersion) }, { method: 'DELETE' }));
  ipcMain.handle('review:draft:save', (_event, repo: string, number: number, expectedVersion: number, body: string, event: string) =>
    draft('/drafts/review', { repo, number: String(number) }, {
      method: 'PUT',
      body: JSON.stringify({ expectedVersion, body, event }),
    }));
  ipcMain.handle('review:draft:publish', (_event, repo: string, number: number, expectedVersion: number, itemId: string) =>
    backendJson(reviewUrl(`/drafts/items/${encodeURIComponent(itemId)}/publish`, { repo, number: String(number), expectedVersion: String(expectedVersion) }), {
      method: 'POST',
      headers: agentHeaders(),
    }).then(parseReviewPublishResult));
  ipcMain.handle('review:draft:submit', (_event, repo: string, number: number, expectedVersion: number, body: string, event: string) =>
    backendJson(reviewUrl('/drafts/submit', { repo, number: String(number) }),
      jsonInit('POST', { expectedVersion, body, event }, agentHeaders()))
      .then(parseReviewPublishResult));
  ipcMain.handle('review:draft:ineligible', (_event, repo: string, number: number) =>
    backendJson(reviewUrl('/drafts/ineligible', { repo, number: String(number) }), { headers: agentHeaders() })
      .then(parseReviewIneligible));
  ipcMain.handle('agent:providers:refresh', () =>
    backendJson(`${backendBase()}/agent/providers/status`, {
      headers: { 'X-ByteQuay-Session': sessionToken() },
    }).then(parseAgentProviderStatuses));
  ipcMain.handle('cherrypick:execution:start', (_event, request: CherryPickExecutionRequest) => {
    const { workspaceId, ...body } = request;
    return backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/cherry-pick/executions`, {
      ...jsonInit('POST', body, agentHeaders()),
      signal: AbortSignal.timeout(15 * 60_000),
    });
  });
  ipcMain.handle('cherrypick:execution:get', (_event, executionId: string) =>
    backendJson(`${backendBase()}/api/cherry-pick-ranges/executions/${encodeURIComponent(executionId)}`, {
      headers: { 'X-ByteQuay-Session': sessionToken() },
    }));
  ipcMain.handle('cherrypick:execution:message', (_event, executionId: string, message: string) =>
    backendJson(`${backendBase()}/api/cherry-pick-ranges/executions/${encodeURIComponent(executionId)}/messages`,
      jsonInit('POST', { message }, agentHeaders())));
  ipcMain.handle('cherrypick:execution:cancel', (_event, workspaceId: string, executionId: string) => {
    const url = `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/cherry-pick/executions/${encodeURIComponent(executionId)}/cancel`;
    return backendJson(url, {
      method: 'POST',
      headers: { 'X-ByteQuay-Session': sessionToken() },
    });
  });
  const deliveryUrl = (workspaceId: string, executionId: string, action: string) =>
    `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}`
      + `/cherry-pick/executions/${encodeURIComponent(executionId)}/${action}`;
  ipcMain.handle('cherrypick:delivery:get', (_event, workspaceId: string, executionId: string) =>
    backendJson(deliveryUrl(workspaceId, executionId, 'delivery'), {
      headers: { 'X-ByteQuay-Session': sessionToken() },
    }));
  ipcMain.handle('cherrypick:delivery:push', (_event, workspaceId: string, executionId: string) =>
    backendJson(deliveryUrl(workspaceId, executionId, 'push'), {
      ...jsonInit('POST', { confirmed: true }, agentHeaders()),
      signal: AbortSignal.timeout(15 * 60_000),
    }));
  ipcMain.handle('cherrypick:delivery:pull-request', (
    _event,
    workspaceId: string,
    executionId: string,
  ) => backendJson(
    deliveryUrl(workspaceId, executionId, 'pull-request'),
    jsonInit('POST', { confirmed: true }, agentHeaders()),
  ));
  ipcMain.handle('cherrypick:execution:list', (_event, limit: number) => {
    const url = new URL(`${backendBase()}/api/cherry-pick-ranges/executions`);
    url.searchParams.set('limit', String(limit));
    return backendJson(url, { headers: { 'X-ByteQuay-Session': sessionToken() } });
  });
  // Local checkout browsing for the workspace Commits tab. Only the workspace
  // ID crosses IPC; the backend owns and resolves the managed checkout path.
  const gitGet = (workspaceId: string, endpoint: string, params: Record<string, string>) => {
    const url = new URL(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/git/${endpoint}`,
    );
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
    return backendJson(url, { headers: { 'X-ByteQuay-Session': sessionToken() } });
  };
  const gitPost = (workspaceId: string, endpoint: string, body: unknown, timeoutMs = 120_000) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/git/${endpoint}`, {
      ...jsonInit('POST', body, agentHeaders()),
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
    await backendJson(`${backendBase()}/api/auth/github/token`, jsonInit('POST', { value: pat.trim() }));
    refreshDashboardInBackground();
    return true;
  });
  ipcMain.handle('pat:has', async () => {
    if (!await waitForBackendReady()) return false;
    try {
      const result = await backendJson<{ configured: boolean }>(
        `${backendBase()}/api/auth/github/configured`,
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
      return await backendJson(`${backendBase()}/api/auth/github/account`);
    }
    catch {
      return { configured: false, maskedToken: null };
    }
  });
  // The token value only ever transits main-process memory on its way to the
  // clipboard; the renderer sees a boolean.
  ipcMain.handle('pat:copy', async () => {
    const result = await backendJson<{ value: string }>(
      `${backendBase()}/api/auth/github/token/value`,
    );
    clipboard.writeText(result.value);
    return true;
  });
  ipcMain.handle('pat:delete', () =>
    backendVoid(`${backendBase()}/api/auth/github/token`, { method: 'DELETE' }));

  ipcMain.handle('settings:models', (_event, workspaceId?: string) =>
    backendJson(`${backendBase()}/api/settings/models${workspaceId === undefined ? '' : `?workspaceId=${encodeURIComponent(workspaceId)}`}`)
      .then(parseModelSettings));
  ipcMain.handle('settings:models:agent:set', (
    _event,
    type: string,
    configId: string,
    model: string,
    effort: string | null,
  ) => backendJson(
    `${backendBase()}/api/settings/models/agents/${encodeURIComponent(type)}`,
    jsonInit('PUT', { configId, model, effort }),
  ).then(parseModelSettings));
  ipcMain.handle('settings:models:agent:clear', (_event, type: string) =>
    backendJson(`${backendBase()}/api/settings/models/agents/${encodeURIComponent(type)}`, { method: 'DELETE' })
      .then(parseModelSettings));
  ipcMain.handle('settings:api-keys:add', (_event, provider: string, alias: string, key: string) =>
    backendJson(`${backendBase()}/api/settings/api-keys`, jsonInit('POST', { provider, alias, key }))
      .then(parseModelSettings));
  ipcMain.handle('settings:api-keys:remove', (_event, alias: string) =>
    backendJson(`${backendBase()}/api/settings/api-keys/${encodeURIComponent(alias)}`, {
      method: 'DELETE',
    }).then(parseModelSettings));
  ipcMain.handle('settings:agent-skills', () =>
    backendJson(`${backendBase()}/api/settings/agent-skills`).then(parseAgentSkills));
  ipcMain.handle('settings:agent-skills:add', (_event, skill: unknown) =>
    backendJson(`${backendBase()}/api/settings/agent-skills`, jsonInit('POST', parseAgentSkillInput(skill)))
      .then(parseAgentSkills));
  ipcMain.handle('settings:agent-skills:update', (_event, id: string, skill: unknown) =>
    backendJson(`${backendBase()}/api/settings/agent-skills/${encodeURIComponent(id)}`, jsonInit('PUT', parseAgentSkillInput(skill)))
      .then(parseAgentSkills));
  ipcMain.handle('settings:agent-skills:remove', (_event, id: string) =>
    backendJson(`${backendBase()}/api/settings/agent-skills/${encodeURIComponent(id)}`, { method: 'DELETE' })
      .then(parseAgentSkills));
  ipcMain.handle('settings:quiet-hours', () =>
    backendJson(`${backendBase()}/api/settings/quiet-hours`));
  ipcMain.handle('settings:quiet-hours:save', (_event, quietHours: unknown) =>
    backendJson(`${backendBase()}/api/settings/quiet-hours`, jsonInit('PUT', quietHours)));
  ipcMain.handle('settings:development-automation', () =>
    backendJson(`${backendBase()}/api/settings/development-automation`).then(value => parseDevelopmentAutomation(value)));
  ipcMain.handle('settings:development-automation:save', (_event, setting: unknown) =>
    backendJson(`${backendBase()}/api/settings/development-automation`, jsonInit('PUT', parseDevelopmentAutomation(setting)))
      .then(value => parseDevelopmentAutomation(value)));
  ipcMain.handle('settings:codegraph', () =>
    backendJson(`${backendBase()}/api/settings/codegraph`));
  ipcMain.handle('settings:codegraph:save', (_event, setting: unknown) =>
    backendJson(`${backendBase()}/api/settings/codegraph`, jsonInit('PUT', setting)));
  ipcMain.handle('settings:codegraph:baseline', () =>
    backendJson(`${backendBase()}/api/settings/codegraph/baseline`));
  ipcMain.handle('settings:app', () =>
    backendJson(`${backendBase()}/api/settings/app`));
  ipcMain.handle('settings:app:save', (_event, preferences: unknown) =>
    backendJson(`${backendBase()}/api/settings/app`, jsonInit('PUT', preferences)));

  ipcMain.handle('workspaces:settings', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/settings`));
  ipcMain.handle('workspaces:commits-target:set', (_event, workspaceId: string, branch: string) =>
    backendJson(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/commits-target`,
      jsonInit('PUT', { branch }),
    ));
  ipcMain.handle('workspaces:commits-target:clear', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/commits-target`, { method: 'DELETE' }));
  ipcMain.handle('workspaces:rename', (_event, workspaceId: string, name: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}`, jsonInit('PATCH', { name })));
  ipcMain.handle('workspaces:remotes:add', (_event, workspaceId: string, name: string, url: string) =>
    backendJson(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/remotes`,
      jsonInit('POST', { name, url }),
    ));
  ipcMain.handle('workspaces:remotes:remove', (_event, workspaceId: string, name: string) =>
    backendJson(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/remotes/${encodeURIComponent(name)}`,
      { method: 'DELETE' },
    ));
  ipcMain.handle('workspaces:agent-model:set', (
    _event,
    workspaceId: string,
    type: string,
    configId: string,
    model: string,
    effort: string | null,
  ) => backendJson(
    `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/agent-model/${encodeURIComponent(type)}`,
    jsonInit('PUT', { configId, model, effort }),
  ));
  ipcMain.handle('workspaces:agent-model:clear', (_event, workspaceId: string, type: string) =>
    backendJson(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/agent-model/${encodeURIComponent(type)}`,
      { method: 'DELETE' },
    ));
  ipcMain.handle('workspaces:token:save', (_event, workspaceId: string, value: string) =>
    backendJson(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/github-token`,
      jsonInit('PUT', { value }),
    ));
  ipcMain.handle('workspaces:token:delete', (_event, workspaceId: string) =>
    backendVoid(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/github-token`, {
      method: 'DELETE',
    }));
  ipcMain.handle('workspaces:token:copy', async (_event, workspaceId: string) => {
    const result = await backendJson<{ value: string }>(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/github-token/value`,
    );
    clipboard.writeText(result.value);
    return true;
  });

  // Recent is local-only: every read answers from SQLite, so these stay
  // useful with the network off.
  ipcMain.handle('recent:list', (_event, query: RecentFilterQuery) => {
    const url = new URL(`${backendBase()}/recent`);
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
    const url = new URL(`${backendBase()}/recent/search`);
    url.searchParams.set('q', query ?? '');
    url.searchParams.set('limit', String(limit));
    return backendJson(url);
  });
  ipcMain.handle('recent:words', (_event, query: string, limit: number) => {
    const url = new URL(`${backendBase()}/recent/words`);
    url.searchParams.set('q', query ?? '');
    url.searchParams.set('limit', String(limit));
    return backendJson(url);
  });
  ipcMain.handle('recent:changes', () => backendJson(`${backendBase()}/recent/changes`));
  ipcMain.handle('recent:touch', (_event, touch: RecentTouchRequest) =>
    backendVoid(`${backendBase()}/recent/touch`, jsonInit('POST', touch)));
  ipcMain.handle('recent:draft', (_event, draft: RecentDraftRequest) =>
    backendVoid(`${backendBase()}/recent/draft`, jsonInit('POST', draft)));
  ipcMain.handle('recent:clear', () => backendVoid(`${backendBase()}/recent`, { method: 'DELETE' }));

  ipcMain.handle('prs:list', (_event, workspaceId: string | undefined, page: number, pageSize: number, search?: string) => {
    const url = new URL(workspaceId
      ? `/api/workspaces/${encodeURIComponent(workspaceId)}/pull-requests`
      : '/prs', backendBase());
    url.searchParams.set('page', String(page));
    url.searchParams.set('size', String(pageSize));
    if (search?.trim()) url.searchParams.set('search', search.trim());
    return backendJson(url);
  });
  ipcMain.handle('prs:attention', () => backendJson(`${backendBase()}/prs/attention`));
  ipcMain.handle('prs:monitor', async (_event, prId: number, monitored: boolean) => {
    if (!Number.isSafeInteger(prId) || prId <= 0 || typeof monitored !== 'boolean') throw new Error('Invalid PR monitor request');
    prNotificationGeneration++;
    prMonitorMutations++;
    repollPrNotifications = true;
    try {
      return await backendJson<PullRequestDto>(`${backendBase()}/prs/${prId}/monitor`, jsonInit('PATCH', { monitored }));
    }
    finally {
      prNotificationGeneration++;
      prMonitorMutations--;
      void pollPrNotifications();
    }
  });
  ipcMain.handle('prs:notification:pending', () => {
    const reference = pendingPrNotification;
    pendingPrNotification = null;
    return reference;
  });
  ipcMain.handle('prs:attention:handled', (_event, handled: unknown) => {
    notificationState().handled = parsePrNotificationState({ handled }).handled;
    saveNotificationState();
  });
  ipcMain.handle('prs:sync', (_event, workspaceId?: string) => backendVoid(workspaceId
    ? `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/pull-requests/sync`
    : `${backendBase()}/api/settings/sync/trigger?listOnly=true`, {
      method: 'POST',
      signal: AbortSignal.timeout(15 * 60_000),
    }));
  ipcMain.handle('prs:detail', (_event, prId: number) =>
    backendJson(`${backendBase()}/prs/${encodeURIComponent(String(prId))}/detail`));
  ipcMain.handle('prs:detail:by-ref', (_event, repo: string, number: number) =>
    backendJson(prUrl('/prs/detail', repo, number)));
  ipcMain.handle('prs:users', (_event, repo: string, workspaceId?: string) => {
    const url = new URL('/prs/users', backendBase());
    url.searchParams.set('repo', repo);
    if (workspaceId) url.searchParams.set('workspaceId', workspaceId);
    return backendJson(url);
  });
  ipcMain.handle('prs:labels', (_event, repo: string) => {
    const url = new URL('/prs/labels', backendBase());
    url.searchParams.set('repo', repo);
    return backendJson(url);
  });
  ipcMain.handle('prs:metadata:update', (
    _event,
    repo: string,
    number: number,
    assignees: string[],
    labels: string[],
  ) => backendVoid(prUrl('/prs/metadata', repo, number), jsonInit('PATCH', { assignees, labels })));
  ipcMain.handle('prs:reviewer:update', (
    _event,
    repo: string,
    number: number,
    login: string,
    requested: boolean,
  ) => backendVoid(prUrl('/prs/reviewers', repo, number), jsonInit('PATCH', { login, requested })));
  ipcMain.handle('prs:merge-mode', (_event, repo: string, branch: string) => {
    const url = new URL('/prs/merge-mode', backendBase());
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
  ) => backendJson(prUrl('/prs/review-comment', repo, number), jsonInit('POST', comment)));
  ipcMain.handle('prs:description:update', (_event, repo: string, number: number, body: string) =>
    backendVoid(prUrl('/prs/description', repo, number), jsonInit('PATCH', { body })));
  ipcMain.handle('prs:comment', (
    _event,
    repo: string,
    number: number,
    body: string,
  ) => {
    const url = prUrl('/prs/comment', repo, number);
    return backendJson(url, jsonInit('POST', { body }));
  });
  ipcMain.handle('prs:reaction:set', (
    _event,
    repo: string,
    number: number,
    target: import('./types').PullReactionTarget,
    commentId: number | null,
    content: import('./types').PullReactionContent,
    viewerHasReacted: boolean | null,
  ) => backendJson<{ viewerHasReacted: boolean }>(
    prUrl('/prs/reactions', repo, number),
    jsonInit('POST', { target, commentId, content, viewerHasReacted }),
  ));
  ipcMain.handle('prs:comment:edit', (
    _event,
    repo: string,
    number: number,
    target: import('./types').PullCommentTarget,
    commentId: number,
    body: string,
  ) => backendJson(prUrl('/prs/comments', repo, number), jsonInit('PATCH', { target, commentId, body })));
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
    return backendVoid(url, jsonInit('POST', { body }));
  });
  ipcMain.handle('prs:review-thread:resolution', (
    _event,
    repo: string,
    number: number,
    commentId: number,
    resolved: boolean,
  ) => {
    const url = prUrl(`/prs/review-comments/${commentId}/resolution`, repo, number);
    return backendVoid(url, jsonInit('POST', { resolved }));
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
    return backendJson(url, jsonInit('POST', { event, body, comments }));
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

  ipcMain.handle('workspaces:search', (_event, query: string, limit: number) => {
    const url = new URL(`${backendBase()}/api/repository-search`);
    url.searchParams.set('q', query);
    url.searchParams.set('limit', String(limit));
    return backendJson(url);
  });
  ipcMain.handle('workspaces:preview', (_event, selectedRepositoryId: number) =>
    backendJson(`${backendBase()}/api/workspaces/watch-preview`, jsonInit('POST', { selectedRepositoryId })));
  ipcMain.handle('workspaces:watch', (_event, command: WatchRepositoryCommand) =>
    backendJson(`${backendBase()}/api/workspaces`, jsonInit('POST', command)));
  ipcMain.handle('workspaces:list', () => backendJson(`${backendBase()}/api/workspaces`));
  ipcMain.handle('workspaces:list-retired', () => backendJson(`${backendBase()}/api/workspaces/retired`));
  ipcMain.handle('workspaces:get', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}`));
  ipcMain.handle('workspaces:metadata', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/metadata`));
  ipcMain.handle('workspaces:issues', (_event, workspaceId: string, page: number, pageSize: number) => {
    const url = new URL(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues`);
    url.searchParams.set('page', String(page));
    url.searchParams.set('size', String(pageSize));
    return backendJson(url);
  });
  ipcMain.handle('workspaces:issue', (_event, workspaceId: string, issueNumber: number) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}`));
  ipcMain.handle('workspaces:issues:refresh', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/refresh`, {
      method: 'POST',
      signal: AbortSignal.timeout(15 * 60_000),
    }));
  ipcMain.handle('workspaces:issues:detail', (_event, workspaceId: string, issueNumber: number) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/detail`));
  ipcMain.handle('workspaces:issues:detail:refresh', (_event, workspaceId: string, issueNumber: number) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/detail/refresh`, {
      method: 'POST',
    }));
  ipcMain.handle('workspaces:issues:comment', (_event, workspaceId: string, issueNumber: number, body: string) =>
    backendJson(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/comments`,
      jsonInit('POST', { body }),
    ));
  ipcMain.handle('workspaces:issues:close', (_event, workspaceId: string, issueNumber: number) =>
    backendVoid(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/close`, {
      method: 'POST',
    }));
  ipcMain.handle('workspaces:issues:type:update', (_event, workspaceId: string, issueNumber: number, type: string | null) =>
    backendJson(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/type`,
      jsonInit('PATCH', { type }),
    ));
  ipcMain.handle('workspaces:issues:metadata:update', (
    _event,
    workspaceId: string,
    issueNumber: number,
    assignees: string[],
    labels: string[],
  ) => backendJson(
    `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/metadata`,
    jsonInit('PATCH', { assignees, labels }),
  ));
  ipcMain.handle('workspaces:sync', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/sync`, {
      method: 'POST',
    }));
  ipcMain.handle('workspaces:upstream', (
    _event,
    workspaceId: string,
    upstreamRepositoryId: number | null,
  ) => backendJson(
    `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/upstream`,
    jsonInit('PUT', { upstreamRepositoryId }),
  ));
  ipcMain.handle('workspaces:remove', (_event, workspaceId: string) =>
    backendVoid(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}`, {
      method: 'DELETE',
    }));
  ipcMain.handle('workspaces:delete', (_event, workspaceId: string, confirmed: boolean, repositoryName: string) =>
    backendVoid(
      `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/data`,
      jsonInit('DELETE', { confirmed, repositoryName }),
    ));
  ipcMain.handle('repos:profile', () => backendJson(`${backendBase()}/api/profile`));
  ipcMain.handle('repos:user-profile', (_event, login: string) =>
    backendJson(`${backendBase()}/api/profile/${encodeURIComponent(login)}`));
  ipcMain.handle('repos:contribution', (_event, login: string) => {
    const url = new URL(`${backendBase()}/api/contribution-graph`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });
  ipcMain.handle('repos:contribution-day', (_event, login: string, date: string) => {
    const url = new URL(`${backendBase()}/api/contribution-graph/day`);
    url.searchParams.set('login', login);
    url.searchParams.set('date', date);
    return backendJson(url);
  });
  ipcMain.handle('repos:pull', (_event, owner: string, repo: string, number: number) =>
    backendJson(
      `${backendBase()}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pulls/${number}`,
    ));
  ipcMain.handle('repos:issue-preview', (_event, owner: string, repo: string, number: number) =>
    backendJson(
      `${backendBase()}/api/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${number}/preview`,
    ));
  ipcMain.handle('repos:code-snippet', (_event, request: GitHubCodeSnippetRequest) => {
    const url = new URL(
      `${backendBase()}/api/repos/${encodeURIComponent(request.owner)}/${encodeURIComponent(request.repo)}/code-snippet`,
    );
    url.searchParams.set('commit', request.commit);
    url.searchParams.set('path', request.path);
    url.searchParams.set('startLine', String(request.startLine));
    url.searchParams.set('endLine', String(request.endLine));
    return backendJson(url);
  });
  ipcMain.handle('repos:activity', (_event, login: string) => {
    const url = new URL(`${backendBase()}/api/activity/recent`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });
  ipcMain.handle('repos:following', (_event, login: string) => {
    const url = new URL(`${backendBase()}/api/activity/following`);
    url.searchParams.set('login', login);
    return backendJson(url);
  });

  ipcMain.handle('github:cli-available', () =>
    backendJson(`${backendBase()}/api/auth/github/cli`));
  ipcMain.handle('github:cli-import', () =>
    backendJson(`${backendBase()}/api/auth/github/cli/import`, { method: 'POST' }));

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
  const backendReady = await waitForBackendReady();
  if (!backendReady) reportBackendFailure();
  await ensureWindow();
  if (!backendReady) return;
  refreshDashboardInBackground();
  void pollPrNotifications();
  prNotificationTimer = setInterval(() => { void pollPrNotifications(); }, 30_000);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
app.on('before-quit', () => {
  clearInterval(prNotificationTimer);
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
