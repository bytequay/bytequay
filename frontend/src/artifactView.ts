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
import { ipcMain, protocol, WebContentsView, type BrowserWindow } from 'electron';
// eslint-disable-next-line import/no-unresolved -- Vite inlines the stylesheet's text; the resolver does not know ?raw.
import theme from './artifacts/theme.css?raw';
import { browserBounds } from './browserTabs';
import { parseArtifactTarget, type ArtifactReaderTarget, type ArtifactTarget } from './artifacts/contracts';
import { artifactReader, artifactReaderPage } from './artifacts/reader';

const SCHEME = 'bytequay-artifact';

protocol.registerSchemesAsPrivileged([{ scheme: SCHEME, privileges: { standard: true, secure: true } }]);

const ID = /^[a-z0-9-]{1,64}$/;

// Inline scripts and styles only: a page brings everything it needs and reaches nothing.
const POLICY = "default-src 'none'; script-src 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src data:; media-src data: blob:; form-action 'none'; base-uri 'none'";

/** The page base every page links as `theme.css`, so it matches ByteQuay and follows its light or dark choice. */
export const ARTIFACT_THEME = theme;

/** Each artifact is its own origin, so one page cannot read another page's storage. */
export function artifactUrl(target: ArtifactTarget): string {
  if (!ID.test(target.artifactId) || !ID.test(target.workspaceId)) throw new Error('Invalid artifact');
  return `${SCHEME}://${target.artifactId}/${target.workspaceId}/`;
}

/** Shows the book in one native view; each saved page runs in an opaque-origin sandbox without a bridge. */
export class ArtifactView {
  private view: WebContentsView | null = null;
  // The page and version this view shows; its HTML is read before loading so a failed read reports the backend's reason.
  private wanted: string | null = null;
  private loading: Promise<void> | undefined;
  private book: { url: string; key: string; target: ArtifactReaderTarget; html: Map<string, Promise<string>> } | null = null;
  private proxied: Promise<void> = Promise.resolve();
  private idle: ReturnType<typeof setTimeout> | undefined;
  private covered: string | null = null;
  private restoreFocus = false;

  constructor(private readonly window: () => BrowserWindow | null, private readonly readPage: (target: ArtifactTarget) => Promise<string>,
    private readonly onPresent: () => void = () => {}) {}

  register(): void {
    ipcMain.handle('artifacts:show', (event, target: unknown, bounds: unknown, allowFocusRestore: unknown = true) => {
      const window = this.window();
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Unknown artifact owner');
      if (typeof allowFocusRestore !== 'boolean') throw new Error('Invalid artifact focus restoration');
      return this.show(target === null ? null : parseArtifactTarget(target), bounds, allowFocusRestore);
    });
  }

  async show(target: ArtifactReaderTarget | null, bounds: unknown, allowFocusRestore = true): Promise<void> {
    const window = this.window();
    clearTimeout(this.idle);
    if (target === null || !window || window.isDestroyed()) {
      this.covered = null;
      this.restoreFocus = false;
      this.view?.setVisible(false);
      // A hidden page keeps its renderer process and its timers; one hidden for a minute closes and loads again when shown.
      if (this.view) this.idle = setTimeout(() => this.closeHidden(), 60_000).unref();
      return;
    }
    const url = artifactUrl(target);
    const key = `${url} ${JSON.stringify(target.pages)}`;
    const wanted = `${key} ${target.pageId}`;
    if (bounds === null) {
      if (this.covered !== wanted) this.restoreFocus = this.wanted === wanted && this.view?.webContents.isFocused() === true;
      if (!allowFocusRestore) this.restoreFocus = false;
      this.covered = wanted;
      // A modal covers the active page; keep its unsaved interactive state for the whole interruption.
      this.view?.setVisible(false);
      return;
    }
    const restoreFocus = allowFocusRestore && this.covered === wanted && this.restoreFocus;
    this.covered = null;
    this.restoreFocus = false;
    const view = this.view ?? this.create();
    view.setBounds(browserBounds(bounds, window));
    window.contentView.addChildView(view);
    view.setVisible(true);
    this.onPresent();
    if (restoreFocus && window.isFocused()) view.webContents.focus();
    if (this.wanted === wanted) return this.loading;
    this.wanted = wanted;
    // A covering dialog can hide the reader during a load; it still has to finish layout before its background is captured.
    view.webContents.setBackgroundThrottling(false);
    this.loading = (async () => {
      try {
        const book = this.book?.key === key ? this.book : { url, key, target, html: new Map<string, Promise<string>>() };
        await this.pageHtml(book, target.pageId);
        // Another page, or a newer save of this one, replaced it while it was read or loaded.
        await this.proxied;
        if (this.wanted !== wanted || this.view !== view) return;
        if (this.book === book) {
          // The app can jump from a transcript mark without discarding the other pages' interactive state.
          await view.webContents.executeJavaScript(`new Promise(resolve => {
            const id = ${JSON.stringify(target.pageId)};
            const ready = event => { if (event.detail === id) { window.removeEventListener('artifact-ready', ready); resolve(); } };
            window.addEventListener('artifact-ready', ready);
            window.dispatchEvent(new CustomEvent('artifact-select', { detail: id }));
          })`);
        }
        else {
          this.book = book;
          await view.webContents.loadURL(url);
        }
      }
      catch (error) {
        if (this.wanted !== wanted || this.view !== view) return;
        this.wanted = null;
        this.book = null;
        throw error;
      }
    })();
    const loading = this.loading;
    const settled = () => {
      if (this.loading !== loading) return;
      this.loading = undefined;
      if (this.view === view && !view.webContents.isDestroyed()) view.webContents.setBackgroundThrottling(true);
    };
    void loading.then(settled, settled);
    return loading;
  }

