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
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, type ComponentProps } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import PullRequestPreview from './PullRequestPreview';
import { invalidate, setCached } from './dataCache';
import type {
  ActivityItemDto,
  PullRequestDetailDto,
  PullRequestDto,
  ReactionsDto,
  ReviewMessageDto,
  ReviewThreadDto,
} from './types';

// React 19 enforces this flag before async act() works.
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const ZERO_REACTIONS: ReactionsDto = {
  plusOne: 0, minusOne: 0, laugh: 0, hooray: 0,
  confused: 0, heart: 0, rocket: 0, eyes: 0,
};

function makePr(overrides: Partial<PullRequestDto> = {}): PullRequestDto {
  return {
    id: 1,
    repo: 'trinodb/trino',
    number: 42,
    title: 'Test PR',
    author: 'octocat',
    htmlUrl: 'https://github.com/trinodb/trino/pull/42',
    createdAt: '2026-04-29T10:00:00Z',
    updatedAt: '2026-04-29T11:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    additions: 10,
    deletions: 5,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: '2026-04-29T11:00:00Z',
    reviewerVerdicts: {},
    snoozedUntil: null,
    snoozeWakeReason: null,
    ...overrides,
  };
}

function makeMessage(overrides: Partial<ReviewMessageDto> = {}): ReviewMessageDto {
  return {
    githubId: 1001,
    author: 'reviewer',
    body: 'Looks good',
    createdAt: '2026-04-29T11:30:00Z',
    reactions: { ...ZERO_REACTIONS, plusOne: 2 },
    reviewId: null,
    authorAssociation: 'MEMBER',
    ...overrides,
  };
}

function makeThread(overrides: Partial<ReviewThreadDto> = {}): ReviewThreadDto {
  return {
    rootGithubId: 5001,
    filePath: 'src/foo.ts',
    line: 42,
    side: 'RIGHT',
    diffHunk: '@@ -1,3 +1,3 @@\n-old\n+new',
    messages: [makeMessage()],
    resolved: false,
    outdated: false,
    startLine: null,
    startSide: null,
    originalLine: null,
    originalStartLine: null,
    ...overrides,
  };
}

function makeActivity(overrides: Partial<ActivityItemDto> = {}): ActivityItemDto {
  return {
    actor: 'commenter',
    eventType: 'commented',
    timestamp: '2026-04-29T11:00:00Z',
    body: 'Top-level comment',
    state: null,
    beforeSha: null,
    afterSha: null,
    requestedReviewer: null,
    reviewId: null,
    authorAssociation: 'MEMBER',
    githubId: 9001,
    reactions: { ...ZERO_REACTIONS, heart: 1 },
    ...overrides,
  };
}

