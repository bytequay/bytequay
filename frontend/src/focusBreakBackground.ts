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
import { ipcMain, WebContentsView, type BrowserWindow } from 'electron';
import type { FocusBreakBackground } from './focus/contracts';

export function registerFocusBreakBackground(window: () => BrowserWindow | null): void {
  ipcMain.handle('focus:background', async (event): Promise<FocusBreakBackground[]> => {
    const current = window();
    if (!current || current.isDestroyed() || event.sender !== current.webContents || event.senderFrame !== current.webContents.mainFrame) {
      throw new Error('Break backgrounds are only available from the app.');
    }
    const zoom = current.webContents.getZoomFactor();
    // Native views must detach so DOM companions can cover them; keep their last visible pixels in memory.
    const pictures = await Promise.all(current.contentView.children.map(async view => {
      if (!(view instanceof WebContentsView) || !view.getVisible() || view.webContents === current.webContents || view.webContents.isDestroyed()) return null;
      const { x, y, width, height } = view.getBounds();
      if (width <= 0 || height <= 0) return null;
      try {
        const picture = await view.webContents.capturePage(undefined, { stayHidden: true });
        if (picture.isEmpty()) return null;
        return { image: picture.toDataURL(), x: x / zoom, y: y / zoom, width: width / zoom, height: height / zoom };
      }
      catch { return null; }
    }));
    return pictures.filter(picture => picture !== null);
  });
}
