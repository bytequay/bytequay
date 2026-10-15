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
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
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
const notice: AppNotice = { id: 'approval:one', kind: 'APPROVAL', taskId: 'task', workspaceId: 'workspace', turnId: 'turn', reviewId: null, title: 'Build',
  pullRequestTitle: null, changeSummary: null, message: 'Permission needed', repository: 'owner/repo', laneId: 'codex', occurredAt: '2026-09-27T10:00:00Z' };
let directory: string;
function setup() {
  const children: unknown[] = [];
  const window = Object.assign(new EventEmitter(), {
    webContents: Object.assign(new EventEmitter(), { mainFrame: {}, send: vi.fn(), getZoomFactor: () => 1 }), contentView: { children, addChildView: vi.fn(child => {
      const index = children.indexOf(child); if (index >= 0) children.splice(index, 1);
      children.push(child);
    }) },
    isDestroyed: () => false, isVisible: (): boolean => true, isMinimized: (): boolean => false, isFocused: (): boolean => true,
    getContentBounds: () => ({ x: 0, y: 0, width: 1000, height: 620 }),
  });
  const read = vi.fn(async (): Promise<unknown> => []);
  const open = vi.fn(async () => {});
  const host = new NoticeHost(() => window as unknown as BrowserWindow, read, open, () => path.join(directory, 'dismissed-notices.json'));
  host.register(); host.attach(window as unknown as BrowserWindow);
  return { host, read, open, window };
}
beforeEach(() => {
  directory = fs.mkdtempSync(path.join(os.tmpdir(), 'bytequay-notice-host-'));
  desktop.handlers.clear(); desktop.views.length = 0; vi.stubGlobal('MAIN_WINDOW_VITE_DEV_SERVER_URL', 'http://localhost:5173');
});
afterEach(() => { vi.unstubAllGlobals(); fs.rmSync(directory, { recursive: true, force: true }); });

it('authenticates IPC, layers only the card rectangle, and rechecks requests before navigation', async () => {
  const { host, read, open, window } = setup();
  await host.refresh(); read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object }; setBounds: ReturnType<typeof vi.fn>; setVisible: ReturnType<typeof vi.fn> };
  const event = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 652, y: 611, width: 340, height: 1 });
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(() => desktop.handlers.get('notices:read')?.({ sender: window.webContents, senderFrame: window.webContents.mainFrame })).toThrow('Unknown notice surface');
  expect(() => desktop.handlers.get('notices:read')?.({ sender: view.webContents, senderFrame: {} })).toThrow('Unknown notice surface');
  desktop.handlers.get('notices:resize')?.(event, 260, 340, false);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 652, y: 352, width: 340, height: 260 });
  expect(window.contentView.addChildView).toHaveBeenLastCalledWith(view);
  expect(window.contentView.addChildView).toHaveBeenCalledTimes(1);
  window.contentView.children.push({}); host.raise();
  expect(window.contentView.children.at(-1)).toBe(view);
  desktop.handlers.get('notices:resize')?.(event, 5000, 5000, false);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 652, y: 56, width: 340, height: 556 });
  read.mockResolvedValue([]);
  await desktop.handlers.get('notices:open')?.(event, notice.id);
  expect(open).not.toHaveBeenCalled();
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', null);
  desktop.handlers.get('notices:resize')?.(event, 62, 140, false);
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', null);
  desktop.handlers.get('notices:resize')?.(event, 0, 340, false);
  read.mockResolvedValue([notice]); await host.refresh();
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  desktop.handlers.get('notices:resize')?.(event, 260, 340, false);
  expect(view.setVisible).toHaveBeenLastCalledWith(true);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 652, y: 352, width: 340, height: 260 });
  host.stop();
});

it('shrinks the native hit area to the launcher and restores it for cards', async () => {
  const { host, read, window } = setup();
  read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object }; setBounds: ReturnType<typeof vi.fn> };
  const event = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  desktop.handlers.get('notices:resize')?.(event, 62, 168.5, false);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 823, y: 550, width: 169, height: 62 });
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', { x: 823, y: 550, width: 169, height: 62 });
  expect(() => desktop.handlers.get('notices:measure')?.(event)).toThrow('Unknown notice owner');
  desktop.handlers.get('notices:measure')?.({ sender: window.webContents, senderFrame: window.webContents.mainFrame });
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', { x: 823, y: 550, width: 169, height: 62 });
  window.emit('resize');
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 823, y: 550, width: 169, height: 62 });
  const calls = view.setBounds.mock.calls.length;
  for (const invalid of [0, -1, NaN, Infinity, true, '169']) desktop.handlers.get('notices:resize')?.(event, 62, invalid, false);
  expect(view.setBounds).toHaveBeenCalledTimes(calls);
  desktop.handlers.get('notices:resize')?.(event, 56, 32, true);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 968, y: 556, width: 32, height: 56 });
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', { x: 968, y: 556, width: 32, height: 56 });
  window.emit('resize');
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 968, y: 556, width: 32, height: 56 });
  for (const invalid of [null, undefined, 1, 'true']) desktop.handlers.get('notices:resize')?.(event, 260, 340, invalid);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 968, y: 556, width: 32, height: 56 });
  desktop.handlers.get('notices:resize')?.(event, 260, 340, false);
  expect(view.setBounds).toHaveBeenLastCalledWith({ x: 652, y: 352, width: 340, height: 260 });
  host.stop();
});

