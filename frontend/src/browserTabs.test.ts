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
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { BrowserWindow } from 'electron';
import type { EventEmitter } from 'node:events';
import { BrowserTabs, browserBounds, registerBrowserTabIpc } from './browserTabs';

const electron = vi.hoisted(() => ({ views: [] as unknown[], handlers: new Map<string, (...args: unknown[]) => unknown>() }));
vi.mock('electron', async () => {
  const { EventEmitter } = await import('node:events');
  return {
    ipcMain: { handle: (channel: string, handler: (...args: unknown[]) => unknown) => electron.handlers.set(channel, handler) },
    WebContentsView: class {
      webContents = Object.assign(new EventEmitter(), {
        url: '', title: '', destroyed: false, formValue: '', scrollY: 0,
        isDestroyed: () => this.webContents.destroyed,
        getURL: () => this.webContents.url,
        getTitle: () => this.webContents.title,
        isLoadingMainFrame: () => false,
        navigationHistory: { canGoBack: () => true, canGoForward: () => true, goBack: vi.fn(), goForward: vi.fn() },
        loadURL: vi.fn(async (url: string) => {
          this.webContents.url = url;
          this.webContents.emit('did-start-navigation', {}, url, false, true);
          this.webContents.emit('did-navigate');
        }),
        close: vi.fn(() => { this.webContents.destroyed = true; this.webContents.emit('destroyed'); }),
        reload: vi.fn(), focus: vi.fn(), setWindowOpenHandler: vi.fn(),
      });
      setVisible = vi.fn();
      setBounds = vi.fn();
      setBackgroundColor = vi.fn();
      constructor() { electron.views.push(this); }
    },
  };
});
type MockView = {
  webContents: EventEmitter & {
    url: string; title: string; formValue: string; scrollY: number;
    loadURL: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn>; reload: ReturnType<typeof vi.fn>;
    navigationHistory: { goBack: ReturnType<typeof vi.fn> };
  };
  setVisible: ReturnType<typeof vi.fn>; setBounds: ReturnType<typeof vi.fn>;
};
const view = (index: number) => electron.views[index] as MockView;
const bounds = { x: 48, y: 80, width: 800, height: 600 };
let directory: string;
let file: string;
let tabs: BrowserTabs;
let window: BrowserWindow;
let sender: { mainFrame: object; send: ReturnType<typeof vi.fn>; focus: ReturnType<typeof vi.fn>; getZoomFactor: ReturnType<typeof vi.fn>; isDestroyed: () => boolean };
let contentView: { addChildView: ReturnType<typeof vi.fn>; removeChildView: ReturnType<typeof vi.fn> };
beforeEach(() => {
  electron.views.length = 0;
  electron.handlers.clear();
  directory = fs.mkdtempSync(path.join(os.tmpdir(), 'bytequay-browser-test-'));
  file = path.join(directory, 'browser-tabs.json');
  sender = { mainFrame: {}, send: vi.fn(), focus: vi.fn(), getZoomFactor: vi.fn(() => 1), isDestroyed: () => false };
  contentView = { addChildView: vi.fn(), removeChildView: vi.fn() };
  window = { webContents: sender, contentView, isDestroyed: () => false, getContentBounds: () => ({ x: 0, y: 0, width: 1000, height: 800 }) } as unknown as BrowserWindow;
  tabs = new BrowserTabs(() => window, () => file);
});
afterEach(() => { tabs.releaseWindow(); fs.rmSync(directory, { recursive: true, force: true }); });
function open(url: string): string {
  tabs.open(url);
  return tabs.getState().activeTabId as string;
}

it('preserves each live page/history across switch, hide and renderer reload; ignores stale layout', () => {
  const first = open('https://example.com/one');
  tabs.mount(first, bounds);
  view(0).webContents.formValue = 'unsent work';
  view(0).webContents.scrollY = 240;
  const second = open('https://example.com/two');
  tabs.mount(second, bounds);
  tabs.unmount(first);
  tabs.mount(first, bounds);
  tabs.setBounds(first, { ...bounds, x: 0 });
  expect(contentView.addChildView).toHaveBeenCalledTimes(2);
  expect(contentView.addChildView).toHaveBeenLastCalledWith(view(1));
  tabs.select(first);
  tabs.mount(first, bounds);
  tabs.back(first);
  expect(view(0).webContents.navigationHistory.goBack).toHaveBeenCalledOnce();
  tabs.hide();
  expect(tabs.getState().activeTabId).toBeNull();
  tabs.select(first);
  tabs.mount(first, bounds);
  tabs.rendererReload();
  expect(tabs.getState().activeTabId).toBe(first);
  tabs.mount(first, bounds);
  expect(view(0).webContents.formValue).toBe('unsent work');
  expect(view(0).webContents.scrollY).toBe(240);
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  expect(view(0).webContents.close).not.toHaveBeenCalled();
  expect(view(1).webContents.close).not.toHaveBeenCalled();
  expect(sender.focus).toHaveBeenCalled();
});

