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

type MockContents = EventEmitter & { destroyed: boolean; close: () => void; loadURL: () => Promise<undefined> };
const desktop = vi.hoisted(() => ({ contents: [] as MockContents[], removeChildView: vi.fn() }));
vi.mock('electron', async () => {
  const { EventEmitter } = await import('node:events');
  return {
    protocol: { registerSchemesAsPrivileged: vi.fn() },
    WebContentsView: class {
      get webContents() { return this.contents.destroyed ? undefined : this.contents; }
      contents = Object.assign(new EventEmitter(), {
        destroyed: false,
        isDestroyed: () => this.contents.destroyed,
        close: vi.fn(() => { this.contents.destroyed = true; this.contents.emit('destroyed'); }),
        loadURL: vi.fn(async () => undefined), setWebRTCIPHandlingPolicy: vi.fn(), setWindowOpenHandler: vi.fn(),
        session: {
          protocol: { isProtocolHandled: () => false, handle: vi.fn() },
          webRequest: { onBeforeRequest: vi.fn() }, setPermissionRequestHandler: vi.fn(), setPermissionCheckHandler: vi.fn(),
          on: vi.fn(), setProxy: vi.fn(async () => undefined),
        },
      });
      setVisible = vi.fn(); setBounds = vi.fn();
      constructor() { desktop.contents.push(this.contents); }
    },
  };
});

const window = {
  isDestroyed: () => false,
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
afterEach(() => { artifacts.releaseWindow(); });

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
