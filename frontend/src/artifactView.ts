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
import { parseArtifactTarget, type ArtifactTarget } from './artifacts/contracts';

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

/** Shows one saved page over the Artifacts page; its scripts run, but every request beyond its own document and theme is cancelled. */
export class ArtifactView {
  private view: WebContentsView | null = null;
  // The page and version this view shows; its HTML is read before loading so a failed read reports the backend's reason.
  private wanted: string | null = null;
  private loading: Promise<void> | undefined;
  private page: { url: string; html: string } | null = null;
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

  async show(target: ArtifactTarget | null, bounds: unknown, allowFocusRestore = true): Promise<void> {
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
    const wanted = `${url} ${target.pageId}`;
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
    this.loading = (async () => {
      try {
        const html = await this.readPage(target);
        // Another page, or a newer save of this one, replaced it while it was read or loaded.
        await this.proxied;
        if (this.wanted !== wanted || this.view !== view) return;
        this.page = { url, html };
        await view.webContents.loadURL(url);
      }
      catch (error) {
        if (this.wanted !== wanted || this.view !== view) return;
        this.wanted = null;
        throw error;
      }
    })();
    return this.loading;
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
    contents.on('will-frame-navigate', event => { if (!event.isMainFrame) event.preventDefault(); });
    contents.on('render-process-gone', () => { this.wanted = null; });
    contents.on('destroyed', () => { if (this.view === view) this.closeHidden(); });
    this.view = view;
    return view;
  }

  private serve(request: Request): Response {
    const url = new URL(request.url);
    if (url.pathname.endsWith('/theme.css')) return new Response(ARTIFACT_THEME, { headers: { 'Content-Type': 'text/css; charset=utf-8' } });
    if (this.page === null || url.href !== this.page.url) return new Response('Not found', { status: 404 });
    return new Response(this.page.html, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': POLICY, 'X-DNS-Prefetch-Control': 'off' } });
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
    this.page = null;
    this.covered = null;
    this.restoreFocus = false;
    if (contents && !contents.isDestroyed()) contents.close();
  }
}
