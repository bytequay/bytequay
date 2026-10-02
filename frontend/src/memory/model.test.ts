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
import { parseMemoryHarvestStarted, parseMemoryId, parseMemoryPage, parseStartMemoryHarvestRequest } from './contracts';
import type { MemoryDto, MemorySourceDto } from './contracts';
import { age, flagged, kilobytes, matches, memoryFlags, pageLabel, sourceChip, sourceHead } from './model';

const review = (number: number, overrides: Partial<NonNullable<MemorySourceDto['pullRequest']>> = {}): MemorySourceDto => ({
  kind: 'PULL_REQUEST', url: `https://github.com/trinodb/trino/pull/${number}`, quote: 'Docs, please.', at: '2026-10-01T00:00:00Z', section: null,
  pullRequest: { number, part: 'REVIEW_COMMENT', author: 'alice', maintainer: true, merged: true, resolved: true, outdated: false, ...overrides }, user: null,
  command: null,
});
const memory = (overrides: Partial<MemoryDto> = {}): MemoryDto => ({
  id: 'm12', kind: 'RULE', text: 'Document every new table property on the connector page.', scope: ['plugin/'], sources: [review(31201), review(30877)],
  origin: { user: false, description: 'Learn from my PRs', taskId: 'task' }, version: 1, previous: null, delivered: 3, cited: 1, reports: [], missingScope: [],
  createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', ...overrides,
});

describe('memory model', () => {
  it('flags a reported memory and scope paths gone from the head, without hiding it', () => {
    expect(flagged(memory())).toBe(false);
    const reported = memory({ reports: [{ reason: 'The writer now encodes partitions', quote: '', taskId: 'task', at: '2026-10-02T00:00:00Z' }], missingScope: ['plugin/x/'] });
    expect(memoryFlags(reported)).toEqual([
      { title: 'Reported wrong', detail: 'The writer now encodes partitions' },
      { title: 'Scope gone', detail: 'plugin/x/ is not at the monitored head' },
    ]);
  });

  it('labels sources as the page and the drawer state them', () => {
    expect(sourceChip(memory())).toEqual({ kind: 'PR', label: '#31201 @alice', more: '2 PRs' });
    expect(sourceChip(memory({ sources: [review(31007, { merged: false, part: 'CLOSE' })] }))).toEqual({ kind: 'PR', label: '#31007 @alice', more: 'closed' });
    expect(sourceHead(review(31201))).toEqual({ head: 'PR #31201 · review comment · @alice, maintainer · merged', note: 'Applied: thread resolved', link: 'Open on GitHub' });
    expect(sourceHead(review(31007, { merged: false, part: 'CLOSE', resolved: false })).note).toBe('A maintainer explained the close');
    const page: MemorySourceDto = { kind: 'PAGE', url: 'https://github.com/delta-io/delta/blob/master/PROTOCOL.md', quote: '', at: '2026-10-01T00:00:00Z',
      section: 'Checkpoints', pullRequest: null, user: null, command: null };
    expect(sourceHead(page)).toEqual({ head: 'Page · github.com/delta-io/delta/blob/master/PROTOCOL.md · Checkpoints', note: '', link: 'Open page' });
    expect(sourceChip(memory({ kind: 'SPEC', sources: [page] }))).toEqual({ kind: 'PAGE', label: 'github.com · PROTOCOL.md', more: '' });
    expect(pageLabel('https://iceberg.apache.org/')).toBe('iceberg.apache.org');
  });

  it('labels the user’s words and a passing command, which have no address', () => {
    const words: MemorySourceDto = { kind: 'USER', url: null, quote: 'Keep refactors in their own commits.', at: '2026-10-02T00:00:00Z', section: null,
      pullRequest: null, user: { where: 'REVIEW_MESSAGE', id: 'review', item: 'message' }, command: null };
    const passed: MemorySourceDto = { kind: 'COMMAND', url: null, quote: './mvnw -pl core test', at: '2026-10-02T00:00:00Z', section: null, pullRequest: null,
      user: null, command: { taskId: 'task', turnId: 'turn', command: './mvnw -pl core test', directory: 'core', commit: 'c'.repeat(40) } };
    expect(sourceHead(words)).toEqual({ head: 'You, in a review', note: 'Your words', link: null });
    expect(sourceHead(passed)).toEqual({ head: 'Command passed in core at ccccccc', note: './mvnw -pl core test', link: null });
    const recipe = memory({ kind: 'RECIPE', sources: [passed] });
    expect(sourceChip(recipe)).toEqual({ kind: 'COMMAND', label: 'Passed', more: 'ccccccc' });
    expect(sourceChip(memory({ sources: [words] }))).toEqual({ kind: 'USER', label: 'You, in a review', more: '' });
    expect(matches(recipe, 'RECIPE', 'TASK', '')).toBe(true);
    expect(matches(memory(), 'ALL', 'TASK', '')).toBe(false);
  });

  it('filters by kind, source and literal text', () => {
    expect(matches(memory(), 'ALL', 'ANY', 'connector page')).toBe(true);
    expect(matches(memory(), 'SPEC', 'ANY', '')).toBe(false);
    expect(matches(memory(), 'ALL', 'USER', '')).toBe(false);
    expect(matches(memory({ origin: { user: true, description: 'Edited by you', taskId: null } }), 'ALL', 'USER', '')).toBe(true);
    expect(matches(memory(), 'ALL', 'PAGE', '')).toBe(false);
    expect(matches(memory(), 'ALL', 'ANY', 'plugin/')).toBe(true);
    expect(matches(memory(), 'ALL', 'ANY', '@alice')).toBe(true);
    expect(matches(memory(), 'ALL', 'ANY', 'iceberg')).toBe(false);
  });

  it('states ages and sizes compactly', () => {
    const now = Date.parse('2026-10-02T00:00:00Z');
    expect(age('2026-10-01T23:30:00Z', now)).toBe('now');
    expect(age('2026-10-01T19:00:00Z', now)).toBe('5h');
    expect(age('2026-09-29T00:00:00Z', now)).toBe('3d');
    expect(age('2026-09-10T00:00:00Z', now)).toBe('3w');
    expect(age('2026-06-01T00:00:00Z', now)).toBe('4mo');
    expect(kilobytes(17 * 1024 + 1)).toBe('18 KB');
    expect(kilobytes(10)).toBe('1 KB');
  });
});

