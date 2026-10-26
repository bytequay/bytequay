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
import { app, BrowserWindow, dialog, ipcMain, WebContentsView, type IpcMainInvokeEvent, type Rectangle, type Session, type WebContents } from 'electron';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import type { InAppBrowserState, InAppBrowserTab } from './types';
import { normalizeWebAddress } from './webAddress';
import { labPage, resolveLabPage } from '@bytequay/lab/main';

type Tab = { state: InAppBrowserTab; view: WebContentsView | null; loadVersion: number; committedUrl: string; closing: boolean };
type PinnedTab = Pick<InAppBrowserTab, 'id' | 'url' | 'title' | 'name' | 'favicon'>;

const LOCAL_NETWORK_PERMISSIONS = new Set(['local-network-access', 'local-network', 'loopback-network']);

// Okta FastPass signs in through Okta Verify's localhost listener, which Chromium gates behind local network access.
// ponytail: okta.com hosts only; Okta custom sign-in domains still fall back to "Verify with something else".
function oktaLocalNetwork(permission: string, url: unknown): boolean {
  if (!LOCAL_NETWORK_PERMISSIONS.has(permission) || typeof url !== 'string') return false;
  try {
    const { protocol, hostname } = new URL(url);
    return protocol === 'https:' && hostname.endsWith('.okta.com');
  }
  catch { return false; }
}

// Copy buttons and video players need these; neither reaches beyond the page.
const PAGE_PERMISSIONS = new Set(['clipboard-sanitized-write', 'fullscreen']);

function allowed(permission: string, url: unknown): boolean {
  return PAGE_PERMISSIONS.has(permission) || oktaLocalNetwork(permission, url);
}

// ponytail: one question at a time; a page that keeps asking after Cancel still gets one sheet per try.
let askingExternal = false;

/** Links such as zoommtg: or slack: hand off to another app, so the user decides each one. */
async function confirmExternal(contents: WebContents | null, externalUrl: unknown, requestingUrl: unknown): Promise<boolean> {
  if (askingExternal || typeof externalUrl !== 'string') return false;
  const name = app.getApplicationNameForProtocol(externalUrl);
  if (!name) return false;
  const site = URL.parse(String(requestingUrl))?.host || 'This page';
  const options = {
    type: 'question' as const, message: `Open ${name}?`, detail: `${site} wants to open this link in ${name}.`, buttons: ['Open', 'Cancel'], defaultId: 0, cancelId: 1,
  };
  const window = (contents && BrowserWindow.fromWebContents(contents)) ?? BrowserWindow.getFocusedWindow();
  askingExternal = true;
  try { return (await (window ? dialog.showMessageBox(window, options) : dialog.showMessageBox(options))).response === 0; }
  finally { askingExternal = false; }
}

/** Electron grants browser permissions by default; remote pages get only the few that stay inside the page. */
export function limitBrowserPermissions(session: Session): void {
  session.setPermissionCheckHandler((_contents, permission, origin) => allowed(permission, origin));
  session.setPermissionRequestHandler((contents, permission, callback, details) => {
    if (permission !== 'openExternal') return callback(allowed(permission, details.requestingUrl));
    const externalUrl = 'externalURL' in details ? details.externalURL : undefined;
    void confirmExternal(contents, externalUrl, details.requestingUrl).then(callback, () => callback(false));
  });
}

function faviconUrl(value: unknown): string | null {
  if (typeof value !== 'string' || !/^https?:/i.test(value)) return null;
  try { return normalizeWebAddress(value); }
  catch { return null; }
}

function restoredTabs(value: unknown): PinnedTab[] {
  if (!Array.isArray(value)) return [];
  const ids = new Set<string>();
  return value.flatMap((item: unknown) => {
    if (!item || typeof item !== 'object') return [];
    const tab = item as Record<string, unknown>;
    if (typeof tab.id !== 'string' || !tab.id || ids.has(tab.id) || typeof tab.title !== 'string') return [];
    try {
      const url = normalizeWebAddress(tab.url);
      ids.add(tab.id);
      const name = typeof tab.name === 'string' ? tab.name.trim() : '';
      return [{ id: tab.id, url, title: tab.title, name: name === '' ? null : name, favicon: faviconUrl(tab.favicon) }];
    }
    catch { return []; }
  });
}

