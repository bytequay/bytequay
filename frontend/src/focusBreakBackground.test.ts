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
import { beforeEach, expect, it, vi } from 'vitest';
import type { BrowserWindow, IpcMainInvokeEvent, NativeImage } from 'electron';
import { WebContentsView } from 'electron';
import { registerFocusBreakBackground } from './focusBreakBackground';
import type { FocusBreakBackground } from './focus/contracts';

const desktop = vi.hoisted(() => ({ handler: null as ((event: IpcMainInvokeEvent) => Promise<FocusBreakBackground[]>) | null }));
vi.mock('electron', () => ({
  ipcMain: { handle: (_channel: string, handler: typeof desktop.handler) => { desktop.handler = handler; } },
  WebContentsView: class {
    visible = true;
    destroyed = false;
    bounds = { x: 100, y: 200, width: 800, height: 600 };
    getVisible = () => this.visible;
    getBounds = () => this.bounds;
    webContents = {
      isDestroyed: () => this.destroyed,
      capturePage: vi.fn(async () => ({ isEmpty: () => false, toDataURL: () => 'data:image/png;base64,page' })),
    };
  },
}));

type MockView = WebContentsView & { visible: boolean; destroyed: boolean; bounds: { x: number; y: number; width: number; height: number } };
const sender = { mainFrame: {}, getZoomFactor: () => 2 };
let children: unknown[] = [];
const window = { isDestroyed: () => false, webContents: sender, contentView: { get children() { return children; } } } as unknown as BrowserWindow;
const trusted = () => ({ sender, senderFrame: sender.mainFrame }) as unknown as IpcMainInvokeEvent;
const capture = (event = trusted()) => desktop.handler?.(event);

beforeEach(() => {
  children = [];
  registerFocusBreakBackground(() => window);
});

it('captures visible native panes in stacking order and maps their bounds to renderer CSS pixels', async () => {
  const first = new WebContentsView() as MockView;
  const second = new WebContentsView() as MockView;
  const hidden = new WebContentsView() as MockView;
  hidden.visible = false;
  const destroyed = new WebContentsView() as MockView;
  destroyed.destroyed = true;
  const empty = new WebContentsView() as MockView;
  empty.bounds.width = 0;
  vi.mocked(second.webContents.capturePage).mockResolvedValue({ isEmpty: () => false, toDataURL: () => 'data:image/png;base64,second' } as NativeImage);
  children = [first, {}, hidden, destroyed, empty, second];
  expect(await capture()).toEqual([
    { image: 'data:image/png;base64,page', x: 50, y: 100, width: 400, height: 300 },
    { image: 'data:image/png;base64,second', x: 50, y: 100, width: 400, height: 300 },
  ]);
  expect(first.webContents.capturePage).toHaveBeenCalledWith(undefined, { stayHidden: true });
  expect(hidden.webContents.capturePage).not.toHaveBeenCalled();
  expect(destroyed.webContents.capturePage).not.toHaveBeenCalled();
  expect(empty.webContents.capturePage).not.toHaveBeenCalled();
});

it('skips unavailable pictures and rejects callers outside the app main frame', async () => {
  const failing = new WebContentsView();
  const empty = new WebContentsView();
  vi.mocked(failing.webContents.capturePage).mockRejectedValue(new Error('Page closed'));
  vi.mocked(empty.webContents.capturePage).mockResolvedValue({ isEmpty: () => true } as NativeImage);
  children = [failing, empty];
  expect(await capture()).toEqual([]);
  await expect(capture({ ...trusted(), senderFrame: {} } as IpcMainInvokeEvent)).rejects.toThrow('only available from the app');
  await expect(capture({ ...trusted(), sender: {} } as IpcMainInvokeEvent)).rejects.toThrow('only available from the app');
});
