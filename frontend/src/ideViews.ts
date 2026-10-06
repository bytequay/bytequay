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
import { ipcMain, net, protocol, WebContentsView, type BrowserWindow, type IpcMainInvokeEvent } from 'electron';
import { randomUUID } from 'node:crypto';
import { realpath } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { browserBounds } from './browserTabs';
import { backendBase, sessionToken } from './backendProcess';
import { parseCodeHost, codeHostKey, type CodeHostRef } from './pulls/code-tests/contracts';
import { validateIdeAction } from './ide/actionPolicy';
import type { IdeConnection, IdeContext, IdeEvent, IdeReply, IdeRequest } from './ide/contracts';

declare const IDE_WINDOW_VITE_DEV_SERVER_URL: string | undefined;

protocol.registerSchemesAsPrivileged([{ scheme: 'bytequay-ide', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }]);

type Editor = {
  id: string;
  host: CodeHostRef;
  connection: IdeConnection;
  view: WebContentsView;
  context: IdeContext | null;
  visible: boolean;
  dirty: number;
  busy: boolean;
  lastUsed: number;
  closing: Promise<void> | null;
  pending: Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>;
};

async function runtimeRequest(route: string, method: string, body?: unknown): Promise<unknown> {
  const response = await fetch(`${backendBase()}/agent/ide${route}`, {
    method, body: body === undefined ? undefined : JSON.stringify(body), headers: { 'X-ByteQuay-Session': sessionToken(), 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(65_000),
  });
  if (!response.ok) {
    const body: unknown = await response.json().catch((): unknown => null);
    const message = body && typeof body === 'object' && 'message' in body && typeof body.message === 'string' ? body.message : `IDE request failed (${response.status})`;
    throw new Error(body && typeof body === 'object' && 'code' in body && body.code === 'IDE_FILE_CHANGED' ? `IDE_FILE_CHANGED: ${message}` : message);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : undefined;
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

/** Native view ownership, resource access and idle cleanup live in main, outside repository-controlled content. */
export class IdeViews {
  private readonly editors = new Map<string, Editor>();
  private readonly opening = new Map<string, Promise<string>>();
  private readonly timer: ReturnType<typeof setInterval>;

  constructor(private readonly window: () => BrowserWindow | null) {
    this.timer = setInterval(() => { void this.maintain(); }, 15_000);
    this.timer.unref();
  }

  register(): void {
    const parent = (event: IpcMainInvokeEvent) => {
      const window = this.window();
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Unknown IDE owner');
    };
    const child = (event: IpcMainInvokeEvent): Editor => {
      const editor = [...this.editors.values()].find(item => item.view.webContents === event.sender);
      if (!editor || event.senderFrame !== event.sender.mainFrame) throw new Error('Unknown IDE view');
      return editor;
    };
    ipcMain.handle('ide:open', (event, host: unknown) => { parent(event); return this.open(parseCodeHost(host)); });
    ipcMain.handle('ide:present', (event, id: string, bounds: unknown) => {
      parent(event);
      const editor = this.require(id);
      const window = this.window();
      if (!window) return;
      if (editor.view.webContents.isDestroyed()) throw new Error('The editor is closing; reopen Code & Tests');
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
      editor.view.webContents.send('ide:context', context);
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
    ipcMain.handle('ide:request', (event, method: unknown, args: unknown, userGesture: boolean) => {
      const editor = child(event);
      validateIdeAction(method, args, editor.context, userGesture === true);
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
    if (existing && !existing.view.webContents.isDestroyed()) return Promise.resolve(existing.id);
    if (existing) return this.close(existing.id).then(() => this.open(host));
    const pending = this.opening.get(key);
    if (pending) return pending;
    const opening = this.create(host).finally(() => this.opening.delete(key));
    this.opening.set(key, opening);
    return opening;
  }

  private async create(host: CodeHostRef): Promise<string> {
    const route = host.kind === 'review' ? `/reviews/${encodeURIComponent(host.reviewId)}`
      : `/tasks/${encodeURIComponent(host.workspaceId)}/${encodeURIComponent(host.taskId)}`;
    const connection = connectionFrom(await runtimeRequest(route, 'POST'));
    const existing = [...this.editors.values()].find(item => item.connection.taskId === connection.taskId);
    if (existing) {
      if (codeHostKey(existing.host) !== codeHostKey(host)) throw new Error('This working copy is already open in another review; close that editor first');
      return existing.id;
    }
    const id = randomUUID();
    const view = new WebContentsView({ webPreferences: {
      preload: path.join(__dirname, 'idePreload.js'), sandbox: true, contextIsolation: true, nodeIntegration: false, partition: `persist:bytequay-ide-${connection.taskId}`,
    } });
    const editor: Editor = { id, host, connection, view, context: null, visible: false, dirty: 0, busy: false, lastUsed: Date.now(), closing: null, pending: new Map() };
    this.editors.set(id, editor);
    try {
      const resources = await Promise.all(['extensions', 'server'].map(name => realpath(path.join(connection.bundleRoot, name))));
      const dist = path.resolve(__dirname, '../renderer/ide_window');
      const serve = async (request: Request): Promise<Response> => {
        try {
          const url = new URL(request.url);
          const isApp = url.protocol === 'bytequay-ide:' && url.hostname === 'app';
          if (!isApp && url.hostname !== 'ide-assets.bytequay.invalid') return new Response('Forbidden', { status: 403 });
          const target = await realpath(isApp ? path.join(dist, decodeURIComponent(url.pathname)) : decodeURIComponent(url.pathname));
          const roots = isApp ? [await realpath(dist)] : resources;
          if (!roots.some(root => target.startsWith(root + path.sep))) return new Response('Forbidden', { status: 403 });
          return net.fetch(pathToFileURL(target).href);
        }
        catch { return new Response('Not found', { status: 404 }); }
      };
      const session = view.webContents.session;
      session.protocol.unhandle('bytequay-ide');
      session.protocol.unhandle('https');
      session.protocol.handle('bytequay-ide', serve);
      session.protocol.handle('https', serve);
      session.setPermissionRequestHandler((contents, permission, callback) => callback(contents === view.webContents && permission === 'clipboard-sanitized-write'));
      session.setPermissionCheckHandler((contents, permission) => contents === view.webContents && permission === 'clipboard-sanitized-write');
      const dev = typeof IDE_WINDOW_VITE_DEV_SERVER_URL === 'string' ? new URL(IDE_WINDOW_VITE_DEV_SERVER_URL) : null;
      session.webRequest.onBeforeRequest((details, callback) => {
        const url = new URL(details.url);
        const local = ['http:', 'ws:'].includes(url.protocol) && (url.host === connection.authority || url.host === dev?.host);
        const resource = url.protocol === 'https:' && url.hostname === 'ide-assets.bytequay.invalid';
        callback({ cancel: !local && !resource && !['bytequay-ide:', 'data:', 'blob:'].includes(url.protocol) });
      });
      view.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
      view.webContents.on('will-navigate', event => event.preventDefault());
      view.webContents.on('will-attach-webview', event => event.preventDefault());
      view.webContents.on('render-process-gone', () => {
        this.window()?.webContents.send('ide:event', { id, dirty: editor.dirty, busy: false, error: 'The editor stopped. Reopen Code & Tests to reconnect.' } satisfies IdeEvent);
        void this.close(id).catch(() => {});
      });
      view.setVisible(false);
      await view.webContents.loadURL(dev ? new URL('ide/index.html', dev).href : 'bytequay-ide://app/ide/index.html');
      return id;
    }
    catch (error) {
      await this.close(id).catch(() => {});
      throw error;
    }
  }

  private require(id: string): Editor {
    const editor = this.editors.get(id);
    if (!editor) throw new Error('The IDE session has ended');
    return editor;
  }

  private async maintain(): Promise<void> {
    await Promise.allSettled([...this.editors.values()].map(async editor => {
      if (editor.view.webContents.isDestroyed() || (!editor.visible && editor.dirty === 0 && !editor.busy && editor.pending.size === 0 && Date.now() - editor.lastUsed > 120_000)) {
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
      if (!editor.view.webContents.isDestroyed()) {
        editor.view.setVisible(false);
        this.window()?.contentView.removeChildView(editor.view);
        editor.view.webContents.close({ waitForBeforeUnload: false });
      }
      for (const pending of editor.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(new Error('The editor closed'));
      }
      editor.pending.clear();
      // Failed termination remains registered so maintenance and explicit close retry the same process tree.
      await runtimeRequest(`/${editor.connection.taskId}/${editor.connection.sessionId}`, 'DELETE');
      this.editors.delete(id);
      this.window()?.webContents.send('ide:event', { id, dirty: editor.dirty, busy: false, closed: true } satisfies IdeEvent);
    };
    editor.closing = closing().finally(() => { editor.closing = null; });
    return editor.closing;
  }

  releaseWindow(): void {
    for (const editor of this.editors.values()) {
      editor.visible = false;
      editor.lastUsed = Date.now();
      if (!editor.view.webContents.isDestroyed()) editor.view.setVisible(false);
    }
  }

  dispose(): void {
    clearInterval(this.timer);
    for (const editor of this.editors.values()) {
      if (!editor.view.webContents.isDestroyed()) editor.view.webContents.close({ waitForBeforeUnload: false });
    }
    // Spring shutdown performs the verified process-tree stop; renderer destruction alone is not its proof.
  }
}
