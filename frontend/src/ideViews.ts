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
import { ipcMain, net, protocol, WebContentsView, BrowserWindow, type IpcMainInvokeEvent, type WebContents } from 'electron';
import { randomUUID } from 'node:crypto';
import { realpath } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { browserBounds } from './browserTabs';
import { backendBase, sessionToken } from './backendProcess';
import { parseCodeHost, codeHostKey, parseReviewCodeFiles, parseReviewCodeFile, parseLocalRuns, parseLocalRunOutput, parseWorktreeChanges, type CodeHostRef } from './pulls/code-tests/contracts';
import { parseCodeQuery, parseCodeQueryReply, queryFailure, type CodeQueryRequest, type CodeQueryReply } from './ide/codeQuery';
import { parseExecutionRequest } from './ide/executionProtocol';
import { parseLocalRun } from './pulls/code-tests/contracts';
import { validateIdeAction } from './ide/actionPolicy';
import type { IdeConnection, IdeContext, IdeEvent, IdeReply, IdeRequest } from './ide/contracts';

declare const IDE_WINDOW_VITE_DEV_SERVER_URL: string | undefined;

protocol.registerSchemesAsPrivileged([{ scheme: 'bytequay-ide', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }]);

type Editor = {
  id: string;
  host: CodeHostRef;
  connection: IdeConnection;
  view: WebContentsView;
  contents: WebContents;
  context: IdeContext | null;
  visible: boolean;
  dirty: number;
  busy: boolean;
  lastUsed: number;
  queryReady: boolean;
  execution: string | null;
  executionStarting: boolean;
  queries: Map<string, { request: CodeQueryRequest; finish: (reply: CodeQueryReply) => void }>;
  closing: Promise<void> | null;
  pending: Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>;
};

async function backendRequest(route: string, method: string, body?: unknown, signal?: AbortSignal): Promise<unknown> {
  const response = await fetch(`${backendBase()}${route}`, {
    method, body: body === undefined ? undefined : JSON.stringify(body), headers: { 'X-ByteQuay-Session': sessionToken(), 'Content-Type': 'application/json' }, signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(65_000)]) : AbortSignal.timeout(65_000),
  });
  if (!response.ok) {
    const body: unknown = await response.json().catch((): unknown => null);
    const message = body && typeof body === 'object' && 'message' in body && typeof body.message === 'string' ? body.message : `IDE request failed (${response.status})`;
    throw new Error(body && typeof body === 'object' && 'code' in body && body.code === 'IDE_FILE_CHANGED' ? `IDE_FILE_CHANGED: ${message}` : message);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : undefined;
}

function runtimeRequest(route: string, method: string, body?: unknown, signal?: AbortSignal): Promise<unknown> {
  return backendRequest(`/agent/ide${route}`, method, body, signal);
}

function connectionFrom(value: unknown): IdeConnection {
  if (!value || typeof value !== 'object') throw new Error('Invalid IDE connection');
  const data = value as Record<string, unknown>;
  for (const key of ['taskId', 'sessionId', 'root', 'bundleRoot', 'cacheRoot', 'connectionToken', 'authority', 'commit']) {
    if (typeof data[key] !== 'string' || !data[key]) throw new Error('Incomplete IDE connection');
  }
  if (!/^127\.0\.0\.1:\d{1,5}$/.test(data.authority as string) || !/^[a-f0-9]{40}$/.test(data.commit as string)) throw new Error('Invalid IDE server identity');
  return data as IdeConnection;
}

function isGitHubAvatar(url: URL): boolean {
  return url.protocol === 'https:' && !url.port && !url.username && !url.password
    && (url.hostname === 'avatars.githubusercontent.com'
      || (url.hostname === 'github.com' && /^\/[a-z\d-]+\.png$/i.test(url.pathname)));
}

