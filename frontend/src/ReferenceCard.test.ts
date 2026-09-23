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
import { describe, expect, it } from 'vitest';
import { anchorPanel, clampCardOrigin, previewMarkdown, stateChip } from './ReferenceCard';
import { runBrowserFixture } from './fixtures/browserFixture';

describe('clampCardOrigin', () => {
  const card = { width: 480, height: 400 };
  const view = { width: 1200, height: 800 };

  it('keeps a moved card where it was dropped inside the window', () => {
    expect(clampCardOrigin(300, 120, card, view)).toEqual({ left: 300, top: 120 });
  });

  it('lets half the width pass the right edge and 30% of the height pass the bottom', () => {
    expect(clampCardOrigin(1190, 790, card, view)).toEqual({ left: 960, top: 520 });
  });

  it('holds the top left corner on screen', () => {
    expect(clampCardOrigin(-40, -90, card, view)).toEqual({ left: 0, top: 0 });
    expect(clampCardOrigin(10, 10, card, { width: 200, height: 200 })).toEqual({ left: 0, top: 0 });
  });
});

describe('stateChip', () => {
  it('names the state a surface has no reason of its own to explain', () => {
    expect(stateChip('OPEN')?.label).toBe('Open');
    expect(stateChip('open', true)?.label).toBe('Draft');
    expect(stateChip('merged')?.label).toBe('Merged');
    expect(stateChip('closed', true)?.label).toBe('Closed');
    expect(stateChip(null)).toBeNull();
    expect(stateChip('unknown')).toBeNull();
  });
});

describe('anchorPanel', () => {
  const viewport = { width: 1000, height: 800 };
  const row = (top: number) => ({ top, bottom: top + 44, left: 100, right: 620 });

  it('opens below the anchor when there is room', () => {
    expect(anchorPanel(row(200), 210, 160, 410, viewport)).toEqual({ top: 250, left: 410 });
  });

  it('flips above the anchor when the panel would fall off the bottom', () => {
    expect(anchorPanel(row(700), 210, 160, 410, viewport)).toEqual({ top: 534, left: 410 });
  });

  it('clamps to both side edges', () => {
    expect(anchorPanel(row(200), 428, 320, 900, viewport).left).toBe(560);
    expect(anchorPanel(row(200), 428, 320, -40, viewport).left).toBe(12);
  });

  it('never pushes a panel taller than the viewport off the top', () => {
    expect(anchorPanel(row(60), 210, 900, 410, viewport).top).toBe(10);
  });
});

describe('previewMarkdown', () => {
  it('keeps the Markdown but drops comments, images and blank runs', () => {
    const body = [
      '<!-- issue template, do not remove -->',
      '',
      '## Description',
      '',
      '',
      '',
      'Restores the **Kudu** connector, see [the issue](https://x/1).',
      '',
      '![build badge](https://img.shields.io/badge.svg)',
      '<img src="https://x/logo.png">',
    ].join('\n');

    expect(previewMarkdown(body)).toBe(
      '## Description\n\nRestores the **Kudu** connector, see [the issue](https://x/1).');
    expect(previewMarkdown('')).toBe('');
    expect(previewMarkdown(null)).toBe('');
  });
});

it('shows a short hover card beside its link and resolves a pull-shaped issue link in Chromium', async () => {
  await runBrowserFixture({
    prefix: 'bytequay-reference-card-',
    entry: 'src/fixtures/referenceCardBrowser.tsx',
    global: 'ReferenceCardTests',
    styles: true,
    width: 900,
    height: 800,
  });
}, 30_000);
