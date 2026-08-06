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
import type { UpstreamCommitDto } from './workspaceApi';
import type { UpstreamCommitsDto } from './workspaceApi';
import {
  contiguousRangeAfterToggle,
  rangeLabel,
  suggestedTarget,
} from './WorkspaceUpstreamCommits';

describe('upstream commit range selection', () => {
  it('only grows and trims a contiguous range', () => {
    expect(contiguousRangeAfterToggle(null, 4)).toEqual([4, 4]);
    expect(contiguousRangeAfterToggle([4, 4], 7)).toEqual([4, 7]);
    expect(contiguousRangeAfterToggle([4, 7], 2)).toEqual([2, 7]);
    expect(contiguousRangeAfterToggle([2, 7], 2)).toEqual([3, 7]);
    expect(contiguousRangeAfterToggle([3, 7], 5)).toEqual([3, 4]);
    expect(contiguousRangeAfterToggle([3, 3], 3)).toBeNull();
  });

  it('uses live tag names for the selected range label', () => {
    const commit = (sha: string, tags: string[]): UpstreamCommitDto => ({
      sha, shortSha: sha, subject: sha, authorName: 'A', authorEmail: 'a@example.com',
      committedAt: null, tags, picked: false,
    });
    expect(rangeLabel([commit('c', ['v482']), commit('b', []), commit('a', ['v476'])]))
      .toBe('v476 → v482');
    expect(rangeLabel([commit('c', ['v482']), commit('b', [])], commit('a', ['v476'])))
      .toBe('v476 → v482');
    expect(rangeLabel([commit('a', [])])).toBe('contiguous range');
  });
});

describe('suggested cherry-pick branch name', () => {
  const snapshot = { upstreamRepoFullName: 'trinodb/trino' } as UpstreamCommitsDto;
  const commit = (sha: string): UpstreamCommitDto => ({
    sha, shortSha: sha.slice(0, 7), subject: sha, authorName: 'A',
    authorEmail: 'a@example.com', committedAt: null, tags: [], picked: false,
  });

  it('names the branch after the range, oldest to newest', () => {
    // The list runs newest-first, so the last row is the oldest pick.
    expect(suggestedTarget(snapshot, [commit('aaa5f0e1234567890'), commit('1c82dcb1234567890')]))
      .toBe('bump-trino-1c82dcb-to-aaa5f0e');
  });

  it('does not repeat itself for a single commit', () => {
    expect(suggestedTarget(snapshot, [commit('1c82dcb1234567890')]))
      .toBe('bump-trino-1c82dcb');
  });

  it('uses a typed range as typed', () => {
    expect(suggestedTarget(snapshot, [], '1c82dcb1234', 'aaa5f0e9999'))
      .toBe('bump-trino-1c82dcb-to-aaa5f0e');
  });

  it('two different ranges never propose the same branch', () => {
    // The old name was the same for every untagged range, so the second run
    // was refused for a name the first one had taken.
    const first = suggestedTarget(snapshot, [commit('bbb2222aaaa'), commit('aaa1111bbbb')]);
    const second = suggestedTarget(snapshot, [commit('ddd4444cccc'), commit('ccc3333dddd')]);
    expect(first).not.toBe(second);
  });

  it('falls back rather than emit a branch named after nothing', () => {
    expect(suggestedTarget(snapshot, [])).toBe('bump-trino-update');
    expect(suggestedTarget(snapshot, [], 'not-a-sha', 'nope')).toBe('bump-trino-update');
  });
});
