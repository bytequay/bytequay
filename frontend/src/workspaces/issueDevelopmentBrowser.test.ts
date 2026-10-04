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

it('starts issue development through the existing composer and retains source navigation in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-issue-development-',
    entry: 'src/workspaces/fixtures/issueDevelopmentBrowser.tsx',
    global: 'IssueDevelopmentTests',
    styles: true,
    charset: true,
    body: `
      window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const results = await window.webContents.executeJavaScript('IssueDevelopmentTests.run()');
      for (const width of [1060, 600]) {
        window.setSize(width, 820);
        const source = await window.webContents.executeJavaScript('IssueDevelopmentTests.mountIssueConversation()');
        await window.webContents.debugger.sendCommand('Input.dispatchMouseEvent', { type: 'mouseMoved', ...source });
        await window.webContents.executeJavaScript('IssueDevelopmentTests.verifyIssuePreview()');
        await window.webContents.debugger.sendCommand('Input.dispatchMouseEvent', { type: 'mouseMoved', x: width - 5, y: 5 });
        await window.webContents.executeJavaScript('IssueDevelopmentTests.verifyIssueKeyboard()');
        await window.webContents.executeJavaScript('IssueDevelopmentTests.unmountIssueConversation()');
        await window.webContents.executeJavaScript('IssueDevelopmentTests.mountIssueConversation(true)');
        await window.webContents.executeJavaScript('IssueDevelopmentTests.unmountIssueConversation()');
      }
    `,
  });
  expect(results).toHaveLength(6);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, 30_000);