/** Native view ownership, resource access and idle cleanup live in main, outside repository-controlled content. */
export class IdeViews {
  private readonly editors = new Map<string, Editor>();
  private readonly queryPump = new AbortController();
  private readonly runningQueries = new Map<string, AbortController>();
  private pumping = false;
  private background: BrowserWindow | null = null;
  private readonly opening = new Map<string, Promise<string>>();
  private readonly timer: ReturnType<typeof setInterval>;

  constructor(private readonly window: () => BrowserWindow | null, private readonly terminalOpen: (sender: WebContents) => boolean = () => false) {
    this.timer = setInterval(() => { void this.maintain(); }, 15_000);
    this.timer.unref();
  }

  terminalHost(event: IpcMainInvokeEvent): CodeHostRef {
    const editor = [...this.editors.values()].find(item => item.contents === event.sender);
    if (!editor?.context || event.senderFrame !== event.sender.mainFrame) throw new Error('Unknown IDE terminal owner');
    return editor.host;
  }

  register(): void {
    const parent = (event: IpcMainInvokeEvent) => {
      const window = this.window();
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Unknown IDE owner');
    };
    const child = (event: IpcMainInvokeEvent): Editor => {
      const editor = [...this.editors.values()].find(item => item.contents === event.sender);
      if (!editor || event.senderFrame !== event.sender.mainFrame) throw new Error('Unknown IDE view');
      return editor;
    };
    ipcMain.on('ide:query-ready', event => {
      const editor = child(event);
      editor.queryReady = true;
      for (const { request } of editor.queries.values()) editor.contents.send('ide:query', request);
    });
    ipcMain.on('ide:query-reply', (event, id: unknown, value: unknown) => {
      const editor = child(event);
      if (typeof id !== 'string') return;
      const query = editor.queries.get(id);
      if (!query) return;
      try { query.finish(parseCodeQueryReply(value)); }
      catch { query.finish(queryFailure('UNAVAILABLE', 'The language service returned an invalid result')); }
    });
    ipcMain.handle('ide:open', (event, host: unknown) => { parent(event); return this.open(parseCodeHost(host)); });
    ipcMain.handle('ide:present', (event, id: string, bounds: unknown) => {
      parent(event);
      const retained = this.editors.get(id);
      if (bounds === null && (!retained || retained.closing || retained.contents.isDestroyed())) return;
      const editor = this.require(id);
      const window = this.window();
      if (!window) return;
      if (bounds !== null || editor.visible) editor.lastUsed = Date.now();
      editor.visible = bounds !== null;
      if (bounds === null) {
        editor.view.setVisible(false);
        return;
      }
      editor.view.setBounds(browserBounds(bounds, window));
      window.contentView.addChildView(editor.view);
      editor.view.setVisible(true);
    });
    ipcMain.handle('ide:update', (event, id: string, context: IdeContext) => {
      parent(event);
      const editor = this.require(id);
      if (codeHostKey(parseCodeHost(context.host)) !== codeHostKey(editor.host)) throw new Error('IDE context belongs to another task');
      editor.context = context;
      editor.contents.send('ide:context', context);
    });
    ipcMain.handle('ide:close', async (event, id: string) => { parent(event); await this.close(id); });
    ipcMain.handle('ide:bootstrap', event => {
      const editor = child(event);
      return { connection: editor.connection, context: editor.context };
    });
    ipcMain.handle('ide:save', async (event, file: unknown, content: unknown, expectedHash: unknown) => {
      const editor = child(event);
      if (typeof file !== 'string' || !file || file.length > 4096 || file.includes('\0') || file.startsWith('/') || file.split('/').some(part => part === '.' || part === '..')
        || typeof content !== 'string' || content.length > 2_800_000 || Buffer.from(content, 'base64').toString('base64') !== content
        || typeof expectedHash !== 'string' || (expectedHash !== '-' && !/^[a-f0-9]{64}$/.test(expectedHash))) throw new Error('Invalid editor save');
      const host = editor.host;
      const route = host.kind === 'review' ? `/reviews/${encodeURIComponent(host.reviewId)}` : `/tasks/${encodeURIComponent(host.workspaceId)}/${encodeURIComponent(host.taskId)}`;
      await runtimeRequest(`${route}/file`, 'PUT', { path: file, content, expectedHash });
    });
    ipcMain.handle('ide:execution', async (event, value: unknown) => {
      const editor = child(event);
      if (!editor.context) throw new Error('Open Code & Tests before running code');
      const request = parseExecutionRequest(value);
      const connection = editor.connection;
      if (request.operation === 'start') {
        if (editor.execution || editor.executionStarting) throw new Error('An execution is already using this working copy');
        editor.executionStarting = true;
        try {
          const host = editor.host;
          const route = host.kind === 'review' ? `/reviews/${encodeURIComponent(host.reviewId)}` : `/tasks/${encodeURIComponent(host.workspaceId)}/${encodeURIComponent(host.taskId)}`;
          const run = parseLocalRun(await runtimeRequest(`${route}/executions`, 'POST', { ...request.request, sessionId: connection.sessionId }));
          editor.execution = run.id;
          return run;
        } finally { editor.executionStarting = false; }
      }
      if (request.id !== editor.execution) throw new Error('The execution does not belong to this editor');
      const route = `/${connection.taskId}/${connection.sessionId}/executions/${request.id}`;
      if (request.operation === 'output') return runtimeRequest(`${route}/output`, 'POST', request.request);
      const run = parseLocalRun(await runtimeRequest(request.operation === 'finish' ? `${route}/finish` : route,
        request.operation === 'finish' ? 'POST' : 'DELETE', request.operation === 'finish' ? request.request : undefined));
      if (!run.treeUnverified && run.status !== 'RUNNING') editor.execution = null;
      return run;
    });
    ipcMain.handle('ide:request', (event, method: unknown, args: unknown, userGesture: boolean) => {
      const editor = child(event);
      validateIdeAction(method, args, editor.context, userGesture === true);
      // A retained editor can refresh while its parent React surface is unmounted.
      // These reads must not wait for that surface to relay a reply.
      if (['listCodeFiles', 'readCodeFile', 'listCodeRuns', 'readCodeRunOutput', 'readWorktreeChanges'].includes(String(method))) {
        const host = editor.host;
        const route = host.kind === 'review' ? `/api/agent-review/${encodeURIComponent(host.reviewId)}/code` : `/agent/tasks/${encodeURIComponent(host.taskId)}/code`;
        const params = new URLSearchParams(host.kind === 'task' ? { workspaceId: host.workspaceId } : {});
        let endpoint: string;
        let parse: (value: unknown) => unknown;
        switch (method) {
          case 'listCodeFiles': endpoint = '/files'; parse = parseReviewCodeFiles; break;
          case 'readCodeFile':
            endpoint = '/file'; parse = parseReviewCodeFile;
            params.set('path', String(args[1]));
            if (args[2] !== undefined) params.set('expectedRevision', String(args[2]));
            break;
          case 'listCodeRuns': endpoint = '/runs'; parse = parseLocalRuns; break;
          case 'readCodeRunOutput':
            endpoint = `/runs/${encodeURIComponent(String(args[1]))}/output`; parse = parseLocalRunOutput;
            params.set('after', String(args[2]));
            break;
          default: {
            endpoint = '/changes'; parse = parseWorktreeChanges;
            const query = args[1] as Record<string, unknown>;
            for (const key of ['scope', 'commit', 'base', 'head']) if (query[key] != null) params.set(key, String(query[key]));
          }
        }
        return backendRequest(`${route}${endpoint}${params.size ? `?${params}` : ''}`, 'GET').then(parse);
      }
      if (method === 'readCodeBlame') {
        const host = editor.host;
        const route = host.kind === 'review' ? `/reviews/${encodeURIComponent(host.reviewId)}` : `/tasks/${encodeURIComponent(host.workspaceId)}/${encodeURIComponent(host.taskId)}`;
        return runtimeRequest(`${route}/blame?path=${encodeURIComponent(String(args[1]))}`, 'GET');
      }
      if (method === 'readCodeHistory' || method === 'readHistoryCommit' || method === 'readHistorySnapshot') {
        const host = editor.host;
        const route = host.kind === 'review' ? `/reviews/${encodeURIComponent(host.reviewId)}` : `/tasks/${encodeURIComponent(host.workspaceId)}/${encodeURIComponent(host.taskId)}`;
        const query = args[1] as Record<string, unknown>;
        const params = new URLSearchParams();
        const endpoint = method === 'readCodeHistory' ? 'history' : method === 'readHistoryCommit' ? 'commit' : 'snapshot';
        for (const key of endpoint === 'history' ? ['revision', 'path', 'offset'] : endpoint === 'commit' ? ['revision', 'parent'] : ['revision', 'path']) {
          if (query[key] !== undefined) params.set(key, String(query[key]));
        }
        return runtimeRequest(`${route}/${endpoint}?${params}`, 'GET');
      }
      if (editor.pending.size >= 64) throw new Error('Too many pending IDE actions');
      const requestId = randomUUID();
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          editor.pending.delete(requestId);
          reject(new Error('The ByteQuay action timed out; check its result before retrying'));
        }, 120_000);
        editor.pending.set(requestId, { resolve, reject, timer });
        this.window()?.webContents.send('ide:request', { id: editor.id, requestId, method: String(method), args } satisfies IdeRequest);
      });
    });
    ipcMain.on('ide:reply', (event, reply: IdeReply) => {
      parent(event);
      for (const editor of this.editors.values()) {
        const pending = editor.pending.get(reply.requestId);
        if (!pending) continue;
        editor.pending.delete(reply.requestId);
        clearTimeout(pending.timer);
        if (reply.error !== undefined) pending.reject(new Error(reply.error));
        else pending.resolve(reply.value);
        return;
      }
    });
    ipcMain.on('ide:report', (event, report: Pick<IdeEvent, 'dirty' | 'busy' | 'error'>) => {
      const editor = child(event);
      if (!Number.isSafeInteger(report.dirty) || report.dirty < 0 || typeof report.busy !== 'boolean') return;
      editor.dirty = report.dirty;
      editor.busy = report.busy;
      const error = typeof report.error === 'string' ? report.error.slice(0, 10_000) : undefined;
      this.window()?.webContents.send('ide:event', { id: editor.id, dirty: editor.dirty, busy: editor.busy, error } satisfies IdeEvent);
    });
  }

  open(host: CodeHostRef): Promise<string> {
    const key = codeHostKey(host);
    const existing = [...this.editors.values()].find(item => codeHostKey(item.host) === key);
    if (existing && !existing.closing && !existing.contents.isDestroyed()) return Promise.resolve(existing.id);
    if (existing) return this.close(existing.id).then(() => this.open(host));
    const pending = this.opening.get(key);
    if (pending) return pending;
    const opening = this.create(host).finally(() => this.opening.delete(key));
    this.opening.set(key, opening);
    return opening;
  }

  private async create(host: CodeHostRef, suppliedConnection?: IdeConnection): Promise<string> {
    const route = host.kind === 'review' ? `/reviews/${encodeURIComponent(host.reviewId)}`
      : `/tasks/${encodeURIComponent(host.workspaceId)}/${encodeURIComponent(host.taskId)}`;
    const connection = suppliedConnection ?? connectionFrom(await runtimeRequest(route, 'POST'));
    const existing = [...this.editors.values()].find(item => item.connection.taskId === connection.taskId);
    if (existing) {
      if (existing.closing || existing.connection.sessionId !== connection.sessionId || existing.contents.isDestroyed()) {
        await this.close(existing.id);
        return this.create(host, connection);
      }
      if (codeHostKey(existing.host) !== codeHostKey(host) && existing.context) throw new Error('This working copy is already open in another review; close that editor first');
      existing.host = host;
      return existing.id;
    }
    const id = randomUUID();
    const view = new WebContentsView({ webPreferences: {
      preload: path.join(__dirname, 'idePreload.js'), sandbox: true, contextIsolation: true, nodeIntegration: false, backgroundThrottling: false, partition: `persist:bytequay-ide-${connection.taskId}`,
    } });
    // Electron clears view.webContents on destruction; retain the handle for cleanup and isDestroyed().
    const contents = view.webContents;
    const editor: Editor = { id, host, connection, view, contents, context: null, visible: false, dirty: 0, busy: false, lastUsed: Date.now(), queryReady: false, execution: null, executionStarting: false, queries: new Map(), closing: null, pending: new Map() };
    this.editors.set(id, editor);
    try {
      const resources = await Promise.all(['extensions', 'server'].map(name => realpath(path.join(connection.bundleRoot, name))));
      const dist = path.resolve(__dirname, '../renderer/ide_window');
      const serve = async (request: Request): Promise<Response> => {
        try {
          const url = new URL(request.url);
          // Shared PR cards load public avatars without the desktop's GitHub session or credentials.
          if (request.method === 'GET' && isGitHubAvatar(url)) {
            return await contents.session.fetch(url.href, { bypassCustomProtocolHandlers: true, credentials: 'omit' });
          }
          const isApp = url.protocol === 'bytequay-ide:' && url.hostname === 'app';
          if (!isApp && url.hostname !== 'ide-assets.bytequay.invalid') return new Response('Forbidden', { status: 403 });
          const target = await realpath(isApp ? path.join(dist, decodeURIComponent(url.pathname)) : decodeURIComponent(url.pathname));
          const roots = isApp ? [await realpath(dist)] : resources;
          if (!roots.some(root => target.startsWith(root + path.sep))) return new Response('Forbidden', { status: 403 });
          return net.fetch(pathToFileURL(target).href);
        }
        catch { return new Response('Not found', { status: 404 }); }
      };
      const session = contents.session;
      for (const scheme of ['bytequay-ide', 'https']) {
        if (await session.protocol.isProtocolHandled(scheme)) session.protocol.unhandle(scheme);
      }
      session.protocol.handle('bytequay-ide', serve);
      session.protocol.handle('https', serve);
      session.setPermissionRequestHandler((requester, permission, callback) => callback(requester === contents && permission === 'clipboard-sanitized-write'));
      session.setPermissionCheckHandler((requester, permission) => requester === contents && permission === 'clipboard-sanitized-write');
      const dev = typeof IDE_WINDOW_VITE_DEV_SERVER_URL === 'string' ? new URL(IDE_WINDOW_VITE_DEV_SERVER_URL) : null;
      session.webRequest.onBeforeRequest((details, callback) => {
        const url = new URL(details.url);
        const local = ['http:', 'ws:'].includes(url.protocol) && (url.host === connection.authority || url.host === dev?.host);
        const resource = url.protocol === 'https:' && url.hostname === 'ide-assets.bytequay.invalid';
        callback({ cancel: !local && !resource && !isGitHubAvatar(url) && !['bytequay-ide:', 'data:', 'blob:'].includes(url.protocol) });
      });
      contents.setWindowOpenHandler(() => ({ action: 'deny' }));
      contents.on('will-navigate', event => event.preventDefault());
      contents.on('will-attach-webview', event => event.preventDefault());
      contents.on('render-process-gone', () => {
        this.window()?.webContents.send('ide:event', { id, dirty: editor.dirty, busy: false, error: 'The editor stopped. Reopen Code & Tests to reconnect.' } satisfies IdeEvent);
        void this.close(id).catch(() => {});
      });
      view.setVisible(false);
      // Unattached native views can stall initialization before agent queries become ready.
      view.setBounds({ x: 0, y: 0, width: 1000, height: 700 });
      this.owner().contentView.addChildView(view);
      await contents.loadURL(dev ? new URL('ide/index.html', dev).href : 'bytequay-ide://app/ide/index.html');
      return id;
    }
    catch (error) {
      await this.close(id).catch(() => {});
      throw error;
    }
  }

  /** Keeps only a query lease. Opening a code view later reuses this exact hidden runtime. */
  async query(request: CodeQueryRequest, signal: AbortSignal): Promise<{ result: CodeQueryReply; connection: IdeConnection }> {
    const query = parseCodeQuery(request.query);
    const connection = connectionFrom(request.connection);
    if (connection.taskId !== request.taskId) throw new Error('Code query belongs to another runtime');
    const existing = [...this.editors.values()].find(editor => editor.connection.taskId === request.taskId && editor.connection.sessionId === connection.sessionId && !editor.closing && !editor.contents.isDestroyed());
    const id = existing?.id ?? await this.create({ kind: 'task', taskId: request.taskId, workspaceId: request.workspaceId }, connection);
    const editor = this.require(id);
    if (signal.aborted) return { result: queryFailure('CANCELLED', 'Code query cancelled'), connection: editor.connection };
    if (editor.queries.size >= 4) return { result: queryFailure('UNAVAILABLE', 'Too many active queries for this working copy'), connection: editor.connection };
    editor.lastUsed = Date.now();
    const result = await new Promise<CodeQueryReply>(resolve => {
      const cancel = () => {
        if (!editor.contents.isDestroyed()) editor.contents.send('ide:query', { ...request, cancel: true });
        finish(queryFailure('CANCELLED', 'Code query cancelled'));
      };
      const timeout = setTimeout(cancel, Math.max(0, request.expiresAt - Date.now()));
      const finish = (reply: CodeQueryReply) => {
        clearTimeout(timeout); signal.removeEventListener('abort', cancel); editor.queries.delete(request.id);
        editor.lastUsed = Date.now(); resolve(reply);
      };
      signal.addEventListener('abort', cancel, { once: true });
      const bounded = { ...request, query };
      editor.queries.set(request.id, { request: bounded, finish });
      if (editor.queryReady) editor.contents.send('ide:query', bounded);
    });
    return { result, connection: editor.connection };
  }

  /** Main alone polls the authenticated queue. Repository content never receives its credential or another task's request. */
  startQueries(): void {
    if (this.pumping) return;
    this.pumping = true;
    void this.pumpQueries();
  }

  private async pumpQueries(): Promise<void> {
    let version = -1;
    while (!this.queryPump.signal.aborted) {
      try {
        const snapshot = await runtimeRequest(`/queries?after=${version}`, 'GET', undefined, this.queryPump.signal) as { version: number; requests: CodeQueryRequest[] };
        if (!Number.isSafeInteger(snapshot.version) || !Array.isArray(snapshot.requests) || snapshot.requests.length > 32) throw new Error('Invalid IDE request queue');
        version = snapshot.version;
        const active = new Set(snapshot.requests.map(request => request.id));
        for (const [id, controller] of this.runningQueries) {
          if (!active.has(id)) { controller.abort(); this.runningQueries.delete(id); }
        }
        for (const request of snapshot.requests) {
          if (this.runningQueries.has(request.id) || request.expiresAt <= Date.now()) continue;
          const controller = new AbortController();
          this.runningQueries.set(request.id, controller);
          void this.query(request, controller.signal).then(async ({ result, connection }) => {
            if (controller.signal.aborted) return;
            await runtimeRequest(`/queries/${encodeURIComponent(request.id)}`, 'POST', { taskId: connection.taskId, sessionId: connection.sessionId, result }, controller.signal);
          }).catch(async error => {
            if (controller.signal.aborted) return;
            await runtimeRequest(`/queries/${encodeURIComponent(request.id)}`, 'POST', {
              taskId: request.taskId, result: queryFailure('UNAVAILABLE', String(error).slice(0, 4000)),
            }, controller.signal).catch(() => {});
          });
        }
      } catch {
        if (!this.queryPump.signal.aborted) await new Promise<void>(resolve => {
          const timeout = setTimeout(done, 5_000);
          const signal = this.queryPump.signal;
          signal.addEventListener('abort', done, { once: true });
          function done() { clearTimeout(timeout); signal.removeEventListener('abort', done); resolve(); }
        });
      }
    }
  }

  private owner(): BrowserWindow {
    const window = this.window();
    if (window && !window.isDestroyed()) return window;
    this.background ??= new BrowserWindow({ show: false, width: 1000, height: 700,
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } });
    return this.background;
  }

  private require(id: string): Editor {
    const editor = this.editors.get(id);
    if (!editor) throw new Error('The IDE session has ended');
    if (editor.closing || editor.contents.isDestroyed()) throw new Error('The editor is closing; reopen Code & Tests');
    return editor;
  }

  private async maintain(): Promise<void> {
    await Promise.allSettled([...this.editors.values()].map(async editor => {
      if (editor.contents.isDestroyed() || (!editor.visible && editor.dirty === 0 && !editor.busy && !this.terminalOpen(editor.contents) && editor.pending.size === 0 && editor.queries.size === 0 && editor.execution === null && !editor.executionStarting && Date.now() - editor.lastUsed > 120_000)) {
        await this.close(editor.id);
        return;
      }
      try { await runtimeRequest(`/${editor.connection.taskId}/${editor.connection.sessionId}/touch`, 'POST'); }
      catch (cause) {
        this.window()?.webContents.send('ide:event', { id: editor.id, dirty: editor.dirty, busy: editor.busy, error: `The IDE connection needs recovery: ${String(cause)}` } satisfies IdeEvent);
      }
    }));
  }

  async close(id: string): Promise<void> {
    const editor = this.editors.get(id);
    if (!editor) return;
    if (editor.closing) return editor.closing;
    const closing = async () => {
      editor.visible = false;
      const window = this.window();
      if (window && !window.isDestroyed()) window.contentView.removeChildView(editor.view);
      this.background?.contentView.removeChildView(editor.view);
      if (!editor.contents.isDestroyed()) {
        editor.view.setVisible(false);
        editor.contents.close({ waitForBeforeUnload: false });
      }
      for (const pending of editor.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(new Error('The editor closed'));
      }
      editor.pending.clear();
      for (const query of editor.queries.values()) query.finish(queryFailure('UNAVAILABLE', 'The IDE closed during the query'));
      // Failed termination remains registered so maintenance and explicit close retry the same process tree.
      await runtimeRequest(`/${editor.connection.taskId}/${editor.connection.sessionId}`, 'DELETE');
      this.editors.delete(id);
      if (this.editors.size === 0) { this.background?.destroy(); this.background = null; }
      this.window()?.webContents.send('ide:event', { id, dirty: editor.dirty, busy: false, closed: true } satisfies IdeEvent);
    };
    editor.closing = closing().finally(() => { editor.closing = null; });
    return editor.closing;
  }

  releaseWindow(): void {
    for (const editor of this.editors.values()) {
      editor.visible = false;
      editor.lastUsed = Date.now();
      if (!editor.contents.isDestroyed()) {
        editor.view.setVisible(false);
        this.owner().contentView.addChildView(editor.view);
      }
    }
  }

  dispose(): void {
    clearInterval(this.timer);
    this.queryPump.abort();
    for (const controller of this.runningQueries.values()) controller.abort();
    this.runningQueries.clear();
    for (const editor of this.editors.values()) {
      if (!editor.contents.isDestroyed()) editor.contents.close({ waitForBeforeUnload: false });
    }
    this.background?.destroy(); this.background = null;
    // Spring shutdown performs the verified process-tree stop; renderer destruction alone is not its proof.
  }
}
