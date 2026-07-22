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
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PRTimeline } from './PRTimeline';
import type { LocalPR, LocalPRComment, LocalPRCommit, LocalPRTimelineEvent } from '../../types/localPr';
import type { ActivityItemDto, PullRequestDetailDto, ReviewThreadDto } from '../../types';
import type { GitHubThreadActions } from './GitHubTimelineRow';
import { createAgentReviewFixture } from '../../review/agentReviewTestData';

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

function detail(over: Partial<PullRequestDetailDto> = {}): PullRequestDetailDto {
  return {
    repo: 'acme/widget',
    number: 42,
    body: null,
    labels: [],
    draft: false,
    mergeable: null,
    mergeableState: null,
    additions: 31,
    deletions: 4,
    changedFiles: 2,
    approvalCount: 0,
    changesRequestedCount: 0,
    pendingReviewerCount: 0,
    requestedReviewers: [],
    ciStatus: 'NONE',
    files: [
      { filename: 'frontend/src/pr/localpr/PRTimeline.tsx', additions: 20, deletions: 2, status: 'modified' },
      { filename: 'backend/src/main/java/com/bytequay/app/service/localpr/PRServiceImpl.java', additions: 11, deletions: 2, status: 'modified' },
    ],
    recentActivity: [],
    checkRuns: [],
    reviewThreads: [],
    linkedIssues: [],
    viewerCanWrite: true,
    headRef: 'feat/x',
    headRepo: 'acme/widget',
    baseRef: 'main',
    baseRepo: 'acme/widget',
    mergeQueueState: null,
    mergeQueueEnabled: false,
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

  it('renders the brain adversarial code-review branch as a person-event too', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      actor: 'brain', isLocalOnly: true,
      payload: { scope: 'dev', verdict: 'approved', iteration: 1 },
    })]} />);

    expect(screen.getByText(/reviewed the code with no obvious bugs and approved it/)).toBeTruthy();
    expect(screen.queryByText(/Brain left an adversarial code review/)).toBeNull();
  });

  it('does not turn a missing brain verdict into an approval or an empty card', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      actor: 'brain', isLocalOnly: true,
      payload: { reviewEvent: 'finished', scope: 'dev', verdict: null, iteration: 1, findingCount: 0 },
    })]} />);

    expect(screen.getByText(/reviewed the code; no verdict was recorded/)).toBeTruthy();
    expect(screen.queryByText(/No review comments/)).toBeNull();
    expect(screen.queryByText(/approved it/)).toBeNull();
  });

  it('states the purpose of each adversarial review pass', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[
      reviewEvent({ id: 'start-1', createdAt: 1, actor: 'brain', isLocalOnly: true,
        payload: { reviewEvent: 'started', scope: 'dev', iteration: 1 } }),
      reviewEvent({ id: 'fix-1', createdAt: 2, actor: 'claude-code', isLocalOnly: true,
        payload: { reviewEvent: 'addressing-started', scope: 'dev', iteration: 1 } }),
      reviewEvent({ id: 'start-2', createdAt: 3, actor: 'brain', isLocalOnly: true,
        payload: { reviewEvent: 'started', scope: 'dev', iteration: 2 } }),
    ]} />);

    expect(screen.getByText('Pass 1 · Audit the completed implementation for bugs before Local Review')).toBeTruthy();
    expect(screen.getByText('Fix pass 1 · Resolve findings before verification pass 2')).toBeTruthy();
    expect(screen.getByText('Pass 2 · Verify fixes from pass 1 and check for regressions before Local Review')).toBeTruthy();
  });

  it('falls back to the generic review event when agent-review data is unavailable', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      payload: { reviewEvent: 'submitted', verdict: 'APPROVED' },
    })]} />);

    expect(screen.getByText(/approved these changes/)).toBeTruthy();
  });

  it('renders the plan self-review branch with plan-specific copy, no "view changes" link', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      actor: 'brain', isLocalOnly: true,
      payload: { scope: 'plan', verdict: 'approved', iteration: 1 },
    })]} onReviewChanges={() => {}} />);

    expect(screen.getByText(/reviewed the plan with no obvious issues and approved it/)).toBeTruthy();
    expect(screen.queryByText(/approved these changes/)).toBeNull();
    expect(screen.queryByText('View reviewed changes')).toBeNull();
  });
});