function makeDetail(overrides: Partial<PullRequestDetailDto> = {}): PullRequestDetailDto {
  return {
    repo: 'trinodb/trino',
    number: 42,
    body: 'PR body markdown',
    labels: [],
    draft: false,
    mergeable: true,
    mergeableState: 'clean',
    additions: 10,
    deletions: 5,
    changedFiles: 2,
    approvalCount: 1,
    changesRequestedCount: 0,
    pendingReviewerCount: 0,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    files: [
      { filename: 'src/foo.ts', additions: 5, deletions: 2, status: 'modified' },
      { filename: 'src/bar.ts', additions: 5, deletions: 3, status: 'modified' },
    ],
    recentActivity: [
      makeActivity(),
      // APPROVED review with no body / threads → exercises the compact
      // approved row in the timeline, which references the colored
      // verdict marker and the underlined relative-time link.
      makeActivity({
        actor: 'approver',
        eventType: 'reviewed',
        state: 'APPROVED',
        body: null,
        reviewId: 7001,
        githubId: null,
        reactions: ZERO_REACTIONS,
      }),
      // CHANGES_REQUESTED with a body → exercises ReviewActivityRow
      // (the expandable card) including the verdict pill.
      makeActivity({
        actor: 'critic',
        eventType: 'reviewed',
        state: 'CHANGES_REQUESTED',
        body: 'Please address these.',
        reviewId: 7002,
        githubId: null,
      }),
      // Burst of three review_requested events from the same actor
      // within 60s → exercises the grouping renderer that emits
      // "x requested @a, @b and @c for review".
      makeActivity({
        actor: 'lead',
        eventType: 'review_requested',
        timestamp: '2026-04-29T12:00:00Z',
        body: null,
        requestedReviewer: 'alice',
        githubId: null,
      }),
      makeActivity({
        actor: 'lead',
        eventType: 'review_requested',
        timestamp: '2026-04-29T12:00:10Z',
        body: null,
        requestedReviewer: 'bob',
        githubId: null,
      }),
      makeActivity({
        actor: 'lead',
        eventType: 'review_requested',
        timestamp: '2026-04-29T12:00:20Z',
        body: null,
        requestedReviewer: 'carol',
        githubId: null,
      }),
      // committed event so the timeline rail is exercised.
      makeActivity({
        actor: 'octocat',
        eventType: 'committed',
        body: null,
        afterSha: 'abc123def456',
        githubId: null,
      }),
    ],
    checkRuns: [
      { githubId: null, name: 'build', status: 'completed', conclusion: 'success', htmlUrl: 'https://ci/build', outputTitle: null, outputSummary: null },
    ],
    reviewThreads: [
      // Unresolved thread → reaction chips, reply stub, resolve button.
      makeThread(),
      // Resolved thread → starts folded; tests the resolved pill + the
      // unresolve button label.
      makeThread({
        rootGithubId: 5002,
        filePath: 'src/bar.ts',
        line: 7,
        resolved: true,
        messages: [
          makeMessage({ githubId: 1002, body: 'fixed.' }),
          makeMessage({ githubId: 1003, author: 'octocat', body: 'thanks.' }),
        ],
      }),
      // Outdated thread — surfaces the "outdated" pill.
      makeThread({
        rootGithubId: 5003,
        outdated: true,
        messages: [makeMessage({ githubId: 1004 })],
      }),
    ],
    linkedIssues: [],
    viewerCanWrite: false,
    headRef: null,
    headRepo: null,
    baseRef: null,
    baseRepo: null,
    mergeQueueState: null,
    mergeQueueEnabled: false,
    ...overrides,
  };
}

function bridgeStub(detail: PullRequestDetailDto) {
  return {
    fetchPullRequestDetail: vi.fn().mockResolvedValue(detail),
    fetchPrCommits: vi.fn().mockResolvedValue([]),
    refreshPullRequestDetail: vi.fn().mockResolvedValue(detail),
    setPrDraft: vi.fn().mockResolvedValue(undefined),
    addRequestedReviewer: vi.fn().mockResolvedValue(undefined),
    removeRequestedReviewer: vi.fn().mockResolvedValue(undefined),
    setReviewThreadResolved: vi.fn().mockResolvedValue(undefined),
    addIssueCommentReaction: vi.fn().mockResolvedValue(undefined),
    addReviewCommentReaction: vi.fn().mockResolvedValue(undefined),
    replyToReviewThread: vi.fn().mockResolvedValue(undefined),
    editIssueComment: vi.fn().mockResolvedValue(undefined),
    editReviewComment: vi.fn().mockResolvedValue(undefined),
    commentPr: vi.fn().mockResolvedValue(undefined),
    updatePrBody: vi.fn().mockResolvedValue(undefined),
    getSuggestedReviewers: vi.fn().mockResolvedValue([]),
    searchUsers: vi.fn().mockResolvedValue([]),
  };
}

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  invalidate('home:profile');
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

async function render(
  detail: PullRequestDetailDto,
  pr = makePr(),
  props: Partial<Omit<ComponentProps<typeof PullRequestPreview>, 'pr'>> = {},
) {
  const bridge = bridgeStub(detail);
  (window as unknown as { bridge: ReturnType<typeof bridgeStub> }).bridge = bridge;
  await act(async () => {
    root.render(<PullRequestPreview pr={pr} {...props} />);
  });
  // Drain the fetchPullRequestDetail promise + any chained setStates.
  await act(async () => { await Promise.resolve(); });
  await act(async () => { await Promise.resolve(); });
  return bridge;
}

function updateTextarea(textarea: HTMLTextAreaElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
  setter?.call(textarea, value);
  textarea.dispatchEvent(new Event('input', { bubbles: true }));
}

