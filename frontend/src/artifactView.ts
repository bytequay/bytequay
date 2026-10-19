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
import palette from './css/palette.css?raw';
import { browserBounds } from './browserTabs';
import { parseArtifactTarget, type ArtifactTarget } from './artifacts/contracts';

const SCHEME = 'bytequay-artifact';

protocol.registerSchemesAsPrivileged([{ scheme: SCHEME, privileges: { standard: true, secure: true } }]);

const ID = /^[a-z0-9-]{1,64}$/;

// Inline scripts and styles only: a page brings everything it needs and reaches nothing.
const POLICY = "default-src 'none'; script-src 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src data:; media-src data: blob:; form-action 'none'; base-uri 'none'";

/** The app's palette, so a page that links `theme.css` matches ByteQuay and follows its light or dark choice. */
export const ARTIFACT_THEME = `${palette}
:root {
  color-scheme: light dark;
  --font-body: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
  --font-mono: ui-monospace, "SFMono-Regular", "SF Mono", Menlo, Monaco, Consolas, monospace;
  font: 14px/1.6 var(--font-body);
  color: var(--fg-default);
  background: var(--surface-canvas);
}
body { margin: 0; padding: 24px 32px; }
code, pre, kbd { font-family: var(--font-mono); font-size: 12px; }
a { color: var(--fg-link); }
`;

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
  private page: { url: string; html: string } | null = null;

  constructor(private readonly window: () => BrowserWindow | null, private readonly readPage: (target: ArtifactTarget) => Promise<string>,
    private readonly onPresent: () => void = () => {}) {}

  register(): void {
    ipcMain.handle('artifacts:show', (event, target: unknown, bounds: unknown) => {
      const window = this.window();
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Unknown artifact owner');
      return this.show(target === null ? null : parseArtifactTarget(target), bounds);
    });
  }

  async show(target: ArtifactTarget | null, bounds: unknown): Promise<void> {
    const window = this.window();
    if (target === null || bounds === null || !window || window.isDestroyed()) {
      this.view?.setVisible(false);
      return;
    }
    const url = artifactUrl(target);
    const wanted = `${url} ${target.version}`;
    const view = this.view ?? this.create();
    view.setBounds(browserBounds(bounds, window));
    window.contentView.addChildView(view);
    view.setVisible(true);
    this.onPresent();
    if (this.wanted === wanted) return;
    this.wanted = wanted;
    try {
      const html = await this.readPage(target);
      // Another page, or a newer save of this one, replaced it while it was read or loaded.
      if (this.wanted !== wanted) return;
      this.page = { url, html };
      await view.webContents.loadURL(url);
    }
    catch (error) {
      if (this.wanted !== wanted) return;
      this.wanted = null;
      throw error;
    }
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
    }
    contents.setWindowOpenHandler(() => ({ action: 'deny' }));
    contents.on('will-navigate', event => event.preventDefault());
    contents.on('will-frame-navigate', event => { if (!event.isMainFrame) event.preventDefault(); });
    contents.on('render-process-gone', () => { this.wanted = null; });
    this.view = view;
    return view;
  }

  private serve(request: Request): Response {
    const url = new URL(request.url);
    if (url.pathname.endsWith('/theme.css')) return new Response(ARTIFACT_THEME, { headers: { 'Content-Type': 'text/css; charset=utf-8' } });
    if (this.page === null || url.href !== this.page.url) return new Response('Not found', { status: 404 });
    return new Response(this.page.html, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': POLICY } });
  }

  /** The page belongs to the window; a new window opens it again. */
  releaseWindow(): void {
    if (this.view && !this.view.webContents.isDestroyed()) this.view.webContents.close();
    this.view = null;
    this.wanted = null;
    this.page = null;
  }
}
