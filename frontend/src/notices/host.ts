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
import { ipcMain, WebContentsView, type BrowserWindow, type IpcMainEvent, type IpcMainInvokeEvent } from 'electron';
import path from 'node:path';
import { NOTICE_WIDTH, parseNotices, type AppNotice, type NoticeSnapshot } from './contracts';
import { NoticeStore } from './store';

/** One trusted app surface above native browser/editor views; its bridge cannot execute task actions. */
export class NoticeHost {
  private readonly store = new NoticeStore();
  private view: WebContentsView | null = null;
  private timer: ReturnType<typeof setInterval> | undefined;
  private reading: Promise<void> | null = null;
  private height = 0;
  private width = NOTICE_WIDTH;
  private folded = false;
  private obscured = false;
  private sources = new Map<string, { taskId: string; turnId: string | null }>();

  constructor(private readonly window: () => BrowserWindow | null, private readonly read: () => Promise<unknown>,
    private readonly openNotice: (notice: AppNotice) => Promise<void>) {}

  register(): void {
    ipcMain.on('notices:obscured', (event, obscured: unknown) => {
      const window = this.window();
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Unknown notice owner');
      if (typeof obscured !== 'boolean') return;
      this.obscured = obscured; this.publish();
    });
    ipcMain.on('notices:measure', event => {
      const window = this.window();
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Unknown notice owner');
      this.layout();
    });
    const child = (event: IpcMainInvokeEvent | IpcMainEvent): void => {
      if (!this.view || event.sender !== this.view.webContents || event.senderFrame !== event.sender.mainFrame) throw new Error('Unknown notice surface');
    };
    ipcMain.handle('notices:read', event => { child(event); return this.snapshot(); });
    ipcMain.handle('notices:dismiss', (event, id: unknown) => {
      child(event);
      if (typeof id !== 'string') throw new Error('Invalid notice');
      this.store.dismiss(id); this.publish();
    });
    ipcMain.handle('notices:open', async (event, id: unknown) => {
      child(event);
      if (typeof id !== 'string') throw new Error('Invalid notice');
      await this.refresh();
      const notice = this.snapshot().notices.find(item => item.id === id);
      if (!notice) return;
      await this.openNotice(notice);
      this.store.dismiss(id); this.publish();
    });
    ipcMain.on('notices:resize', (event, height: unknown, width: unknown, folded: unknown) => {
      child(event);
      if (typeof height !== 'number' || !Number.isFinite(height) || height < 0
        || typeof width !== 'number' || !Number.isFinite(width) || width <= 0 || typeof folded !== 'boolean') return;
      this.height = Math.ceil(height); this.width = Math.ceil(width); this.folded = folded; this.layout();
    });
    ipcMain.handle('notices:source', (event, key: unknown, source: unknown) => {
      const window = this.window();
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Unknown notice owner');
      if (typeof key !== 'string' || key.length > 200) throw new Error('Invalid notice source');
      if (source === null) this.sources.delete(key);
      else {
        if (typeof source !== 'object') throw new Error('Invalid notice source');
        const value = source as Record<string, unknown>;
        if (typeof value.taskId !== 'string' || value.taskId.length > 200
          || (value.turnId !== null && (typeof value.turnId !== 'string' || value.turnId.length > 200))) throw new Error('Invalid notice source');
        this.sources.set(key, { taskId: value.taskId, turnId: value.turnId as string | null });
      }
      this.publish();
    });
  }

  start(): void {
    if (this.timer !== undefined) return;
    void this.refresh();
    this.timer = setInterval(() => { void this.refresh(); }, 5000);
    this.timer.unref();
  }

  attach(window: BrowserWindow): void {
    window.on('resize', () => this.publish());
    window.on('focus', () => { this.publish(); void this.refresh(); });
    window.on('blur', () => this.publish());
    window.on('hide', () => this.publish());
    window.on('show', () => { this.publish(); void this.refresh(); });
    window.on('minimize', () => this.publish());
    window.on('restore', () => this.publish());
    window.webContents.on('did-start-loading', () => { this.sources.clear(); this.obscured = false; this.publish(); });
    this.publish();
  }

