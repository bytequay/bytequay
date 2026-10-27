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
import { BrowserWindow, globalShortcut, ipcMain, screen, type IpcMainEvent, type WebContents } from 'electron';
import path from 'node:path';
import type { QuickNavigation } from './QuickAsk';

/** ⌥Space from any app; it stays off while another app, such as another assistant, holds the key. */
export const QUICK_ASK_SHORTCUT = 'Alt+Space';
// The Assistant's 640px float, with room for its shadow.
const WIDTH = 720;

/**
 * The Assistant panel above every app, Space and full-screen window, opened by a system-wide shortcut. It is the app's own page with
 * the app's bridge; what it opens shows in the main window.
 */
export class QuickAsk {
  private window: BrowserWindow | null = null;

  constructor(private readonly navigate: (target: QuickNavigation) => Promise<void>) {}

  register(): void {
    const own = (event: IpcMainEvent): BrowserWindow => {
      const window = this.window;
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Unknown floating Assistant');
      return window;
    };
    ipcMain.on('quick:close', event => own(event).hide());
    ipcMain.on('quick:resize', (event, height: unknown) => {
      const window = own(event);
      if (typeof height !== 'number' || !Number.isFinite(height) || height <= 0) return;
      window.setBounds({ ...window.getBounds(), height: Math.ceil(height) });
    });
    ipcMain.on('quick:navigate', (event, target: unknown) => {
      const window = own(event);
      const valid = typeof target === 'object' && target !== null && (
        'hash' in target && typeof target.hash === 'string' && target.hash.startsWith('#') && target.hash.length < 2048
        || 'zoom' in target && typeof target.zoom === 'object' && target.zoom !== null);
      if (!valid) return;
      window.hide();
      void this.navigate(target as QuickNavigation).catch(error => console.warn('Unable to open from the floating Assistant', error));
    });
    if (!globalShortcut.register(QUICK_ASK_SHORTCUT, () => this.toggle())) console.warn(`Another app holds ${QUICK_ASK_SHORTCUT}; the floating Assistant is off`);
  }

  /** Its page, which reads and opens notices like the main window's Assistant. */
  contents(): WebContents | null {
    return this.window && !this.window.isDestroyed() ? this.window.webContents : null;
  }

  /** Its window stays transparent when the theme changes. */
  owns(window: BrowserWindow): boolean {
    return window === this.window;
  }

  private toggle(): void {
    const window = this.window;
    if (window?.isVisible()) window.hide();
    else if (window) this.show(window);
    else {
      const created = this.create();
      created.once('ready-to-show', () => this.show(created));
    }
  }

  // A panel takes the keys without making ByteQuay the active app, so closing it hands them back to the app the user was in.
  private show(window: BrowserWindow): void {
    const { workArea } = screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
    window.setBounds({ x: Math.round(workArea.x + (workArea.width - WIDTH) / 2), y: Math.round(workArea.y + workArea.height / 8), width: WIDTH });
    window.showInactive();
    window.focus();
  }

  // Made on first use and kept, so a draft and the conversation survive closing it.
  private create(): BrowserWindow {
    const window = new BrowserWindow({
      type: 'panel', width: WIDTH, height: 200, show: false, frame: false, transparent: true, hasShadow: false, alwaysOnTop: true,
      resizable: false, minimizable: false, maximizable: false, fullscreenable: false, skipTaskbar: true,
      webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
    });
    this.window = window;
    window.on('blur', () => window.hide());
    window.on('closed', () => { this.window = null; });
    // A web link in a reply opens in the main window's browser, as it would from the app.
    const open = (url: string) => {
      if (!/^https?:/i.test(url)) return;
      window.hide();
      void this.navigate({ url }).catch(error => console.warn('Unable to open from the floating Assistant', error));
    };
    window.webContents.setWindowOpenHandler(({ url }) => { open(url); return { action: 'deny' }; });
    window.webContents.on('will-navigate', (event, url) => { event.preventDefault(); open(url); });
    const loaded = MAIN_WINDOW_VITE_DEV_SERVER_URL
      ? window.loadURL(new URL('quick.html', MAIN_WINDOW_VITE_DEV_SERVER_URL.replace('://localhost', '://127.0.0.1')).href)
      : window.loadFile(path.join(__dirname, `../renderer/${MAIN_WINDOW_VITE_NAME}/quick.html`));
    void loaded.catch(error => console.warn('Unable to display the floating Assistant', error));
    return window;
  }
}
