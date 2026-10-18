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
import { deadline, runBrowserFixture } from '../fixtures/browserFixture';

it('renders native theme previews and plays them unless reduced motion is requested', async () => {
  const results = await runBrowserFixture<{
    themes: { mode: string; dark: boolean; brightness: number[] }[];
    playback: number[];
    layouts: { width: number; permissions: number }[];
  }>({
    prefix: 'bytequay-feature-preview-', entry: 'src/onboarding/fixtures/featurePreviewBrowser.tsx', global: 'FeaturePreviewTests',
    styles: true, width: 1180, height: 860, timeoutMs: 30_000,
    body: `
      await window.webContents.executeJavaScript('FeaturePreviewTests.mount()');
      const results = { themes: [], playback: [], layouts: [] };
      for (const mode of ['light', 'dark', 'system']) {
        nativeTheme.themeSource = mode;
        results.themes.push({ mode, ...await window.webContents.executeJavaScript('FeaturePreviewTests.appearance(' + nativeTheme.shouldUseDarkColors + ')') });
      }
      results.playback.push(await window.webContents.executeJavaScript('FeaturePreviewTests.playback(false)'));
      for (const width of [1600, 1180, 800]) {
        window.setSize(width, 860); await untilWidth(width);
        results.layouts.push(await window.webContents.executeJavaScript('FeaturePreviewTests.featuresLayout()'));
      }
      window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setEmulatedMedia', {
        features: [{ name: 'prefers-reduced-motion', value: 'reduce' }],
      });
      await window.webContents.executeJavaScript('FeaturePreviewTests.mount()');
      results.playback.push(await window.webContents.executeJavaScript('FeaturePreviewTests.playback(true)'));
    `,
  });
  expect(results.themes.map(result => result.mode)).toEqual(['light', 'dark', 'system']);
  expect(results.themes.map(result => result.dark).slice(0, 2)).toEqual([false, true]);
  for (const result of results.themes) {
    expect(result.brightness).toHaveLength(3);
    for (const brightness of result.brightness) expect(brightness > 128, `${result.mode} preview brightness: ${brightness}`).toBe(!result.dark);
  }
  expect(results.playback).toEqual([4, 4]);
  expect(results.layouts).toEqual([1600, 1180, 800].map(width => ({ width, permissions: 2 })));
}, deadline(30_000));
