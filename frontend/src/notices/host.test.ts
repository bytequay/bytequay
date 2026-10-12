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
import { EventEmitter } from 'node:events';
import type { BrowserWindow } from 'electron';
import { NoticeHost } from './host';
import type { AppNotice, NoticeSnapshot } from './contracts';

const desktop = vi.hoisted(() => ({ handlers: new Map<string, (...args: unknown[]) => unknown>(), views: [] as unknown[] }));
vi.mock('electron', async () => {
  const { EventEmitter } = await import('node:events');
  return {
    ipcMain: { handle: (key: string, fn: (...args: unknown[]) => unknown) => desktop.handlers.set(key, fn), on: (key: string, fn: (...args: unknown[]) => unknown) => desktop.handlers.set(key, fn) },
    WebContentsView: class {
      webContents = Object.assign(new EventEmitter(), { mainFrame: {}, send: vi.fn(), close: vi.fn(), isDestroyed: () => false,
        setWindowOpenHandler: vi.fn(), loadURL: vi.fn(async () => {}), getZoomFactor: vi.fn(() => 1) });
      setBounds = vi.fn(); setVisible = vi.fn(); setBackgroundColor = vi.fn();
      constructor() { desktop.views.push(this); }
    },
  };
});
const notice: AppNotice = { id: 'approval:one', kind: 'APPROVAL', taskId: 'task', workspaceId: 'workspace', turnId: 'turn', title: 'Build',
  message: 'Permission needed', repository: 'owner/repo', laneId: 'codex', occurredAt: '2026-09-27T10:00:00Z' };
function setup() {
  const children: unknown[] = [];
  const window = Object.assign(new EventEmitter(), {
    webContents: Object.assign(new EventEmitter(), { mainFrame: {}, send: vi.fn(), getZoomFactor: () => 1 }), contentView: { children, addChildView: vi.fn(child => {
      const index = children.indexOf(child); if (index >= 0) children.splice(index, 1);
      children.push(child);
    }) },
    isDestroyed: () => false, isVisible: () => true, isMinimized: () => false, isFocused: () => true,
    getContentBounds: () => ({ x: 0, y: 0, width: 1000, height: 620 }),
  });
  const read = vi.fn(async (): Promise<unknown> => []);
  const open = vi.fn(async () => {});
  const host = new NoticeHost(() => window as unknown as BrowserWindow, read, open);
  host.register(); host.attach(window as unknown as BrowserWindow);
  return { host, read, open, window };
}
beforeEach(() => { desktop.handlers.clear(); desktop.views.length = 0; vi.stubGlobal('MAIN_WINDOW_VITE_DEV_SERVER_URL', 'http://localhost:5173'); });
afterEach(() => { vi.unstubAllGlobals(); });

it('authenticates IPC, layers only the card rectangle, and rechecks requests before navigation', async () => {
  const { host, read, open, window } = setup();
  await host.refresh(); read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object }; setBounds: ReturnType<typeof vi.fn>; setVisible: ReturnType<typeof vi.fn> };
  const event = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 572, y: 611, width: 420, height: 1 });
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(() => desktop.handlers.get('notices:read')?.({ sender: window.webContents, senderFrame: window.webContents.mainFrame })).toThrow('Unknown notice surface');
  expect(() => desktop.handlers.get('notices:read')?.({ sender: view.webContents, senderFrame: {} })).toThrow('Unknown notice surface');
  desktop.handlers.get('notices:resize')?.(event, 260, 420);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 572, y: 352, width: 420, height: 260 });
  expect(window.contentView.addChildView).toHaveBeenLastCalledWith(view);
  expect(window.contentView.addChildView).toHaveBeenCalledTimes(1);
  window.contentView.children.push({}); host.raise();
  expect(window.contentView.children.at(-1)).toBe(view);
  desktop.handlers.get('notices:resize')?.(event, 5000, 5000);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 572, y: 56, width: 420, height: 556 });
  read.mockResolvedValue([]);
  await desktop.handlers.get('notices:open')?.(event, notice.id);
  expect(open).not.toHaveBeenCalled();
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', null);
  desktop.handlers.get('notices:resize')?.(event, 62, 140);
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', null);
  desktop.handlers.get('notices:resize')?.(event, 0, 420);
  read.mockResolvedValue([notice]); await host.refresh();
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  desktop.handlers.get('notices:resize')?.(event, 260, 420);
  expect(view.setVisible).toHaveBeenLastCalledWith(true);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 572, y: 352, width: 420, height: 260 });
  host.stop();
});

