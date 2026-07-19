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
import { describe, it, expect } from 'vitest';
import { followingNarrativeSegments, groupRecentEvents, repoUrl, prUrl, issueUrl, commitsUrl } from './activityNarrative';
import type { RecentEventDto } from './types';

function event(overrides: Partial<RecentEventDto> = {}): RecentEventDto {
  return {
    type: 'PullRequestEvent',
    repo: 'trinodb/trino',
    createdAt: '2026-04-29T12:00:00Z',
    commitCount: 0,
    action: 'opened',
    prTitle: null,
    prNumber: 1234,
    refType: null,
    actorLogin: 'octocat',
    ...overrides,
  };
}

describe('repoUrl / prUrl / issueUrl / commitsUrl', () => {
  it('builds canonical github.com URLs', () => {
    expect(repoUrl('trinodb/trino')).toBe('https://github.com/trinodb/trino');
    expect(prUrl('trinodb/trino', 42)).toBe('https://github.com/trinodb/trino/pull/42');
    expect(issueUrl('trinodb/trino', 42)).toBe('https://github.com/trinodb/trino/issues/42');
    expect(commitsUrl('trinodb/trino')).toBe('https://github.com/trinodb/trino/commits');
  });
});

describe('followingNarrativeSegments', () => {
  // Helpers: pull just the linked segments out so the assertions read as
  // a flat URL list — clearer than zipping over text chunks.
  const links = (segments: ReturnType<typeof followingNarrativeSegments>) =>
    segments.flatMap(s => s.url ? [s.url] : []);

  it('PullRequestEvent (opened) → only the PR is linked, repo is plain text', () => {
    const segs = followingNarrativeSegments(event({ type: 'PullRequestEvent', action: 'opened', prNumber: 7 }));
    expect(segs.map(s => s.text).join('')).toBe('opened PR #7 in trinodb/trino');
    expect(links(segs)).toEqual(['https://github.com/trinodb/trino/pull/7']);
  });

  it('uses the PR title as the linked object when GitHub supplies it', () => {
    const segs = followingNarrativeSegments(event({
      type: 'PullRequestReviewEvent',
      prNumber: 30384,
      prTitle: 'Improve exchange source memory accounting',
    }));
    expect(segs.map(s => s.text).join('')).toBe(
      'reviewed Improve exchange source memory accounting in trinodb/trino',
    );
    expect(links(segs)).toEqual(['https://github.com/trinodb/trino/pull/30384']);
  });

  it('PullRequestEvent (closed) uses "closed" verb', () => {
    const segs = followingNarrativeSegments(event({ type: 'PullRequestEvent', action: 'closed', prNumber: 9 }));
    expect(segs.map(s => s.text).join('')).toBe('closed PR #9 in trinodb/trino');
  });

  it('PullRequestEvent without prNumber has no link at all', () => {
    const segs = followingNarrativeSegments(event({ type: 'PullRequestEvent', prNumber: 0 }));
    expect(segs.map(s => s.text).join('')).toBe('opened a PR in trinodb/trino');
    expect(links(segs)).toEqual([]);
  });

  it('PullRequestReviewEvent / PullRequestReviewCommentEvent link only the PR', () => {
    const review = followingNarrativeSegments(event({ type: 'PullRequestReviewEvent', prNumber: 11 }));
    expect(review.map(s => s.text).join('')).toBe('reviewed PR #11 in trinodb/trino');
    expect(links(review)).toEqual(['https://github.com/trinodb/trino/pull/11']);

    const comment = followingNarrativeSegments(event({ type: 'PullRequestReviewCommentEvent', prNumber: 12 }));
    expect(comment.map(s => s.text).join('')).toBe('commented on PR #12 in trinodb/trino');
    expect(links(comment)).toEqual(['https://github.com/trinodb/trino/pull/12']);
  });

  it('PushEvent links the "N commits" phrase to the commits page, not the repo', () => {
    const single = followingNarrativeSegments(event({ type: 'PushEvent', commitCount: 1, prNumber: 0 }));
    expect(single.map(s => s.text).join('')).toBe('pushed 1 commit to trinodb/trino');
    expect(links(single)).toEqual(['https://github.com/trinodb/trino/commits']);

    const many = followingNarrativeSegments(event({ type: 'PushEvent', commitCount: 4, prNumber: 0 }));
    expect(many.map(s => s.text).join('')).toBe('pushed 4 commits to trinodb/trino');
    expect(links(many)).toEqual(['https://github.com/trinodb/trino/commits']);

    const branch = followingNarrativeSegments(event({
      type: 'PushEvent',
      commitCount: 2,
      prNumber: 0,
      ref: 'refs/heads/optimize-task-query',
    }));
    expect(branch.map(s => s.text).join('')).toBe(
      'pushed 2 commits to optimize-task-query in trinodb/trino',
    );
  });

  it('merged PushEvent (pushCount>1) counts pushes and links the PR when present', () => {
    const merged = followingNarrativeSegments(event({ type: 'PushEvent', prNumber: 0, pushCount: 4 }));
    expect(merged.map(s => s.text).join('')).toBe('pushed 4 times to trinodb/trino');
    expect(links(merged)).toEqual(['https://github.com/trinodb/trino/commits']);

    const withPr = followingNarrativeSegments(event({ type: 'PushEvent', prNumber: 2342, pushCount: 4 }));
    expect(withPr.map(s => s.text).join('')).toBe('pushed 4 times to PR #2342 in trinodb/trino');
    expect(links(withPr)).toEqual(['https://github.com/trinodb/trino/pull/2342']);
  });

  it('merged PullRequestReviewCommentEvent (commentCount>1) counts comments', () => {
    const merged = followingNarrativeSegments(event({ type: 'PullRequestReviewCommentEvent', prNumber: 4043, commentCount: 3 }));
    expect(merged.map(s => s.text).join('')).toBe('commented on PR #4043 3 times in trinodb/trino');
    expect(links(merged)).toEqual(['https://github.com/trinodb/trino/pull/4043']);

    const single = followingNarrativeSegments(event({ type: 'PullRequestReviewCommentEvent', prNumber: 4043, commentCount: 1 }));
    expect(single.map(s => s.text).join('')).toBe('commented on PR #4043 in trinodb/trino');
  });

  it('IssueCommentEvent + IssuesEvent link only the issue, not the repo', () => {
    const com = followingNarrativeSegments(event({ type: 'IssueCommentEvent', prNumber: 33 }));
    expect(com.map(s => s.text).join('')).toBe('commented on issue #33 in trinodb/trino');
    expect(links(com)).toEqual(['https://github.com/trinodb/trino/issues/33']);

    const opened = followingNarrativeSegments(event({ type: 'IssuesEvent', action: 'opened', prNumber: 44 }));
    expect(opened.map(s => s.text).join('')).toBe('opened issue #44 in trinodb/trino');
    expect(links(opened)).toEqual(['https://github.com/trinodb/trino/issues/44']);

    const closed = followingNarrativeSegments(event({ type: 'IssuesEvent', action: 'closed', prNumber: 45 }));
    expect(closed.map(s => s.text).join('')).toBe('closed issue #45 in trinodb/trino');
  });

  it('CreateEvent distinguishes repository vs branch with no links', () => {
    const repo = followingNarrativeSegments(event({ type: 'CreateEvent', refType: 'repository' }));
    expect(repo.map(s => s.text).join('')).toBe('created repository trinodb/trino');
    expect(links(repo)).toEqual([]);

    const branch = followingNarrativeSegments(event({ type: 'CreateEvent', refType: 'branch' }));
    expect(branch.map(s => s.text).join('')).toBe('created a branch in trinodb/trino');
    expect(links(branch)).toEqual([]);
  });

  it('Watch / Fork / unknown render specific verbs without vague activity copy', () => {
    const watch = followingNarrativeSegments(event({ type: 'WatchEvent' }));
    expect(watch.map(s => s.text).join('')).toBe('starred trinodb/trino');
    expect(links(watch)).toEqual([]);

    const fork = followingNarrativeSegments(event({ type: 'ForkEvent' }));
    expect(fork.map(s => s.text).join('')).toBe('forked trinodb/trino');
    expect(links(fork)).toEqual([]);

    const unknown = followingNarrativeSegments(event({ type: 'GollumEvent' }));
    expect(unknown.map(s => s.text).join('')).toBe('updated wiki pages in trinodb/trino');
    expect(unknown.map(s => s.text).join('')).not.toContain('activity in');
    expect(links(unknown)).toEqual([]);
  });
});

