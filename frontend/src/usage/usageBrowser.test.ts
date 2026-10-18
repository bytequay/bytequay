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
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';

it('opens usage on demand, switches accounts, handles missing limits and supports Escape in both themes', async () => {
  const results = await runBrowserFixture<{ abovePace: boolean; belowPace: boolean; unavailable: boolean; apiAlias: boolean; closed: boolean }>({
    prefix: 'bytequay-usage-', entry: 'src/usage/usageBrowser.tsx', global: 'UsageTests', styles: true, width: 700, height: 600,
    setup: "require('electron').nativeTheme.themeSource = 'light';",
    body: `const results = await window.webContents.executeJavaScript('UsageTests.run()');
      if (process.env.BYTEQUAY_USAGE_CAPTURE) require('fs').writeFileSync(process.env.BYTEQUAY_USAGE_CAPTURE + '-light.png', (await window.webContents.capturePage()).toPNG());
      require('electron').nativeTheme.themeSource = 'dark';
      await window.webContents.executeJavaScript('UsageTests.checkLayout()');
      if (process.env.BYTEQUAY_USAGE_CAPTURE) require('fs').writeFileSync(process.env.BYTEQUAY_USAGE_CAPTURE + '-dark.png', (await window.webContents.capturePage()).toPNG());
      window.setSize(360, 600);
      await untilWidth(360);
      await window.webContents.executeJavaScript('UsageTests.checkLayout()');
      window.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Escape' });
      window.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Escape' });
      results.closed = await window.webContents.executeJavaScript('new Promise(resolve => requestAnimationFrame(() => resolve(UsageTests.checkClosed())))');`,
  });
  expect(results).toMatchObject({ abovePace: true, belowPace: true, unavailable: true, apiAlias: true, closed: true });
}, deadline(30_000));
