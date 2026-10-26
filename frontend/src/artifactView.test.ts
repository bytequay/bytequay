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
import type { BrowserWindow } from 'electron';
import type { EventEmitter } from 'node:events';
import { ArtifactView } from './artifactView';

type MockContents = EventEmitter & { destroyed: boolean; focused: boolean; close: () => void; focus: () => void; loadURL: () => Promise<undefined> };
const desktop = vi.hoisted(() => ({ contents: [] as MockContents[], removeChildView: vi.fn() }));
vi.mock('electron', async () => {
  const { EventEmitter } = await import('node:events');
  return {
    protocol: { registerSchemesAsPrivileged: vi.fn() },
    WebContentsView: class {
      get webContents() { return this.contents.destroyed ? undefined : this.contents; }
      contents = Object.assign(new EventEmitter(), {
        destroyed: false,
        focused: false,
        isDestroyed: () => this.contents.destroyed,
        isFocused: () => this.contents.focused,
        focus: vi.fn(() => { this.contents.focused = true; }),
        close: vi.fn(() => { this.contents.destroyed = true; this.contents.emit('destroyed'); }),
        loadURL: vi.fn(async () => undefined), setWebRTCIPHandlingPolicy: vi.fn(), setWindowOpenHandler: vi.fn(),
        session: {
          protocol: { isProtocolHandled: () => false, handle: vi.fn() },
          webRequest: { onBeforeRequest: vi.fn() }, setPermissionRequestHandler: vi.fn(), setPermissionCheckHandler: vi.fn(),
          on: vi.fn(), setProxy: vi.fn(async () => undefined),
        },
      });
      setVisible = vi.fn((visible: boolean) => { if (!visible) this.contents.focused = false; }); setBounds = vi.fn();
      constructor() { desktop.contents.push(this.contents); }
    },
  };
});

const window = {
  isDestroyed: () => false,
  isFocused: () => true,
  webContents: { getZoomFactor: () => 1 },
  getContentBounds: () => ({ width: 1000, height: 800 }),
  contentView: { addChildView: vi.fn(), removeChildView: desktop.removeChildView },
} as unknown as BrowserWindow;
const target = { workspaceId: 'workspace', artifactId: 'artifact', pageId: 'page' };
const bounds = { x: 0, y: 0, width: 800, height: 600 };
const readPage = vi.fn(async () => '<h1>Saved page</h1>');
let artifacts: ArtifactView;
beforeEach(() => {
  vi.clearAllMocks(); desktop.contents.length = 0;
  artifacts = new ArtifactView(() => window, readPage);
});
afterEach(() => { artifacts.releaseWindow(); vi.useRealTimers(); });

it('reopens a page after Electron clears its destroyed web contents, and tolerates owner cleanup', async () => {
  await artifacts.show(target, bounds);
  desktop.contents[0].close();
  expect(desktop.removeChildView).toHaveBeenCalledOnce();
  await artifacts.show(target, bounds);
  expect(desktop.contents).toHaveLength(2);
  expect(desktop.contents[1].loadURL).toHaveBeenCalledWith('bytequay-artifact://artifact/workspace/');
  desktop.contents[1].close();
  expect(() => artifacts.releaseWindow()).not.toThrow();
});

it('ignores a page read from a destroyed view even when the same page is reopened', async () => {
  let finishRead!: (html: string) => void;
  readPage.mockImplementationOnce(() => new Promise<string>(resolve => { finishRead = resolve; }));
  const first = artifacts.show(target, bounds);
  desktop.contents[0].close();
  await artifacts.show(target, bounds);
  finishRead('<h1>Saved page</h1>');
  await first;
  await artifacts.show(target, bounds);
  expect(readPage).toHaveBeenCalledTimes(2);
  expect(desktop.contents[0].loadURL).not.toHaveBeenCalled();
  expect(desktop.contents[1].loadURL).toHaveBeenCalledOnce();
});

