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
import { runBrowserFixture, type ScenarioResult } from '../fixtures/browserFixture';

it('handles attention visits, actions, global pins and timed reminders in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-attention-lifecycle-',
    entry: 'src/home/fixtures/attentionLifecycleBrowser.tsx',
    global: 'AttentionLifecycleTests',
    styles: true,
    charset: true,
    width: 1300,
    height: 900,
    body: `
      const results = await window.webContents.executeJavaScript('AttentionLifecycleTests.run()');
      const screenshots = process.env.BYTEQUAY_ATTENTION_SCREENSHOTS;
      if (screenshots) {
        const { mkdir, writeFile } = require('node:fs/promises');
        const { join } = require('node:path');
        await mkdir(screenshots, { recursive: true });
        for (const width of [1300, 900]) {
          window.setContentSize(width, 780);
          await window.webContents.executeJavaScript('AttentionLifecycleTests.showPreview()');
          await writeFile(join(screenshots, 'attention-' + width + '.png'), (await window.webContents.capturePage()).toPNG());
        }
      }
    `,
  });
  expect(results).toHaveLength(7);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, 30_000);
