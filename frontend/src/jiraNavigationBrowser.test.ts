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
import { runBrowserFixture, deadline } from './fixtures/browserFixture';

it('updates Jira visibility, history and native shortcut navigation in the running app', async () => {
  const results = await runBrowserFixture<{ name: string }[]>({
    prefix: 'bytequay-jira-navigation-',
    entry: 'src/fixtures/jiraNavigationBrowser.tsx',
    global: 'JiraNavigationTests',
    styles: true,
    width: 1000,
    height: 700,
  });
  expect(results).toHaveLength(3);
}, deadline(30_000));