it('retains pinned pages when opening links and closes only the requested view', () => {
  const first = open('https://example.com/one');
  tabs.pin(first, true);
  const second = open('https://example.com/two');
  expect(tabs.getState().tabs.map(tab => tab.url)).toEqual(['https://example.com/one', 'https://example.com/two']);
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  tabs.open('https://example.com/one');
  expect(tabs.getState().activeTabId).toBe(first);
  expect(electron.views).toHaveLength(2);
  tabs.close(first);
  expect(view(0).webContents.close).toHaveBeenCalledOnce();
  expect(view(1).webContents.close).not.toHaveBeenCalled();
  expect(tabs.getState().activeTabId).toBe(second);
  expect(JSON.parse(fs.readFileSync(file, 'utf8'))).toEqual([]);
  tabs.close(second);
  expect(tabs.getState()).toEqual({ tabs: [], activeTabId: null });
});

it('persists committed same-title URLs and restores only pins, lazily, with private file permissions', () => {
  const first = open('https://example.com/one');
  tabs.pin(first, true);
  view(0).webContents.title = 'Same title';
  view(0).webContents.emit('page-title-updated');
  view(0).webContents.emit('did-start-navigation', {}, 'https://example.com/#updated', true, true);
  view(0).webContents.emit('page-title-updated');
  expect(JSON.parse(fs.readFileSync(file, 'utf8'))[0].url).toBe('https://example.com/one');
  view(0).webContents.url = 'https://example.com/#updated';
  view(0).webContents.emit('did-navigate-in-page');
  view(0).webContents.emit('page-favicon-updated', {}, ['javascript:bad', 'https://example.com/favicon.ico']);
  expect(fs.statSync(file).mode & 0o777).toBe(0o600);
  open('https://example.com/temporary');
  tabs.releaseWindow();
  expect(view(0).webContents.close).toHaveBeenCalledOnce();
  expect(view(1).webContents.close).toHaveBeenCalledOnce();
  expect(tabs.getState()).toEqual({ activeTabId: null, tabs: [expect.objectContaining({
    id: first, pinned: true, url: 'https://example.com/#updated', title: 'Same title', favicon: 'https://example.com/favicon.ico', loading: false,
  })] });
  expect(electron.views).toHaveLength(2);
  tabs.select(first);
  expect(electron.views).toHaveLength(3);
  expect(view(2).webContents.loadURL).toHaveBeenCalledWith('https://example.com/#updated');
  tabs.pin(first, false);
  tabs.releaseWindow();
  expect(tabs.getState().tabs).toEqual([]);
});

it('reports failures/crashes per tab, detaches errors and clears the error when retrying', () => {
  const first = open('https://example.com/one');
  tabs.mount(first, bounds);
  view(0).webContents.emit('did-fail-load', {}, -3, 'ERR_ABORTED', '', true);
  view(0).webContents.emit('did-fail-load', {}, -105, 'ERR_NAME_NOT_RESOLVED', '', false);
  expect(tabs.getState().tabs[0].error).toBeNull();
  view(0).webContents.emit('did-fail-load', {}, -105, 'ERR_NAME_NOT_RESOLVED', 'https://example.com/one', true);
  expect(tabs.getState().tabs[0].error).toContain('ERR_NAME_NOT_RESOLVED');
  expect(view(0).setVisible).toHaveBeenLastCalledWith(false);
  tabs.reload(first);
  expect(tabs.getState().tabs[0].error).toBeNull();
  expect(view(0).setVisible).toHaveBeenLastCalledWith(true);
  expect(view(0).webContents.reload).toHaveBeenCalledOnce();
  const second = open('https://example.com/two');
  tabs.mount(second, bounds);
  view(0).webContents.emit('render-process-gone');
  expect(tabs.getState().activeTabId).toBe(second);
  expect(tabs.getState().tabs[0].error).toContain('stopped unexpectedly');
  expect(tabs.getState().tabs[1].error).toBeNull();
  expect(view(1).setVisible).toHaveBeenLastCalledWith(true);
});

it('skips unsafe and duplicate saved entries without loading pages', () => {
  fs.writeFileSync(file, JSON.stringify([
    { id: 'one', url: 'https://example.com', title: 'Page', favicon: 'javascript:alert(1)' },
    { id: 'one', url: 'https://other.example', title: 'Duplicate' },
    { id: 'two', url: 'file:///etc/passwd', title: 'Invalid' },
  ]));
  expect(tabs.getState().tabs).toEqual([expect.objectContaining({ id: 'one', favicon: null })]);
  expect(electron.views).toHaveLength(0);
});

