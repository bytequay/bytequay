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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PRTimeline } from './PRTimeline';
import type { LocalPR, LocalPRTimelineEvent } from '../../types/localPr';
import type { ActivityItemDto, ReviewThreadDto } from '../../types';
import type { GitHubThreadActions } from './GitHubTimelineRow';

afterEach(cleanup);

function pr(over: Partial<LocalPR> = {}): LocalPR {
  return {
    id: 'pr1', taskId: 't1', branchName: 'feat/x', baseBranch: 'main', title: 'T',
    description: '', status: 'local-open', createdAt: 1, pushedAt: null, remotePrNumber: null,
    remotePrUrl: null, mergedAt: null, closedAt: null,
    origin: 'task', repo: null, author: null, syncedAt: null,
    syncedAdditions: null, syncedDeletions: null,
    syncedMergeable: null, syncedMergeableState: null, syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null, ...over,
  };
}

function activity(over: Partial<ActivityItemDto> = {}): ActivityItemDto {
  return {
    actor: 'octocat', eventType: 'commented', timestamp: '2026-06-20T10:00:00Z', body: null,
    state: null, beforeSha: null, afterSha: null, requestedReviewer: null, reviewId: null,
    authorAssociation: null, githubId: 1, reactions: null,
    labelName: null, labelColor: null, milestoneTitle: null, assigneeLogin: null,
    crossRefNumber: null, crossRefTitle: null, crossRefUrl: null, crossRefIsPullRequest: false,
    ...over,
  };
}

function thread(over: Partial<ReviewThreadDto> = {}): ReviewThreadDto {
  return {
    rootGithubId: 501, filePath: 'src/Foo.java', line: 10, side: 'RIGHT', diffHunk: '@@ -1,3 +1,3 @@\n-old\n+new',
    messages: [{
      githubId: 601, author: 'octocat', body: 'Please fix this.', createdAt: '2026-06-20T10:00:00Z',
      reactions: null, reviewId: null, authorAssociation: null,
    }],
    resolved: null, outdated: false, startLine: null, startSide: null, originalLine: null, originalStartLine: null,
    ...over,
  };
}

const noopThreadActions: GitHubThreadActions = {
  repo: 'acme/widget', prAuthor: '@octocat', prHtmlUrl: 'https://github.com/acme/widget/pull/1',
  onReply: async () => {},
};

function reviewEvent(over: Partial<LocalPRTimelineEvent> = {}): LocalPRTimelineEvent {
  return {
    id: 'ev1', localPrId: 'pr1', eventType: 'review', actor: '@reviewer1',
    isLocalOnly: false, strippedOnPushAt: null, createdAt: Date.parse('2026-06-20T10:00:00Z'),
    payload: { verdict: 'APPROVED' }, ...over,
  };
}

describe('PRTimeline review rendering', () => {
  it('renders an approval as a person-event with the review body attached', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      payload: { verdict: 'APPROVED', body: 'Nice cleanup, LGTM.' },
    })]} />);

    expect(screen.getAllByText('reviewer1', { exact: false }).length).toBeGreaterThan(0);
    expect(screen.getByText(/approved these changes/)).toBeTruthy();
    expect(screen.getByText('Nice cleanup, LGTM.')).toBeTruthy();
  });

  it('renders CHANGES_REQUESTED as "reviewed", not "approved"', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      payload: { verdict: 'CHANGES_REQUESTED' },
    })]} />);

    expect(screen.getByText(/reviewed/)).toBeTruthy();
    expect(screen.queryByText(/approved these changes/)).toBeNull();
  });

  it('renders the brain adversarial-review branch as a person-event too', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      actor: 'brain', isLocalOnly: true,
      payload: { scope: 'plan', verdict: 'approved', iteration: 1 },
    })]} />);

    expect(screen.getByText(/approved these changes/)).toBeTruthy();
  });
});

describe('PRTimeline composition', () => {
  it('always renders the description as the first bubble', () => {
    render(<PRTimeline pr={pr({ description: 'Adds a cache layer.' })} comments={[]} events={[]} />);

    expect(screen.getByText('Adds a cache layer.')).toBeTruthy();
    expect(screen.getByText(/drafted the description/)).toBeTruthy();
  });

  it('groups a file-line comment thread into one review-thread card', () => {
    render(<PRTimeline pr={pr()} events={[]} comments={[
      {
        id: 'c1', localPrId: 'pr1', origin: 'local', scope: 'file-line',
        filePath: 'src/Foo.java', lineNumber: 42, side: 'RIGHT', startLine: null, startSide: null,
        author: 'you', body: 'Fix this.',
        createdAt: 2, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: null,
      },
      {
        id: 'c2', localPrId: 'pr1', origin: 'local', scope: 'file-line',
        filePath: 'src/Foo.java', lineNumber: 42, side: 'RIGHT', startLine: null, startSide: null,
        author: 'claude-code', body: 'Fixed.',
        createdAt: 3, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: 'c1', publishedAt: null,
      },
    ]} />);

    expect(screen.getByText('src/Foo.java:42', { exact: false })).toBeTruthy();
    expect(screen.getByText('Fix this.')).toBeTruthy();
    expect(screen.getByText('Fixed.')).toBeTruthy();
  });
});