export function browserBounds(value: unknown, window: BrowserWindow): Rectangle {
  if (!value || typeof value !== 'object') throw new Error('Invalid browser bounds.');
  const target = value as Record<string, unknown>;
  for (const key of ['x', 'y', 'width', 'height']) {
    if (typeof target[key] !== 'number' || !Number.isFinite(target[key])) throw new Error('Invalid browser bounds.');
  }
  const size = window.getContentBounds();
  // DOM rectangles use CSS pixels; native views use DIPs, independent of display scale.
  const zoom = window.webContents.getZoomFactor();
  const left = (target.x as number) * zoom;
  const top = (target.y as number) * zoom;
  const x = Math.min(size.width, Math.max(0, Math.ceil(left)));
  const y = Math.min(size.height, Math.max(0, Math.ceil(top)));
  return {
    x, y,
    width: Math.max(0, Math.min(size.width, Math.floor(left + Math.max(0, target.width as number) * zoom)) - x),
    height: Math.max(0, Math.min(size.height, Math.floor(top + Math.max(0, target.height as number) * zoom)) - y),
  };
}

/** Live page ownership stays in main; mounting only presents an existing tab. */
export class BrowserTabs {
  private tabs = new Map<string, Tab>();
  private restored = false;
  private activeTabId: string | null = null;
  private surface: { id: string; bounds: Rectangle; allowFocusRestore: boolean } | null = null;
  private attached: WebContentsView | null = null;

  /** `claim` takes a link a page opens before any tab does, for links that act in ByteQuay rather than load a page. */
  constructor(private readonly window: () => BrowserWindow | null, private readonly storagePath: () => string, private readonly onPresent: () => void = () => {},
    private readonly claim: (url: string) => boolean = () => false, private readonly changed: (state: InAppBrowserState) => void = () => {}) {}

  getState(): InAppBrowserState {
    this.restore();
    return { tabs: [...this.tabs.values()].map(tab => ({ ...tab.state })), activeTabId: this.activeTabId };
  }

  open(address: unknown): void {
    const url = normalizeWebAddress(address);
    this.restore();
    const existing = [...this.tabs.values()].find(tab => tab.state.url === url);
    if (existing) return this.select(existing.state.id);
    this.addTab(url, false);
  }

  newPage(): void {
    this.restore();
    this.addTab('', false);
  }

  select(id: string): void {
    this.restore();
    const tab = this.requireTab(id);
    if (this.activeTabId !== id) this.unmountSurface();
    this.activeTabId = id;
    if (tab.state.url && (!tab.view || tab.view.webContents.isDestroyed())) {
      this.createView(tab);
      void this.load(tab, tab.state.url);
    }
    this.emit();
  }

  pin(id: string, pinned: boolean): void {
    if (typeof pinned !== 'boolean') throw new Error('Invalid pinned state.');
    if (pinned && !this.requireTab(id).state.url) throw new Error('Open a web address before pinning this page.');
    this.persist(id, { pinned });
  }

  /** A page keeps the name the user gave it while its own title changes underneath. */
  rename(id: string, name: unknown): void {
    if (typeof name !== 'string') throw new Error('Invalid page name.');
    this.persist(id, { name: name.trim() || null });
  }

  private persist(id: string, update: Partial<Pick<Tab['state'], 'pinned' | 'name'>>): void {
    const tab = this.requireTab(id);
    const previous = tab.state;
    tab.state = { ...previous, ...update };
    try { this.save(); }
    catch (error) { tab.state = previous; throw error; }
    this.emit();
  }

  close(id: string): void {
    const tab = this.tabs.get(id);
    if (!tab || tab.closing) return;
    if (tab.state.pinned) {
      if (this.activeTabId === id) this.hide();
      return;
    }
    if (tab.view && !tab.view.webContents.isDestroyed()) {
      tab.closing = true;
      try { tab.view.webContents.close({ waitForBeforeUnload: true }); }
      catch (error) { tab.closing = false; throw error; }
      return;
    }
    this.removeTab(tab);
  }

  private removeTab(tab: Tab): void {
    // Closing the open page returns to the app; the rail is a set of pages, not a tab strip.
    if (this.activeTabId === tab.state.id) this.hide();
    this.tabs.delete(tab.state.id);
    this.emit();
  }

  hide(): void {
    this.unmountSurface();
    this.activeTabId = null;
    this.emit();
  }

  mount(id: string, target: unknown, allowFocusRestore: unknown = true): void {
    if (typeof allowFocusRestore !== 'boolean') throw new Error('Invalid browser focus restoration');
    const window = this.window();
    if (!window || window.isDestroyed()) return;
    const bounds = browserBounds(target, window);
    if (this.activeTabId !== id || !this.tabs.has(id)) return;
    this.surface = { id, bounds, allowFocusRestore };
    this.present();
  }

