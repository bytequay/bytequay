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
import { ipcMain, WebContentsView, type BrowserWindow, type IpcMainInvokeEvent, type Rectangle } from 'electron';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import type { InAppBrowserState, InAppBrowserTab } from './types';
import { normalizeWebAddress } from './webAddress';

type Tab = { state: InAppBrowserTab; view: WebContentsView | null; loadVersion: number; committedUrl: string };
type PinnedTab = Pick<InAppBrowserTab, 'id' | 'url' | 'title' | 'name' | 'favicon'>;

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
  private surface: { id: string; bounds: Rectangle } | null = null;
  private attached: WebContentsView | null = null;

  constructor(private readonly window: () => BrowserWindow | null, private readonly storagePath: () => string, private readonly onPresent: () => void = () => {}) {}

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

  select(id: string): void {
    this.restore();
    const tab = this.requireTab(id);
    if (this.activeTabId !== id) this.unmountSurface();
    this.activeTabId = id;
    if (!tab.view || tab.view.webContents.isDestroyed()) {
      this.createView(tab);
      void this.load(tab, tab.state.url);
    }
    this.emit();
  }

  pin(id: string, pinned: boolean): void {
    if (typeof pinned !== 'boolean') throw new Error('Invalid pinned state.');
    const tab = this.requireTab(id);
    const previous = tab.state.pinned;
    tab.state.pinned = pinned;
    try { this.save(); }
    catch (error) { tab.state.pinned = previous; throw error; }
    this.emit();
  }

  /** A page keeps the name the user gave it while its own title changes underneath. */
  rename(id: string, name: unknown): void {
    if (typeof name !== 'string') throw new Error('Invalid page name.');
    const next = name.trim();
    const tab = this.requireTab(id);
    const previous = tab.state.name;
    tab.state.name = next === '' ? null : next;
    try { this.save(); }
    catch (error) { tab.state.name = previous; throw error; }
    this.emit();
  }

  close(id: string): void {
    const tab = this.tabs.get(id);
    if (!tab) return;
    // Closing the open page returns to the app; the rail is a set of pages, not a tab strip.
    if (this.activeTabId === id) this.hide();
    if (tab.state.pinned) return;
    this.tabs.delete(id);
    if (tab.view && !tab.view.webContents.isDestroyed()) tab.view.webContents.close();
    this.emit();
  }

  hide(): void {
    this.unmountSurface();
    this.activeTabId = null;
    this.emit();
  }

  mount(id: string, target: unknown): void {
    const window = this.window();
    if (!window || window.isDestroyed()) return;
    const bounds = browserBounds(target, window);
    if (this.activeTabId !== id || !this.tabs.has(id)) return;
    this.surface = { id, bounds };
    this.present();
  }

  setBounds(id: string, target: unknown): void {
    const window = this.window();
    if (!window || window.isDestroyed()) return;
    const bounds = browserBounds(target, window);
    if (this.activeTabId !== id || this.surface?.id !== id) return;
    this.surface.bounds = bounds;
    this.present();
  }

  unmount(id: string): void {
    if (this.surface?.id === id) this.unmountSurface();
  }

  /** Renderer reload drops layout, never the live website or its history. */
  rendererReload(): void {
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

  reload(id: string): void {
    const tab = this.requireTab(id);
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
    const tab = this.tab({ id, url, title: '', name: null, favicon: null }, false);
    this.tabs.set(id, tab);
    if (background) {
      this.createView(tab);
      void this.load(tab, url);
    }
    else this.select(id);
  }

  private tab(saved: PinnedTab, pinned: boolean): Tab {
    return { state: { ...saved, pinned, loading: false, canGoBack: false, canGoForward: false, error: null }, view: null, loadVersion: 0, committedUrl: saved.url };
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
      .map(({ state: { id, title, name, favicon }, committedUrl }) => ({ id, url: committedUrl, title, name, favicon }));
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
    const window = this.window();
    if (window && !window.isDestroyed() && !window.webContents.isDestroyed()) window.webContents.send('inapp:state', this.getState());
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
    tab.view.webContents.focus();
  }

  private createView(tab: Tab): void {
    const view = new WebContentsView({ webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, partition: 'persist:inapp-browser' } });
    tab.view = view;
    view.setVisible(false);
    view.setBackgroundColor('#ffffff');
    const contents = view.webContents;
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
    contents.on('destroyed', () => { if (current()) this.fail(tab, 'This page stopped unexpectedly. Reload to try again.'); });
    contents.on('render-process-gone', () => { if (current()) this.fail(tab, 'This page stopped unexpectedly. Reload to try again.'); });
    contents.setWindowOpenHandler(({ url, disposition }) => {
      if (!current()) return { action: 'deny' };
      try { url = normalizeWebAddress(url); }
      catch { return { action: 'deny' }; }
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
    try { await tab.view?.webContents.loadURL(url); }
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
  ipcMain.handle('inapp:select', (event, id: string) => { trusted(event); tabs.select(id); });
  ipcMain.handle('inapp:pin', (event, id: string, pinned: boolean) => { trusted(event); tabs.pin(id, pinned); });
  ipcMain.handle('inapp:rename', (event, id: string, name: unknown) => { trusted(event); tabs.rename(id, name); });
  ipcMain.handle('inapp:close', (event, id: string) => { trusted(event); tabs.close(id); });
  ipcMain.handle('inapp:hide', event => { trusted(event); tabs.hide(); });
  ipcMain.handle('inapp:mount', (event, id: string, target: unknown) => { trusted(event); tabs.mount(id, target); });
  ipcMain.handle('inapp:bounds', (event, id: string, target: unknown) => { trusted(event); tabs.setBounds(id, target); });
  ipcMain.handle('inapp:unmount', (event, id: string) => { trusted(event); tabs.unmount(id); });
  ipcMain.handle('inapp:back', (event, id: string) => { trusted(event); tabs.back(id); });
  ipcMain.handle('inapp:forward', (event, id: string) => { trusted(event); tabs.forward(id); });
  ipcMain.handle('inapp:reload', (event, id: string) => { trusted(event); tabs.reload(id); });
  ipcMain.handle('inapp:navigate', (event, id: string, url: unknown) => { trusted(event); return tabs.navigate(id, url); });
}
