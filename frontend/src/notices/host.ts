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
import { app, ipcMain, Notification, shell, WebContentsView, type BrowserWindow, type IpcMainEvent, type IpcMainInvokeEvent } from 'electron';
import path from 'node:path';
import { APPROVAL_NOTICE, NOTICE_WIDTH, needsAction, noticeLabel, parseNotices, type AppNotice, type NoticeSnapshot } from './contracts';
import { NoticeStore } from './store';

/** One trusted app surface above native browser/editor views; its bridge cannot execute task actions. */
export class NoticeHost {
  private readonly store: NoticeStore;
  private view: WebContentsView | null = null;
  private timer: ReturnType<typeof setInterval> | undefined;
  private reading: Promise<void> | null = null;
  private height = 0;
  private obscured = false;
  private suppressed = false;
  private mainNotices = '';
  private sources = new Map<string, string>();
  // Kept until the alert leaves, so a click on its macOS notification still has a handler; null when none was shown.
  private readonly announced = new Map<string, Notification | null>();
  private nativeDisabled = false;
  private badge = -1;
  private jumped: string | null = null;

  constructor(private readonly window: () => BrowserWindow | null, private readonly read: () => Promise<unknown>,
    private readonly openNotice: (notice: AppNotice) => Promise<void>, storagePath: () => string,
    private readonly reveal: () => Promise<void>) {
    this.store = new NoticeStore(storagePath);
  }