it('waits for the selected page to load when it is presented again during a pending read', async () => {
  await artifacts.show(target, bounds);
  let finishRead!: (html: string) => void;
  let finishLoad!: () => void;
  readPage.mockImplementationOnce(() => new Promise<string>(resolve => { finishRead = resolve; }));
  vi.mocked(desktop.contents[0].loadURL).mockImplementationOnce(() => new Promise<undefined>(resolve => { finishLoad = () => resolve(undefined); }));
  const next = { ...target, pageId: 'another-page' };
  const completed = vi.fn();
  const first = artifacts.show(next, bounds).then(completed);
  const repeated = artifacts.show(next, bounds).then(completed);
  await artifacts.show(next, null);
  const restored = artifacts.show(next, bounds).then(completed);
  await Promise.resolve();
  expect(completed).not.toHaveBeenCalled();
  expect(readPage).toHaveBeenCalledTimes(2);
  finishRead('<h1>Another page</h1>');
  await vi.waitFor(() => expect(desktop.contents[0].loadURL).toHaveBeenCalledTimes(2));
  expect(completed).not.toHaveBeenCalled();
  finishLoad();
  await Promise.all([first, repeated, restored]);
  expect(completed).toHaveBeenCalledTimes(3);
});

it('reports a pending read failure to repeated presentations and allows a retry', async () => {
  let failRead!: (error: Error) => void;
  readPage.mockImplementationOnce(() => new Promise<string>((_resolve, reject) => { failRead = reject; }));
  const first = artifacts.show(target, bounds);
  const repeated = artifacts.show(target, bounds);
  const results = Promise.allSettled([first, repeated]);
  const failure = new Error('Saved page is unavailable');
  failRead(failure);
  expect(await results).toEqual([{ status: 'rejected', reason: failure }, { status: 'rejected', reason: failure }]);
  await artifacts.show(target, bounds);
  expect(readPage).toHaveBeenCalledTimes(2);
  expect(desktop.contents[0].loadURL).toHaveBeenCalledOnce();
});

it('retains a temporarily covered page and restores only its earlier keyboard focus', async () => {
  vi.useFakeTimers();
  await artifacts.show(target, bounds);
  const contents = desktop.contents[0];
  expect(contents.focus).not.toHaveBeenCalled();
  contents.focused = true;
  await artifacts.show(target, null);
  await artifacts.show(target, null);
  await vi.advanceTimersByTimeAsync(5 * 60_000);
  expect(contents.close).not.toHaveBeenCalled();
  await artifacts.show(target, bounds);
  expect(contents.focus).toHaveBeenCalledOnce();
  expect(contents.loadURL).toHaveBeenCalledOnce();
  expect(readPage).toHaveBeenCalledOnce();

  contents.focused = false;
  await artifacts.show(target, null);
  await artifacts.show(target, bounds);
  expect(contents.focus).toHaveBeenCalledOnce();
});

it('still closes an inactive page and does not transfer focus to a different page', async () => {
  vi.useFakeTimers();
  await artifacts.show(target, bounds);
  const contents = desktop.contents[0];
  contents.focused = true;
  await artifacts.show(target, null);
  await artifacts.show({ ...target, pageId: 'another-page' }, bounds);
  expect(contents.focus).not.toHaveBeenCalled();
  await artifacts.show(null, null);
  await vi.advanceTimersByTimeAsync(60_000);
  expect(contents.close).toHaveBeenCalledOnce();
});

it('retains a covered page without reclaiming focus chosen during companion entrance', async () => {
  vi.useFakeTimers();
  await artifacts.show(target, bounds);
  const contents = desktop.contents[0];
  contents.focused = true;
  await artifacts.show(target, null);
  await artifacts.show(target, null, false);
  await artifacts.show(target, null);
  await vi.advanceTimersByTimeAsync(5 * 60_000);
  expect(contents.close).not.toHaveBeenCalled();
  await artifacts.show(target, bounds);
  expect(contents.focus).not.toHaveBeenCalled();
  expect(contents.loadURL).toHaveBeenCalledOnce();
});