  setBounds(id: string, target: unknown): void {
    if (this.surface?.id === id) this.mount(id, target, this.surface.allowFocusRestore);
  }

  unmount(id: string): void {
    if (this.surface?.id === id) this.unmountSurface();
  }

  /** Renderer reload drops layout, never the live website or its history. */
  rendererNavigated({ isSameDocument, isMainFrame }: { isSameDocument: boolean; isMainFrame: boolean }): void {
    // Hash navigation keeps React mounted, so the open page's slot will not mount it again.
    if (isSameDocument || !isMainFrame) return;
    this.unmountSurface();
  }

  releaseWindow(): void {
    this.unmountSurface();
    const views = [...this.tabs.values()].map(tab => tab.view);
    this.tabs.clear();
    this.activeTabId = null;
    this.restored = false;
    for (const view of views) if (view && !view.webContents.isDestroyed()) view.webContents.close();
  }

  async navigate(id: string, address: unknown): Promise<void> {
    const url = normalizeWebAddress(address);
    const tab = this.requireTab(id);
    if (!tab.view || tab.view.webContents.isDestroyed()) this.createView(tab);
    await this.load(tab, url);
  }

  back(id: string): void {
    const contents = this.requireTab(id).view?.webContents;
    if (contents && !contents.isDestroyed() && contents.navigationHistory.canGoBack()) contents.navigationHistory.goBack();
  }

  forward(id: string): void {
    const contents = this.requireTab(id).view?.webContents;
    if (contents && !contents.isDestroyed() && contents.navigationHistory.canGoForward()) contents.navigationHistory.goForward();
  }

  find(id: string, text: string, options: Electron.FindInPageOptions): void {
    if (typeof text !== 'string' || !text || !options || typeof options !== 'object'
      || typeof options.forward !== 'boolean' || typeof options.findNext !== 'boolean') throw new Error('Invalid page search.');
    const tab = this.requireTab(id);
    const contents = tab.view?.webContents;
    if (this.activeTabId === id && !tab.state.error && contents && !contents.isDestroyed()) contents.findInPage(text, options);
  }

  stopFind(id: string): void {
    const contents = this.tabs.get(id)?.view?.webContents;
    if (contents && !contents.isDestroyed()) contents.stopFindInPage('clearSelection');
  }

  reload(id: string): void {
    const tab = this.requireTab(id);
    if (!tab.state.url) return;
    if (labPage(tab.state.url) !== null) {
      void this.navigate(id, tab.state.url);
      return;
    }
    if (!tab.view || tab.view.webContents.isDestroyed()) {
      this.createView(tab);
      void this.load(tab, tab.state.url);
      return;
    }
    tab.state.error = null;
    tab.state.loading = true;
    this.present();
    this.emit();
    tab.view.webContents.reload();
  }

  private addTab(url: string, background: boolean): void {
    const id = randomUUID();
    const tab = this.tab({ id, url, title: url ? '' : 'New page', name: null, favicon: null }, false);
    this.tabs.set(id, tab);
    if (background) {
      this.createView(tab);
      void this.load(tab, url);
    }
    else this.select(id);
  }

  private tab(saved: PinnedTab, pinned: boolean): Tab {
    return { state: { ...saved, pinned, loading: false, canGoBack: false, canGoForward: false, error: null }, view: null, loadVersion: 0, committedUrl: saved.url, closing: false };
  }

