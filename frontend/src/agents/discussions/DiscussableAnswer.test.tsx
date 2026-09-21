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

import { DiscussableAnswer, blockOf } from './DiscussableAnswer';
import { DiscussionFooter, discussionLabel, replyCount } from './DiscussionFooter';
import type { TaskDiscussionDto } from './contracts';

const ANSWER = [
  'It throws INVALID_TABLE_PROPERTY with the joined path.',
  '',
  'Two follow-ups are worth doing:',
  '',
  '- Reject a path that points at a map element.',
  '- Catch INVALID_TABLE_PROPERTY before validation runs.',
].join('\n');

function discussion(over: Partial<TaskDiscussionDto> = {}): TaskDiscussionDto {
  return {
    id: 'd1', taskId: 't1', turnId: 'turn-10', quote: 'INVALID_TABLE_PROPERTY', occurrence: 1,
    resolved: false, pending: false, providerLane: 'claude', version: 0,
    createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z',
    messages: [{ id: 'm1', author: 'USER', body: 'why?', createdAt: '2026-09-21T00:00:00Z', error: null }],
    ...over,
  };
}

describe('placing a footer', () => {
  it('anchors each appearance of a repeated quote to its own block', () => {
    const blocks = [
      'It throws INVALID_TABLE_PROPERTY with the joined path.',
      'Two follow-ups are worth doing:',
      'Reject a path that points at a map element. Catch INVALID_TABLE_PROPERTY before validation runs.',
    ];
    expect(blockOf(blocks, 'INVALID_TABLE_PROPERTY', 1)).toBe(0);
    expect(blockOf(blocks, 'INVALID_TABLE_PROPERTY', 2)).toBe(2);
  });

  it('falls back to the first block holding the text when the index has moved on', () => {
    expect(blockOf(['a INVALID_TABLE_PROPERTY b'], 'INVALID_TABLE_PROPERTY', 4)).toBe(0);
  });

  it('reports no block when the passage spans more than one', () => {
    expect(blockOf(['ends here', 'starts here'], 'ends here starts here', 1)).toBeNull();
  });

  it('renders one footer under each quoted block and the rest at the end', () => {
    const markup = renderToStaticMarkup(
      <DiscussableAnswer
        turnId="turn-10"
        text={ANSWER}
        discussions={[
          discussion({ id: 'first', occurrence: 1 }),
          discussion({ id: 'second', occurrence: 2 }),
          discussion({ id: 'adrift', quote: 'a passage no block holds' }),
        ]}
      />);
    // Each anchored row sits inside the answer, in the order its block appears.
    expect(markup.indexOf('data-discussion="first"')).toBeLessThan(markup.indexOf('Two follow-ups'));
    expect(markup.indexOf('Two follow-ups')).toBeLessThan(markup.indexOf('data-discussion="second"'));
    // A passage spanning blocks belongs to none of them, so its row is last and says why.
    expect(markup.indexOf('data-discussion="second"')).toBeLessThan(markup.indexOf('data-discussion="adrift"'));
    expect(markup).toContain('This passage spans more than one block');
  });
});

describe('a footer row', () => {
  it('counts answers rather than messages', () => {
    expect(replyCount(discussion())).toBe(0);
    expect(replyCount(discussion({ messages: [
      { id: 'm1', author: 'USER', body: 'why?', createdAt: '2026-09-21T00:00:00Z', error: null },
      { id: 'm2', author: 'AGENT', body: 'because', createdAt: '2026-09-21T00:00:00Z', error: null },
    ] }))).toBe(1);
  });

  it('reads as answering while a question is in flight', () => {
    const markup = renderToStaticMarkup(
      <DiscussionFooter discussions={[discussion({ pending: true })]} siblings={[]} />);
    expect(markup).toContain('answering…');
    expect(markup).not.toContain('0 replies');
  });

  it('separates two threads on the same text by their mention', () => {
    const siblings = [discussion({ id: 'a', occurrence: 1 }), discussion({ id: 'b', occurrence: 2 })];
    expect(discussionLabel(siblings[0], siblings).text).toBe('INVALID_TABLE_PROPERTY · 1st mention');
    expect(discussionLabel(siblings[1], siblings).text).toBe('INVALID_TABLE_PROPERTY · 2nd mention');
    // A quote nothing else shares needs no disambiguator.
    expect(discussionLabel(discussion(), [discussion()]).text).toBe('INVALID_TABLE_PROPERTY');
  });

  it('names a bare code token in mono without quote marks', () => {
    // It is a name, not a sentence.
    const label = discussionLabel(discussion(), []);
    expect(label).toEqual({ text: 'INVALID_TABLE_PROPERTY', mono: true, typed: false });
  });

  it('quotes prose, cutting at a word boundary and dropping trailing punctuation', () => {
    const label = discussionLabel(discussion({ quote: 'falls back to any descendant whose name matches the leaf.' }), []);
    expect(label.mono).toBe(false);
    expect(label.text).toBe('“falls back to any descendant whose name matches…”');
  });

  it('keeps the head and the tail of one long symbol rather than cutting from the left', () => {
    const label = discussionLabel(discussion({ quote: 'io.trino.plugin.iceberg.IcebergMetadata#getSortFields' }), []);
    expect(label.mono).toBe(true);
    expect(label.text.startsWith('io.trino')).toBe(true);
    expect(label.text.endsWith('getSortFields')).toBe(true);
    expect(label.text).toContain('…');
  });

  it('extends two passages that truncate alike to where they diverge', () => {
    const long = 'the coordinator limits splits per thread so that small tables ';
    const pair = [
      discussion({ id: 'a', quote: `${long}never use remote generation at all` }),
      discussion({ id: 'b', quote: `${long}always use remote generation instead` }),
    ];
    expect(discussionLabel(pair[0], pair).text).not.toBe(discussionLabel(pair[1], pair).text);
  });
});
