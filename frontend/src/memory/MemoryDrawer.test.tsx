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
import type { MemoryDto } from './contracts';
import { MemoryDrawer } from './MemoryDrawer';

const memory: MemoryDto = {
  id: 'm4', kind: 'RULE', text: 'Keep partition values out of data file paths.', scope: ['plugin/trino-delta-lake/'],
  sources: [{ kind: 'PULL_REQUEST', url: 'https://github.com/trinodb/trino/pull/28770#discussion_r1', quote: 'We use random prefixes for data files.',
    at: '2026-09-01T00:00:00Z', section: null, pullRequest: { number: 28770, part: 'REVIEW_COMMENT', author: 'bo', maintainer: true, merged: true, resolved: true,
      outdated: false }, user: null, command: null }],
  origin: { user: false, description: 'Learn from my PRs', taskId: 'task' }, version: 2, previous: { text: 'Partition values stay out of paths.', scope: [] },
  delivered: 6, cited: 0, reports: [{ reason: 'The writer now encodes partition values', quote: 'path(partition)', taskId: 'task', at: '2026-10-01T00:00:00Z' }],
  missingScope: [], createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
};
const render = (value: MemoryDto) => renderToStaticMarkup(<MemoryDrawer memory={value} busy={false} now={Date.parse('2026-10-02T00:00:00Z')} onClose={() => {}}
  onSave={() => {}} onRestore={() => {}} onKeep={() => {}} onDelete={() => {}} onOpen={() => {}} />);

describe('MemoryDrawer', () => {
  it('shows the flag with Keep, every source with its quote, and the previous version with Restore', () => {
    const markup = render(memory);
    expect(markup).toContain('Reported wrong: The writer now encodes partition values');
    expect(markup).toContain('Keep as it is');
    expect(markup).toContain('PR #28770 · review comment · @bo, maintainer · merged');
    expect(markup).toContain('We use random prefixes for data files.');
    expect(markup).toContain('Applied: thread resolved');
    expect(markup).toContain('Partition values stay out of paths.');
    expect(markup).toContain('Restore');
    expect(markup).not.toContain('agents won’t change it');
  });

  it('marks a memory the user owns and offers no Keep without a report', () => {
    const markup = render({ ...memory, origin: { user: true, description: 'Edited by you', taskId: null }, reports: [], previous: null });
    expect(markup).toContain('Yours · agents won’t change it');
    expect(markup).not.toContain('Keep as it is');
    expect(markup).not.toContain('Previous version');
  });

  it('renders Markdown in source quotes', () => {
    const markup = render({ ...memory, sources: [{ ...memory.sources[0],
      quote: 'Only the **connector call** is external.\n`checkState` throws a `TrinoException`.\n\n- Keep `PERMISSION_DENIED`.\n- Preserve internal errors.',
    }] });
    expect(markup).toContain('class="markdown-body mem-source__quote"');
    expect(markup).toContain('<strong>connector call</strong>');
    expect(markup).toContain('<br/><code>checkState</code> throws a <code>TrinoException</code>.');
    expect(markup).toContain('<ul><li>Keep <code>PERMISSION_DENIED</code>.</li><li>Preserve internal errors.</li></ul>');
    expect(markup).not.toContain('`');
  });
});