  private restore(): void {
    if (this.restored) return;
    this.restored = true;
    try {
      for (const saved of restoredTabs(JSON.parse(fs.readFileSync(this.storagePath(), 'utf8')))) this.tabs.set(saved.id, this.tab(saved, true));
    }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') console.warn('Unable to restore pinned browser pages', error);
    }
  }

  private save(): void {
    const saved = [...this.tabs.values()].filter(tab => tab.state.pinned)
      .map(({ state: { id, url, title, name, favicon }, committedUrl }) => ({ id, url: committedUrl || url, title, name, favicon }));
    const file = this.storagePath();
    fs.writeFileSync(`${file}.tmp`, JSON.stringify(saved), { mode: 0o600 });
    fs.chmodSync(`${file}.tmp`, 0o600);
    fs.renameSync(`${file}.tmp`, file);
  }

  private requireTab(id: string): Tab {
    this.restore();
    const tab = this.tabs.get(id);
    if (!tab) throw new Error('The browser tab is closed.');
    return tab;
  }

  private emit(): void {
    const state = this.getState();
    this.changed(state);
    const window = this.window();
    if (window && !window.isDestroyed() && !window.webContents.isDestroyed()) window.webContents.send('inapp:state', state);
  }

  private unmountSurface(): void {
    this.surface = null;
    this.detach();
  }

  private detach(): void {
    const view = this.attached;
    this.attached = null;
    const window = this.window();
    if (view) {
      view.setVisible(false);
      if (window && !window.isDestroyed()) window.contentView.removeChildView(view);
    }
    if (view && window && !window.isDestroyed()) window.webContents.focus();
  }

  private present(): void {
    const window = this.window();
    const tab = this.surface && this.surface.id === this.activeTabId ? this.tabs.get(this.surface.id) : null;
    if (!window || window.isDestroyed() || !tab?.view || tab.view.webContents.isDestroyed() || tab.state.error || !this.surface) {
      this.detach();
      return;
    }
    tab.view.setBounds(this.surface.bounds);
    if (this.attached === tab.view) return;
    this.detach();
    window.contentView.addChildView(tab.view);
    tab.view.setVisible(true);
    this.onPresent();
    this.attached = tab.view;
    if (this.surface.allowFocusRestore) tab.view.webContents.focus();
  }

  private createView(tab: Tab): void {
    const view = new WebContentsView({ webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, partition: 'persist:inapp-browser' } });
    tab.view = view;
    view.setVisible(false);
    view.setBackgroundColor('#00000000');
    const contents = view.webContents;
    limitBrowserPermissions(contents.session);
    const current = () => this.tabs.get(tab.state.id) === tab && tab.view === view;
    const update = () => {
      if (!current() || contents.isDestroyed()) return;
      const url = contents.getURL();
      if (/^https?:/i.test(url)) tab.state.url = url;
      tab.state.title = contents.getTitle();
      tab.state.canGoBack = contents.navigationHistory.canGoBack();
      tab.state.canGoForward = contents.navigationHistory.canGoForward();
      tab.state.loading = tab.state.error === null && contents.isLoadingMainFrame();
      if (tab.state.pinned) {
        try { this.save(); }
        catch (error) { console.warn('Unable to save pinned browser page', error); }
      }
      this.emit();
    };
    contents.on('did-start-navigation', (_event, url, inPlace, mainFrame) => {
      if (!current() || !mainFrame) return;
      tab.state.error = null;
      tab.state.loading = true;
      if (!inPlace) tab.state.favicon = null;
      if (/^https?:/i.test(url)) tab.state.url = url;
      this.present();
      this.emit();
    });
    const committed = () => {
      if (!current() || contents.isDestroyed()) return;
      const url = contents.getURL();
      if (/^https?:/i.test(url)) {
        tab.committedUrl = url;
        // Pages that declare no icon link never raise page-favicon-updated, so ask for the site default.
        tab.state.favicon ??= faviconUrl(new URL('/favicon.ico', url).href);
      }
      update();
    };
    contents.on('did-navigate', committed);
    contents.on('did-navigate-in-page', committed);
    contents.on('did-start-loading', update);
    contents.on('did-stop-loading', update);
    contents.on('page-title-updated', update);
    contents.on('page-favicon-updated', (_event, urls) => {
      if (!current()) return;
      tab.state.favicon = urls.map(faviconUrl).find(url => url !== null) ?? null;
      if (tab.state.pinned) {
        try { this.save(); }
        catch (error) { console.warn('Unable to save pinned browser icon', error); }
      }
      this.emit();
    });
    contents.on('did-fail-load', (_event, code, description, url, mainFrame) => {
      if (!current() || !mainFrame || code === -3) return;
      if (/^https?:/i.test(url)) tab.state.url = url;
      this.fail(tab, `Unable to load this page: ${description}`);
    });
    contents.on('will-prevent-unload', event => {
      if (!current()) return;
      const window = this.window();
      if (!window || window.isDestroyed()) { tab.closing = false; return; }
      // The decision must be synchronous so this event can permit the pending unload.
      const response = dialog.showMessageBoxSync(window, {
        type: 'question', message: 'Leave this page?', detail: 'Changes you made may not be saved.', buttons: ['Stay', 'Leave'], defaultId: 0, cancelId: 0,
      });
      if (response === 1) event.preventDefault();
      else {
        tab.closing = false;
        tab.state.url = tab.committedUrl;
        tab.state.loading = false;
        this.emit();
      }
    });
    contents.on('destroyed', () => {
      if (!current()) return;
      // Electron clears view.webContents when it is destroyed; retries need a new native view.
      tab.view = null;
      if (tab.closing) this.removeTab(tab);
      else this.fail(tab, 'This page stopped unexpectedly. Reload to try again.');
    });
    contents.on('render-process-gone', () => {
      if (!current()) return;
      tab.closing = false;
      this.fail(tab, 'This page stopped unexpectedly. Reload to try again.');
    });
    contents.on('before-input-event', (event, input) => {
      if (!current() || this.activeTabId !== tab.state.id || input.type !== 'keyDown' || input.alt || !(input.meta || input.control) || input.key.toLowerCase() !== 'f') return;
      const window = this.window();
      if (!window || window.isDestroyed() || window.webContents.isDestroyed()) return;
      event.preventDefault();
      if (input.isAutoRepeat) return;
      window.webContents.focus();
      window.webContents.send('inapp:find', tab.state.id);
    });
    contents.on('found-in-page', (_event, result) => {
      if (!current() || this.activeTabId !== tab.state.id) return;
      const window = this.window();
      if (!window || window.isDestroyed() || window.webContents.isDestroyed()) return;
      window.webContents.send('find:result', { activeMatchOrdinal: result.activeMatchOrdinal, matches: result.matches, tabId: tab.state.id });
    });
    contents.setWindowOpenHandler(({ url, disposition }) => {
      if (!current()) return { action: 'deny' };
      try { url = normalizeWebAddress(url); }
      catch { return { action: 'deny' }; }
      if (this.claim(url)) return { action: 'deny' };
      // Chromium supplies tab dispositions for modified clicks and target="_blank".
      if (disposition === 'background-tab' || disposition === 'foreground-tab') this.addTab(url, disposition === 'background-tab');
      else void this.navigate(tab.state.id, url).catch(() => {});
      return { action: 'deny' };
    });
  }

  private fail(tab: Tab, message: string): void {
    tab.state.error = message;
    tab.state.loading = false;
    this.present();
    this.emit();
  }

  private async load(tab: Tab, url: string): Promise<void> {
    const version = ++tab.loadVersion;
    tab.state.error = null;
    tab.state.loading = true;
    tab.state.url = url;
    this.present();
    this.emit();
    try {
      if (labPage(url) !== null) {
        url = await resolveLabPage(url);
        if (this.tabs.get(tab.state.id) !== tab || tab.loadVersion !== version) return;
        tab.state.url = url;
      }
      await tab.view?.webContents.loadURL(url);
    }
    catch (error) {
      if (this.tabs.get(tab.state.id) !== tab || tab.loadVersion !== version || (error as { code?: string }).code === 'ERR_ABORTED') return;
      this.fail(tab, error instanceof Error ? error.message : 'Unable to load this page.');
    }
  }
}