it('shrinks the native hit area to the launcher and restores it for cards', async () => {
  const { host, read, window } = setup();
  read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object }; setBounds: ReturnType<typeof vi.fn> };
  const event = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  desktop.handlers.get('notices:resize')?.(event, 62, 168.5);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 823, y: 550, width: 169, height: 62 });
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', { x: 823, y: 550, width: 169, height: 62 });
  expect(() => desktop.handlers.get('notices:measure')?.(event)).toThrow('Unknown notice owner');
  desktop.handlers.get('notices:measure')?.({ sender: window.webContents, senderFrame: window.webContents.mainFrame });
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', { x: 823, y: 550, width: 169, height: 62 });
  window.emit('resize');
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 823, y: 550, width: 169, height: 62 });
  const calls = view.setBounds.mock.calls.length;
  for (const invalid of [0, -1, NaN, Infinity, true, '169']) desktop.handlers.get('notices:resize')?.(event, 62, invalid);
  expect(view.setBounds).toHaveBeenCalledTimes(calls);
  desktop.handlers.get('notices:resize')?.(event, 260, 420);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 572, y: 352, width: 420, height: 260 });
  host.stop();
});

it('suppresses a visible source, preserves failed reads, and dismisses without handling the task', async () => {
  const { host, read, open, window } = setup();
  await host.refresh(); read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object } };
  const event = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  const snapshot = () => desktop.handlers.get('notices:read')?.(event) as NoticeSnapshot;
  expect(snapshot().popupIds).toEqual([notice.id]);
  desktop.handlers.get('notices:source')?.({ sender: window.webContents, senderFrame: window.webContents.mainFrame }, 'screen', { taskId: notice.taskId, turnId: notice.turnId });
  expect(snapshot().popupIds).toEqual([]);
  expect(snapshot().notices).toEqual([notice]);
  read.mockRejectedValueOnce(new Error('Backend unavailable'));
  await host.refresh(); expect(snapshot().notices).toEqual([notice]);
  await desktop.handlers.get('notices:open')?.(event, notice.id);
  expect(open).toHaveBeenCalledWith(notice);
  expect(snapshot().notices).toEqual([notice]);
  host.stop();
});

it('fits the native hit area to zoomed notices and keeps the counter eight pixels from the corner', async () => {
  const { host, read, window } = setup();
  read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object; getZoomFactor: ReturnType<typeof vi.fn> }; setBounds: ReturnType<typeof vi.fn> };
  const event = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  for (const zoom of [0.8, 1.25]) {
    view.webContents.getZoomFactor.mockReturnValue(zoom);
    desktop.handlers.get('notices:resize')?.(event, 260, 420);
    expect(view.setBounds).toHaveBeenLastCalledWith({ x: 992 - 420 * zoom, y: 612 - 260 * zoom, width: 420 * zoom, height: 260 * zoom });
    expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', { x: 992 - 420 * zoom, y: 612 - 260 * zoom, width: 420 * zoom, height: 260 * zoom });
    expect((desktop.handlers.get('notices:read')?.(event) as NoticeSnapshot).maxHeight).toBe(556 / zoom);
  }
  host.stop();
});

it('releases native input while a modal is open and restores pending notices afterward', async () => {
  const { host, read, window } = setup();
  read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object }; setVisible: ReturnType<typeof vi.fn> };
  const child = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  const owner = { sender: window.webContents, senderFrame: window.webContents.mainFrame };
  desktop.handlers.get('notices:resize')?.(child, 260, 420);
  expect(() => desktop.handlers.get('notices:obscured')?.(child, true)).toThrow('Unknown notice owner');
  desktop.handlers.get('notices:obscured')?.(owner, true);
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', null);
  expect(desktop.handlers.get('notices:read')?.(child)).toMatchObject({ active: false, notices: [notice] });
  desktop.handlers.get('notices:resize')?.(child, 300, 420);
  await host.refresh();
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  desktop.handlers.get('notices:obscured')?.(owner, 'false');
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  desktop.handlers.get('notices:obscured')?.(owner, false);
  expect(view.setVisible).toHaveBeenLastCalledWith(true);
  expect(desktop.handlers.get('notices:read')?.(child)).toMatchObject({ active: true, notices: [notice] });
  host.stop();
});