describe('PRTimeline plan-finalized rendering', () => {
  it('renders a link card that jumps to the approved PlanStage', () => {
    const onOpenStage = vi.fn();
    render(<PRTimeline pr={pr()} comments={[]} events={[{
      id: 'ev2', localPrId: 'pr1', eventType: 'plan-finalized', actor: 'you',
      isLocalOnly: true, strippedOnPushAt: null, createdAt: Date.parse('2026-06-20T10:00:00Z'),
      payload: { planStageId: 'plan-stage-1' },
    }]} currentUserLogin="chenjian2664" onOpenStage={onOpenStage} />);

    expect(screen.getByText('chenjian2664')).toBeTruthy();
    expect(screen.getByText(/approved the plan/)).toBeTruthy();
    fireEvent.click(screen.getByText('View the plan'));
    expect(onOpenStage).toHaveBeenCalledWith('plan-stage-1');
  });
});

describe('PRTimeline composition', () => {
  it('retains the remote pull request creation milestone beside the GitHub activity feed', () => {
    render(<PRTimeline
      pr={pr({ remotePrNumber: 145, repo: 'acme/widget' })}
      comments={[]}
      events={[{
        id: 'pr-created', localPrId: 'pr1', eventType: 'pull-request-created', actor: 'you',
        isLocalOnly: false, strippedOnPushAt: null, createdAt: Date.parse('2026-06-20T10:00:00Z'),
        payload: {
          branch: 'feature/timeline', baseBranch: 'main', number: 145,
          url: 'https://github.com/acme/widget/pull/145', additions: 12, deletions: 3,
        },
      }]}
      activity={[]}
      reviewThreads={[]}
      threadActions={noopThreadActions}
    />);

    expect(screen.getByText('Pull request created')).toBeTruthy();
    expect(screen.getByText('#145')).toBeTruthy();
    expect(screen.getByText('feature/timeline')).toBeTruthy();
    expect(screen.getByText('+12')).toBeTruthy();
    expect(screen.getByText('-3')).toBeTruthy();
  });

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

  it('renders a PR-level agent finding as Markdown and persists local replies', async () => {
    const localPr = pr();
    const data = createAgentReviewFixture({
      pr: localPr,
      commits: [{ id: 'c1', localPrId: localPr.id, sha: 'abcdef012345', message: 'change', additions: 2, deletions: 1, authoredAt: 1, pushedAt: null }],
      timeline: [], checks: [], comments: [],
    }, [{
      filename: 'src/ChangedFile.ts', status: 'modified', additions: 2, deletions: 1,
      patch: '@@ -3,2 +3,3 @@\n-old\n+new\n context',
    }]);
    data.pr_comments[0] = {
      ...data.pr_comments[0], scope: 'pr', filePath: null, lineNumber: null,
      body: 'DynamicTrinoCatalog should use **cleanup** around `connection.close()` instead of reusing ConnectorIdentity state.\n\nCould you clarify the intended behavior here? Keep the cache identity-safe.',
    };
    const onReply = vi.fn(async () => {});
    const onAnswer = vi.fn(async () => true);
    const onResolve = vi.fn(async () => true);
    const onPromote = vi.fn();

    const { container } = render(<PRTimeline
      pr={localPr}
      events={[]}
      comments={data.pr_comments}
      reviewData={data}
      onReplyThread={onReply}
      onAnswerFinding={onAnswer}
      onSetFindingResolved={onResolve}
      onToggleFindingPromotion={onPromote}
      canPromoteFindings
    />);

    expect(container.querySelector('strong')?.textContent).toBe('cleanup');
    const findingCard = [...container.querySelectorAll<HTMLElement>('.rail-thread .prc-review-thread')]
      .find(card => card.textContent?.includes('Pull request review'))!;
    const card = within(findingCard);
    expect(findingCard).toBeTruthy();
    expect(card.getByText('Pull request review')).toBeTruthy();
    expect(findingCard.querySelector('.prc-comment-role--local')?.textContent).toBe('LOCAL');
    expect(container.querySelector('.rail-thread__agent-marker')).toBeNull();
    expect([...container.querySelectorAll('code')].map(node => node.textContent)).toEqual(expect.arrayContaining([
      'DynamicTrinoCatalog', 'connection.close()', 'ConnectorIdentity',
    ]));
    expect([...container.querySelectorAll('strong')].map(node => node.textContent)).toContain('Question:');
    fireEvent.click(card.getByTitle('Collapse thread'));
    expect(screen.queryByText(/Could you clarify the intended behavior/)).toBeNull();
    fireEvent.click(card.getByTitle('Expand thread'));
    fireEvent.click(card.getByPlaceholderText('Reply…'));
    fireEvent.change(card.getByPlaceholderText('Write a reply'), {
      target: { value: 'I checked **this path**.' },
    });
    fireEvent.click(card.getByRole('button', { name: 'Reply' }));
    await waitFor(() => expect(onReply).toHaveBeenCalledWith(data.pr_comments[0].id, 'I checked **this path**.'));
    await waitFor(() => expect(onAnswer).toHaveBeenCalledWith('finding-1', 'I checked **this path**.'));
    fireEvent.click(card.getByRole('button', { name: 'Resolve conversation' }));
    await waitFor(() => expect(onResolve).toHaveBeenCalledWith('finding-1', true));
    fireEvent.click(card.getByRole('button', { name: 'Add to remote review' }));
    expect(onPromote).toHaveBeenCalledWith('finding-1');
  });

  it('records judgement from the existing local file-line conversation instead of a duplicate card', async () => {
    const localPr = pr();
    const data = createAgentReviewFixture({
      pr: localPr,
      commits: [{ id: 'c1', localPrId: localPr.id, sha: 'abcdef012345', message: 'change', additions: 2, deletions: 1, authoredAt: 1, pushedAt: null }],
      timeline: [], checks: [], comments: [],
    }, [{
      filename: 'src/ChangedFile.ts', status: 'modified', additions: 2, deletions: 1,
      patch: '@@ -3,2 +3,3 @@\n-old\n+new\n context',
    }]);
    const question = data.pr_comments.find(comment => comment.findingId === 'finding-2');
    if (question === undefined) throw new Error('question finding comment missing');
    const onReply = vi.fn(async () => {});
    const onAnswer = vi.fn();

    const { container } = render(<PRTimeline
      pr={localPr}
      events={[]}
      comments={[question]}
      reviewData={data}
      onReplyLineThread={onReply}
      onAnswerFinding={onAnswer}
    />);

    expect(container.querySelector('.agent-judgement-card')).toBeNull();
    expect(container.querySelector('.rail-thread .prc-review-thread')).not.toBeNull();
    fireEvent.click(screen.getByPlaceholderText('Reply…'));
    fireEvent.change(screen.getByPlaceholderText('Write a reply'), {
      target: { value: 'The strict behavior is intentional.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Reply' }));
    await waitFor(() => expect(onReply).toHaveBeenCalledWith(
      question.id, question.filePath, question.side, question.lineNumber,
      undefined, undefined, 'The strict behavior is intentional.',
    ));
    await waitFor(() => expect(onAnswer).toHaveBeenCalledWith('finding-2', 'The strict behavior is intentional.'));
  });

  it('drops a published local draft when the GitHub feed renders it as a live thread', () => {
    // A submitted review comment is marked publishedAt on the local row and
    // also comes back from the GitHub feed. Rendering the local copy too
    // would show the same comment twice (once as a "Discard draft" card,
    // once as the published review).
    render(<PRTimeline
      pr={pr({ remotePrNumber: 42, repo: 'acme/widget', origin: 'external', author: '@octocat' })}
      events={[]} activity={[]} reviewThreads={[]} threadActions={noopThreadActions}
      comments={[{
        id: 'c1', localPrId: 'pr1', origin: 'local', scope: 'file-line',
        filePath: 'src/Foo.java', lineNumber: 42, side: 'RIGHT', startLine: null, startSide: null,
        author: 'you', body: 'Published note.',
        createdAt: 2, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: 5,
      }]}
    />);

    expect(screen.queryByText('Published note.')).toBeNull();
  });

  it('interleaves review milestones with ordinary PR events by timestamp', () => {
    const localPr = pr({ description: 'Review this change.' });
    const reviewData = createAgentReviewFixture({
      pr: localPr,
      commits: [{ id: 'c1', localPrId: localPr.id, sha: 'abcdef012345', message: 'change', additions: 2, deletions: 1, authoredAt: 1, pushedAt: 1 }],
      timeline: [], checks: [], comments: [],
    }, [{
      filename: 'src/ChangedFile.ts', status: 'modified', additions: 2, deletions: 1,
      patch: '@@ -3,2 +3,3 @@\n-old\n+new\n context',
    }]);
    const completedAt = reviewData.pr_timeline_events.find(event => event.payload?.reviewEvent === 'round-complete')?.createdAt ?? 0;
    const replyAt = reviewData.pr_timeline_events.find(event => event.payload?.reviewEvent === 'author-reply')?.createdAt ?? 0;
    const between: LocalPRTimelineEvent = {
      id: 'between', localPrId: localPr.id, eventType: 'commit', actor: 'maria', isLocalOnly: false,
      strippedOnPushAt: null, createdAt: Math.floor((completedAt + replyAt) / 2),
      payload: { sha: '123456789', message: 'landed between review events' },
    };

    const { container } = render(<PRTimeline pr={localPr} events={[between]} comments={[]} reviewData={reviewData} />);
    const text = container.textContent ?? '';
    expect(text.indexOf('Round 1 complete')).toBeLessThan(text.indexOf('landed between review events'));
    expect(text.indexOf('landed between review events')).toBeLessThan(text.indexOf('Author replied on F1'));
  });
});