export function registerBrowserTabIpc(tabs: BrowserTabs, window: () => BrowserWindow | null): void {
  const trusted = (event: IpcMainInvokeEvent) => {
    const current = window();
    if (!current || current.isDestroyed() || event.sender !== current.webContents || event.senderFrame !== current.webContents.mainFrame) {
      throw new Error('Browser controls are only available from the app.');
    }
  };
  ipcMain.handle('inapp:state', event => { trusted(event); return tabs.getState(); });
  ipcMain.handle('inapp:open', (event, url: unknown) => { trusted(event); tabs.open(url); });
  ipcMain.handle('inapp:new', event => { trusted(event); tabs.newPage(); });
  ipcMain.handle('inapp:select', (event, id: string) => { trusted(event); tabs.select(id); });
  ipcMain.handle('inapp:pin', (event, id: string, pinned: boolean) => { trusted(event); tabs.pin(id, pinned); });
  ipcMain.handle('inapp:rename', (event, id: string, name: unknown) => { trusted(event); tabs.rename(id, name); });
  ipcMain.handle('inapp:close', (event, id: string) => { trusted(event); tabs.close(id); });
  ipcMain.handle('inapp:hide', event => { trusted(event); tabs.hide(); });
  ipcMain.handle('inapp:mount', (event, id: string, target: unknown, allowFocusRestore: unknown = true) => { trusted(event); tabs.mount(id, target, allowFocusRestore); });
  ipcMain.handle('inapp:bounds', (event, id: string, target: unknown) => { trusted(event); tabs.setBounds(id, target); });
  ipcMain.handle('inapp:unmount', (event, id: string) => { trusted(event); tabs.unmount(id); });
  ipcMain.handle('inapp:back', (event, id: string) => { trusted(event); tabs.back(id); });
  ipcMain.handle('inapp:forward', (event, id: string) => { trusted(event); tabs.forward(id); });
  ipcMain.handle('inapp:reload', (event, id: string) => { trusted(event); tabs.reload(id); });
  ipcMain.handle('inapp:navigate', (event, id: string, url: unknown) => { trusted(event); return tabs.navigate(id, url); });
}
