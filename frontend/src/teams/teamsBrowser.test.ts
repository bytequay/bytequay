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

it('manages private circles and inspects recorded activity in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-teams-',
    entry: 'src/teams/fixtures/teamsBrowser.tsx',
    global: 'TeamsBrowserTests',
    styles: true,
    charset: true,
    width: 1500,
    height: 900,
    timeoutMs: 60_000,
    body: `
      const results = await window.webContents.executeJavaScript('TeamsBrowserTests.run()');
      const hover = await window.webContents.executeJavaScript('TeamsBrowserTests.hoverTarget()');
      window.webContents.sendInputEvent({ type: 'mouseMove', x: hover.x, y: hover.y });
      await new Promise(resolve => setTimeout(resolve, 200));
      const unfolded = await window.webContents.executeJavaScript('TeamsBrowserTests.commentShown()');
      results.push(unfolded ? { name: 'commentOnHover' } : { name: 'commentOnHover', error: 'Hovering the latest line must unfold the comment' });
      const screenshots = process.env.BYTEQUAY_TEAMS_SCREENSHOTS;
      if (screenshots) {
        const { mkdir, writeFile } = require('node:fs/promises');
        const { join } = require('node:path');
        await mkdir(screenshots, { recursive: true });
        for (const width of [1500, 1100]) {
          window.setContentSize(width, 900);
          for (const view of ['needs', 'switcher', 'activity', 'members', 'insights', 'person', 'create', 'edit', 'empty', 'remove']) {
            await window.webContents.executeJavaScript('TeamsBrowserTests.showPreview(' + JSON.stringify(view) + ')');
            await new Promise(resolve => setTimeout(resolve, 150));
            await writeFile(join(screenshots, 'bytequay-circles-' + view + '-' + width + '.png'), (await window.webContents.capturePage()).toPNG());
          }
          const point = await window.webContents.executeJavaScript('TeamsBrowserTests.hoverTarget()');
          window.webContents.sendInputEvent({ type: 'mouseMove', x: point.x, y: point.y });
          await new Promise(resolve => setTimeout(resolve, 200));
          await writeFile(join(screenshots, 'bytequay-circles-comment-' + width + '.png'), (await window.webContents.capturePage()).toPNG());
        }
      }
    `,
  });
  expect(results).toHaveLength(14);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, 90_000);