it.each(['TURN_FINISHED', 'APPROVAL'] as const)('removes %s notices without restoring them on refresh or handling their task', async kind => {
  const { host, read, open, window } = setup();
  const current = { ...notice, kind, occurredAt: new Date().toISOString() };
  await host.refresh(); read.mockResolvedValue([current]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object; send: ReturnType<typeof vi.fn> }; setVisible: ReturnType<typeof vi.fn> };
  const child = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  const remove = desktop.handlers.get('notices:remove');
  expect(() => remove?.({ sender: window.webContents, senderFrame: window.webContents.mainFrame }, current.id)).toThrow('Unknown notice surface');
  expect(() => remove?.({ sender: view.webContents, senderFrame: {} }, current.id)).toThrow('Unknown notice surface');
  expect(() => remove?.(child, null)).toThrow('Invalid notice');
  desktop.handlers.get('notices:resize')?.(child, 260, 340, false);
  expect(view.setVisible).toHaveBeenLastCalledWith(true);
  await remove?.(child, current.id);
  expect(view.webContents.send).toHaveBeenLastCalledWith('notices:changed', expect.objectContaining({ notices: [], popupIds: [], viewedIds: [] }));
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', null);
  await host.refresh();
  expect(desktop.handlers.get('notices:read')?.(child)).toMatchObject({ notices: [], popupIds: [], viewedIds: [] });
  expect(open).not.toHaveBeenCalled();
  host.stop();
  const restarted = setup();
  restarted.read.mockResolvedValue([current]); await restarted.host.refresh();
  const newView = desktop.views.at(-1) as { webContents: { mainFrame: object } };
  expect(desktop.handlers.get('notices:read')?.({ sender: newView.webContents, senderFrame: newView.webContents.mainFrame })).toMatchObject({ notices: [], popupIds: [] });
  restarted.host.stop();
});

it('suppresses a visible source, preserves failed reads, and dismisses without handling the task', async () => {
  const { host, read, open, window } = setup();
  await host.refresh(); read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object } };
  const event = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  const snapshot = () => desktop.handlers.get('notices:read')?.(event) as NoticeSnapshot;
  expect(snapshot().popupIds).toEqual([notice.id]);
  desktop.handlers.get('notices:source')?.({ sender: window.webContents, senderFrame: window.webContents.mainFrame }, 'screen', notice.taskId);
  expect(snapshot().popupIds).toEqual([]);
  expect(snapshot().viewedIds).toEqual([notice.id]);
  expect(snapshot().notices).toEqual([notice]);
  read.mockRejectedValueOnce(new Error('Backend unavailable'));
  await host.refresh(); expect(snapshot().notices).toEqual([notice]);
  await desktop.handlers.get('notices:open')?.(event, notice.id);
  expect(open).toHaveBeenCalledWith(notice);
  expect(snapshot().notices).toEqual([notice]);
  host.stop();
});

it.each(['TURN_FINISHED', 'APPROVAL'] as const)('keeps %s notices quiet for the open task across turns and navigation', async kind => {
  const { host, read, open, window } = setup();
  await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object; send: ReturnType<typeof vi.fn> } };
  const owner = { sender: window.webContents, senderFrame: window.webContents.mainFrame };
  const child = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  const snapshot = () => desktop.handlers.get('notices:read')?.(child) as NoticeSnapshot;
  const current = { ...notice, kind, occurredAt: new Date().toISOString() };
  const other = { ...current, id: 'other', taskId: 'other-task' };
  desktop.handlers.get('notices:source')?.(owner, 'screen', current.taskId);
  read.mockResolvedValue([current, other]); await host.refresh();
  expect(snapshot()).toMatchObject({ popupIds: [other.id], viewedIds: [current.id] });
  expect(view.webContents.send).toHaveBeenLastCalledWith('notices:changed', snapshot());
  const next = { ...current, id: 'next', turnId: 'next-turn' };
  read.mockResolvedValue([next, other]); await host.refresh();
  expect(snapshot()).toMatchObject({ popupIds: [other.id], viewedIds: [next.id] });
  desktop.handlers.get('notices:source')?.(owner, 'screen', null);
  await host.refresh();
  expect(snapshot()).toMatchObject({ popupIds: [other.id], viewedIds: [next.id] });
  const unseen = { ...next, id: 'unseen', turnId: 'unseen-turn' };
  read.mockResolvedValue([unseen]); await host.refresh();
  expect(snapshot().popupIds).toContain(unseen.id);
  expect(snapshot().viewedIds).toEqual([]);
  expect(open).not.toHaveBeenCalled();
  host.stop();
});