describe('PRTimeline GitHub-native feed', () => {
  it('uses the authenticated GitHub user for a task PR description avatar', () => {
    render(<PRTimeline
      pr={pr({ remotePrNumber: 42, repo: 'acme/widget', origin: 'task', author: 'claude-code' })}
      events={[]} comments={[]} activity={[]} reviewThreads={[]}
      threadActions={noopThreadActions} currentUserLogin="octocat"
    />);

    const avatar = screen.getByAltText('octocat') as HTMLImageElement;
    expect(avatar.src).toContain('github.com/octocat.png');
    expect(screen.getByText('octocat')).toBeTruthy();
    expect(screen.getByText(/drafted the description/)).toBeTruthy();
  });

  it('renders GitHub merged and closed activity as one merged-commit event', () => {
    const timestamp = '2026-06-20T11:00:00Z';
    const { container } = render(<PRTimeline
      pr={pr({ remotePrNumber: 42, repo: 'acme/widget', status: 'merged', author: 'octocat' })}
      events={[]} comments={[]}
      activity={[
        activity({ eventType: 'merged', timestamp, afterSha: 'f77da91abc123', githubId: 20 }),
        activity({ eventType: 'closed', timestamp, githubId: 21 }),
      ]}
      reviewThreads={[]}
      remoteDetail={detail({
        baseRef: 'main',
        checkRuns: [
          { githubId: 1, name: 'build', status: 'completed', conclusion: 'success', htmlUrl: null, outputTitle: null, outputSummary: null },
          { githubId: 2, name: 'lint', status: 'completed', conclusion: 'neutral', htmlUrl: null, outputTitle: null, outputSummary: null },
        ],
      })}
      threadActions={noopThreadActions}
    />);

    expect(container.textContent).toContain('octocat merged commit f77da91 into main');
    expect(screen.getByText('2 checks passed')).toBeTruthy();
    expect(screen.queryByText(/closed this pull request/)).toBeNull();
    expect(container.querySelector('.tic.merged')).toBeTruthy();
  });

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

  it('uses the remote PR author for a pushed task-origin description', () => {
    render(<PRTimeline
      pr={pr({ remotePrNumber: 42, repo: 'acme/widget', status: 'remote-open', author: '@chenjian2664', description: 'Remote description.' })}
      events={[]} comments={[]}
      activity={[]} reviewThreads={[]} threadActions={noopThreadActions}
    />);

    expect(screen.getByText('chenjian2664')).toBeTruthy();
    expect(screen.queryByText('claude-code')).toBeNull();
  });

  it('folds task-local activity above the GitHub timeline once pushed', () => {
    const events: LocalPRTimelineEvent[] = [
      { id: 'ci1', localPrId: 'pr1', eventType: 'ci', actor: 'claude-code', isLocalOnly: true, strippedOnPushAt: null,
        createdAt: 5, payload: { kind: 'local', name: 'mvn verify', status: 'passed' } },
      { id: 'commit1', localPrId: 'pr1', eventType: 'commit', actor: 'claude-code', isLocalOnly: false, strippedOnPushAt: null,
        createdAt: 6, payload: { sha: 'aaa1111', message: 'local commit that should fold' } },
    ];
    const commits: LocalPRCommit[] = [
      { id: 'c1', localPrId: 'pr1', sha: 'aaa1111', message: 'First', additions: 10, deletions: 1, authoredAt: 1, pushedAt: null },
      { id: 'c2', localPrId: 'pr1', sha: 'bbb2222', message: 'Second', additions: 20, deletions: 2, authoredAt: 2, pushedAt: null },
      { id: 'c3', localPrId: 'pr1', sha: 'ccc3333', message: 'Third', additions: 1, deletions: 1, authoredAt: 3, pushedAt: null },
    ];

    render(<PRTimeline
      pr={pr({ remotePrNumber: 42, repo: 'acme/widget', status: 'remote-open', author: '@chenjian2664' })}
      events={events}
      comments={[]}
      commits={commits}
      remoteDetail={detail()}
      activity={[activity({ eventType: 'labeled', actor: 'octocat', labelName: 'ready', labelColor: '0e8a16' })]}
      reviewThreads={[]}
      threadActions={noopThreadActions}
    />);

    expect(screen.getByText('Local work before push')).toBeTruthy();
    expect(screen.getByText(/2 changed files/)).toBeTruthy();
    expect(screen.getByText(/3 commits/)).toBeTruthy();
    expect(screen.getByText('frontend/pr')).toBeTruthy();
    expect(screen.getByText('backend/service')).toBeTruthy();
    expect(screen.queryByText(/local commit that should fold/)).toBeNull();
    expect(screen.getByText('ready')).toBeTruthy();
  });

  it('keeps the adversarial review, its comments, and the auto-approved push gate visible after push', () => {
    const events: LocalPRTimelineEvent[] = [
      { id: 'plan-approved', localPrId: 'pr1', eventType: 'plan-finalized', actor: 'you', isLocalOnly: true,
        strippedOnPushAt: 9, createdAt: 3, payload: { planStageId: 'plan-stage-1' } },
      { id: 'review-start', localPrId: 'pr1', eventType: 'review', actor: 'brain', isLocalOnly: true,
        strippedOnPushAt: 9, createdAt: 4,
        payload: { reviewEvent: 'started', scope: 'dev', iteration: 1, roundId: 'round-1' } },
      { id: 'review-finish', localPrId: 'pr1', eventType: 'review', actor: 'brain', isLocalOnly: true,
        strippedOnPushAt: 9, createdAt: 6,
        payload: { reviewEvent: 'finished', scope: 'dev', verdict: 'changes_requested', iteration: 1,
          roundId: 'round-1', findingCount: 1, commentIds: ['brain-comment'],
          body: 'The final reviewer response is retained even if its MCP tools disconnect.' } },
      { id: 'review-address', localPrId: 'pr1', eventType: 'review', actor: 'claude-code', isLocalOnly: true,
        strippedOnPushAt: 9, createdAt: 7,
        payload: { reviewEvent: 'addressing-started', scope: 'dev', iteration: 1, roundId: 'round-1' } },
      { id: 'push-gate', localPrId: 'pr1', eventType: 'status', actor: 'you', isLocalOnly: false,
        strippedOnPushAt: null, createdAt: 8,
        payload: { gate: 'push', decision: 'approved', automatic: true, reason: 'auto-merge' } },
    ];
    const comments: LocalPRComment[] = [{
      id: 'brain-comment', localPrId: 'pr1', origin: 'local' as const, scope: 'file-line' as const,
      filePath: 'src/Foo.java', lineNumber: 42, side: 'RIGHT' as const, startLine: null, startSide: null,
      author: 'brain', body: 'This null path loses the original error.', createdAt: 5,
      resolvedAt: 7, dismissedAt: null, strippedOnPushAt: 9, parentCommentId: null, publishedAt: null,
    }];

    render(<PRTimeline
      pr={pr({ remotePrNumber: 42, repo: 'acme/widget', status: 'remote-open', author: '@chenjian2664' })}
      events={events} comments={comments}
      activity={[]} reviewThreads={[]} threadActions={noopThreadActions} currentUserLogin="chenjian2664"
    />);

    expect(screen.getAllByText('chenjian2664')).toHaveLength(2);
    expect(screen.getByText(/approved the plan/)).toBeTruthy();
    expect(screen.getByText(/started an adversarial code review/)).toBeTruthy();
    expect(screen.getByText(/Adversarial review finished with 1 finding/)).toBeTruthy();
    expect(screen.getByText(/final reviewer response is retained/)).toBeTruthy();
    fireEvent.click(screen.getByTitle('Expand thread'));
    expect(screen.getByText('This null path loses the original error.')).toBeTruthy();
    expect(screen.getByText(/started addressing the adversarial review comments/)).toBeTruthy();
    expect(screen.getByText(/Push approved automatically because auto-merge is enabled/)).toBeTruthy();
  });
});