  refresh(): Promise<void> {
    if (this.reading !== null) return this.reading;
    this.reading = this.read().then(value => { this.store.update(parseNotices(value)); this.publish(); })
      .catch(() => { /* A failed local read preserves the last snapshot and never announces task failure. */ })
      .finally(() => { this.reading = null; });
    return this.reading;
  }

  private snapshot(): NoticeSnapshot {
    const window = this.window();
    const zoom = this.view?.webContents.getZoomFactor() ?? 1;
    return this.store.snapshot(Boolean(!this.obscured && window && !window.isDestroyed() && window.isVisible() && !window.isMinimized() && window.isFocused()),
      window && !window.isDestroyed() ? Math.max(180, (window.getContentBounds().height - 64) / zoom) : 600);
  }

  private publish(): void {
    const window = this.window();
    if (!window || window.isDestroyed()) return;
    const state = this.snapshot();
    if (state.active) {
      for (const notice of state.notices) {
        if (notice.kind !== 'MAIL' && [...this.sources.values()].some(source => source.taskId === notice.taskId && (notice.turnId === null || source.turnId === notice.turnId))) {
          this.store.dismiss(notice.id);
        }
      }
      state.popupIds = this.snapshot().popupIds;
    }
    if (this.view === null) this.create(window);
    this.view?.webContents.send('notices:changed', state);
    this.layout();
  }

  private create(window: BrowserWindow): void {
    const view = new WebContentsView({ webPreferences: {
      preload: path.join(__dirname, 'noticePreload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, backgroundThrottling: false,
    } });
    this.view = view;
    view.setBackgroundColor('#00000000');
    view.setVisible(false);
    window.contentView.addChildView(view);
    view.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    view.webContents.on('will-navigate', event => event.preventDefault());
    view.webContents.on('did-finish-load', () => this.publish());
    const loaded = MAIN_WINDOW_VITE_DEV_SERVER_URL
      ? view.webContents.loadURL(new URL('notices.html', MAIN_WINDOW_VITE_DEV_SERVER_URL.replace('://localhost', '://127.0.0.1')).href)
      : view.webContents.loadFile(path.join(__dirname, `../renderer/${MAIN_WINDOW_VITE_NAME}/notices.html`));
    void loaded.catch(error => console.warn('Unable to display app notices', error));
  }

  /** Called after a browser/editor is attached, since native view order is independent of CSS. */
  raise(): void {
    const window = this.window();
    if (window && !window.isDestroyed() && this.view && !this.view.webContents.isDestroyed()
      && window.contentView.children.at(-1) !== this.view) window.contentView.addChildView(this.view);
  }

  private layout(): void {
    const window = this.window();
    if (!window || window.isDestroyed() || !this.view || this.view.webContents.isDestroyed()) return;
    const { width, height } = window.getContentBounds();
    // Renderer measurements are CSS pixels; native bounds are DIPs, including when the app is zoomed.
    const noticeZoom = this.view.webContents.getZoomFactor();
    const w = Math.min(Math.max(1, width - 16), Math.ceil(Math.min(NOTICE_WIDTH, this.width) * noticeZoom));
    const h = this.snapshot().notices.length ? Math.min(Math.max(0, height - 64), Math.ceil(this.height * noticeZoom)) : 0;
    // Bootstrap a one-pixel viewport so Chromium can measure before the first card resize.
    const viewportHeight = Math.max(1, h);
    const right = this.folded ? 0 : 8;
    this.view.setBounds({ x: width - w - right, y: height - viewportHeight - 8, width: w, height: viewportHeight });
    this.view.setVisible(h > 0 && !this.obscured);
    const zoom = window.webContents.getZoomFactor();
    window.webContents.send('notices:bounds', h > 0 && !this.obscured
      ? { x: (width - w - right) / zoom, y: (height - h - 8) / zoom, width: w / zoom, height: h / zoom } : null);
    this.raise();
  }

  releaseWindow(): void {
    if (this.view && !this.view.webContents.isDestroyed()) this.view.webContents.close();
    this.view = null; this.height = 0; this.width = NOTICE_WIDTH; this.folded = false; this.obscured = false; this.sources.clear();
  }

  stop(): void { clearInterval(this.timer); this.timer = undefined; this.releaseWindow(); }
}
