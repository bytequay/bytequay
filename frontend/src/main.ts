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
import type { JiraLocation, JiraQuery, JiraTokenRequest } from './jira/contracts';
import { parseTeamActivity, parseTeamContributors, parseTeamCreateInput, parseTeamInput, parseTeamMemberInput, parseTeamQuery, parseTeams, teamId, teamMemberId } from './teams/contracts';
import { parseWorkItem, type CreateWorkItem, type WorkItemDecision, parseGoal, parseGoalExchange, parseGoalGap, parseGoalItem, parseGoalKnowledgeNote, parseGoalLesson, parseGoalRecommendation, parseList, type GoalItemKind, type GoalPatch } from './goals/contracts';
import { app, BrowserWindow, clipboard, dialog, ipcMain, Menu, nativeImage, Notification, screen, shell } from 'electron';
import fs from 'node:fs';
import os from 'node:os';
import { spawn as spawnPty } from 'node-pty';
import { BrowserTabs, registerBrowserTabIpc } from './browserTabs';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { notificationVersion, parsePrNotificationState, pendingPrNotifications, type PrNotificationState } from './prNotifications';
import {
  parseAgentApproval,
  parseAgentAttachment,
  parseAgentCompletion,
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
  parsePullRequestTaskLink,
  parseAgentTaskActivity,
  parseAgentTaskChanges,
  parseAgentTaskStartup,
  parseAgentTasks,
  parseAgentTurn,
  parseAgentTurnSettings,
  parseDevelopmentAutomation,
  parseModelSettings,
} from './agents/contracts';
import { parseDiscussionReplyDraft, parseTaskDiscussion, parseTaskDiscussions, type AskCommentDiscussionRequest, type AskDiscussionRequest, type DiscussionQuery, type DraftDiscussionReplyRequest, type OpenDiscussionRequest, type ResolveDiscussionRequest, type RetryDiscussionRequest } from './agents/discussions/contracts';
import { parseLocalReviewThread, parseLocalReviewThreads, type CreateDevelopmentLearningCommentRequest, type CreateLocalReviewRequest, type LocalReviewQuery, type ReplyLocalReviewRequest, type ResolveLocalReviewRequest, type RetryLocalReviewRequest } from './pulls/local-review/contracts';
import { parseAgentHistoryPage, parseAgentPendingApprovals } from './agents/events';
import {
  parseContextBaseline,
  parseContextBuildView,
  parseContextSpecCheck,
  parseContextSpecChecks,
  parseContextSpecCheckStarted,
  parseContextSpecModules,
  parseContextSpecSources,
  parseDismissContextSpecFindingRequest,
  parseSpecCheckDirectory,
  parseStartContextSpecCheckRequest,
} from './repository-context/contracts';
import {
  parseMemoryHarvestStarted, parseMemoryId, parseMemoryPage, parseMemorySwitches, parseMemoryText, parseMemoryTitle, parseMemoryVersion, parseStartMemoryHarvestRequest,
} from './memory/contracts';
import { parseMarkReplayRequest, parseReplay, parseReplayId, parseReplays, parseStartReplayRequest } from './memory/replays';
import {
  parseAgentReview,
  parseAgentReviews,
  parseReviewDraft,
} from './pulls/agent-review/contracts';
import { parseWorktreeChanges, codeHostKey, parseCodeHost, parseLocalRun, parseLocalRunOutput, parseLocalRuns, parseReviewCodeFile, parseReviewCodeFiles, parseReviewCodeSymbols } from './pulls/code-tests/contracts';
import { AgentSubscriptions } from './agents/subscriptions';
import { CodeTerminals, terminalEnvironment } from './terminals';
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
      const version = notificationVersion(item, state.handled[item.key]);
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
  const url = new URL(taskUrl(taskId, '/attachments'));
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
const browserTabs = new BrowserTabs(() => mainWindow, () => path.join(app.getPath('userData'), 'browser-tabs.json'));
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
  try { browserTabs.open(url); }
  catch (error) { console.warn('Unable to open browser page', error); }
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
  const { width, height } = screen.getPrimaryDisplay().workAreaSize;
  mainWindow = new BrowserWindow({
    width: Math.min(1600, width),
    height: Math.min(1000, height),
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
    browserTabs.releaseWindow();
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
  mainWindow.webContents.on('did-start-loading', () => browserTabs.rendererReload());
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
/** The backend URL for one agent task. `path` is appended verbatim; `query` becomes the search string. */
function taskUrl(taskId: string, path = '', query?: Record<string, string>): string {
  const base = `${backendBase()}/agent/tasks/${encodeURIComponent(taskId)}${path}`;
  return query === undefined ? base : `${base}?${new URLSearchParams(query)}`;
}

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

// An interactive login shell rebuilds the user's own PATH, which a Finder-launched app does not inherit.
// `-lc` keeps that PATH for a one-shot command, and the shell exits when the command does.
const codeTerminals = new CodeTerminals((cwd, cols, rows, command) => spawnPty(
  os.userInfo().shell ?? '/bin/zsh',
  command === undefined ? ['-l'] : ['-lc', command],
  { name: 'xterm-256color', cwd, cols, rows, env: terminalEnvironment(process.env) },
));

const agentSubscriptions = new AgentSubscriptions((taskId, after, signal) => fetch(
  taskUrl(taskId, '/stream')
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

function githubAccountChanged(): void {
  for (const window of BrowserWindow.getAllWindows()) {
    if (!window.isDestroyed() && !window.webContents.isDestroyed()) window.webContents.send('github:account-changed');
  }
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
    repository?: string,
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
  ipcMain.handle('agent:pr-task:find', async (_event, workspaceId: string, repo: string, number: number) => {
    const query = new URLSearchParams({ workspaceId, repo, number: String(number) });
    const response = await fetch(`${backendBase()}/agent/pull-request-task?${query}`, { headers: agentHeaders() });
    if (response.status === 204) return null;
    if (!response.ok) throw new Error(extractMessage(await response.text().catch(() => '')) || `Task lookup returned ${response.status}`);
    return parsePullRequestTaskLink(await response.json());
  });
  ipcMain.handle('agent:pr-task:assign', async (_event, workspaceId: string, repo: string, number: number) =>
    parsePullRequestTaskLink(await backendJson(`${backendBase()}/agent/pull-request-task`, {
      method: 'POST', headers: agentHeaders(true), body: JSON.stringify({ workspaceId, repo, number }), signal: AbortSignal.timeout(15 * 60_000),
    })));

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
    backendJson(taskUrl(taskId, '', { workspaceId }), { headers: agentHeaders() })
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:tasks:startup', (_event, taskId: string) =>
    backendJson(taskUrl(taskId, '/startup'), { headers: agentHeaders() })
      .then((progress) => parseAgentTaskStartup(progress)));
  ipcMain.handle('agent:task:defaults', (
    _event,
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    defaults: unknown,
  ) =>
    backendJson(taskUrl(taskId, '/defaults', { workspaceId }),
    jsonInit('PUT', { expectedVersion, defaults }, agentHeaders()))
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:tasks:changes', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/changes?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then(parseAgentTaskChanges));
  ipcMain.handle('agent:tasks:activity', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/agent/tasks/activity?${new URLSearchParams({ workspaceId })}`, { headers: agentHeaders() })
      .then(parseAgentTaskActivity));
  ipcMain.handle('agent:task:pull-requests', (_event, taskId: string, workspaceId: string, refresh?: boolean) =>
    backendJson(taskUrl(taskId, '/pull-requests')
      + `${refresh === true ? '/sync' : ''}?${new URLSearchParams({ workspaceId })}`,
    { method: refresh === true ? 'POST' : 'GET', headers: agentHeaders() }));
  ipcMain.handle('ci-autofix:list', (_event, limit: number) =>
    backendJson(`${backendBase()}/api/ci-autofix/runs?${new URLSearchParams({ limit: String(limit) })}`, { headers: agentHeaders() })
      .then((runs: unknown) => {
        if (!Array.isArray(runs)) throw new Error('ciRuns is not an array');
        return runs.map(parseCiAutofixRun);
      }));
  ipcMain.handle('ci-autofix:get', (_event, runId: string) =>
    backendJson(`${backendBase()}/api/ci-autofix/runs/${encodeURIComponent(runId)}`, { headers: agentHeaders() }).then(parseCiAutofixRun));
  ipcMain.handle('ci-autofix:message', (_event, runId: string, message: string, idempotencyKey: string) =>
    backendJson(`${backendBase()}/api/ci-autofix/runs/${encodeURIComponent(runId)}/messages`,
      jsonInit('POST', { message }, { ...agentHeaders(), 'Idempotency-Key': idempotencyKey })).then(parseCiAutofixRun));
  ipcMain.handle('agent:task:rename', (
    _event,
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    title: string,
  ) =>
    backendJson(taskUrl(taskId, '', { workspaceId }),
    jsonInit('PATCH', { expectedVersion, title }, agentHeaders()))
      .then((task) => parseAgentTask(task)));
  ipcMain.handle('agent:task:archive', (
    _event,
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    archived: boolean,
  ) =>
    backendJson(taskUrl(taskId, '', { workspaceId }),
    jsonInit('PATCH', { expectedVersion, archived }, agentHeaders()))
      .then((task) => parseAgentTask(task)));
  // Deleting removes the shells' working directory, so they go first.
  ipcMain.handle('agent:task:delete', (_event, taskId: string, workspaceId: string) => {
    codeTerminals.close(`task:${taskId}`);
    const url = new URL(taskUrl(taskId));
    url.searchParams.set('workspaceId', workspaceId);
    return backendVoid(url, { method: 'DELETE', headers: agentHeaders() });
  });
  ipcMain.handle('agent:task:effective-settings', (_event, taskId: string, workspaceId: string) =>
    backendJson(taskUrl(taskId, '/effective-settings', { workspaceId }), { headers: agentHeaders() })
      .then((settings) => parseAgentTurnSettings(settings)));
  ipcMain.handle('agent:history:read', async (_event, taskId: string, before?: string, after?: string) => {
    const query = new URLSearchParams();
    if (before !== undefined) query.set('before', before);
    if (after !== undefined) query.set('after', after);
    const page = await backendJson<unknown>(
      `${taskUrl(taskId, '/history')}?${query}`,
      { headers: agentHeaders() },
    );
    return parseAgentHistoryPage(page, taskId);
  });
  ipcMain.handle('agent:approvals:pending', async (_event, taskId: string, turnId: string) => {
    const approvals = await backendJson<unknown>(
      taskUrl(taskId, '/pending-approvals', { turnId }),
      { headers: agentHeaders() },
    );
    return parseAgentPendingApprovals(approvals, taskId, turnId);
  });
  const localReviewUrl = (taskId: string, workspaceId: string, suffix = ''): string =>
    taskUrl(taskId, `/local-review-comments${suffix}`, { workspaceId });
  const learningUrl = (taskId: string, workspaceId: string, suffix = ''): string =>
    taskUrl(taskId, `/learning${suffix}`, { workspaceId });
  ipcMain.handle('agent:learning:get', async (_event, taskId: string, workspaceId: string) => {
    const turn = await backendJson(learningUrl(taskId, workspaceId), { headers: agentHeaders() });
    return turn === null ? null : parseAgentTurn(turn);
  });
  ipcMain.handle('agent:learning:comment', async (_event, request: CreateDevelopmentLearningCommentRequest) => {
    const { taskId, workspaceId, idempotencyKey, ...body } = request;
    return parseLocalReviewThread(await backendJson(learningUrl(taskId, workspaceId, '/comments'),
      jsonInit('POST', body, { ...agentHeaders(true), 'Idempotency-Key': idempotencyKey })));
  });
  const discussionUrl = (taskId: string, workspaceId: string, suffix = ''): string =>
    taskUrl(taskId, `/discussions${suffix}`, { workspaceId });
  ipcMain.handle('agent:discussions:list', async (_event, query: DiscussionQuery) =>
    parseTaskDiscussions(await backendJson(discussionUrl(query.taskId, query.workspaceId), { headers: agentHeaders() })));
  ipcMain.handle('agent:discussions:open', async (_event, request: OpenDiscussionRequest) => {
    const { taskId, workspaceId, ...body } = request;
    return parseTaskDiscussion(await backendJson(discussionUrl(taskId, workspaceId), jsonInit('POST', body, agentHeaders(true))));
  });
  ipcMain.handle('agent:discussions:ask', async (_event, request: AskDiscussionRequest) => {
    const { taskId, workspaceId, discussionId, ...body } = request;
    return parseTaskDiscussion(await backendJson(discussionUrl(taskId, workspaceId, `/${encodeURIComponent(discussionId)}/messages`),
      jsonInit('POST', body, agentHeaders(true))));
  });
  ipcMain.handle('agent:discussions:resolve', async (_event, request: ResolveDiscussionRequest) => {
    const { taskId, workspaceId, discussionId, ...body } = request;
    return parseTaskDiscussion(await backendJson(discussionUrl(taskId, workspaceId, `/${encodeURIComponent(discussionId)}`),
      jsonInit('PATCH', body, agentHeaders(true))));
  });
  ipcMain.handle('agent:discussions:retry', async (_event, request: RetryDiscussionRequest) => {
    const { taskId, workspaceId, discussionId, messageId } = request;
    return parseTaskDiscussion(await backendJson(discussionUrl(taskId, workspaceId,
      `/${encodeURIComponent(discussionId)}/messages/${encodeURIComponent(messageId)}/retry`), jsonInit('POST', {}, agentHeaders(true))));
  });
  ipcMain.handle('agent:discussions:ask-comment', async (_event, request: AskCommentDiscussionRequest) => {
    const { taskId, workspaceId, ...body } = request;
    return parseTaskDiscussion(await backendJson(discussionUrl(taskId, workspaceId, '/comments'), jsonInit('POST', body, agentHeaders(true))));
  });
  ipcMain.handle('agent:discussions:draft-reply', async (_event, request: DraftDiscussionReplyRequest) => {
    const { taskId, workspaceId, discussionId } = request;
    return parseDiscussionReplyDraft(await backendJson(discussionUrl(taskId, workspaceId, `/${encodeURIComponent(discussionId)}/reply-draft`),
      jsonInit('POST', {}, agentHeaders(true))));
  });
  ipcMain.handle('agent:local-review:list', async (_event, query: LocalReviewQuery) => {
    const url = 'taskId' in query ? localReviewUrl(query.taskId, query.workspaceId)
      : `${backendBase()}/agent/local-review-comments?${new URLSearchParams({
        repo: query.repo, number: String(query.number), ...(query.workspaceId === undefined ? {} : { workspaceId: query.workspaceId }),
      })}`;
    return parseLocalReviewThreads(await backendJson(url, { headers: agentHeaders() }));
  });
  ipcMain.handle('agent:local-review:create', async (_event, request: CreateLocalReviewRequest) => {
    const { taskId, workspaceId, idempotencyKey, ...body } = request;
    return parseLocalReviewThread(await backendJson(localReviewUrl(taskId, workspaceId),
      jsonInit('POST', body, { ...agentHeaders(true), 'Idempotency-Key': idempotencyKey })));
  });
  ipcMain.handle('agent:local-review:reply', async (_event, request: ReplyLocalReviewRequest) => {
    const { taskId, workspaceId, threadId, idempotencyKey, ...body } = request;
    return parseLocalReviewThread(await backendJson(localReviewUrl(taskId, workspaceId, `/${encodeURIComponent(threadId)}/messages`),
      jsonInit('POST', body, { ...agentHeaders(true), 'Idempotency-Key': idempotencyKey })));
  });
  ipcMain.handle('agent:local-review:resolve', async (_event, request: ResolveLocalReviewRequest) => {
    const { taskId, workspaceId, threadId, idempotencyKey, ...body } = request;
    return parseLocalReviewThread(await backendJson(localReviewUrl(taskId, workspaceId, `/${encodeURIComponent(threadId)}`),
      jsonInit('PATCH', body, { ...agentHeaders(true), 'Idempotency-Key': idempotencyKey })));
  });
  ipcMain.handle('agent:local-review:retry', async (_event, request: RetryLocalReviewRequest) => {
    const { taskId, workspaceId, threadId, messageId, ...body } = request;
    return parseLocalReviewThread(await backendJson(localReviewUrl(taskId, workspaceId,
      `/${encodeURIComponent(threadId)}/messages/${encodeURIComponent(messageId)}/retry`), jsonInit('POST', body, agentHeaders(true))));
  });
  ipcMain.handle('agent:turns:start', (_event, request: StartAgentTurnRequest) =>
    backendJson(taskUrl(request.taskId, '/turns'), {
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
    backendJson(taskUrl(request.taskId, '/queue', { workspaceId: request.workspaceId }), {
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
    backendJson(taskUrl(taskId, '/queue', { workspaceId }), { headers: agentHeaders() })
      .then((queue) => parseAgentQueue(queue)));
  ipcMain.handle('agent:queue:remove', (_event, taskId: string, workspaceId: string, queuedInputId: string) =>
    backendJson(taskUrl(taskId, `/queue/${encodeURIComponent(queuedInputId)}`, { workspaceId }), { method: 'DELETE', headers: agentHeaders() })
      .then((queued) => parseAgentQueuedInput(queued)));
  ipcMain.handle('agent:queue:resume', (_event, taskId: string, workspaceId: string) =>
    backendJson(taskUrl(taskId, '/queue/resume', { workspaceId }), { method: 'POST', headers: agentHeaders() })
      .then((turn) => parseAgentTurn(turn)));
  ipcMain.handle('agent:queue:steer', (_event, taskId: string, workspaceId: string, queuedInputId: string, turnId: string) =>
    backendJson(taskUrl(taskId, `/queue/${encodeURIComponent(queuedInputId)}/steer`, { workspaceId, turnId }), { method: 'POST', headers: agentHeaders() })
      .then(parseAgentInputDelivery));
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
    const url = new URL(taskUrl(taskId, `/attachments/${encodeURIComponent(attachmentId)}`));
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
    const url = new URL(taskUrl(taskId, `/attachments/${encodeURIComponent(attachmentId)}`));
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
    answers?: Record<string, string>,
  ) =>
    backendJson(`${backendBase()}/agent/approvals/${encodeURIComponent(approvalId)}`, jsonInit('POST', {
      decision: decision === 'ALLOW_FOR_TASK' ? 'ALLOW' : decision,
      forTask: decision === 'ALLOW_FOR_TASK',
      scopedPermission: scopedPermission ?? null,
      answers: answers ?? null,
    }, agentHeaders())).then((approval) => parseAgentApproval(approval)));
  ipcMain.handle('agent:tasks:complete', (_event, taskId: string, workspaceId: string, notes?: string) => {
    // Completing removes the worktree a Code & Tests shell runs in, as review:end does for a review.
    codeTerminals.close(`task:${taskId}`);
    return backendJson(taskUrl(taskId, '/complete', { workspaceId }),
    jsonInit('POST', { notes: notes ?? null }, agentHeaders()))
      .then((completion) => parseAgentCompletion(completion));
  });
  ipcMain.handle('agent:task:cleanup', (_event, taskId: string, workspaceId: string) => {
    codeTerminals.close(`task:${taskId}`);
    return backendJson(taskUrl(taskId, '/cleanup', { workspaceId }), {
      method: 'POST',
      headers: agentHeaders(),
    }).then((task) => parseAgentTask(task));
  });
  ipcMain.handle('agent:task:mark-failed', (_event, taskId: string, workspaceId: string, reason: string) => {
    codeTerminals.close(`task:${taskId}`);
    return backendJson(taskUrl(taskId, '/mark-failed', { workspaceId }),
    jsonInit('POST', { reason }, agentHeaders()))
      .then((task) => parseAgentTask(task));
  });
  ipcMain.handle('agent:task:stop-impact', (_event, taskId: string, workspaceId: string) =>
    backendJson(taskUrl(taskId, '/runtime/stop-impact', { workspaceId }), { headers: agentHeaders() })
      .then((impact) => parseAgentRuntimeStopImpact(impact)));
  ipcMain.handle('agent:task:stop', (_event, taskId: string, workspaceId: string, generation: string) =>
    backendJson(taskUrl(taskId, '/runtime/stop', { workspaceId }), {
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
  // Starting or updating a review fetches remote refs and waits for provider startup before responding.
  ipcMain.handle('review:start', (_event, workspaceId: string, repo: string, number: number) =>
    review('', {}, { method: 'POST', body: JSON.stringify({ workspaceId, repo, number }), signal: AbortSignal.timeout(15 * 60_000) }));
  ipcMain.handle('review:task:find', async (_event, workspaceId: string, taskId: string) => {
    const response = await fetch(reviewUrl(`/tasks/${encodeURIComponent(taskId)}`, { workspaceId }), { headers: agentHeaders() });
    if (response.status === 204) return null;
    if (!response.ok) throw new Error(extractMessage(await response.text().catch(() => '')) || `Review lookup returned ${response.status}`);
    return parseAgentReview(await response.json());
  });
  ipcMain.handle('review:task:start', (_event, workspaceId: string, taskId: string) =>
    review(`/tasks/${encodeURIComponent(taskId)}`, { workspaceId }, { method: 'POST', signal: AbortSignal.timeout(15 * 60_000) }));
  ipcMain.handle('review:get', (_event, reviewId: string) => review(`/${encodeURIComponent(reviewId)}`));
  ipcMain.handle('review:stop', (_event, reviewId: string) => review(`/${encodeURIComponent(reviewId)}/stop`, {}, { method: 'POST' }));
  ipcMain.handle('review:runtime:stop-impact', (_event, reviewId: string) =>
    backendJson(reviewUrl(`/${encodeURIComponent(reviewId)}/runtime/stop-impact`), { headers: agentHeaders() })
      .then(parseAgentRuntimeStopImpact));
  ipcMain.handle('review:runtime:stop', (_event, reviewId: string, generation: string) =>
    review(`/${encodeURIComponent(reviewId)}/runtime/stop`, {}, {
      method: 'POST', body: JSON.stringify({ generation }), signal: AbortSignal.timeout(90_000),
    }));
  ipcMain.handle('review:resume', (_event, reviewId: string) =>
    review(`/${encodeURIComponent(reviewId)}/resume`, {}, { method: 'POST', signal: AbortSignal.timeout(15 * 60_000) }));
  ipcMain.handle('review:update', (_event, reviewId: string) =>
    review(`/${encodeURIComponent(reviewId)}/update`, {}, { method: 'POST', signal: AbortSignal.timeout(15 * 60_000) }));
  // Ending removes the shells' working directory, so they go first.
  ipcMain.handle('review:end', (_event, reviewId: string) => {
    codeTerminals.close(`review:${reviewId}`);
    return review(`/${encodeURIComponent(reviewId)}/end`, {}, { method: 'POST' });
  });
  // A review's private threads are discussions of its task, which only the review routes reach.
  const commentThreadUrl = (reviewId: string, suffix = ''): string => reviewUrl(`/${encodeURIComponent(reviewId)}/comment-threads${suffix}`);
  ipcMain.handle('review:comment-threads:list', async (_event, reviewId: string) =>
    parseTaskDiscussions(await backendJson(commentThreadUrl(reviewId), { headers: agentHeaders() })));
  ipcMain.handle('review:comment-threads:ask', async (_event, reviewId: string, key: string, question: string) =>
    parseTaskDiscussion(await backendJson(commentThreadUrl(reviewId), jsonInit('POST', { key, question }, agentHeaders(true)))));
  ipcMain.handle('review:comment-threads:resolve', async (_event, reviewId: string, discussionId: string, resolved: boolean, expectedVersion: number) =>
    parseTaskDiscussion(await backendJson(commentThreadUrl(reviewId, `/${encodeURIComponent(discussionId)}`),
      jsonInit('PATCH', { resolved, expectedVersion }, agentHeaders(true)))));
  ipcMain.handle('review:comment-threads:retry', async (_event, reviewId: string, discussionId: string, messageId: string) =>
    parseTaskDiscussion(await backendJson(commentThreadUrl(reviewId, `/${encodeURIComponent(discussionId)}/messages/${encodeURIComponent(messageId)}/retry`),
      jsonInit('POST', {}, agentHeaders(true)))));
  ipcMain.handle('review:comment-threads:draft-reply', async (_event, reviewId: string, discussionId: string) =>
    parseDiscussionReplyDraft(await backendJson(commentThreadUrl(reviewId, `/${encodeURIComponent(discussionId)}/reply-draft`),
      jsonInit('POST', {}, agentHeaders(true)))));
  // Code & Tests reads a review's working copy through its review, and a development task's through the task and its PR.
  const codeUrl = (value: unknown, path: string, query: Record<string, string> = {}): string => {
    const host = parseCodeHost(value);
    if (host.kind === 'review') return reviewUrl(`/${encodeURIComponent(host.reviewId)}/code${path}`, query);
    const url = new URL(taskUrl(host.taskId, `/code${path}`));
    Object.entries({ workspaceId: host.workspaceId, ...query })
      .forEach(([key, value]) => url.searchParams.set(key, value));
    return url.toString();
  };
  ipcMain.handle('terminal:open', (event, host: unknown, cols: number, rows: number) =>
    codeTerminals.open(event.sender, codeHostKey(parseCodeHost(host)), cols, rows, async () => {
      const body = await backendJson<{ workingDirectory?: unknown }>(codeUrl(host, '/terminal'), {
        method: 'POST', headers: agentHeaders(),
      });
      if (typeof body.workingDirectory !== 'string') throw new Error('Malformed terminal response');
      return body.workingDirectory;
    }));
  // The command is read from provider readiness, never from the renderer: this runs in a login shell,
  // and it carries the provider-home variable that keeps the sign-in out of the user's own home.
  ipcMain.handle('provider:signin:open', async (event, laneId: unknown, cols: number, rows: number) => {
    if (typeof laneId !== 'string' || laneId === '') throw new Error('Malformed provider lane');
    const statuses = await backendJson(`${backendBase()}/agent/providers/status`, {
      headers: agentHeaders(),
    }).then(parseAgentProviderStatuses);
    const command = statuses.find(status => status.laneId === laneId)?.signInCommand;
    if (typeof command !== 'string' || command.trim() === '') throw new Error(`${laneId} reports no sign-in command`);
    return codeTerminals.open(event.sender, `provider:${laneId}`, cols, rows, async () => os.homedir(), command);
  });
  ipcMain.on('terminal:write', (event, terminalId: string, data: string) => codeTerminals.write(event.sender, terminalId, data));
  ipcMain.on('terminal:resize', (event, terminalId: string, cols: number, rows: number) => codeTerminals.resize(event.sender, terminalId, cols, rows));
  ipcMain.handle('code:changes', (_event, host: unknown, request: unknown) => {
    if (typeof request !== 'object' || request === null) throw new Error('Malformed change selection');
    const selection = request as Record<string, unknown>;
    if (!['ALL', 'UNCOMMITTED', 'COMMITTED', 'COMMIT'].includes(String(selection.scope))) throw new Error('Malformed change scope');
    const query: Record<string, string> = { scope: String(selection.scope) };
    for (const field of ['commit', 'base', 'head']) {
      if (selection[field] === undefined) continue;
      if (typeof selection[field] !== 'string' || !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(selection[field])) throw new Error('Malformed change revision');
      query[field] = selection[field];
    }
    return backendJson(codeUrl(host, '/changes', query), { headers: agentHeaders() }).then(parseWorktreeChanges);
  });
  ipcMain.handle('code:files', (_event, host: unknown) =>
    backendJson(codeUrl(host, '/files'), { headers: agentHeaders() }).then(parseReviewCodeFiles));
  ipcMain.handle('code:file', (_event, host: unknown, path: string, expectedRevision?: string) =>
    backendJson(codeUrl(host, '/file', { path, ...(expectedRevision === undefined ? {} : { expectedRevision }) }), { headers: agentHeaders() }).then(parseReviewCodeFile));
  ipcMain.handle('code:symbols', (_event, host: unknown, query: string, limit: number) =>
    backendJson(codeUrl(host, '/symbols', { query, limit: String(limit) }), { headers: agentHeaders() })
      .then(parseReviewCodeSymbols));
  ipcMain.handle('code:outline', (_event, host: unknown, path: string) =>
    backendJson(codeUrl(host, '/outline', { path }), { headers: agentHeaders() }).then(parseReviewCodeSymbols));
  ipcMain.handle('code:runs:list', (_event, host: unknown) =>
    backendJson(codeUrl(host, '/runs'), { headers: agentHeaders() }).then(parseLocalRuns));
  // A refused preflight (no Maven wrapper, no JDK) is an answer for the run panel, not an error.
  ipcMain.handle('code:runs:start', async (_event, host: unknown, path: string, method: string | null) => {
    const response = await fetch(codeUrl(host, '/runs'), jsonInit('POST', { path, method }, agentHeaders(true)));
    const body = await response.text();
    if (response.status === 422) {
      const refusal = JSON.parse(body) as { reason?: unknown; action?: unknown };
      return { refusal: { reason: typeof refusal.reason === 'string' ? refusal.reason : 'This run cannot start', action: refusal.action === 'OPEN_TERMINAL' ? 'OPEN_TERMINAL' : null } };
    }
    if (!response.ok) throw new Error(extractMessage(body) || `Starting the run returned ${response.status}`);
    return { run: parseLocalRun(JSON.parse(body)) };
  });
  ipcMain.handle('code:runs:stop', (_event, host: unknown, runId: string) =>
    backendJson(codeUrl(host, `/runs/${encodeURIComponent(runId)}/stop`), { method: 'POST', headers: agentHeaders() }).then(parseLocalRun));
  // A stale draft is a conflict the editor resolves with the user, not an error.
  ipcMain.handle('code:save', async (_event, host: unknown, path: string, content: string, expectedHash: string) => {
    const response = await fetch(codeUrl(host, '/file'), jsonInit('PUT', { path, content, expectedHash }, agentHeaders(true)));
    const body = await response.text();
    if (response.status === 409) {
      const parsed = JSON.parse(body) as { currentHash?: unknown; currentContent?: unknown };
      if ('currentHash' in parsed) {
        return { conflict: { currentHash: typeof parsed.currentHash === 'string' ? parsed.currentHash : null,
          currentContent: typeof parsed.currentContent === 'string' ? parsed.currentContent : null } };
      }
    }
    if (!response.ok) throw new Error(extractMessage(body) || `Saving the file returned ${response.status}`);
    return { file: parseReviewCodeFile(JSON.parse(body)) };
  });
  ipcMain.handle('review:code:discard', (_event, reviewId: string, expectedHead: string) =>
    backendJson(reviewUrl(`/${encodeURIComponent(reviewId)}/code/discard`), jsonInit('POST', { expectedHead }, agentHeaders(true))).then(parseReviewCodeFiles));
  ipcMain.handle('review:code:sync', (_event, reviewId: string, expectedHead: string) =>
    review(`/${encodeURIComponent(reviewId)}/code/sync`, {}, {
      method: 'POST', body: JSON.stringify({ expectedHead }), signal: AbortSignal.timeout(15 * 60_000),
    }));
  ipcMain.handle('code:runs:output', (_event, host: unknown, runId: string, after: number) =>
    backendJson(codeUrl(host, `/runs/${encodeURIComponent(runId)}/output`, { after: String(Math.max(0, Math.floor(after))) }), { headers: agentHeaders() })
      .then(parseLocalRunOutput));
  ipcMain.handle('review:ask', (_event, reviewId: string, findingId: string | null, targetRevision: number, message: string, cardId?: string) =>
    review(`/${encodeURIComponent(reviewId)}/questions`, {}, {
      method: 'POST',
      body: JSON.stringify({ findingId, targetRevision, message, cardId }),
    }));
  ipcMain.handle('review:message', (_event, reviewId: string, message: string, delivery: string, idempotencyKey: string) =>
    backendJson(reviewUrl(`/${encodeURIComponent(reviewId)}/messages`), {
      method: 'POST',
      headers: { ...agentHeaders(true), 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ message, delivery }),
    }).then(parseAgentReview));
  ipcMain.handle('review:queue:remove', (_event, reviewId: string, queuedInputId: string) =>
    review(`/${encodeURIComponent(reviewId)}/queue/${encodeURIComponent(queuedInputId)}`, {}, { method: 'DELETE' }));
  ipcMain.handle('review:queue:steer', (_event, reviewId: string, queuedInputId: string, turnId: string) =>
    review(`/${encodeURIComponent(reviewId)}/queue/${encodeURIComponent(queuedInputId)}/steer`, { turnId }, { method: 'POST' }));
  ipcMain.handle('review:finding:resolve', (_event, reviewId: string, findingId: string, expectedVersion: number, reason: string, remember: boolean) =>
    review(`/${encodeURIComponent(reviewId)}/findings/${encodeURIComponent(findingId)}/resolve`, {}, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion, reason, remember: remember === true }),
    }));
  ipcMain.handle('review:finding:reopen', (_event, reviewId: string, findingId: string, expectedVersion: number) =>
    review(`/${encodeURIComponent(reviewId)}/findings/${encodeURIComponent(findingId)}/reopen`, {}, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion }),
    }));
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
  ipcMain.handle('review:approvals:resolve', (_event, reviewId: string, approvalId: string, decision: string, answers?: Record<string, string>) =>
    backendJson(reviewUrl(`/${encodeURIComponent(reviewId)}/approvals/${encodeURIComponent(approvalId)}`),
      jsonInit('POST', { decision, answers: answers ?? null }, agentHeaders()))
      .then(parseAgentApproval));
  ipcMain.handle('review:stream:subscribe', (event, reviewId: string, after: number, statusOnly?: boolean) =>
    reviewSubscriptions.start(event.sender, reviewId, after, statusOnly === true));
  ipcMain.handle('review:stream:unsubscribe', (_event, subscriptionId: string) => {
    reviewSubscriptions.stop(subscriptionId);
  });

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
    }));
  ipcMain.handle('review:draft:submit', (_event, repo: string, number: number, expectedVersion: number, body: string, event: string) =>
    backendJson(reviewUrl('/drafts/submit', { repo, number: String(number) }),
      jsonInit('POST', { expectedVersion, body, event }, agentHeaders()))
      );
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
    githubAccountChanged();
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
  ipcMain.handle('pat:delete', async () => {
    await backendVoid(`${backendBase()}/api/auth/github/token`, { method: 'DELETE' });
    githubAccountChanged();
  });

  ipcMain.handle('settings:models', (_event, workspaceId?: string) =>
    backendJson(`${backendBase()}/api/settings/models${workspaceId === undefined ? '' : `?workspaceId=${encodeURIComponent(workspaceId)}`}`)
      .then(parseModelSettings));
  ipcMain.handle('settings:models:refresh', (_event, force = false) =>
    backendJson(`${backendBase()}/api/settings/models/refresh?force=${force === true}`, { method: 'POST' })
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

  ipcMain.handle('backport-chains:list', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/backport-chains`));
  ipcMain.handle('backport-chains:source', (_event, workspaceId: string, id: string, selection: { version: number; oldest: string; newest: string }) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/backport-chains/${encodeURIComponent(id)}/source`, jsonInit('POST', selection)));
  for (const action of ['stop', 'resume'] as const) {
    ipcMain.handle(`backport-chains:${action}`, (_event, workspaceId: string, id: string) =>
      backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/backport-chains/${encodeURIComponent(id)}/${action}`, { method: 'POST' }));
  }
  ipcMain.handle('workspaces:linked-work', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/linked-work`));
  ipcMain.handle('workspaces:related-request', (_event, workspaceId: string, taskId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/tasks/${encodeURIComponent(taskId)}/related-request`));
  ipcMain.handle('workspaces:relationships', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/relationships`));
  ipcMain.handle('workspaces:relationships:save', (_event, workspaceId: string, relationship: import('./types').WorkspaceRelationshipDto) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/relationships`,
      jsonInit('PUT', relationship)));
  ipcMain.handle('workspaces:relationships:remove', (_event, workspaceId: string, destinationId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/relationships/${encodeURIComponent(destinationId)}`,
      { method: 'DELETE' }));
  ipcMain.handle('workspaces:settings', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/settings`));
  const contextBuildUrl = (workspaceId: string) => `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/repository-context/build`;
  ipcMain.handle('repository-context:build:get', (_event, workspaceId: string) =>
    backendJson(contextBuildUrl(workspaceId), { headers: agentHeaders() }).then(value => parseContextBuildView(value, workspaceId)));
  ipcMain.handle('repository-context:build:baseline', (_event, workspaceId: string) =>
    backendJson(contextBuildUrl(workspaceId) + '/baseline', { headers: agentHeaders() }).then(parseContextBaseline));
  const specCheckUrl = (workspaceId: string) => `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/repository-context/spec-checks`;
  ipcMain.handle('repository-context:spec-checks:get', (_event, workspaceId: string) =>
    backendJson(specCheckUrl(workspaceId), { headers: agentHeaders() }).then(parseContextSpecChecks));
  ipcMain.handle('repository-context:spec-checks:modules', (_event, workspaceId: string) =>
    backendJson(`${specCheckUrl(workspaceId)}/modules`, { headers: agentHeaders() }).then(parseContextSpecModules));
  ipcMain.handle('repository-context:spec-checks:sources', (_event, workspaceId: string, directory: unknown) =>
    backendJson(`${specCheckUrl(workspaceId)}/sources?${new URLSearchParams({ directory: parseSpecCheckDirectory(directory) })}`, { headers: agentHeaders() })
      .then(parseContextSpecSources));
  ipcMain.handle('repository-context:spec-checks:start', (_event, workspaceId: string, request: unknown) =>
    backendJson(specCheckUrl(workspaceId), jsonInit('POST', parseStartContextSpecCheckRequest(request), agentHeaders()))
      .then(value => parseContextSpecCheckStarted(value, workspaceId)));
  ipcMain.handle('repository-context:spec-checks:dismiss', (_event, workspaceId: string, checkId: string, candidateId: string, request: unknown) =>
    backendJson(`${specCheckUrl(workspaceId)}/${encodeURIComponent(checkId)}/candidates/${encodeURIComponent(candidateId)}/dismiss`,
      jsonInit('POST', parseDismissContextSpecFindingRequest(request), agentHeaders())).then(parseContextSpecCheck));
  const memoryUrl = (workspaceId: string, path = '') => `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/memory${path}`;
  ipcMain.handle('memory:page', (_event, workspaceId: string) => backendJson(memoryUrl(workspaceId)).then(parseMemoryPage));
  ipcMain.handle('memory:edit', (_event, workspaceId: string, memoryId: unknown, expectedVersion: unknown, title: unknown, text: unknown) =>
    backendJson(memoryUrl(workspaceId, `/${parseMemoryId(memoryId)}`),
      jsonInit('PATCH', { expectedVersion: parseMemoryVersion(expectedVersion), title: parseMemoryTitle(title), text: parseMemoryText(text) }))
      .then(parseMemoryPage));
  ipcMain.handle('memory:restore', (_event, workspaceId: string, memoryId: unknown, expectedVersion: unknown) =>
    backendJson(memoryUrl(workspaceId, `/${parseMemoryId(memoryId)}/restore`), jsonInit('POST', { expectedVersion: parseMemoryVersion(expectedVersion) })).then(parseMemoryPage));
  ipcMain.handle('memory:keep', (_event, workspaceId: string, memoryId: unknown) =>
    backendJson(memoryUrl(workspaceId, `/${parseMemoryId(memoryId)}/keep`), { method: 'POST' }).then(parseMemoryPage));
  ipcMain.handle('memory:delete', (_event, workspaceId: string, memoryId: unknown) =>
    backendJson(memoryUrl(workspaceId, `/${parseMemoryId(memoryId)}`), { method: 'DELETE' }).then(parseMemoryPage));
  ipcMain.handle('memory:undo-remember', (_event, workspaceId: string, memoryId: unknown) =>
    backendJson(memoryUrl(workspaceId, `/${parseMemoryId(memoryId)}/undo-remember`), { method: 'POST' }).then(parseMemoryPage));
  ipcMain.handle('memory:undo-delete', (_event, workspaceId: string, memoryId: unknown) =>
    backendJson(memoryUrl(workspaceId, `/${parseMemoryId(memoryId)}/undo-delete`), { method: 'POST' }).then(parseMemoryPage));
  ipcMain.handle('memory:switches', (_event, workspaceId: string, switches: unknown) =>
    backendJson(memoryUrl(workspaceId, '/switches'), jsonInit('PUT', parseMemorySwitches(switches))).then(parseMemoryPage));
  ipcMain.handle('memory:harvest', (_event, workspaceId: string, request: unknown) =>
    backendJson(memoryUrl(workspaceId, '/harvest'), jsonInit('POST', parseStartMemoryHarvestRequest(request))).then(value => parseMemoryHarvestStarted(value, workspaceId)));
  const replayUrl = (path = '') => `${backendBase()}/api/agent-review/replays${path}`;
  ipcMain.handle('replay:list', (_event, workspaceId: string) =>
    backendJson(`${replayUrl()}?workspaceId=${encodeURIComponent(workspaceId)}`, { headers: agentHeaders() }).then(parseReplays));
  // A start reads GitHub, fetches the replayed head and starts two reviews, which can outlast the usual minute.
  ipcMain.handle('replay:start', (_event, workspaceId: string, request: unknown) =>
    backendJson(replayUrl(), { ...jsonInit('POST', { workspaceId, ...parseStartReplayRequest(request) }, agentHeaders()), signal: AbortSignal.timeout(15 * 60_000) })
      .then(parseReplay));
  ipcMain.handle('replay:mark', (_event, workspaceId: string, replayId: unknown, request: unknown) =>
    backendJson(replayUrl(`/${parseReplayId(replayId)}/marks`), jsonInit('PUT', { workspaceId, ...parseMarkReplayRequest(request) }, agentHeaders())).then(parseReplay));
  ipcMain.handle('replay:delete', (_event, workspaceId: string, replayId: unknown) =>
    backendVoid(`${replayUrl(`/${parseReplayId(replayId)}`)}?workspaceId=${encodeURIComponent(workspaceId)}`, { method: 'DELETE', headers: agentHeaders() }));
  ipcMain.handle('repository-context:build:stop', (_event, workspaceId: string, taskId: string) =>
    backendJson(contextBuildUrl(workspaceId) + '/stop', jsonInit('POST', { taskId }, agentHeaders())).then(value => parseContextBuildView(value, workspaceId)));
  ipcMain.handle('repository-context:build:stop-impact', (_event, workspaceId: string, taskId: string) =>
    backendJson(contextBuildUrl(workspaceId) + '/runtime/stop-impact?taskId=' + encodeURIComponent(taskId), { headers: agentHeaders() }).then(parseAgentRuntimeStopImpact));
  ipcMain.handle('repository-context:build:stop-runtime', (_event, workspaceId: string, taskId: string, generation: string) =>
    backendJson(contextBuildUrl(workspaceId) + '/runtime/stop', jsonInit('POST', { taskId, generation }, agentHeaders())).then(value => parseContextBuildView(value, workspaceId)));
  ipcMain.handle('repository-context:build:fail', (_event, workspaceId: string, taskId: string, reason: string) =>
    backendJson(contextBuildUrl(workspaceId) + '/mark-failed', jsonInit('POST', { taskId, reason }, agentHeaders())).then(value => parseContextBuildView(value, workspaceId)));
  ipcMain.handle('repository-context:build:cleanup', (_event, workspaceId: string, taskId: string) =>
    backendJson(contextBuildUrl(workspaceId) + '/cleanup', jsonInit('POST', { taskId }, agentHeaders())).then(value => parseContextBuildView(value, workspaceId)));
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

  ipcMain.handle('prs:list', (_event, workspaceId: string | undefined, page: number, pageSize: number) => {
    const url = new URL(workspaceId
      ? `/api/workspaces/${encodeURIComponent(workspaceId)}/pull-requests`
      : '/prs', backendBase());
    url.searchParams.set('page', String(page));
    url.searchParams.set('size', String(pageSize));
    return backendJson(url);
  });
  ipcMain.handle('prs:attention', () => backendJson(`${backendBase()}/prs/attention`));
  ipcMain.handle('prs:stored', (_event, repo: string, number: number) =>
    backendJson(prUrl('/prs/stored', repo, number)));
  ipcMain.handle('prs:stored:description', (_event, repo: string, number: number) =>
    backendJson(prUrl('/prs/stored/description', repo, number)));
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
  ipcMain.handle('prs:attention:pinned', (_event, keys: unknown) => {
    notificationState().pinnedKeys = parsePrNotificationState({ pinnedKeys: keys }).pinnedKeys;
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
  ipcMain.handle('prs:detail:refresh:by-ref', (_event, repo: string, number: number, checksOnly?: boolean) => {
    const url = prUrl('/prs/detail/refresh', repo, number);
    if (checksOnly === true) url.searchParams.set('checksOnly', 'true');
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
  ipcMain.handle('prs:title:update', (_event, repo: string, number: number, title: string) =>
    backendVoid(prUrl('/prs/title', repo, number), jsonInit('PATCH', { title })));
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
  ipcMain.handle('prs:ready-for-review', (_event, repo: string, number: number) =>
    backendVoid(prUrl('/prs/ready-for-review', repo, number), { method: 'POST' }));
  ipcMain.handle('prs:merge-queue:remove', (_event, repo: string, number: number) =>
    backendVoid(prUrl('/prs/merge-queue', repo, number), { method: 'DELETE' }));
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
  ipcMain.handle('prs:checks:log', (_event, repo: string, number: number, jobId: number) => {
    const url = prUrl('/prs/checks/log', repo, number);
    url.searchParams.set('jobId', String(jobId));
    return backendJson(url);
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
  const jiraSitePath = (connectionId: string, cloudId: string) => `${backendBase()}/api/jira/connections/${encodeURIComponent(connectionId)}/sites/${encodeURIComponent(cloudId)}`;
  const jiraIssuePath = (location: JiraLocation) => `${jiraSitePath(location.connectionId, location.cloudId)}/issues/${encodeURIComponent(location.issueId)}`;
  ipcMain.handle('jira:config', () => backendJson(`${backendBase()}/api/jira/config`, { headers: agentHeaders() }));
  ipcMain.handle('jira:reconnect', (_event, id: string) => backendJson(`${backendBase()}/api/jira/connections/${encodeURIComponent(id)}/reconnect`, { method: 'POST', headers: agentHeaders() }));
  ipcMain.handle('jira:connections', () => backendJson(`${backendBase()}/api/jira/connections`, { headers: agentHeaders() }));
  ipcMain.handle('jira:connect', () => backendJson(`${backendBase()}/api/jira/connections`, { method: 'POST', headers: agentHeaders() }));
  ipcMain.handle('jira:token:connect', (_event, request: JiraTokenRequest) => backendJson(`${backendBase()}/api/jira/connections/token`, jsonInit('POST', request, agentHeaders())));
  ipcMain.handle('jira:token:replace', (_event, id: string, request: JiraTokenRequest) => backendJson(`${backendBase()}/api/jira/connections/${encodeURIComponent(id)}/token`, jsonInit('PUT', request, agentHeaders())));
  ipcMain.handle('jira:connection:refresh', (_event, id: string) => backendJson(`${backendBase()}/api/jira/connections/${encodeURIComponent(id)}/refresh`, { method: 'POST', headers: agentHeaders() }));
  ipcMain.handle('jira:disconnect', (_event, id: string) => backendVoid(`${backendBase()}/api/jira/connections/${encodeURIComponent(id)}`, { method: 'DELETE', headers: agentHeaders() }));
  ipcMain.handle('jira:boards', (_event, connectionId: string, cloudId: string, refresh: boolean) =>
    backendJson(`${jiraSitePath(connectionId, cloudId)}/boards${refresh ? '/refresh' : ''}`, { method: refresh ? 'POST' : 'GET', headers: agentHeaders() }));
  ipcMain.handle('jira:sprints', (_event, connectionId: string, cloudId: string, boardId: number, refresh: boolean) =>
    backendJson(`${jiraSitePath(connectionId, cloudId)}/boards/${encodeURIComponent(String(boardId))}/sprints${refresh ? '/refresh' : ''}`, { method: refresh ? 'POST' : 'GET', headers: agentHeaders() }));
  ipcMain.handle('jira:issues', (_event, query: JiraQuery, refresh: boolean) => {
    const params = new URLSearchParams({ boardId: String(query.boardId), mine: String(query.mine) });
    if (query.sprintId !== null) params.set('sprintId', String(query.sprintId));
    if (query.completed) params.set('completed', 'true');
    return backendJson(`${jiraSitePath(query.connectionId, query.cloudId)}/issues${refresh ? '/refresh' : ''}?${params}`, { method: refresh ? 'POST' : 'GET', headers: agentHeaders() });
  });
  ipcMain.handle('jira:issue', (_event, location: JiraLocation, refresh: boolean, automatic: boolean) =>
    backendJson(`${jiraIssuePath(location)}${refresh ? '/refresh' : ''}${refresh && automatic ? '?automatic=true' : ''}`, { method: refresh ? 'POST' : 'GET', headers: agentHeaders() }));
  ipcMain.handle('jira:key:refresh', (_event, connectionId: string, cloudId: string, key: string) =>
    backendJson(`${jiraSitePath(connectionId, cloudId)}/keys/${encodeURIComponent(key)}/refresh`, { method: 'POST', headers: agentHeaders() }));
  ipcMain.handle('jira:issue:recent', (_event, location: JiraLocation, dwellSeconds: number) =>
    backendJson(`${jiraIssuePath(location)}/recent`, jsonInit('POST', { dwellSeconds }, agentHeaders())));
  ipcMain.handle('jira:issue:update', (_event, location: JiraLocation, fields: Record<string, unknown>, expectedUpdatedAt: string) =>
    backendJson(jiraIssuePath(location), jsonInit('PATCH', { fields, expectedUpdatedAt }, agentHeaders())));
  ipcMain.handle('jira:issue:transition', (_event, location: JiraLocation, transitionId: string, fields: Record<string, unknown>, expectedUpdatedAt: string) =>
    backendJson(`${jiraIssuePath(location)}/transitions`, jsonInit('POST', { transitionId, fields, expectedUpdatedAt }, agentHeaders())));
  ipcMain.handle('jira:issue:sprint', (_event, location: JiraLocation, sprintId: number | null, boardId: number, expectedUpdatedAt: string) =>
    backendJson(`${jiraIssuePath(location)}/sprint`, jsonInit('POST', { sprintId, boardId, expectedUpdatedAt }, agentHeaders())));
  ipcMain.handle('jira:issue:comment', (_event, location: JiraLocation, body: string, operationId: string, parentId?: string) =>
    backendJson(`${jiraIssuePath(location)}/comments`, jsonInit('POST', { body, operationId, parentId: parentId ?? null }, agentHeaders())));
  ipcMain.handle('jira:comment:edit', (_event, location: JiraLocation, commentId: string, body: string, expectedUpdated: string) =>
    backendJson(`${jiraIssuePath(location)}/comments/${encodeURIComponent(commentId)}`, jsonInit('PUT', { body, expectedUpdated }, agentHeaders())));
  ipcMain.handle('jira:issue:goal', (_event, location: JiraLocation, isGoal: boolean) =>
    backendJson(`${jiraIssuePath(location)}/goal`, jsonInit('PUT', { isGoal }, agentHeaders())));
  ipcMain.handle('jira:issue:workspace', (_event, location: JiraLocation, workspaceId: string) =>
    backendJson(`${jiraIssuePath(location)}/workspace`, jsonInit('PUT', { workspaceId }, agentHeaders())));
  ipcMain.handle('jira:attention', () => backendJson(`${backendBase()}/api/jira/attention`, { headers: agentHeaders() }));
  ipcMain.handle('jira:issue:child-types', (_event, location: JiraLocation) =>
    backendJson(`${jiraIssuePath(location)}/child-types`, { method: 'POST', headers: agentHeaders() }));
  ipcMain.handle('jira:issue:child', (_event, location: JiraLocation, issueTypeId: string, summary: string) =>
    backendJson(`${jiraIssuePath(location)}/children`, jsonInit('POST', { issueTypeId, summary }, agentHeaders())));
  ipcMain.handle('jira:link-types', (_event, connectionId: string, cloudId: string) =>
    backendJson(`${jiraSitePath(connectionId, cloudId)}/link-types`, { method: 'POST', headers: agentHeaders() }));
  ipcMain.handle('jira:issue:link', (_event, location: JiraLocation, typeId: string, outward: boolean, key: string) =>
    backendJson(`${jiraIssuePath(location)}/links`, jsonInit('POST', { typeId, outward, key }, agentHeaders())));
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
  ipcMain.handle('workspaces:issues:attention', (_event, workspaceId: string) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/attention`));
  ipcMain.handle('workspaces:issues:goal', (_event, workspaceId: string, issueNumber: number, isGoal: boolean) =>
    backendJson(`${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/issues/${encodeURIComponent(issueNumber)}/goal`,
      jsonInit('PATCH', { isGoal })));
  const goalsUrl = (workspaceId: string, path = '') => `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/goals${path}`;
  const workItemsUrl = (workspaceId: string, suffix = '') => `${backendBase()}/api/workspaces/${encodeURIComponent(workspaceId)}/work-items${suffix}`;
  ipcMain.handle('work-items:list', (_event, workspaceId: string) =>
    backendJson(workItemsUrl(workspaceId)).then(items => parseList(items, parseWorkItem, 'work items')));
  ipcMain.handle('work-items:create', (_event, workspaceId: string, request: CreateWorkItem) =>
    backendJson(workItemsUrl(workspaceId), jsonInit('POST', request)).then(parseWorkItem));
  ipcMain.handle('work-items:decide', (_event, workspaceId: string, itemId: string, command: WorkItemDecision) =>
    backendJson(workItemsUrl(workspaceId, `/${encodeURIComponent(itemId)}/decisions`), jsonInit('POST', command)).then(parseWorkItem));
  ipcMain.handle('work-items:link', (_event, workspaceId: string, itemId: string, expectedRevision: number, kind: GoalItemKind, key: string) =>
    backendJson(workItemsUrl(workspaceId, `/${encodeURIComponent(itemId)}/links`), jsonInit('POST', { expectedRevision, kind, key })).then(parseWorkItem));
  ipcMain.handle('goals:list', (_event, workspaceId: string) =>
    backendJson(goalsUrl(workspaceId)).then(goals => parseList(goals, parseGoal, 'goals')));
  ipcMain.handle('goals:create', (_event, workspaceId: string, title: string, description: string) =>
    backendJson(goalsUrl(workspaceId), jsonInit('POST', { title, description })).then(parseGoal));
  ipcMain.handle('goals:update', (_event, workspaceId: string, goalId: string, patch: GoalPatch) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}`), jsonInit('PATCH', patch)).then(parseGoal));
  ipcMain.handle('goals:delete', (_event, workspaceId: string, goalId: string) =>
    backendVoid(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}`), { method: 'DELETE' }));
  ipcMain.handle('goals:items:add', (_event, workspaceId: string, goalId: string, kind: GoalItemKind, key: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/items`), jsonInit('POST', { kind, key })).then(parseGoal));
  ipcMain.handle('goals:items:remove', (_event, workspaceId: string, goalId: string, kind: GoalItemKind, key: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/items?${new URLSearchParams({ kind, key })}`), { method: 'DELETE' }).then(parseGoal));
  ipcMain.handle('goals:candidates', (_event, workspaceId: string, query: string) =>
    backendJson(goalsUrl(workspaceId, `/candidates?${new URLSearchParams({ q: query })}`)).then(items => parseList(items, parseGoalItem, 'goal candidates')));
  const exchanges = (value: unknown) => parseList(value, parseGoalExchange, 'goal exchanges');
  ipcMain.handle('goals:start', (_event, workspaceId: string, operationId: string, message: string) =>
    backendJson(goalsUrl(workspaceId, '/conversation'), jsonInit('POST', { operationId, message })).then(parseGoal));
  ipcMain.handle('goals:exchanges', (_event, workspaceId: string, goalId: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/exchanges`)).then(exchanges));
  ipcMain.handle('goals:ask', (_event, workspaceId: string, goalId: string, operationId: string, message: string, lessonId: string | null) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/exchanges`), jsonInit('POST', { operationId, message, lessonId })).then(exchanges));
  ipcMain.handle('goals:retry', (_event, workspaceId: string, goalId: string, exchangeId: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/exchanges/${encodeURIComponent(exchangeId)}/retry`), { method: 'POST' }).then(exchanges));
  ipcMain.handle('goals:summarize', (_event, workspaceId: string, goalId: string, workItemId: string) =>
    backendVoid(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/work/${encodeURIComponent(workItemId)}/summary`), { method: 'POST' }));
  ipcMain.handle('goals:reassess', (_event, workspaceId: string, goalId: string, operationId: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/reassess`), jsonInit('POST', { operationId })).then(exchanges));
  ipcMain.handle('goals:stop', (_event, workspaceId: string, goalId: string, exchangeId: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/exchanges/${encodeURIComponent(exchangeId)}/stop`), { method: 'POST' }).then(exchanges));
  const goalModelUrl = (workspaceId: string, goalId: string) => goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/model`);
  ipcMain.handle('goals:model', (_event, workspaceId: string, goalId: string) => backendJson(goalModelUrl(workspaceId, goalId)));
  ipcMain.handle('goals:model:set', (_event, workspaceId: string, goalId: string, configId: string, model: string, effort: string | null) =>
    backendJson(goalModelUrl(workspaceId, goalId), jsonInit('PUT', { configId, model, effort })));
  ipcMain.handle('goals:model:clear', (_event, workspaceId: string, goalId: string) =>
    backendJson(goalModelUrl(workspaceId, goalId), { method: 'DELETE' }));
  ipcMain.handle('goals:accept', (_event, workspaceId: string, goalId: string, exchangeId: string, title: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/definition`), jsonInit('POST', { exchangeId, title })).then(parseGoal));
  ipcMain.handle('goals:course', (_event, workspaceId: string, goalId: string, exchangeId: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/course`), jsonInit('POST', { exchangeId })).then(parseGoal));
  const recommendationUrl = (workspaceId: string, goalId: string, recommendationId: string, action: string) =>
    goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/recommendations/${encodeURIComponent(recommendationId)}/${action}`);
  ipcMain.handle('goals:recommendations:start', (_event, workspaceId: string, goalId: string, recommendationId: string) =>
    backendJson(recommendationUrl(workspaceId, goalId, recommendationId, 'start'), { method: 'POST' }).then(parseGoalRecommendation));
  ipcMain.handle('goals:recommendations:dismiss', (_event, workspaceId: string, goalId: string, recommendationId: string) =>
    backendJson(recommendationUrl(workspaceId, goalId, recommendationId, 'dismiss'), { method: 'POST' }).then(parseGoalRecommendation));
  const lessonUrl = (workspaceId: string, goalId: string, lessonId: string, suffix = '') =>
    goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/lessons/${encodeURIComponent(lessonId)}${suffix}`);
  ipcMain.handle('goals:lessons:open', (_event, workspaceId: string, goalId: string, lessonId: string) =>
    backendJson(lessonUrl(workspaceId, goalId, lessonId), { method: 'POST' }).then(parseGoalLesson));
  ipcMain.handle('goals:lessons:read', (_event, workspaceId: string, goalId: string, lessonId: string) =>
    backendJson(lessonUrl(workspaceId, goalId, lessonId)).then(parseGoalLesson));
  ipcMain.handle('goals:lessons:done', (_event, workspaceId: string, goalId: string, lessonId: string, operationId: string) =>
    backendJson(lessonUrl(workspaceId, goalId, lessonId, '/done'), jsonInit('POST', { operationId })).then(parseGoal));
  ipcMain.handle('goals:topics', (_event, workspaceId: string, goalId: string, topic: string, level: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/topics`), jsonInit('PUT', { topic, level })).then(parseGoal));
  ipcMain.handle('goals:gaps', (_event, workspaceId: string, goalId: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/gaps`)).then(gaps => parseList(gaps, parseGoalGap, 'goal gaps')));
  ipcMain.handle('goals:gaps:add', (_event, workspaceId: string, goalId: string, candidateId: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/gaps/${encodeURIComponent(candidateId)}`), jsonInit('POST', {}))
      .then(gaps => parseList(gaps, parseGoalGap, 'goal gaps')));
  ipcMain.handle('goals:knowledge', (_event, workspaceId: string, goalId: string) =>
    backendJson(goalsUrl(workspaceId, `/${encodeURIComponent(goalId)}/knowledge`)).then(notes => parseList(notes, parseGoalKnowledgeNote, 'goal notes')));
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
  const teamUrl = (id: unknown) => `${backendBase()}/api/teams/${encodeURIComponent(teamId(id))}`;
  ipcMain.handle('teams:list', () => backendJson(`${backendBase()}/api/teams`, { headers: agentHeaders() }).then(parseTeams));
  ipcMain.handle('teams:contributors', () => backendJson(`${backendBase()}/api/teams/contributors`, { headers: agentHeaders() }).then(parseTeamContributors));
  ipcMain.handle('teams:create', (_event, input: unknown) =>
    backendJson(`${backendBase()}/api/teams`, jsonInit('POST', parseTeamCreateInput(input), agentHeaders())).then(parseTeams));
  ipcMain.handle('teams:update', (_event, id: unknown, input: unknown) =>
    backendJson(teamUrl(id), jsonInit('PUT', parseTeamInput(input), agentHeaders())).then(parseTeams));
  ipcMain.handle('teams:remove', (_event, id: unknown) =>
    backendJson(teamUrl(id), { method: 'DELETE', headers: agentHeaders() }).then(parseTeams));
  ipcMain.handle('teams:members:add', (_event, id: unknown, member: unknown) =>
    backendJson(`${teamUrl(id)}/members`, jsonInit('POST', parseTeamMemberInput(member), agentHeaders())).then(parseTeams));
  ipcMain.handle('teams:members:remove', (_event, id: unknown, memberId: unknown) =>
    backendJson(`${teamUrl(id)}/members/${teamMemberId(memberId)}`, { method: 'DELETE', headers: agentHeaders() }).then(parseTeams));
  for (const action of ['activity', 'refresh'] as const) {
    ipcMain.handle(`teams:${action}`, (_event, input: unknown) => {
      const url = new URL(`${backendBase()}/api/teams/${action}`);
      for (const [key, value] of Object.entries(parseTeamQuery(input))) url.searchParams.set(key, String(value));
      return backendJson(url, {
        method: action === 'refresh' ? 'POST' : 'GET', headers: agentHeaders(),
        ...(action === 'refresh' ? { signal: AbortSignal.timeout(15 * 60_000) } : {}),
      }).then(parseTeamActivity);
    });
  }
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
  ipcMain.handle('github:cli-import', async () => {
    const result = await backendJson(`${backendBase()}/api/auth/github/cli/import`, { method: 'POST' });
    githubAccountChanged();
    return result;
  });

  ipcMain.handle('external:open', async (_event, url: string) => {
    if (/^https?:/i.test(url)) await shell.openExternal(url);
  });
  registerBrowserTabIpc(browserTabs, () => mainWindow);
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
  prNotificationTimer = setInterval(() => { void pollPrNotifications(); }, 5_000);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
app.on('before-quit', () => {
  clearInterval(prNotificationTimer);
  // Streams read from the backend, so they stop before it does.
  agentSubscriptions.stopAll();
  codeTerminals.stopAll();
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
