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
const lab = vi.hoisted(() => ({ resolve: vi.fn() }));
vi.mock('@bytequay/lab/main', async importOriginal => ({
  ...await importOriginal<typeof import('@bytequay/lab/main')>(), resolveLabPage: lab.resolve,
}));
vi.mock('electron', async () => {
  const { EventEmitter } = await import('node:events');
  return {
    ipcMain: { handle: (channel: string, handler: (...args: unknown[]) => unknown) => electron.handlers.set(channel, handler) },
    WebContentsView: class {
      webContents = Object.assign(new EventEmitter(), {
        url: '', title: '', destroyed: false, formValue: '', scrollY: 0,
        session: { setPermissionCheckHandler: vi.fn(), setPermissionRequestHandler: vi.fn() },
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
    setWindowOpenHandler: ReturnType<typeof vi.fn>;
    session: { setPermissionCheckHandler: ReturnType<typeof vi.fn>; setPermissionRequestHandler: ReturnType<typeof vi.fn> };
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
  lab.resolve.mockReset().mockImplementation(async (url: string) => url);
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

it('denies sensitive permissions before loading an untrusted browser page', () => {
  open('https://example.com/');
  const contents = view(0).webContents;
  const check = contents.session.setPermissionCheckHandler.mock.calls[0][0];
  const request = contents.session.setPermissionRequestHandler.mock.calls[0][0];
  for (const permission of ['clipboard-read', 'notifications', 'geolocation', 'media', 'fileSystem', 'openExternal']) {
    expect(check(contents, permission, 'https://example.com', {})).toBe(false);
    const result = vi.fn();
    request(contents, permission, result, {});
    expect(result).toHaveBeenCalledOnce();
    expect(result).toHaveBeenCalledWith(false);
  }
  expect(contents.session.setPermissionRequestHandler.mock.invocationCallOrder[0]).toBeLessThan(contents.loadURL.mock.invocationCallOrder[0]);
});

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
  contentView.removeChildView.mockClear();
  tabs.rendererNavigated({ isSameDocument: true, isMainFrame: true });
  tabs.rendererNavigated({ isSameDocument: false, isMainFrame: false });
  expect(contentView.removeChildView).not.toHaveBeenCalled();
  tabs.rendererNavigated({ isSameDocument: false, isMainFrame: true });
  expect(contentView.removeChildView.mock.calls).toEqual([[view(0)]]);
  expect(tabs.getState().activeTabId).toBe(first);
  tabs.mount(first, bounds);
  expect(view(0).webContents.formValue).toBe('unsent work');
  expect(view(0).webContents.scrollY).toBe(240);
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  expect(view(0).webContents.close).not.toHaveBeenCalled();
  expect(view(1).webContents.close).not.toHaveBeenCalled();
  expect(sender.focus).toHaveBeenCalled();
});

it('minimizes pinned pages and fully closes them only after unpinning', () => {
  const first = open('https://example.com/one');
  tabs.pin(first, true);
  const second = open('https://example.com/two');
  expect(tabs.getState().tabs.map(tab => tab.url)).toEqual(['https://example.com/one', 'https://example.com/two']);
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  tabs.open('https://example.com/one');
  expect(tabs.getState().activeTabId).toBe(first);
  expect(electron.views).toHaveLength(2);
  tabs.mount(first, bounds);
  view(0).webContents.formValue = 'unsent work';
  tabs.close(first);
  expect(tabs.getState().activeTabId).toBeNull();
  expect(tabs.getState().tabs[0]).toMatchObject({ id: first, pinned: true });
  expect(JSON.parse(fs.readFileSync(file, 'utf8'))[0].id).toBe(first);
  expect(view(0).setVisible).toHaveBeenLastCalledWith(false);
  expect(view(0).webContents.close).not.toHaveBeenCalled();
  tabs.select(second);
  tabs.mount(second, bounds);
  tabs.close(first);
  expect(tabs.getState().activeTabId).toBe(second);
  expect(view(1).setVisible).toHaveBeenLastCalledWith(true);
  tabs.select(first);
  tabs.mount(first, bounds);
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  expect(view(0).webContents.formValue).toBe('unsent work');
  tabs.pin(first, false);
  expect(view(0).webContents.close).not.toHaveBeenCalled();
  tabs.close(first);
  expect(view(0).webContents.close).toHaveBeenCalledOnce();
  expect(view(1).webContents.close).not.toHaveBeenCalled();
  expect(tabs.getState().activeTabId).toBeNull();
  expect(JSON.parse(fs.readFileSync(file, 'utf8'))).toEqual([]);
  tabs.close(second);
  expect(tabs.getState()).toEqual({ tabs: [], activeTabId: null });
});

it('returns to the app when the open page closes, leaving other pages alone', () => {
  const pinned = open('https://slack.example/client/T1');
  tabs.pin(pinned, true);
  const opened = open('https://github.example/pull/30682');
  tabs.mount(opened, bounds);
  tabs.close(opened);
  expect(tabs.getState()).toMatchObject({ activeTabId: null, tabs: [{ id: pinned }] });
  expect(view(1).webContents.close).toHaveBeenCalledOnce();
  expect(contentView.addChildView).toHaveBeenCalledOnce();
});

it('restores a minimized pin in a new app instance until explicitly unpinned', () => {
  const id = open('https://example.com/pinned');
  tabs.pin(id, true);
  tabs.close(id);
  tabs.releaseWindow();
  tabs = new BrowserTabs(() => window, () => file);
  expect(tabs.getState()).toEqual({ activeTabId: null, tabs: [expect.objectContaining({ id, url: 'https://example.com/pinned', pinned: true })] });
  expect(electron.views).toHaveLength(1);
  tabs.select(id);
  expect(view(1).webContents.loadURL).toHaveBeenCalledWith('https://example.com/pinned');
  tabs.pin(id, false);
  tabs.close(id);
  expect(view(1).webContents.close).toHaveBeenCalledOnce();
  tabs.releaseWindow();
  tabs = new BrowserTabs(() => window, () => file);
  expect(tabs.getState()).toEqual({ activeTabId: null, tabs: [] });
});

it('starts the current dashboard when a saved Lab pin is selected and keeps its page and name', async () => {
  const old = 'http://127.0.0.1:45000/lab-01234567-89ab-cdef-0123-456789abcdef/Resources';
  const current = 'http://127.0.0.1:45001/lab-11111111-2222-3333-4444-555555555555/Resources';
  const id = open(old);
  await vi.waitFor(() => expect(view(0).webContents.loadURL).toHaveBeenCalledWith(old));
  tabs.pin(id, true);
  tabs.rename(id, 'App health');
  tabs.releaseWindow();
  tabs = new BrowserTabs(() => window, () => file);
  lab.resolve.mockReset().mockResolvedValue(current);
  expect(tabs.getState().tabs[0]).toMatchObject({ id, url: old, pinned: true, name: 'App health' });
  expect(lab.resolve).not.toHaveBeenCalled();
  tabs.select(id);
  await vi.waitFor(() => expect(view(1).webContents.loadURL).toHaveBeenCalledWith(current));
  expect(lab.resolve).toHaveBeenCalledWith(old);
  expect(view(1).webContents.loadURL).not.toHaveBeenCalledWith(old);
  expect(tabs.getState().tabs).toEqual([expect.objectContaining({ id, url: current, pinned: true, name: 'App health' })]);
  expect(JSON.parse(fs.readFileSync(file, 'utf8'))[0].url).toBe(current);
  tabs.reload(id);
  await vi.waitFor(() => expect(view(1).webContents.loadURL).toHaveBeenCalledTimes(2));
  expect(lab.resolve).toHaveBeenLastCalledWith(current);
});

it('reports Lab startup failure on its pin and lets reload retry', async () => {
  const url = 'http://127.0.0.1:45000/lab-01234567-89ab-cdef-0123-456789abcdef/';
  lab.resolve.mockRejectedValueOnce(new Error('Could not refresh Lab data.'));
  const id = open(url);
  await vi.waitFor(() => expect(tabs.getState().tabs[0].error).toBe('Could not refresh Lab data.'));
  expect(view(0).webContents.loadURL).not.toHaveBeenCalled();
  tabs.reload(id);
  await vi.waitFor(() => expect(view(0).webContents.loadURL).toHaveBeenCalledWith(url));
  expect(tabs.getState().tabs[0].error).toBeNull();
});

it('does not load a Lab page after its tab is closed during startup', async () => {
  const url = 'http://127.0.0.1:45000/lab-01234567-89ab-cdef-0123-456789abcdef/';
  let ready: (url: string) => void = () => {};
  lab.resolve.mockImplementationOnce(() => new Promise<string>(resolve => { ready = resolve; }));
  const id = open(url);
  tabs.close(id);
  ready(url);
  await Promise.resolve();
  expect(view(0).webContents.loadURL).not.toHaveBeenCalled();
});

it('opens Cmd/middle-click links in distinct background tabs without replacing or blurring the current page', () => {
  const first = open('https://example.com/source');
  tabs.mount(first, bounds);
  view(0).webContents.formValue = 'unsent draft';
  view(0).webContents.scrollY = 240;
  const request = view(0).webContents.setWindowOpenHandler.mock.calls[0][0];
  for (let i = 0; i < 2; i++) {
    expect(request({ url: 'https://example.com/linked', disposition: 'background-tab' })).toEqual({ action: 'deny' });
  }
  const state = tabs.getState();
  expect(state.activeTabId).toBe(first);
  expect(state.tabs.map(tab => tab.url)).toEqual(['https://example.com/source', 'https://example.com/linked', 'https://example.com/linked']);
  expect(new Set(state.tabs.map(tab => tab.id)).size).toBe(3);
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  expect(view(0).webContents.formValue).toBe('unsent draft');
  expect(view(0).webContents.scrollY).toBe(240);
  expect(contentView.removeChildView).not.toHaveBeenCalled();
  expect(sender.focus).not.toHaveBeenCalled();
  expect(view(1).webContents.loadURL).toHaveBeenCalledWith('https://example.com/linked');
  expect(view(1).setVisible).toHaveBeenLastCalledWith(false);
  tabs.select(state.tabs[1].id);
  tabs.mount(state.tabs[1].id, bounds);
  expect(contentView.addChildView).toHaveBeenLastCalledWith(view(1));
  expect(view(1).webContents.loadURL).toHaveBeenCalledOnce();
});

it('selects a new foreground tab for Cmd-Shift-click without navigating the original', () => {
  const first = open('https://example.com/source');
  tabs.mount(first, bounds);
  const request = view(0).webContents.setWindowOpenHandler.mock.calls[0][0];
  expect(request({ url: 'https://example.com/linked', disposition: 'foreground-tab' })).toEqual({ action: 'deny' });
  const state = tabs.getState();
  expect(state.activeTabId).toBe(state.tabs[1].id);
  expect(state.tabs[0]).toMatchObject({ id: first, url: 'https://example.com/source' });
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  expect(view(0).webContents.close).not.toHaveBeenCalled();
  tabs.mount(state.tabs[1].id, bounds);
  expect(contentView.addChildView).toHaveBeenLastCalledWith(view(1));
});

it('keeps popup navigation in its existing tab and denies unsafe or closed-page tab requests', () => {
  const first = open('https://example.com/source');
  const request = view(0).webContents.setWindowOpenHandler.mock.calls[0][0];
  request({ url: 'https://example.com/sign-in', disposition: 'new-window' });
  expect(tabs.getState().tabs).toEqual([expect.objectContaining({ id: first, url: 'https://example.com/sign-in' })]);
  for (const url of ['file:///etc/passwd', 'https://user:password@example.com/']) {
    expect(request({ url, disposition: 'background-tab' })).toEqual({ action: 'deny' });
  }
  expect(tabs.getState().tabs).toHaveLength(1);
  tabs.close(first);
  expect(request({ url: 'https://example.com/linked', disposition: 'foreground-tab' })).toEqual({ action: 'deny' });
  expect(tabs.getState().tabs).toHaveLength(0);
});

it('hands a link it claims to the app without opening, loading or replacing a page', () => {
  tabs.releaseWindow();
  const task = 'http://127.0.0.1:49152/lab-launch/task?repository=o%2Fn&text=t';
  const claim = vi.fn((url: string) => url === task);
  tabs = new BrowserTabs(() => window, () => file, () => {}, claim);
  const first = open('http://127.0.0.1:49152/lab-launch/');
  const request = view(0).webContents.setWindowOpenHandler.mock.calls[0][0];
  for (const disposition of ['foreground-tab', 'background-tab', 'new-window']) expect(request({ url: task, disposition })).toEqual({ action: 'deny' });
  expect(claim).toHaveBeenCalledTimes(3);
  expect(tabs.getState()).toEqual({ activeTabId: first, tabs: [expect.objectContaining({ id: first, url: 'http://127.0.0.1:49152/lab-launch/' })] });
  expect(view(0).webContents.loadURL).toHaveBeenCalledOnce();
  expect(request({ url: 'file:///etc/passwd', disposition: 'foreground-tab' })).toEqual({ action: 'deny' });
  expect(claim).toHaveBeenCalledTimes(3);
  request({ url: 'https://example.com/linked', disposition: 'foreground-tab' });
  expect(tabs.getState().tabs.map(tab => tab.url)).toEqual(['http://127.0.0.1:49152/lab-launch/', 'https://example.com/linked']);
});

it('shows the site icon for pages that declare none, until the page declares one', () => {
  open('https://slack.example/client/T1');
  expect(tabs.getState().tabs[0].favicon).toBe('https://slack.example/favicon.ico');
  view(0).webContents.emit('page-favicon-updated', {}, ['https://cdn.example/icon.png']);
  expect(tabs.getState().tabs[0].favicon).toBe('https://cdn.example/icon.png');
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

it('keeps a renamed page named while its own title changes, and restores the name', () => {
  const id = open('https://slack.example/client/T1');
  tabs.pin(id, true);
  tabs.rename(id, '  Slack dev  ');
  view(0).webContents.title = 'dev (Channel) - apache';
  view(0).webContents.emit('page-title-updated');
  expect(tabs.getState().tabs[0]).toMatchObject({ name: 'Slack dev', title: 'dev (Channel) - apache' });
  expect(() => tabs.rename(id, 7)).toThrow('Invalid page name');
  tabs.releaseWindow();
  tabs = new BrowserTabs(() => window, () => file);
  expect(tabs.getState().tabs[0]).toMatchObject({ name: 'Slack dev', title: 'dev (Channel) - apache' });
  tabs.rename(id, '   ');
  expect(tabs.getState().tabs[0].name).toBeNull();
  expect(JSON.parse(fs.readFileSync(file, 'utf8'))[0].name).toBeNull();
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
