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
import { readFileSync } from 'node:fs';
import { runBrowserFixture, type ScenarioResult } from '../fixtures/browserFixture';

it('assigns Jira workspaces, opens ticket modals and starts a source-bound task in Chromium', async () => {
  const richDescription = readFileSync('../backend/app/src/test/resources/jira/rich-description.md', 'utf8');
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-jira-', entry: 'src/jira/fixtures/jiraBrowser.tsx', global: 'JiraTests', styles: true, charset: true, width: 1280, height: 920,
    setup: `
      window.webContents.on('console-message', details => {
        if (details.message.startsWith('input:')) window.webContents.sendInputEvent(JSON.parse(details.message.slice('input:'.length)));
      });
    `,
    body: `
      window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const results = await window.webContents.executeJavaScript(${JSON.stringify(`JiraTests.run(${JSON.stringify(richDescription)})`)});
    `,
  });
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
  expect(results).toHaveLength(23);
}, 30_000);