function updateInput(input: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  setter?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('PullRequestPreview render smoke', () => {
  it('renders without throwing for a detail covering every risky branch', async () => {
    await render(makeDetail());
    const html = container.innerHTML;
    // PR title surfaces in the header — proves the component mounted
    // and got past the loading state.
    expect(html).toContain('Test PR');
    // Approved-row marker text — guards against an extraction breaking
    // the verdict-circle path.
    expect(html).toMatch(/approved these changes/i);
    // Burst grouping rendered "x requested @a, @b and @c for review".
    expect(html).toContain('@alice');
    expect(html).toContain('@bob');
    expect(html).toContain('@carol');
    // Review thread file path landed.
    expect(html).toContain('src/foo.ts:42');
    // Resolved thread shows its pill.
    expect(html).toContain('resolved');
    // Outdated thread surfaces the outdated pill.
    expect(html).toContain('outdated');
    // Reaction chip on the issue comment renders the heart count.
    expect(html).toContain('reaction-chip');
    // The "+ reaction" smiley-add button has its specific class.
    expect(html).toContain('reaction-add');
  });

  it('shows "Merge when ready" on a merge-queue repo, even when fully green', async () => {
    // A queue repo can't be merged directly — github.com shows "Merge
    // when ready" (enable auto-merge → joins the queue) regardless of CI
    // / approval. The authoritative flag drives it; not "Rebase and merge".
    await render(makeDetail({
      viewerCanWrite: true,
      mergeQueueEnabled: true,
    }), makePr(), { onMerge: vi.fn() });
    const html = container.innerHTML;
    expect(html).toContain('Merge when ready');
    expect(html).not.toContain('Rebase and merge');
  });

  it('keeps "Merge when ready" on a queue repo blocked by an unresolved conversation', async () => {
    await render(makeDetail({
      viewerCanWrite: true,
      mergeQueueEnabled: true,
      mergeableState: 'blocked',
      changesRequestedCount: 0,
    }), makePr(), { onMerge: vi.fn() });
    expect(container.innerHTML).toContain('Merge when ready');
  });

  it('does not offer the queue / when-ready button when no merge queue is configured', async () => {
    await render(makeDetail({
      viewerCanWrite: true,
      mergeQueueEnabled: false,
    }), makePr(), { onMerge: vi.fn() });
    const html = container.innerHTML;
    expect(html).not.toContain('Add to merge queue');
    expect(html).not.toContain('Merge when ready');
  });

  it('renders an empty PR (no comments / no threads) without throwing', async () => {
    await render(makeDetail({ recentActivity: [], reviewThreads: [] }));
    expect(container.innerHTML).toContain('Test PR');
  });

  it('renders a PR whose only activity is a single committed event', async () => {
    await render(makeDetail({
      recentActivity: [makeActivity({
        actor: 'octocat',
        eventType: 'committed',
        body: null,
        afterSha: 'deadbeef',
        githubId: null,
      })],
      reviewThreads: [],
    }));
    const html = container.innerHTML;
    expect(html).toContain('Test PR');
    expect(html).toContain('deadbee');
  });

  it('handles a single review_requested (no burst grouping)', async () => {
    await render(makeDetail({
      recentActivity: [makeActivity({
        actor: 'lead',
        eventType: 'review_requested',
        body: null,
        requestedReviewer: 'alice',
        githubId: null,
      })],
      reviewThreads: [],
    }));
    expect(container.innerHTML).toContain('@alice');
  });

  it('uses force-refresh reconciliation after a draft toggle', async () => {
    const bridge = await render(makeDetail({ draft: false }));
    bridge.refreshPullRequestDetail.mockResolvedValue(makeDetail({ draft: true }));

    const button = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Convert to draft');
    expect(button).toBeTruthy();

    await act(async () => {
      button!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.setPrDraft).toHaveBeenCalledWith('trinodb/trino', 42, true);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.innerHTML).toContain('Mark as ready');
  });

  it('uses force-refresh reconciliation after a PR body edit', async () => {
    const bridge = await render(makeDetail());
    bridge.refreshPullRequestDetail.mockResolvedValue(makeDetail({ body: 'fresh body from GitHub' }));

    const edit = container.querySelector<HTMLButtonElement>('.description-card__edit');
    expect(edit).toBeTruthy();

    await act(async () => {
      edit!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const textarea = container.querySelector<HTMLTextAreaElement>('.description-card__textarea');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'updated PR body');
    });

    const save = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Save');
    expect(save).toBeTruthy();
    await act(async () => {
      save!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.updatePrBody).toHaveBeenCalledWith('trinodb/trino', 42, 'updated PR body');
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.innerHTML).toContain('fresh body from GitHub');
  });

  it('uses force-refresh reconciliation after removing a requested reviewer', async () => {
    const bridge = await render(
      makeDetail({ requestedReviewers: ['alice'] }),
      makePr({ requestedReviewers: ['alice'] }),
    );
    bridge.refreshPullRequestDetail.mockResolvedValue(makeDetail({ requestedReviewers: [] }));

    const remove = container.querySelector<HTMLButtonElement>('[aria-label="Remove alice"]');
    expect(remove).toBeTruthy();

    await act(async () => {
      remove!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.removeRequestedReviewer).toHaveBeenCalledWith('trinodb/trino', 42, 'alice');
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.querySelector('[aria-label="Remove alice"]')).toBeNull();
  });

  it('uses force-refresh reconciliation after adding a requested reviewer', async () => {
    const bridge = await render(makeDetail({ requestedReviewers: [] }));
    bridge.refreshPullRequestDetail.mockResolvedValue(makeDetail({ requestedReviewers: ['dana'] }));

    const open = container.querySelector<HTMLButtonElement>('.prc-reviewer-add');
    expect(open).toBeTruthy();
    await act(async () => {
      open!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const input = container.querySelector<HTMLInputElement>('.prc-reviewer-add-input');
    expect(input).toBeTruthy();
    await act(async () => {
      updateInput(input!, 'dana');
    });

    const add = container.querySelector<HTMLButtonElement>('.prc-reviewer-add-btn');
    expect(add).toBeTruthy();
    await act(async () => {
      add!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.addRequestedReviewer).toHaveBeenCalledWith('trinodb/trino', 42, 'dana');
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.querySelector('[aria-label="Remove dana"]')).toBeTruthy();
  });

  it('uses force-refresh reconciliation after re-requesting a past reviewer', async () => {
    const bridge = await render(makeDetail({ requestedReviewers: [] }));
    bridge.refreshPullRequestDetail.mockResolvedValue(makeDetail({ requestedReviewers: ['critic'] }));

    const reRequest = container.querySelector<HTMLButtonElement>('[aria-label="Re-request review from critic"]');
    expect(reRequest).toBeTruthy();

    await act(async () => {
      reRequest!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.addRequestedReviewer).toHaveBeenCalledWith('trinodb/trino', 42, 'critic');
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.querySelector('[aria-label="Remove critic"]')).toBeTruthy();
  });

  it('uses force-refresh reconciliation after closing a pull request', async () => {
    const bridge = await render(makeDetail());
    bridge.refreshPullRequestDetail.mockResolvedValue(makeDetail());

    const close = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Close pull request');
    expect(close).toBeTruthy();

    await act(async () => {
      close!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.commentPr).toHaveBeenCalledWith(1, 'trinodb/trino', 42, '', true);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
  });

  it('refetches PR detail immediately after posting a plain comment', async () => {
    const bridge = await render(makeDetail());

    const textarea = container.querySelector<HTMLTextAreaElement>('.pr-comment-box__input');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'plain comment');
    });

    const comment = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Comment');
    expect(comment).toBeTruthy();

    await act(async () => {
      comment!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.commentPr).toHaveBeenCalledWith(1, 'trinodb/trino', 42, 'plain comment', false);
    // Posting first revalidates against GitHub (maxAge 0) to catch a PR
    // that moved under the user; the mock returns the same snapshot so
    // nothing is stale and the post proceeds. A plain comment has no
    // optimistic append, so the page then refetches to reflect the new
    // comment. With the mount refresh that's three calls:
    // mount(20) + guard(0) + post(20).
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledTimes(3);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42, 0);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42, 20);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
  });

  it('holds the comment and warns when the PR changed on GitHub since load', async () => {
    const bridge = await render(makeDetail({ approvalCount: 1 }));
    // The next probe (the pre-submit guard) reports a new approval, so the
    // snapshot the user composed against is stale.
    bridge.refreshPullRequestDetail.mockResolvedValue(makeDetail({ approvalCount: 2 }));

    const textarea = container.querySelector<HTMLTextAreaElement>('.pr-comment-box__input');
    await act(async () => {
      updateTextarea(textarea!, 'my comment');
    });
    const comment = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Comment');
    await act(async () => {
      comment!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });

    // The post is held back — nothing is written to GitHub — the draft is
    // preserved, and the composer explains what changed.
    expect(bridge.commentPr).not.toHaveBeenCalled();
    expect(container.textContent).toContain('changed on GitHub since you opened it');
    expect(container.querySelector<HTMLTextAreaElement>('.pr-comment-box__input')?.value)
      .toBe('my comment');
  });

  it('offers @mention autocomplete from PR participants and inserts the pick', async () => {
    await render(makeDetail());
    const textarea = container.querySelector<HTMLTextAreaElement>('.pr-comment-box__input');
    expect(textarea).toBeTruthy();

    // Typing an "@token" surfaces a selectable option for a matching
    // participant ('critic' is one of the fixture's activity actors).
    await act(async () => {
      updateTextarea(textarea!, '@cr');
    });
    const option = Array.from(container.querySelectorAll('[role="option"]'))
      .find(el => el.textContent === '@critic');
    expect(option).toBeTruthy();

    // mousedown (the picker uses it so the textarea keeps focus) inserts
    // the full handle into the composer.
    await act(async () => {
      option!.querySelector('button')!
        .dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });
    expect(textarea!.value).toContain('@critic ');
  });

  it('uses force-refresh reconciliation after merging a pull request', async () => {
    const onMerge = vi.fn().mockResolvedValue(undefined);
    const bridge = await render(makeDetail({ viewerCanWrite: true }), makePr(), { onMerge });
    bridge.refreshPullRequestDetail.mockResolvedValue(makeDetail({ viewerCanWrite: true }));

    const merge = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Rebase and merge');
    expect(merge).toBeTruthy();

    await act(async () => {
      merge!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const confirm = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Yes, merge');
    expect(confirm).toBeTruthy();

    await act(async () => {
      confirm!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(onMerge).toHaveBeenCalledWith(1, 'trinodb/trino', 42, 'rebase');
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
  });

  it('keeps issue reactions optimistic without fetching stale detail', async () => {
    const bridge = await render(makeDetail());
    const chip = container.querySelector('.prc-comment-card .reaction-chip--clickable');
    expect(chip).toBeTruthy();

    await act(async () => {
      chip!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.addIssueCommentReaction).toHaveBeenCalledWith('trinodb/trino', 9001, 'heart');
    // No L1 cache after the polling refactor — every mount calls
    // refreshPullRequestDetail (with maxAgeSeconds=10), so we assert
    // that the action itself didn't pile on an extra refresh beyond
    // the natural mount call.
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledTimes(1);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42, 20);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.querySelector('.prc-comment-card .reaction-chip__count')?.textContent).toBe('2');
  });

  it('keeps review-thread reactions optimistic without fetching stale detail', async () => {
    const bridge = await render(makeDetail());
    const chip = container.querySelector('.prc-review-thread .reaction-chip--clickable');
    expect(chip).toBeTruthy();

    await act(async () => {
      chip!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.addReviewCommentReaction).toHaveBeenCalledWith('trinodb/trino', 1001, '+1');
    // No L1 cache after the polling refactor — every mount calls
    // refreshPullRequestDetail (with maxAgeSeconds=10), so we assert
    // that the action itself didn't pile on an extra refresh beyond
    // the natural mount call.
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledTimes(1);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42, 20);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.querySelector('.prc-review-thread .reaction-chip__count')?.textContent).toBe('3');
  });

  it('keeps review-thread replies optimistic without fetching stale detail', async () => {
    const bridge = await render(makeDetail());
    const stub = container.querySelector<HTMLInputElement>('.prc-review-thread__reply-stub-input');
    expect(stub).toBeTruthy();

    await act(async () => {
      stub!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const textarea = container.querySelector<HTMLTextAreaElement>('.prc-review-thread__reply-input');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'thanks for the fix');
    });

    const button = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Reply');
    expect(button).toBeTruthy();
    await act(async () => {
      button!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.replyToReviewThread).toHaveBeenCalledWith('trinodb/trino', 42, 5001, 'thanks for the fix');
    // No L1 cache after the polling refactor — every mount calls
    // refreshPullRequestDetail (with maxAgeSeconds=10), so we assert
    // that the action itself didn't pile on an extra refresh beyond
    // the natural mount call.
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledTimes(1);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42, 20);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.innerHTML).toContain('thanks for the fix');
  });

  it('keeps issue comment edits optimistic without fetching stale detail', async () => {
    setCached('home:profile', { login: 'commenter' });
    const bridge = await render(makeDetail());
    // Edit lives in the comment's "⋯" menu now — open it, then click Edit.
    const trigger = container.querySelector<HTMLButtonElement>('.prc-comment-card .prc-comment-menu__trigger');
    expect(trigger).toBeTruthy();
    await act(async () => {
      trigger!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    const edit = Array.from(container.querySelectorAll<HTMLButtonElement>('.prc-comment-menu__item'))
      .find(el => el.textContent === 'Edit');
    expect(edit).toBeTruthy();
    await act(async () => {
      edit!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const textarea = container.querySelector<HTMLTextAreaElement>('.editable-comment-body__textarea');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'updated top-level comment');
    });

    const save = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Save');
    expect(save).toBeTruthy();
    await act(async () => {
      save!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.editIssueComment).toHaveBeenCalledWith('trinodb/trino', 9001, 'updated top-level comment');
    // No L1 cache after the polling refactor — every mount calls
    // refreshPullRequestDetail (with maxAgeSeconds=10), so we assert
    // that the action itself didn't pile on an extra refresh beyond
    // the natural mount call.
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledTimes(1);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42, 20);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.innerHTML).toContain('updated top-level comment');
  });

  it('keeps review-thread message edits optimistic without fetching stale detail', async () => {
    setCached('home:profile', { login: 'reviewer' });
    const bridge = await render(makeDetail());
    // Edit lives in the message's "⋯" menu now — open it, then click Edit.
    const trigger = container.querySelector<HTMLButtonElement>('.prc-review-thread .prc-comment-menu__trigger');
    expect(trigger).toBeTruthy();
    await act(async () => {
      trigger!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    const edit = Array.from(container.querySelectorAll<HTMLButtonElement>('.prc-comment-menu__item'))
      .find(el => el.textContent === 'Edit');
    expect(edit).toBeTruthy();
    await act(async () => {
      edit!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const textarea = container.querySelector<HTMLTextAreaElement>('.editable-comment-body__textarea');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'updated review-thread comment');
    });

    const save = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Save');
    expect(save).toBeTruthy();
    await act(async () => {
      save!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.editReviewComment).toHaveBeenCalledWith('trinodb/trino', 1001, 'updated review-thread comment');
    // No L1 cache after the polling refactor — every mount calls
    // refreshPullRequestDetail (with maxAgeSeconds=10), so we assert
    // that the action itself didn't pile on an extra refresh beyond
    // the natural mount call.
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledTimes(1);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42, 20);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    expect(container.innerHTML).toContain('updated review-thread comment');
  });

  it('keeps thread resolution optimistic without fetching stale detail', async () => {
    const bridge = await render(makeDetail());
    const button = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Resolve conversation');
    expect(button).toBeTruthy();

    await act(async () => {
      button!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.setReviewThreadResolved).toHaveBeenCalledWith('trinodb/trino', 1, 5001, true);
    // No L1 cache after the polling refactor — every mount calls
    // refreshPullRequestDetail (with maxAgeSeconds=10), so we assert
    // that the action itself didn't pile on an extra refresh beyond
    // the natural mount call.
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledTimes(1);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42, 20);
    expect(bridge.fetchPullRequestDetail).not.toHaveBeenCalled();
    // The thread auto-folds once it flips to resolved (matches
    // github.com), which hides the Unresolve button. Assert the
    // optimistic flip via the always-visible resolved pill in the
    // header instead — that's what proves the local-state patch landed
    // without waiting on a backend refetch.
    const resolvedThread = container.querySelector('.prc-review-thread--resolved');
    expect(resolvedThread).toBeTruthy();
    expect(resolvedThread!.querySelector('.prc-review-thread__resolved-pill')).toBeTruthy();
  });
});
