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
import { expect, it } from 'vitest';
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';

const FRAMES = process.env.BYTEQUAY_DICTATION_FRAMES;

it('talks to the Assistant to start a task, then asks it to open the running task', async () => {
  const holds = await runBrowserFixture<[string, number][]>({
    prefix: 'bytequay-dictation-movie-',
    entry: 'src/assistant/fixtures/dictationMovieBrowser.tsx',
    global: 'DictationMovie',
    styles: true,
    // The website's frames are dark and drawn at 2x.
    beforeReady: FRAMES === undefined ? undefined : "nativeTheme.themeSource = 'dark'; app.commandLine.appendSwitch('force-device-scale-factor', '2');",
    timeoutMs: 40_000,
    body: `
      const frames = process.env.BYTEQUAY_DICTATION_FRAMES;
      const { mkdirSync, writeFileSync } = require('node:fs');
      const { join } = require('node:path');
      window.setContentSize(1100, 700);
      await untilWidth(1100);
      if (frames) mkdirSync(frames, { recursive: true });
      const results = [];
      for (;;) {
        const hold = await window.webContents.executeJavaScript('DictationMovie.next()');
        if (hold === null) break;
        const name = 'frame-' + String(results.length).padStart(2, '0') + '.png';
        if (frames) writeFileSync(join(frames, name), (await window.webContents.capturePage()).toPNG());
        results.push([name, hold]);
      }
      if (frames) writeFileSync(join(frames, 'holds.json'), JSON.stringify(results));
    `,
  });
  expect(holds).toHaveLength(24);
}, deadline(60_000));
