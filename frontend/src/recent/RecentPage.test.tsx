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
import type { RecentRowDto } from '../types';
import { Row } from './RecentPage';

const row: RecentRowDto = {
  id: 'pr:trinodb/trino#30835@2026-08-28',
  type: 'PULL_REQUEST',
  itemKey: 'pr:trinodb/trino#30835',
  number: 30835,
  title: 'Fix memory blob cache caching partial content with stale length',
  repo: 'trinodb/trino',
  workspaceId: null,
  mine: true,
  touchedAt: new Date(2026, 7, 28, 14, 14).toISOString(),
  reason: 'PUSHED',
  reasonLabel: 'Pushed 2',
  htmlUrl: 'https://github.com/trinodb/trino/pull/30835',
  subtitle: 'ezra-b',
  snippet: 'Nested field ordering is resolved per split here.',
  textId: 'pr:trinodb/trino#30835:comment:1',
};

function render(props: Partial<Parameters<typeof Row>[0]> = {}) {
  return renderToStaticMarkup(
    <Row row={row} change={undefined} open={false} onToggle={() => {}} onOpen={() => {}} {...props} />,
  );
}

describe('a stream row', () => {
  it('keeps the number outside the truncating span so it survives the ellipsis', () => {
    const markup = render();
    expect(markup).toContain('<span class="rc-row__text">Fix memory blob cache');
    expect(markup).toContain('<span class="rc-row__number">#30835</span>');
    // The number must not sit inside the element that ellipsises.
    expect(markup).not.toMatch(/rc-row__text">[^<]*#30835/);
  });

  it('tones the badge by the reason and colours the glyph by role', () => {
    const markup = render();
    expect(markup).toContain('rc-badge is-green">Pushed 2');
    expect(markup).toContain('rc-glyph rc-row__glyph is-mine');
    expect(render({ row: { ...row, mine: false } })).toContain('rc-glyph rc-row__glyph is-theirs');
  });

  it('shows the repository owner and the author beside the metadata', () => {
    const markup = render();
    expect(markup).toContain('aria-label="trinodb/trino"');
    expect(markup).toContain('alt="ezra-b"');
  });

  it('shows no author face on a row whose subtitle is not a person', () => {
    // A commit's subtitle is its sha and a task's is its lane.
    const markup = render({ row: { ...row, type: 'COMMIT', number: null, subtitle: 'a1b2c3d' } });
    expect(markup).toContain('aria-label="trinodb/trino"');
    expect(markup).not.toContain('alt="a1b2c3d"');
  });

  it('leads with what changed when something moved since the last look', () => {
    const markup = render({
      change: {
        itemKey: row.itemKey,
        moved: true,
        wantsYou: false,
        summary: '2 new replies and CI went red since you read it',
      },
    });
    expect(markup).toContain('2 new replies and CI went red since you read it');
    expect(markup).toContain('rc-dot is-moved');
  });

  it('expands in place to the text the user wrote, with a copy that never navigates', () => {
    const markup = render({ open: true });
    expect(markup).toContain('WHAT YOU WROTE');
    expect(markup).toContain('Nested field ordering is resolved per split here.');
    expect(markup).toContain('Copy this text');
    expect(markup).toContain('Open the thread');
  });

  it('offers nothing to expand into when the user wrote nothing here', () => {
    // The row still opens the item on click; there is just no panel to show.
    const markup = render({ open: true, row: { ...row, snippet: null, textId: null } });
    expect(markup).not.toContain('rc-wrote');
    expect(markup).not.toContain('aria-expanded');
  });
});
