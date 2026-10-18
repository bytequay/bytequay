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
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import { assert, settle, until } from '../../fixtures/harness';
import { SettingsCard, SettingsRow, Toggle } from '../../settings/primitives';
import FeaturePreview, { type PreviewFeature } from '../FeaturePreview';
import '../../css/base.css';
import '../../css/settings-window.css';
import '../setup.css';

const FEATURES: PreviewFeature[] = ['today', 'assistant', 'development', 'automation'];
let root: Root | null = null;
const host = document.createElement('div');
document.body.append(host);

export async function mount() {
  if (root !== null) flushSync(() => root?.unmount());
  root = createRoot(host);
  flushSync(() => root?.render(<div className="setup-shell"><aside className="setup-sidebar" />
    <main className="setup-main"><div className="setup-body"><div className="setup-features">
      {FEATURES.map(feature => <section key={feature} className="setup-feature" data-feature={feature}>
        <SettingsRow title={feature === 'assistant' ? 'Assistant' : feature} />
        {feature === 'assistant' && <SettingsCard>
          <SettingsRow title="Start tasks when I ask" subtitle="Uses the automation preferences you chose.">
            <Toggle label="Assistant can start tasks" on={false} onChange={() => {}} />
          </SettingsRow>
          <SettingsRow title="Answer tasks when I ask" subtitle="Send your replies and answer supported approvals and questions.">
            <Toggle label="Assistant can answer tasks" on={false} onChange={() => {}} />
          </SettingsRow>
        </SettingsCard>}
        <FeaturePreview feature={feature} />
      </section>)}
    </div></div></main>
  </div>));
  await settle();
}

export async function appearance(dark: boolean) {
  await until(() => matchMedia('(prefers-color-scheme: dark)').matches === dark, 'The native theme must reach preview media queries');
  const brightness: number[] = [];
  for (const image of host.querySelectorAll<HTMLImageElement>('picture img')) {
    const source = image.parentElement?.querySelector('source');
    assert(source !== null && source !== undefined && source.srcset !== image.src, 'Light and dark previews need separate image assets');
    const expected = dark ? source.srcset : image.src;
    await until(() => image.currentSrc === expected && image.complete && image.naturalWidth > 0, `${image.alt} did not load the ${dark ? 'dark' : 'light'} image`);
    await image.decode();
    const canvas = document.createElement('canvas'); canvas.width = 48; canvas.height = 32;
    const context = canvas.getContext('2d'); assert(context !== null, 'Canvas must be available to inspect preview pixels');
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let total = 0;
    for (let i = 0; i < pixels.length; i += 4) total += (pixels[i] + pixels[i + 1] + pixels[i + 2]) / 3;
    brightness.push(total / (pixels.length / 4));
  }
  return { dark, brightness };
}

export async function playback(reduced: boolean) {
  await until(() => matchMedia('(prefers-reduced-motion: reduce)').matches === reduced, 'The browser must apply the motion preference');
  const previews = [...host.querySelectorAll<HTMLElement>('.setup-preview')];
  const running = (preview: HTMLElement) => preview.getAnimations({ subtree: true }).some(animation => animation.playState === 'running');
  for (const preview of previews) {
    const button = preview.querySelector('button'); assert(button !== null, 'Each preview needs playback controls');
    assert(button.textContent?.trim() === (reduced ? 'Play preview' : 'Pause preview'), 'The initial playback label must follow reduced motion');
    assert(button.getAttribute('aria-pressed') === String(!reduced), 'The control must expose whether the preview is playing');
    assert(running(preview) === !reduced, 'Only previews without reduced motion may autoplay');
    if (reduced) continue;
    button.click(); await settle();
    assert(button.textContent?.trim() === 'Play preview' && button.getAttribute('aria-pressed') === 'false' && !running(preview), 'Pause must stop the animation');
    button.click(); await settle();
    assert(button.textContent?.trim() === 'Pause preview' && button.getAttribute('aria-pressed') === 'true' && running(preview), 'Play must restart the animation');
  }
  return previews.length;
}

export async function featuresLayout() {
  await settle();
  const assistant = host.querySelector<HTMLElement>('[data-feature="assistant"]');
  assert(assistant !== null, 'The Features step must include Assistant');
  const image = assistant?.querySelector('img');
  const picture = image?.parentElement;
  const caption = assistant?.querySelector('figcaption');
  assert(image !== null && image !== undefined && picture != null && caption != null, 'Assistant must show its preview and caption');
  await image.decode();
  const animation = image.getAnimations()[0]; assert(animation !== undefined, 'Assistant must animate on the Features step');
  animation.pause(); animation.currentTime = 0; await settle();
  const start = image.getBoundingClientRect();
  animation.currentTime = Number(animation.effect?.getTiming().duration) / 2; await settle();
  const middle = image.getBoundingClientRect();
  assert(Math.abs(middle.width - start.width) + Math.abs(middle.height - start.height) + Math.abs(middle.x - start.x) + Math.abs(middle.y - start.y) > 1,
    `Assistant image must visibly move at ${innerWidth}px, not only report a running animation`);
  assert(['hidden', 'clip'].includes(getComputedStyle(picture).overflow), 'The moving image must be clipped inside its picture');
  const frame = picture.getBoundingClientRect();
  assert(frame.bottom <= caption.getBoundingClientRect().top + 1, 'The clipped preview must not overlap its caption');
  assert(middle.left <= frame.left + 1 && middle.right >= frame.right - 1 && middle.top <= frame.top + 1 && middle.bottom >= frame.bottom - 1,
    'The moving image must continue to fill its clipped frame');
  animation.play();
  const rows = [...assistant.querySelectorAll<HTMLElement>('.setw-card .setw-row')];
  for (const row of rows) {
    const content = row.querySelector('.setw-row-main'); const controls = row.querySelector('.setw-row-controls');
    assert(content !== null && controls !== null, 'Assistant permissions need labels and toggles');
    const bounds = row.getBoundingClientRect();
    assert(content.getBoundingClientRect().left - bounds.left >= 12 && bounds.right - controls.getBoundingClientRect().right >= 12,
      `Nested Assistant permissions must retain both card gutters at ${innerWidth}px`);
  }
  return { width: innerWidth, permissions: rows.length };
}
