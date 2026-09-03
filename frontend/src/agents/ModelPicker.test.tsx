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
      <ModelPickerCard view="models" models={MODELS} provider="Codex" selectedModel="gpt-5.6-sol" selectedEffort="low" onSelect={() => {}} onView={() => {}} />);
    expect(list).toContain('role="listbox"');
    // The lane heads its list, as the reference harness heads its models with the provider's name.
    expect(list).toContain('<div class="agt-picker__heading">Codex</div>');
    expect(list).toMatch(/<button[^>]*class="agt-picker__row is-current"[^>]*aria-selected="true"[^>]*>5\.6 Sol<span class="agt-picker__check"/);
    expect(list).toContain('>5.3 Codex Spark</button>');
    expect(list).not.toContain('Reliable.');
  });

  it('opens on two rows, Model and Effort, each naming the current choice', () => {
    const menu = renderToStaticMarkup(
      <ModelPickerCard view="menu" models={MODELS} selectedModel="gpt-5.6-sol" selectedEffort="medium" onSelect={() => {}} onView={() => {}} />);
    expect(menu).toContain('role="menu"');
    expect(menu).toMatch(/<button type="button" role="menuitem" class="agt-picker__item"><span>Model<\/span><span class="agt-picker__value">5\.6 Sol<\/span><svg class="chev"/);
    expect(menu).toMatch(/<span>Effort<\/span><span class="agt-picker__value">Medium<\/span>/);
    expect(menu).not.toContain('type="range"');
  });

  it('lists the effort levels of the chosen model with the current one checked', () => {
    const efforts = renderToStaticMarkup(
      <ModelPickerCard view="effort" models={MODELS} selectedModel="gpt-5.6-sol" selectedEffort="medium" onSelect={() => {}} onView={() => {}} />);
    expect(efforts).toContain('<div class="agt-picker__heading">Effort</div>');
    expect(efforts).toMatch(/class="agt-picker__row is-current"[^>]*aria-selected="true"[^>]*>Medium<span class="agt-picker__check"/);
    expect(efforts).toContain('>Extra high</button>');
    expect(efforts.match(/role="option"/g)).toHaveLength(5);
  });
});