describe('memory contracts', () => {
  it('reads the page the backend sends and refuses malformed values', () => {
    const source = { ...review(31201), pullRequest: { ...review(31201).pullRequest, commentId: '7' } };
    const stored: Record<string, unknown> = { id: 'm12', workspaceId: 'w', kind: 'RULE', text: 'Rule', scope: [], sources: [source], origin: { user: false, description: 'Learn', taskId: null },
      version: 1, previous: { text: 'Old', scope: ['plugin/'] }, delivered: 0, cited: 0, reports: [], createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' };
    const page = parseMemoryPage({ memories: [{ memory: stored, missingScope: ['plugin/'] }], switches: { reviews: true, development: false },
      guidelines: [{ path: 'CONTRIBUTING.md', bytes: 5120, cut: false, url: 'https://github.com/trinodb/trino/blob/a/CONTRIBUTING.md' }], head: null,
      harvest: { cursor: { number: 31201, closedAt: '2026-10-01T00:00:00Z' }, last: { harvestId: 'h', label: 'Learn from my PRs', startedAt: '2026-10-02T00:00:00Z',
        endedAt: null, pulls: 3, threads: 7, saved: ['m12'], seenAgain: 1, replaced: 0, outcome: 'RUNNING' } } });
    expect(page.memories[0]).toMatchObject({ id: 'm12', missingScope: ['plugin/'], previous: { text: 'Old', scope: ['plugin/'] } });
    expect(page.switches).toEqual({ reviews: true, development: false });
    expect(page.head).toBeNull();
    expect(page.harvest.cursor?.number).toBe(31201);
    expect(page.harvest.last?.saved).toEqual(['m12']);
    expect(() => parseMemoryPage({ ...page, memories: [{ memory: { ...stored, kind: 'NOTE' }, missingScope: [] }] })).toThrow('Malformed memory kind');
    expect(() => parseMemoryId('m0')).toThrow('Malformed memory id');
    expect(parseMemoryId('m12')).toBe('m12');
  });

  it('checks a harvest request before it leaves the renderer and reads what started', () => {
    expect(parseStartMemoryHarvestRequest({ requestKey: 'k', expectedBuildSequence: 2, pull: null, repo: null }))
      .toEqual({ requestKey: 'k', expectedBuildSequence: 2, pull: null, repo: null });
    expect(parseStartMemoryHarvestRequest({ requestKey: 'k', expectedBuildSequence: 2, pull: 31240, repo: 'trinodb/trino' }).pull).toBe(31240);
    expect(() => parseStartMemoryHarvestRequest({ requestKey: 'k', expectedBuildSequence: 2, pull: 31240, repo: null })).toThrow('Malformed harvest request');
    expect(() => parseStartMemoryHarvestRequest({ requestKey: 'k', expectedBuildSequence: 2, pull: null, repo: null, token: 'x' })).toThrow('Malformed harvest request');
    expect(() => parseStartMemoryHarvestRequest({ requestKey: 'k', expectedBuildSequence: 2, pull: 1, repo: '../x' })).toThrow('Malformed harvest request');
    const started = parseMemoryHarvestStarted({ started: true, pulls: 12, threads: 31, view: { buildRequestSequence: 4, canStart: false, build: null } }, 'w');
    expect(started).toEqual({ started: true, pulls: 12, threads: 31, view: { buildRequestSequence: 4, canStart: false, build: null } });
  });
});
