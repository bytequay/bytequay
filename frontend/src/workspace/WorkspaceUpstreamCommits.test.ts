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
import { contiguousRangeAfterToggle, rangeLabel } from './WorkspaceUpstreamCommits';

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
