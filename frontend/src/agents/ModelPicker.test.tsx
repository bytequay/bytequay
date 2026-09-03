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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ModelPicker, ModelPickerCard } from './ModelPicker';
import type { ModelOptionDto } from '../types';

const MODELS: ModelOptionDto[] = [
  { id: 'gpt-5.6-sol', name: 'GPT-5.6-Sol', description: 'Reliable.', efforts: ['low', 'medium', 'high', 'xhigh', 'max'], defaultEffort: 'medium', inputModalities: ['text'] },
  { id: 'gpt-5.3-codex-spark', name: 'GPT-5.3-Codex-Spark', description: 'Fast.', efforts: ['high'], defaultEffort: 'high', inputModalities: ['text'] },
];

describe('model picker', () => {
  it('names the model and effort on the chip the way Codex does', () => {
    const chip = renderToStaticMarkup(
      <ModelPicker ariaLabel="Model" models={MODELS} selectedModel="gpt-5.6-sol" selectedEffort="xhigh" onSelect={() => {}} />);
    expect(chip).toContain('5.6 Sol');
    expect(chip).toContain('Extra high');
    expect(chip).not.toContain('GPT-5.6-Sol');
    expect(renderToStaticMarkup(<ModelPicker ariaLabel="Model" models={MODELS} selectedModel="" onSelect={() => {}} />))
      .toContain('Select model');
  });

  it('lists the models by short name with the current one checked', () => {
    const list = renderToStaticMarkup(
      <ModelPickerCard view="models" models={MODELS} selectedModel="gpt-5.6-sol" selectedEffort="low" onSelect={() => {}} onView={() => {}} />);
    expect(list).toContain('role="listbox"');
    expect(list).toMatch(/<button[^>]*class="agt-picker__row is-current"[^>]*aria-selected="true"[^>]*>5\.6 Sol<span class="agt-picker__check"/);
    expect(list).toContain('>5.3 Codex Spark</button>');
    expect(list).not.toContain('Reliable.');
  });

  it("sets the effort on a slider with one stop per level, purple at the top", () => {
    const mid = renderToStaticMarkup(
      <ModelPickerCard view="effort" models={MODELS} selectedModel="gpt-5.6-sol" selectedEffort="medium" onSelect={() => {}} onView={() => {}} />);
    // A native range input: dragging, arrow keys and assistive technology come with it.
    expect(mid).toMatch(/<input type="range"[^>]*min="0"[^>]*max="4"[^>]*step="1"[^>]*value="1"/);
    expect(mid).toContain('aria-valuetext="Medium"');
    expect(mid).toContain('--fill:25%');
    expect(mid.match(/class="agt-picker__stop"/g)).toHaveLength(4);
    expect(mid.match(/class="agt-picker__stop is-under-thumb"/g)).toHaveLength(1);
    expect(mid).toContain('<span class="agt-picker__level">Medium</span>');
    expect(mid).not.toContain('is-max');

    const top = renderToStaticMarkup(
      <ModelPickerCard view="effort" models={MODELS} selectedModel="gpt-5.6-sol" selectedEffort="max" onSelect={() => {}} onView={() => {}} />);
    expect(top).toContain('agt-picker__slider is-max');
    expect(top).toContain('--fill:100%');

    // One level is a fact, not a slider.
    const single = renderToStaticMarkup(
      <ModelPickerCard view="effort" models={MODELS} selectedModel="gpt-5.3-codex-spark" selectedEffort="high" onSelect={() => {}} onView={() => {}} />);
    expect(single).not.toContain('type="range"');
    // Static markup escapes the apostrophe, so the sentence is checked around it.
    expect(single).toContain('High is this model');
    expect(single).toContain('only effort level.');
  });
});
