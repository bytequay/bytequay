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
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { KeyboardEvent, ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { Card, Clickable, IconButton, ItemRow, Pill } from './Card';

const css = readFileSync(resolve(process.cwd(), 'src/css/cards.css'), 'utf8');

function press(element: ReactElement<{ onKeyDown: (event: KeyboardEvent) => void }>, key: string, fromInside = false) {
  const target = {};
  const preventDefault = vi.fn();
  element.props.onKeyDown({ key, target: fromInside ? {} : target, currentTarget: target, preventDefault } as unknown as KeyboardEvent);
  return preventDefault;
}

describe('card base', () => {
  it('opens a card or row on Enter or Space, but not on keys meant for a control inside it', () => {
    const onOpen = vi.fn();
    const row = Clickable({ onOpen, children: 'Row' }) as ReactElement<{ onKeyDown: (event: KeyboardEvent) => void }>;

    expect(press(row, 'Enter')).toHaveBeenCalled();
    expect(press(row, ' ')).toHaveBeenCalled();
    expect(onOpen).toHaveBeenCalledTimes(2);
    press(row, 'a');
    press(row, 'Enter', true);
    expect(onOpen).toHaveBeenCalledTimes(2);
  });

  it('draws one box, row, pill and button for every card', () => {
    expect(renderToStaticMarkup(<Card compact selected className="goal-card" label="Open #1" onOpen={() => {}}>x</Card>))
      .toBe('<div class="card is-compact is-selected goal-card" role="button" tabindex="0" aria-label="Open #1">x</div>');
    expect(renderToStaticMarkup(<Pill outline>Review requested</Pill>)).toBe('<span class="pill is-outline">Review requested</span>');
    expect(renderToStaticMarkup(<Pill tone="red" className="is-square">Blocked</Pill>)).toBe('<span class="pill is-red is-square">Blocked</span>');
    expect(renderToStaticMarkup(<IconButton label="Delete task" tone="danger" onClick={() => {}}>x</IconButton>))
      .toBe('<button type="button" class="card-iconbtn is-danger" title="Delete task" aria-label="Delete task">x</button>');
    expect(renderToStaticMarkup(<ItemRow label="Open #1" onOpen={() => {}} icon="i" title="Fix it" marks={<span className="card-key">#1</span>} meta="alice authored" age="2d">
      Latest</ItemRow>)).toBe('<div class="card-row item-row" role="button" tabindex="0" aria-label="Open #1"><span class="item-row__icon">i</span>'
      + '<div class="item-row__body"><div class="item-row__title"><span title="Fix it">Fix it</span><span class="card-key">#1</span></div>'
      + '<div class="item-row__meta">alice authored</div>Latest</div>2d</div>');
  });

  it('rings every focusable part of a card, since the app has no global focus ring', () => {
    for (const selector of [':is(.card, .card-row):focus-visible', ':is(.card-iconbtn, .item-person):focus-visible']) {
      const start = css.indexOf(`${selector} {`);
      expect(start, `${selector} is missing from cards.css`).toBeGreaterThan(-1);
      expect(css.slice(start, css.indexOf('}', start))).toContain('outline: 2px solid var(--control-strong)');
    }
  });
});