  register(): void {
    const owner = (event: IpcMainInvokeEvent | IpcMainEvent): boolean => {
      const window = this.window();
      return Boolean(window && event.sender === window.webContents && event.senderFrame === window.webContents.mainFrame);
    };
    ipcMain.on('notices:obscured', (event, obscured: unknown) => {
      if (!owner(event)) throw new Error('Unknown notice owner');
      if (typeof obscured !== 'boolean') return;
      this.obscured = obscured; this.publish();
    });
    // Not `obscured`: that also pauses auto-dismiss and viewed-marking, while the open Assistant panel lists every notice.
    ipcMain.on('notices:suppress', (event, suppressed: unknown) => {
      if (!owner(event)) throw new Error('Unknown notice owner');
      if (typeof suppressed !== 'boolean') return;
      this.suppressed = suppressed; this.publish();
    });
    const child = (event: IpcMainInvokeEvent | IpcMainEvent): void => {
      if (!this.view || event.sender !== this.view.webContents || event.senderFrame !== event.sender.mainFrame) throw new Error('Unknown notice surface');
    };
    // The Assistant panel lists the same notices, so the main window may read and open them too.
    const ownerOrChild = (event: IpcMainInvokeEvent): void => { if (!owner(event)) child(event); };
    ipcMain.handle('notices:read', event => { ownerOrChild(event); return this.snapshot(); });
    ipcMain.handle('notices:dismiss', (event, id: unknown, seen: unknown) => {
      child(event);
      if (typeof id !== 'string' || typeof seen !== 'boolean') throw new Error('Invalid notice');
      if (seen) this.store.markViewed(id);
      else this.store.dismiss(id);
      this.publish();
    });
    // Only the panel clears a notice for good; an alert's × just closes the alert.
    ipcMain.handle('notices:remove', (event, id: unknown) => {
      if (!owner(event)) throw new Error('Unknown notice owner');
      if (typeof id !== 'string') throw new Error('Invalid notice');
      this.store.remove(id); this.publish();
    });
    ipcMain.handle('notices:open', async (event, id: unknown) => {
      ownerOrChild(event);
      if (typeof id !== 'string') throw new Error('Invalid notice');
      await this.open(id, !owner(event));
    });
    ipcMain.on('notices:resize', (event, height: unknown) => {
      child(event);
      if (typeof height !== 'number' || !Number.isFinite(height) || height < 0) return;
      this.height = Math.ceil(height); this.layout();
    });
    ipcMain.handle('notices:source', (event, key: unknown, source: unknown) => {
      if (!owner(event)) throw new Error('Unknown notice owner');
      if (typeof key !== 'string' || key.length > 200) throw new Error('Invalid notice source');
      if (source === null) this.sources.delete(key);
      else {
        if (typeof source !== 'string' || !source.trim() || source.length > 200) throw new Error('Invalid notice source');
        this.sources.set(key, source);
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
    window.webContents.on('did-start-navigation', ({ isSameDocument, isMainFrame }) => {
      // Hash navigation keeps React mounted, so visible tasks will not register again.
      if (isSameDocument || !isMainFrame) return;
      this.sources.clear(); this.obscured = false; this.suppressed = false; this.mainNotices = ''; this.publish();
    });
    this.publish();
  }

  refresh(): Promise<void> {
    if (this.reading !== null) return this.reading;
    this.reading = this.read().then(value => { this.store.update(parseNotices(value)); this.publish(); this.announce(); })
      .catch(() => { /* A failed local read preserves the last snapshot and never announces task failure. */ })
      .finally(() => { this.reading = null; });
    return this.reading;
  }

  /** ⇧⌘J: opens the task that has waited longest on the user, then the next one on each press. */
  async next(): Promise<void> {
    await this.refresh();
    const waiting = this.snapshot().notices.filter(needsAction)
      .sort((a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt) || a.id.localeCompare(b.id));
    if (!waiting.length) { shell.beep(); return; }
    const notice = waiting[(waiting.findIndex(item => item.id === this.jumped) + 1) % waiting.length];
    this.jumped = notice.id;
    await this.open(notice.id, false);
  }

  private async open(id: string, fromAlert: boolean): Promise<void> {
    await this.refresh();
    const notice = this.snapshot().notices.find(item => item.id === id);
    if (!notice) return;
    const window = this.window();
    // An alert's Answer opens the panel at the card, whose own buttons answer it; the panel's Open still opens the task.
    if (fromAlert && notice.id.startsWith(APPROVAL_NOTICE) && window && !window.isDestroyed()) {
      window.webContents.focus();
      window.webContents.send('assistant:open-card', notice.id);
    }
    else await this.openNotice(notice);
    if (notice.kind === 'TURN_FINISHED') this.store.remove(id);
    else this.store.markViewed(id);
    this.publish();
  }

  /** A new alert also goes to macOS while the user is in another app; it closes once the in-app alert is answered or closed. */
  private announce(): void {
    const { notices, popupIds } = this.store.snapshot(false);
    for (const [id, shown] of this.announced) {
      if (!popupIds.includes(id)) { shown?.close(); this.announced.delete(id); }
    }
    const window = this.window();
    const away = !window || window.isDestroyed() || !window.isFocused();
    for (const notice of notices) {
      if (popupIds.includes(notice.id) && !this.announced.has(notice.id)) this.announced.set(notice.id, away ? this.notify(notice) : null);
    }
  }

  private notify(notice: AppNotice): Notification | null {
    // Platform support does not imply permission: unsigned macOS builds also pass isSupported() and then fail.
    if (this.nativeDisabled || !Notification.isSupported()) return null;
    try {
      const shown = new Notification({ title: `${noticeLabel(notice)} · ${notice.repository}`, body: notice.title });
      shown.on('click', () => {
        // ponytail: a reopened window's page may not listen yet, so then the click only brings ByteQuay back and keeps the alert.
        const window = this.window();
        const ready = Boolean(window && !window.isDestroyed());
        void this.reveal().then(() => ready ? this.open(notice.id, true) : undefined).catch(error => console.warn('Unable to open a task notice', error));
      });
      shown.on('failed', (_event, error) => this.disableNative(error));
      shown.show();
      return shown;
    }
    catch (error) {
      this.disableNative(error);
      return null;
    }
  }

  private disableNative(error: unknown): void {
    if (this.nativeDisabled) return;
    this.nativeDisabled = true;
    console.warn('macOS task notifications paused until restart; ByteQuay still shows them. Use a signed build and allow notifications in System Settings.', error);
  }

  private snapshot(): NoticeSnapshot {
    const window = this.window();
    const zoom = this.view?.webContents.getZoomFactor() ?? 1;
    return this.store.snapshot(Boolean(!this.obscured && window && !window.isDestroyed() && window.isVisible() && !window.isMinimized() && window.isFocused()),
      window && !window.isDestroyed() ? Math.max(180, (window.getContentBounds().height - 64) / zoom) : 600);
  }

  private publish(): void {
    // The Dock counts what the sidebar's Assistant row counts, with or without a window.
    const waiting = this.store.snapshot(false).notices.filter(needsAction).length;
    if (waiting !== this.badge) { this.badge = waiting; app.setBadgeCount(waiting); }
    const window = this.window();
    if (!window || window.isDestroyed()) return;
    let state = this.snapshot();
    if (state.active || this.suppressed) {
      for (const notice of state.notices) {
        // The open panel shows every notice, so none pops up after it closes.
        if (this.suppressed || [...this.sources.values()].includes(notice.taskId)) {
          this.store.markViewed(notice.id);
        }
      }
      state = this.snapshot();
    }
    if (this.view === null) this.create(window);
    this.view?.webContents.send('notices:changed', state);
    // Window events publish often; the panel and the sidebar's unread dot need only a changed list or viewed set.
    const notices = JSON.stringify([state.notices, state.viewedIds]);
    if (notices !== this.mainNotices) { this.mainNotices = notices; window.webContents.send('notices:changed', state); }
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
    const w = Math.min(Math.max(1, width - 16), Math.ceil(NOTICE_WIDTH * noticeZoom));
    const h = this.snapshot().notices.length ? Math.min(Math.max(0, height - 64), Math.ceil(this.height * noticeZoom)) : 0;
    // Bootstrap a one-pixel viewport so Chromium can measure before the first card resize.
    const viewportHeight = Math.max(1, h);
    this.view.setBounds({ x: width - w - 8, y: height - viewportHeight - 8, width: w, height: viewportHeight });
    this.view.setVisible(h > 0 && !this.obscured && !this.suppressed);
    this.raise();
  }

  releaseWindow(): void {
    if (this.view && !this.view.webContents.isDestroyed()) this.view.webContents.close();
    this.view = null; this.height = 0; this.obscured = false; this.suppressed = false; this.mainNotices = '';
    this.sources.clear();
  }

  stop(): void {
    clearInterval(this.timer); this.timer = undefined; this.releaseWindow();
    for (const shown of this.announced.values()) shown?.close();
    this.announced.clear();
  }
}
