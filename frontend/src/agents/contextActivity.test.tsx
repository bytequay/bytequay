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
import { parseDevelopmentContextActivity } from './contextActivity';
import { ActivityCard } from './DevelopmentContextActivityPanel';

describe('development repository note activity', () => {
  it('shows the exact version and match reason but strips saved bodies at the bridge', () => {
    const privateBody = 'PRIVATE_NOTE_BODY';
    const result = parseDevelopmentContextActivity({
      cards: [{
        reference: 'context-search:one', turnId: 'turn', kind: 'SEARCH', createdAt: '2026-09-24T00:00:00Z',
        notebookRevision: 7, delivery: 'CONFIRMED', bytes: 200, query: 'cache', paths: ['src/cache/'],
        entries: [{ id: 'lesson:one', version: 3, kind: 'LESSON', scopeId: 'project', title: 'Cache checks',
          matchReasons: ['Changed path'], included: true, body: privateBody }],
        omittedCount: 0, scopeStates: [], warnings: [], packet: privateBody,
      }], hasMore: false, usageTruncated: false,
    });
    const html = renderToStaticMarkup(<ActivityCard card={result.cards[0]} />);
    expect(html).toContain('Cache checks');
    expect(html).toContain('v3');
    expect(html).toContain('Changed path');
    expect(html).toContain('Results delivered');
    expect(html).not.toContain(privateBody);
    expect(JSON.stringify(result)).not.toContain(privateBody);
  });

  it('distinguishes complete note text, pointers and an empty offer', () => {
    const base = parseDevelopmentContextActivity({ cards: [{
      reference: 'context-offer:one', turnId: 'turn', kind: 'OFFER', createdAt: '2026-09-24T00:00:00Z',
      notebookRevision: 7, delivery: 'CONFIRMED', bytes: 20, query: '', paths: [], entries: [],
      omittedCount: 0, scopeStates: [], warnings: [],
    }], hasMore: false, usageTruncated: false }).cards[0];
    expect(renderToStaticMarkup(<ActivityCard card={base} />)).toContain('Turn accepted; no note provided');
    const pointer = { ...base, entries: [{ id: 'lesson:one', version: 3, kind: 'MEMORY', scopeId: 'project',
      title: 'Cache checks', matchReasons: [] as string[], included: false }] };
    expect(renderToStaticMarkup(<ActivityCard card={pointer} />)).toContain('Pointers delivered');
    expect(renderToStaticMarkup(<ActivityCard card={{ ...pointer, entries: [{ ...pointer.entries[0], included: true }] }} />))
      .toContain('Full note text provided');
  });

  it('offers Wrong only on note versions the task received in full or opened', () => {
    const entry = { id: 'lesson:one', version: 3, kind: 'MEMORY', scopeId: 'project', title: 'Cache checks', matchReasons: [] as string[], included: true };
    const card = (kind: 'OFFER' | 'SEARCH' | 'READ', included = true) => parseDevelopmentContextActivity({ cards: [{
      reference: `context-${kind}:one`, turnId: 'turn', kind, createdAt: '2026-09-24T00:00:00Z', notebookRevision: 7, delivery: 'CONFIRMED', bytes: 20,
      query: '', paths: [], entries: [{ ...entry, included }], omittedCount: 0, scopeStates: [], warnings: [] }], hasMore: false, usageTruncated: false }).cards[0];
    const reporter = { taskId: 'task-dev', workspaceId: 'workspace' };
    const wrong = (html: string) => html.includes('aria-label="Report Cache checks v3 as wrong"');
    expect(wrong(renderToStaticMarkup(<ActivityCard card={card('READ')} reporter={reporter} />))).toBe(true);
    expect(wrong(renderToStaticMarkup(<ActivityCard card={card('OFFER')} reporter={reporter} />))).toBe(true);
    expect(wrong(renderToStaticMarkup(<ActivityCard card={card('OFFER', false)} reporter={reporter} />))).toBe(false);
    expect(wrong(renderToStaticMarkup(<ActivityCard card={card('SEARCH')} reporter={reporter} />))).toBe(false);
    expect(wrong(renderToStaticMarkup(<ActivityCard card={card('READ')} />))).toBe(false);
  });
});
