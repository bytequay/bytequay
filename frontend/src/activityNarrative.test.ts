import { describe, it, expect } from 'vitest';
import { followingNarrativeSegments, repoUrl, prUrl, issueUrl, commitsUrl } from './activityNarrative';
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
    segments.filter(s => s.url).map(s => s.url!);

  it('PullRequestEvent (opened) → only the PR is linked, repo is plain text', () => {
    const segs = followingNarrativeSegments(event({ type: 'PullRequestEvent', action: 'opened', prNumber: 7 }));
    expect(segs.map(s => s.text).join('')).toBe('opened pull request #7 in trinodb/trino');
    expect(links(segs)).toEqual(['https://github.com/trinodb/trino/pull/7']);
  });

  it('PullRequestEvent (closed) uses "closed" verb', () => {
    const segs = followingNarrativeSegments(event({ type: 'PullRequestEvent', action: 'closed', prNumber: 9 }));
    expect(segs.map(s => s.text).join('')).toBe('closed pull request #9 in trinodb/trino');
  });

  it('PullRequestEvent without prNumber has no link at all', () => {
    const segs = followingNarrativeSegments(event({ type: 'PullRequestEvent', prNumber: 0 }));
    expect(segs.map(s => s.text).join('')).toBe('opened a pull request in trinodb/trino');
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
    const single = followingNarrativeSegments(event({ type: 'PushEvent', commitCount: 1 }));
    expect(single.map(s => s.text).join('')).toBe('pushed 1 commit to trinodb/trino');
    expect(links(single)).toEqual(['https://github.com/trinodb/trino/commits']);

    const many = followingNarrativeSegments(event({ type: 'PushEvent', commitCount: 4 }));
    expect(many.map(s => s.text).join('')).toBe('pushed 4 commits to trinodb/trino');
    expect(links(many)).toEqual(['https://github.com/trinodb/trino/commits']);
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

  it('Watch / Fork / unknown render as plain text (no links)', () => {
    const watch = followingNarrativeSegments(event({ type: 'WatchEvent' }));
    expect(watch.map(s => s.text).join('')).toBe('starred trinodb/trino');
    expect(links(watch)).toEqual([]);

    const fork = followingNarrativeSegments(event({ type: 'ForkEvent' }));
    expect(fork.map(s => s.text).join('')).toBe('forked trinodb/trino');
    expect(links(fork)).toEqual([]);

    const unknown = followingNarrativeSegments(event({ type: 'GollumEvent' }));
    expect(unknown.map(s => s.text).join('')).toBe('activity in trinodb/trino');
    expect(links(unknown)).toEqual([]);
  });
});