it('requires the app main frame for every browser IPC and validates/clamps native bounds', () => {
  registerBrowserTabIpc(tabs, () => window);
  const id = open('https://example.com');
  for (const handler of electron.handlers.values()) {
    expect(() => handler({ sender: {}, senderFrame: sender.mainFrame }, id, bounds)).toThrow('only available from the app');
    expect(() => handler({ sender, senderFrame: {} }, id, bounds)).toThrow('only available from the app');
  }
  const good = { sender, senderFrame: sender.mainFrame };
  electron.handlers.get('inapp:mount')?.(good, id, bounds);
  expect(contentView.addChildView).toHaveBeenLastCalledWith(view(0));
  expect(electron.handlers.get('inapp:state')?.(good)).toEqual(tabs.getState());
  expect(() => browserBounds({ ...bounds, x: Infinity }, window)).toThrow('Invalid browser bounds');
  expect(() => browserBounds({ ...bounds, width: NaN }, window)).toThrow('Invalid browser bounds');
  expect(() => browserBounds({ ...bounds, x: '0' }, window)).toThrow('Invalid browser bounds');
  expect(browserBounds({ x: -10, y: 799.7, width: 10000, height: 10000 }, window)).toEqual({ x: 0, y: 800, width: 1000, height: 0 });
});

it('reports rejected loads but ignores an older request after newer navigation or close', async () => {
  const id = open('https://example.com/start');
  view(0).webContents.loadURL.mockRejectedValueOnce(new Error('Network disconnected'));
  await tabs.navigate(id, 'https://example.com/unreachable');
  expect(tabs.getState().tabs[0].error).toBe('Network disconnected');
  let rejectOld: (error: Error) => void = () => {};
  view(0).webContents.loadURL.mockImplementationOnce(() => new Promise<void>((_resolve, reject) => { rejectOld = reject; }));
  const old = tabs.navigate(id, 'https://example.com/slow');
  await tabs.navigate(id, 'https://example.com/new');
  rejectOld(new Error('Late failure'));
  await old;
  expect(tabs.getState().tabs[0]).toMatchObject({ url: 'https://example.com/new', error: null });
  view(0).webContents.loadURL.mockImplementationOnce(() => new Promise<void>((_resolve, reject) => { rejectOld = reject; }));
  const closing = tabs.navigate(id, 'https://example.com/closing');
  tabs.close(id);
  rejectOld(new Error('Closed during load'));
  await closing;
  expect(tabs.getState().tabs).toEqual([]);
});

it('recreates a destroyed page on reload without retaining its native view', () => {
  const id = open('https://example.com/start');
  tabs.mount(id, bounds);
  view(0).webContents.close();
  expect(tabs.getState().tabs[0].error).toContain('stopped unexpectedly');
  expect(contentView.removeChildView).toHaveBeenLastCalledWith(view(0));
  tabs.reload(id);
  expect(electron.views).toHaveLength(2);
  expect(contentView.addChildView).toHaveBeenLastCalledWith(view(1));
  expect(tabs.getState().tabs[0].error).toBeNull();
});


it.each([
  [0.8, { x: 39, y: 65, width: 511, height: 319 }],
  [1, { x: 49, y: 81, width: 639, height: 399 }],
  [1.25, { x: 61, y: 101, width: 799, height: 500 }],
] as const)('keeps native page bounds inside the CSS slot at renderer zoom %s without reloading', (zoom, expected) => {
  const slot = { x: 48.25, y: 80.5, width: 640.2, height: 400.4 };
  const id = open('https://example.com/page');
  sender.getZoomFactor.mockReturnValue(zoom);
  tabs.mount(id, slot);
  expect(view(0).setBounds).toHaveBeenLastCalledWith(expected);
  expect(expected.x).toBeGreaterThanOrEqual(slot.x * zoom);
  expect(expected.y).toBeGreaterThanOrEqual(slot.y * zoom);
  expect(expected.x + expected.width).toBeLessThanOrEqual((slot.x + slot.width) * zoom);
  expect(expected.y + expected.height).toBeLessThanOrEqual((slot.y + slot.height) * zoom);
  sender.getZoomFactor.mockReturnValue(1);
  tabs.setBounds(id, slot);
  expect(view(0).setBounds).toHaveBeenLastCalledWith({ x: 49, y: 81, width: 639, height: 399 });
  sender.getZoomFactor.mockReturnValue(zoom);
  tabs.setBounds(id, slot);
  expect(view(0).setBounds).toHaveBeenLastCalledWith(expected);
  expect(electron.views).toHaveLength(1);
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  expect(view(0).webContents.close).not.toHaveBeenCalled();
});