describe('PRTimeline GitHub-native feed', () => {
  const pushedPr = pr({ remotePrNumber: 42, repo: 'acme/widget', origin: 'external', author: '@octocat' });

  it('renders a labeled event as a one-line row', () => {
    render(<PRTimeline
      pr={pushedPr} events={[]} comments={[]}
      activity={[activity({ eventType: 'labeled', actor: 'octocat', labelName: 'bug', labelColor: 'd73a4a' })]}
      reviewThreads={[]} threadActions={noopThreadActions}
    />);

    expect(screen.getByText('bug')).toBeTruthy();
    expect(screen.getByText(/added the/)).toBeTruthy();
  });

  it('merges a same-actor labeled burst into one row', () => {
    render(<PRTimeline
      pr={pushedPr} events={[]} comments={[]}
      activity={[
        activity({ eventType: 'labeled', actor: 'github-actions[bot]', labelName: 'hive', timestamp: '2026-06-20T10:00:00.000Z' }),
        activity({ eventType: 'labeled', actor: 'github-actions[bot]', labelName: 'bigquery', timestamp: '2026-06-20T10:00:01.000Z' }),
        activity({ eventType: 'labeled', actor: 'github-actions[bot]', labelName: 'delta-lake', timestamp: '2026-06-20T10:00:02.000Z' }),
      ]}
      reviewThreads={[]} threadActions={noopThreadActions}
    />);

    expect(screen.getAllByText(/added the/).length).toBe(1);
    expect(screen.getByText('hive')).toBeTruthy();
    expect(screen.getByText('bigquery')).toBeTruthy();
    expect(screen.getByText('delta-lake')).toBeTruthy();
  });

  it('renders an attached review thread with its Outdated pill', () => {
    render(<PRTimeline
      pr={pushedPr} events={[]} comments={[]}
      activity={[activity({ eventType: 'reviewed', actor: 'octocat', state: 'commented', reviewId: 9 })]}
      reviewThreads={[thread({ messages: [{ githubId: 601, author: 'octocat', body: 'Please fix this.', createdAt: '2026-06-20T10:00:00Z', reactions: null, reviewId: 9, authorAssociation: null }], outdated: true })]}
      threadActions={noopThreadActions}
    />);

    expect(screen.getByText('Please fix this.')).toBeTruthy();
    expect(screen.getByText(/outdated/i)).toBeTruthy();
    // The thread card must be offset past the rail line, not rendered flush
    // against it — otherwise the rail visually cuts through the card.
    expect(document.querySelector('.rail-thread')).not.toBeNull();
  });

  it('offsets a standalone (unattached) review thread past the rail too', () => {
    render(<PRTimeline
      pr={pushedPr} events={[]} comments={[]}
      activity={[]}
      reviewThreads={[thread({ messages: [{ githubId: 602, author: 'octocat', body: 'A later reply.', createdAt: '2026-06-20T10:00:00Z', reactions: null, reviewId: null, authorAssociation: null }] })]}
      threadActions={noopThreadActions}
    />);

    expect(screen.getByText('A later reply.')).toBeTruthy();
    expect(document.querySelector('.rail-thread')).not.toBeNull();
  });

  it('once the GitHub feed is active, only local checks render from the local event list', () => {
    const events: LocalPRTimelineEvent[] = [
      { id: 'ci1', localPrId: 'pr1', eventType: 'ci', actor: 'you', isLocalOnly: true, strippedOnPushAt: null,
        createdAt: 5, payload: { kind: 'local', name: 'mvn verify', status: 'passed' } },
      { id: 'commit1', localPrId: 'pr1', eventType: 'commit', actor: '@octocat', isLocalOnly: false, strippedOnPushAt: null,
        createdAt: 6, payload: { sha: 'aaa1111', message: 'synced commit' } },
    ];

    render(<PRTimeline
      pr={pushedPr} events={events} comments={[]}
      activity={[]} reviewThreads={[]} threadActions={noopThreadActions}
    />);

    expect(screen.getByText(/mvn verify/)).toBeTruthy();
    expect(screen.queryByText(/synced commit/)).toBeNull();
  });

  it('filters out stale remote-kind ci rows even though eventType is still "ci"', () => {
    // Rows written by PRServiceImpl.recordSyncedCheck before it stopped
    // emitting a timeline event per synced check (Trino #29099 had 60 of
    // these) — the frontend must not trust eventType alone.
    const events: LocalPRTimelineEvent[] = [
      { id: 'ci-remote', localPrId: 'pr1', eventType: 'ci', actor: 'claude-code', isLocalOnly: false, strippedOnPushAt: null,
        createdAt: 5, payload: { kind: 'remote', name: 'build-success', status: 'passed' } },
    ];

    render(<PRTimeline
      pr={pushedPr} events={events} comments={[]}
      activity={[]} reviewThreads={[]} threadActions={noopThreadActions}
    />);

    expect(screen.queryByText(/build-success/)).toBeNull();
  });

  it('leaves pre-push local-only PRs rendering exactly as before (no remotePrNumber)', () => {
    const events: LocalPRTimelineEvent[] = [
      { id: 'commit1', localPrId: 'pr1', eventType: 'commit', actor: 'you', isLocalOnly: false, strippedOnPushAt: null,
        createdAt: 6, payload: { sha: 'aaa1111', message: 'a local commit' } },
    ];

    render(<PRTimeline pr={pr()} events={events} comments={[]} />);

    expect(screen.getByText(/a local commit/)).toBeTruthy();
  });
});