  private create(): WebContentsView {
    // No preload and no persist: prefix: the page gets no bridge, and nothing it stores outlives the app.
    const view = new WebContentsView({ webPreferences: {
      partition: 'bytequay-artifacts', sandbox: true, contextIsolation: true, nodeIntegration: false, webviewTag: false, spellcheck: false, navigateOnDragDrop: false,
    } });
    const contents = view.webContents;
    const session = contents.session;
    if (!session.protocol.isProtocolHandled(SCHEME)) {
      session.protocol.handle(SCHEME, request => this.serve(request));
      session.webRequest.onBeforeRequest((details, callback) => callback({ cancel: !/^(bytequay-artifact|data|blob):/.test(details.url) }));
      session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
      session.setPermissionCheckHandler(() => false);
      session.on('will-download', event => event.preventDefault());
      // WebRTC, DNS prefetch and preconnect never reach onBeforeRequest; a proxy nothing listens on takes what they would send.
      this.proxied = session.setProxy({ proxyRules: 'socks5://127.0.0.1:9' });
    }
    contents.setWebRTCIPHandlingPolicy('disable_non_proxied_udp');
    contents.setWindowOpenHandler(() => ({ action: 'deny' }));
    contents.on('will-navigate', event => event.preventDefault());
    contents.on('will-frame-navigate', event => {
      // Only the app's top-level reader may load a saved version into one of its direct child frames.
      if (!event.isMainFrame && !(event.initiator === contents.mainFrame && event.frame?.parent === contents.mainFrame
        && this.book?.target.pages.some(page => event.url === `${this.book?.url}pages/${encodeURIComponent(page.id)}/`))) event.preventDefault();
    });
    contents.on('did-navigate-in-page', (_event, address, isMainFrame) => {
      const book = this.book;
      if (!isMainFrame || !book || this.view !== view) return;
      const url = new URL(address);
      const pageId = url.hash.slice(1);
      url.hash = '';
      if (url.href !== book.url || !book.target.pages.some(page => page.id === pageId)) return;
      const wanted = `${book.key} ${pageId}`;
      if (this.loading !== undefined && this.wanted !== wanted) return;
      this.wanted = wanted;
      this.window()?.webContents.send('artifacts:reading', { workspaceId: book.target.workspaceId, artifactId: book.target.artifactId, pageId });
    });
    contents.on('render-process-gone', () => { this.wanted = null; this.book = null; });
    contents.on('destroyed', () => { if (this.view === view) this.closeHidden(); });
    this.view = view;
    return view;
  }

  private pageHtml(book: NonNullable<ArtifactView['book']>, pageId: string): Promise<string> {
    let html = book.html.get(pageId);
    if (!html) {
      html = this.readPage({ ...book.target, pageId }).catch(error => { book.html.delete(pageId); throw error; });
      book.html.set(pageId, html);
    }
    return html;
  }

  private async serve(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.endsWith('/theme.css')) return new Response(ARTIFACT_THEME, { headers: { 'Content-Type': 'text/css; charset=utf-8' } });
    const book = this.book;
    if (book === null) return new Response('Not found', { status: 404 });
    const headers = { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': POLICY, 'X-DNS-Prefetch-Control': 'off' };
    if (url.href === book.url) return new Response(artifactReader(book.target), { headers: { ...headers, 'Content-Security-Policy': `${POLICY}; frame-src 'self'` } });
    const page = book.target.pages.find(page => url.href === `${book.url}pages/${encodeURIComponent(page.id)}/`);
    if (!page) return new Response('Not found', { status: 404 });
    try {
      return new Response(artifactReaderPage(await this.pageHtml(book, page.id), page.id), { headers });
    }
    catch {
      return new Response(artifactReaderPage('<!doctype html><link rel="stylesheet" href="theme.css"><p role="alert">This page could not be opened.</p><button data-artifact-retry>Try again</button>', page.id), { headers });
    }
  }

  private closeHidden(): void {
    const window = this.window();
    if (this.view && window && !window.isDestroyed()) window.contentView.removeChildView(this.view);
    this.releaseWindow();
  }

  /** The page belongs to the window; a new window opens it again. */
  releaseWindow(): void {
    clearTimeout(this.idle);
    const contents = this.view?.webContents;
    this.view = null;
    this.wanted = null;
    this.loading = undefined;
    this.book = null;
    this.covered = null;
    this.restoreFocus = false;
    if (contents && !contents.isDestroyed()) contents.close();
  }
}
