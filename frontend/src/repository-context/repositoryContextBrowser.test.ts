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

// Set BYTEQUAY_CONTEXT_SCREENSHOTS to a directory to also capture every state at desktop and narrow widths.
const SHOTS = [
  ['01-new', 'fresh'], ['02-discovering', 'discovering'], ['03-suggested', 'suggested:select'], ['04-coverage', 'suggested:coverage'],
  ['05-learning', 'learning'], ['06-review', 'review'], ['07-draft', 'review:draft'], ['08-update', 'review:update'], ['09-plan', 'review:plan'],
  ['10-sources', 'review:sources'], ['11-notes', 'review:notes'], ['12-usage', 'review:usage'], ['13-advanced', 'review:advanced'], ['14-agent-use', 'review:use'],
  ['15-home-explore', 'home'], ['16-task-draft', 'task'],
  ['17-learning-run', 'learning:run'], ['18-learning-tasks', 'learning:tasks'],
];

it('keeps every repository context run explicit and every review action run-free in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-repository-context-', entry: 'src/repository-context/fixtures/repositoryContextBrowser.tsx', global: 'RepositoryContextTests',
    styles: true, charset: true, width: 1280, height: 900, timeoutMs: 90_000,
    body: `
      const results = await window.webContents.executeJavaScript('RepositoryContextTests.run()');
      const directory = process.env.BYTEQUAY_CONTEXT_SCREENSHOTS;
      if (directory) {
        const { mkdir, writeFile } = require('node:fs/promises');
        const { join } = require('node:path');
        await mkdir(directory, { recursive: true });
        // Zooming out lays the page out as a window about 2,100px wide.
        for (const [width, suffix, zoom] of [[1280, '', 1], [760, '-narrow', 1], [1280, '-wide', 0.6]]) {
          window.setContentSize(width, 900);
          window.webContents.setZoomFactor(zoom);
          for (const [name, mode] of ${JSON.stringify(SHOTS)}) {
            await window.webContents.executeJavaScript('RepositoryContextTests.show(' + JSON.stringify(mode) + ')');
            await new Promise(resolve => setTimeout(resolve, 120));
            await writeFile(join(directory, name + suffix + '.png'), (await window.webContents.capturePage()).toPNG());
          }
        }
      }`,
  });
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
  expect(results).toHaveLength(19);
}, 120_000);