it.each(['unfocused', 'hidden', 'minimized', 'obscured', 'covered'] as const)('preserves notifications when the task is %s', async visibility => {
  const { host, read, window } = setup();
  await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object } };
  const owner = { sender: window.webContents, senderFrame: window.webContents.mainFrame };
  const snapshot = () => desktop.handlers.get('notices:read')?.({ sender: view.webContents, senderFrame: view.webContents.mainFrame }) as NoticeSnapshot;
  if (visibility === 'unfocused') vi.spyOn(window, 'isFocused').mockReturnValue(false);
  if (visibility === 'hidden') vi.spyOn(window, 'isVisible').mockReturnValue(false);
  if (visibility === 'minimized') vi.spyOn(window, 'isMinimized').mockReturnValue(true);
  if (visibility === 'obscured') desktop.handlers.get('notices:obscured')?.(owner, true);
  desktop.handlers.get('notices:source')?.(owner, 'screen', visibility === 'covered' ? null : notice.taskId);
  read.mockResolvedValue([notice]); await host.refresh();
  expect(snapshot()).toMatchObject({ popupIds: [notice.id], viewedIds: [] });
  host.stop();
});

it.each([
  { navigation: 'hash change', isSameDocument: true, isMainFrame: true, viewed: true },
  { navigation: 'subframe load', isSameDocument: false, isMainFrame: false, viewed: true },
  { navigation: 'page reload', isSameDocument: false, isMainFrame: true, viewed: false },
])('tracks the visible task correctly after a $navigation', async ({ isSameDocument, isMainFrame, viewed }) => {
  const { host, read, window } = setup();
  await host.refresh();
  desktop.handlers.get('notices:source')?.({ sender: window.webContents, senderFrame: window.webContents.mainFrame }, 'screen', notice.taskId);
  window.webContents.emit('did-start-loading');
  window.webContents.emit('did-start-navigation', { isSameDocument, isMainFrame });
  const current = { ...notice, kind: 'TURN_FINISHED', occurredAt: new Date().toISOString() };
  const other = { ...current, id: 'other', taskId: 'other-task' };
  read.mockResolvedValue([current, other]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object } };
  const snapshot = desktop.handlers.get('notices:read')?.({ sender: view.webContents, senderFrame: view.webContents.mainFrame }) as NoticeSnapshot;
  expect(snapshot.viewedIds).toEqual(viewed ? [current.id] : []);
  expect(snapshot.popupIds).toEqual(viewed ? [other.id] : [current.id, other.id]);
  host.stop();
});

it('fits the native hit area to zoomed notices and keeps the counter eight pixels from the corner', async () => {
  const { host, read, window } = setup();
  read.mockResolvedValue([notice]); await host.refresh();
  const view = desktop.views[0] as { webContents: { mainFrame: object; getZoomFactor: ReturnType<typeof vi.fn> }; setBounds: ReturnType<typeof vi.fn> };
  const event = { sender: view.webContents, senderFrame: view.webContents.mainFrame };
  for (const zoom of [0.8, 1.25]) {
    view.webContents.getZoomFactor.mockReturnValue(zoom);
    desktop.handlers.get('notices:resize')?.(event, 260, 340, false);
    expect(view.setBounds).toHaveBeenLastCalledWith({ x: 992 - 340 * zoom, y: 612 - 260 * zoom, width: 340 * zoom, height: 260 * zoom });
    expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', { x: 992 - 340 * zoom, y: 612 - 260 * zoom, width: 340 * zoom, height: 260 * zoom });
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
  desktop.handlers.get('notices:resize')?.(child, 260, 340, false);
  expect(() => desktop.handlers.get('notices:obscured')?.(child, true)).toThrow('Unknown notice owner');
  desktop.handlers.get('notices:obscured')?.(owner, true);
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  expect(window.webContents.send).toHaveBeenLastCalledWith('notices:bounds', null);
  expect(desktop.handlers.get('notices:read')?.(child)).toMatchObject({ active: false, notices: [notice] });
  desktop.handlers.get('notices:resize')?.(child, 300, 340, false);
  await host.refresh();
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  desktop.handlers.get('notices:obscured')?.(owner, 'false');
  expect(view.setVisible).toHaveBeenLastCalledWith(false);
  desktop.handlers.get('notices:obscured')?.(owner, false);
  expect(view.setVisible).toHaveBeenLastCalledWith(true);
  expect(desktop.handlers.get('notices:read')?.(child)).toMatchObject({ active: true, notices: [notice] });
  host.stop();
});
