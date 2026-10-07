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
import { runBrowserFixture } from '../fixtures/browserFixture';

it('renders Mermaid in collapsed Markdown, follows the theme, and recovers from invalid source in Chromium', async () => {
  const result = await runBrowserFixture<{ passed: boolean }>({
    prefix: 'bytequay-mermaid-', entry: 'src/pulls/fixtures/mermaidBrowser.tsx', global: 'MermaidTests',
    styles: true, charset: true, width: 1440, height: 1000,
    body: `const results = await window.webContents.executeJavaScript('MermaidTests.run()');
      if (process.env.BYTEQUAY_MERMAID_CAPTURE) require('fs').writeFileSync(process.env.BYTEQUAY_MERMAID_CAPTURE, (await window.webContents.capturePage()).toPNG());`,
  });
  expect(result).toEqual({ passed: true });
}, 60_000);
