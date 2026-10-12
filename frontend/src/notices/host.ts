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
import { parseNotices, type AppNotice, type NoticeSnapshot } from './contracts';
import { NoticeStore } from './store';

/** One trusted app surface above native browser/editor views; its bridge cannot execute task actions. */
export class NoticeHost {
  private readonly store = new NoticeStore();
  private view: WebContentsView | null = null;
  private timer: ReturnType<typeof setInterval> | undefined;
  private reading: Promise<void> | null = null;
  private height = 0;
  private width = 420;
  private sources = new Map<string, { taskId: string; turnId: string | null }>();

  constructor(private readonly window: () => BrowserWindow | null, private readonly read: () => Promise<unknown>,
    private readonly openNotice: (notice: AppNotice) => Promise<void>) {}

  register(): void {
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
    ipcMain.on('notices:resize', (event, height: unknown, width: unknown) => {
      child(event);
      if (typeof height !== 'number' || !Number.isFinite(height) || height < 0
        || typeof width !== 'number' || !Number.isFinite(width) || width <= 0) return;
      this.height = Math.ceil(height); this.width = Math.ceil(width); this.layout();
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
    window.webContents.on('did-start-loading', () => { this.sources.clear(); this.publish(); });
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
    return this.store.snapshot(Boolean(window && !window.isDestroyed() && window.isVisible() && !window.isMinimized() && window.isFocused()),
      window && !window.isDestroyed() ? Math.max(180, window.getContentBounds().height - 56) : 600);
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
    const w = Math.min(width, 420, this.width);
    const h = this.snapshot().notices.length ? Math.min(Math.max(0, height - 56), this.height) : 0;
    // Bootstrap a one-pixel viewport so Chromium can measure before the first card resize.
    const viewportHeight = Math.max(1, h);
    this.view.setBounds({ x: width - w, y: height - viewportHeight, width: w, height: viewportHeight });
    this.view.setVisible(h > 0);
    const zoom = window.webContents.getZoomFactor();
    window.webContents.send('notices:bounds', h > 0 ? { x: (width - w) / zoom, y: (height - h) / zoom, width: w / zoom, height: h / zoom } : null);
    this.raise();
  }

  releaseWindow(): void {
    if (this.view && !this.view.webContents.isDestroyed()) this.view.webContents.close();
    this.view = null; this.height = 0; this.width = 420; this.sources.clear();
  }

  stop(): void { clearInterval(this.timer); this.timer = undefined; this.releaseWindow(); }
}
