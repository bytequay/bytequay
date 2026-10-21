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
import { testBrowserScenarios } from '../fixtures/browserScenarios';

testBrowserScenarios([
  {
    name: 'sends private comments through shared diff and task controls in Chromium',
    expected: 21, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-diff-agent-comments-',
      entry: 'src/pulls/fixtures/diffAgentCommentBrowser.tsx',
      global: 'DiffAgentCommentTests',
      charset: true,
      setup: `
      window.webContents.on('console-message', details => {
        if (details.message.startsWith('input:')) window.webContents.sendInputEvent(JSON.parse(details.message.slice('input:'.length)));
      });
    `,
      body: `
      window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const results = await window.webContents.executeJavaScript('DiffAgentCommentTests.run()');
    `,
    },
  },
  {
    name: 'moves diff controls into the header and retains file-tree choices per layout in Chromium',
    expected: 1, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-diff-viewer-',
      entry: 'src/pulls/fixtures/diffViewerBrowser.tsx',
      global: 'DiffViewerTests',
    },
  },
  {
    name: 'renders persistent private review cards and safe diff anchors in Chromium',
    expected: 15, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-local-review-cards-',
      entry: 'src/pulls/local-review/fixtures/localReviewBrowser.tsx',
      global: 'LocalReviewTests',
      styles: true,
      charset: true,
      body: `
      window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const results = await window.webContents.executeJavaScript('LocalReviewTests.run()');
    `,
    },
  },
  {
    name: 'keeps private threads local until the user posts a drafted reply, in Chromium',
    expected: 9, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-private-thread-',
      entry: 'src/pulls/fixtures/privateThreadBrowser.tsx',
      global: 'PrivateThreadTests',
      styles: true,
      charset: true,
      width: 1000,
      height: 900,
    },
  },
]);