describe('groupRecentEvents', () => {
  const push = (repo: string, prNumber = 0) =>
    event({ type: 'PushEvent', repo, prNumber, commitCount: 1, action: null });

  it('collapses consecutive same-repo/PR pushes and counts them', () => {
    const grouped = groupRecentEvents([
      push('a/x'), push('a/x'), push('a/x'),
      event({ type: 'PullRequestReviewEvent', repo: 'a/x', prNumber: 5 }),
    ]);
    expect(grouped).toHaveLength(2);
    expect(grouped[0].pushCount).toBe(3);
    expect(grouped[0].commitCount).toBe(3);
    expect(grouped[1].type).toBe('PullRequestReviewEvent');
  });

  it('does not merge across a different repo, PR, or an interrupting event', () => {
    const grouped = groupRecentEvents([
      push('a/x', 1),
      push('a/x', 2),        // different PR
      push('b/y', 2),        // different repo
      event({ type: 'WatchEvent', repo: 'b/y' }),
      push('b/y', 2),        // separated by the watch event
    ]);
    expect(grouped).toHaveLength(5);
    expect(grouped.every(e => e.pushCount === undefined)).toBe(true);
  });

  it('does not merge pushes to different branches', () => {
    const grouped = groupRecentEvents([
      { ...push('a/x'), ref: 'refs/heads/main' },
      { ...push('a/x'), ref: 'refs/heads/release' },
    ]);
    expect(grouped).toHaveLength(2);
  });

  const comment = (repo: string, prNumber: number) =>
    event({ type: 'PullRequestReviewCommentEvent', repo, prNumber });

  it('collapses consecutive same-repo/PR review comments and counts them', () => {
    const grouped = groupRecentEvents([
      comment('a/x', 4043), comment('a/x', 4043), comment('a/x', 4043),
      event({ type: 'PullRequestReviewEvent', repo: 'a/x', prNumber: 30944 }),
    ]);
    expect(grouped).toHaveLength(2);
    expect(grouped[0].commentCount).toBe(3);
    expect(grouped[1].type).toBe('PullRequestReviewEvent');
  });

  it('does not merge comments across a different PR or an interrupting event', () => {
    const grouped = groupRecentEvents([
      comment('a/x', 1),
      comment('a/x', 2),               // different PR
      event({ type: 'WatchEvent', repo: 'a/x' }),
      comment('a/x', 2),                // separated by the watch event
    ]);
    expect(grouped).toHaveLength(4);
    expect(grouped.every(e => e.commentCount === undefined)).toBe(true);
  });
});
