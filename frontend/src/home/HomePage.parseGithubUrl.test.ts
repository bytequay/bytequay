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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecentEventDto } from '../types';
import { enrichActivityPrTitles, parseGithubUrl } from './HomePage';

afterEach(() => {
  delete (window as unknown as { bridge?: unknown }).bridge;
});

describe('parseGithubUrl', () => {
  it('parses a plain repo URL', () => {
    expect(parseGithubUrl('https://github.com/trinodb/trino'))
      .toEqual({ owner: 'trinodb', repo: 'trino' });
  });

  it('parses a PR URL with the number', () => {
    expect(parseGithubUrl('https://github.com/trinodb/trino/pull/42'))
      .toEqual({ owner: 'trinodb', repo: 'trino', prNumber: 42 });
  });

  it('returns null for issue URLs (no in-app issues page yet)', () => {
    expect(parseGithubUrl('https://github.com/trinodb/trino/issues/42')).toBeNull();
  });

  it('returns null for non-github hosts', () => {
    expect(parseGithubUrl('https://gitlab.com/foo/bar')).toBeNull();
    expect(parseGithubUrl('https://example.com/trinodb/trino')).toBeNull();
  });

  it('returns null for profile or root URLs', () => {
    expect(parseGithubUrl('https://github.com/octocat')).toBeNull();
    expect(parseGithubUrl('https://github.com/')).toBeNull();
  });

  it('returns null for non-numeric PR refs (defensive)', () => {
    expect(parseGithubUrl('https://github.com/trinodb/trino/pull/abc')).toBeNull();
    expect(parseGithubUrl('https://github.com/trinodb/trino/pull/0')).toBeNull();
  });

  it('returns null for malformed URLs', () => {
    expect(parseGithubUrl('not a url')).toBeNull();
  });

  it('ignores deeper PR sub-paths but still resolves the PR', () => {
    // /pull/42/files, /pull/42/commits — still navigate to the PR.
    expect(parseGithubUrl('https://github.com/trinodb/trino/pull/42/files'))
      .toEqual({ owner: 'trinodb', repo: 'trino', prNumber: 42 });
  });
});

describe('enrichActivityPrTitles', () => {
  it('looks up each missing PR title once', async () => {
    const getRepoPull = vi.fn(async () => ({ title: 'Fix memory accounting' }));
    window.bridge = { getRepoPull } as unknown as typeof window.bridge;
    const review: RecentEventDto = {
      type: 'PullRequestReviewEvent', repo: 'trinodb/trino', createdAt: '2026-07-19T00:00:00Z',
      commitCount: 0, action: 'created', prTitle: null, prNumber: 30384,
      refType: null, actorLogin: 'octocat',
    };

    const enriched = await enrichActivityPrTitles([review, { ...review }]);

    expect(getRepoPull).toHaveBeenCalledOnce();
    expect(getRepoPull).toHaveBeenCalledWith('trinodb', 'trino', 30384);
    expect(enriched.map(event => event.prTitle)).toEqual([
      'Fix memory accounting',
      'Fix memory accounting',
    ]);
  });
});
