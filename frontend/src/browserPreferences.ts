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

export type BrowserDisplayMode = 'zoom' | 'fullscreen';

const DISPLAY_MODE_KEY = 'bytequay.browser-display-mode';

export function readBrowserDisplayMode(): BrowserDisplayMode {
  try {
    return window.localStorage.getItem(DISPLAY_MODE_KEY) === 'fullscreen' ? 'fullscreen' : 'zoom';
  }
  catch { return 'zoom'; }
}

export function saveBrowserDisplayMode(value: BrowserDisplayMode): void {
  try { window.localStorage.setItem(DISPLAY_MODE_KEY, value); }
  catch { /* Storage is optional; browser pages still open with the default. */ }
}
