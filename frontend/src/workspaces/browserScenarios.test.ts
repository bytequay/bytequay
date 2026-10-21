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
    name: 'starts issue development through the existing composer and retains source navigation in Chromium',
    expected: 9, timeout: 30_000,
    fixture: {
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
        await untilWidth(width);
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
    },
  },
  {
    name: 'filters loaded issues, keeps a closed issue in view and zooms a named issue in Chromium',
    expected: 5, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-issues-screen-',
      entry: 'src/workspaces/fixtures/issuesScreenBrowser.tsx',
      global: 'IssuesScreenTests',
      styles: true,
    },
  },
  {
    name: 'edits workspace relationships and opens their folded navigation within the current workspace',
    expected: 2, timeout: 120_000,
    fixture: { prefix: 'bytequay-related-',
      entry: 'src/workspaces/fixtures/relatedWorkspacesBrowser.tsx', global: 'RelatedWorkspacesTests', styles: true, charset: true,
      width: 1100, height: 850,
      setup: "window.webContents.on('console-message', event => { if (event.level === 'error') console.error(event.message); });" },
  },
]);
