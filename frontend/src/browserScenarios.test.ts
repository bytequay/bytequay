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
import { testBrowserScenarios } from './fixtures/browserScenarios';

testBrowserScenarios([
  {
    name: 'answers CI approvals, approves the squash and confirms abort in a cherry-pick task in Chromium',
    expected: 3, timeout: 20_000,
    fixture: {
      prefix: 'bytequay-cherry-pick-task-',
      entry: 'src/commits/fixtures/cherryPickTaskBrowser.tsx',
      global: 'CherryPickTaskTests',
      styles: true,
      width: 1200,
      height: 800,
    },
  },
  {
    name: 'browses the repo line, cherry-picks and backports a merged pull request in Chromium',
    expected: 4, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-commits-repo-line-',
      entry: 'src/commits/fixtures/repoLineBrowser.tsx',
      global: 'RepoLineTests',
      styles: true,
      width: 1200,
      height: 800,
    },
  },
  {
    name: 'shares safe keyboard submission across message composers',
    expected: 3, timeout: 120_000,
    fixture: {
      prefix: 'bytequay-composer-', entry: 'src/fixtures/composerBrowser.tsx', global: 'ComposerTests', timeoutMs: 60_000,
    },
  },
  {
    name: 'handles attention visits, actions, global pins and timed reminders in Chromium',
    expected: 10, timeout: 30_000,
    fixture: {
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
    },
  },
  {
    name: 'uses cached Google data and Gemini and manages account access in Chromium',
    expected: 17, timeout: 30_000,
    fixture: { prefix: 'bytequay-google-', entry: 'src/integrations/fixtures/googleBrowser.tsx', global: 'GoogleTests', styles: true, width: 1120, height: 820 },
  },
  {
    name: 'completes and resumes setup without showing it again after completion',
    expected: 5, timeout: 60_000,
    fixture: {
      prefix: 'bytequay-setup-', entry: 'src/onboarding/fixtures/setupBrowser.tsx', global: 'SetupTests', styles: true, width: 1180, height: 860,
    },
  },
  {
    name: 'preserves Recent across navigation and loads pages without redundant requests in Chromium',
    expected: 12, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-recent-loading-',
      entry: 'src/recent/fixtures/loadingBrowser.tsx',
      global: 'RecentLoadingTests',
      styles: true,
      width: 1100,
      height: 600,
    },
  },
  {
    name: 'manages private circles and inspects recorded activity in Chromium',
    expected: 14, timeout: 90_000,
    fixture: {
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
      results.push(unfolded ? { name: 'commentOnHover' } : { name: 'commentOnHover', error: 'Hovering the latest line must unfold the comment under the row’s actions too' });
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
    },
  },
  {
    name: 'draws the most recently opened ticket, issue or web page on top in the running app',
    expected: 4, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-zoom-over-jira-',
      entry: 'src/fixtures/zoomOverJiraBrowser.tsx',
      global: 'ZoomOverJiraTests',
      styles: true,
      width: 1280,
      height: 900,
    },
  },
]);
