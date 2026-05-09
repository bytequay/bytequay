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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import DiffViewerScreen from './DiffViewerScreen';
import type { DiffFileDto, PullRequestCommitDto, PullRequestDetailDto, PullRequestDto, ReviewThreadDto } from './types';

// React 19 enforces this flag before async act() works.
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

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
    origin: 'REVIEW_REQUESTED',
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

function makeDetail(overrides: Partial<PullRequestDetailDto> = {}): PullRequestDetailDto {
  return {
    repo: 'trinodb/trino',
    number: 42,
    body: 'PR body',
    labels: [],
    draft: false,
    mergeable: true,
    mergeableState: 'clean',
    additions: 10,
    deletions: 5,
    changedFiles: 1,
    approvalCount: 0,
    changesRequestedCount: 0,
    pendingReviewerCount: 0,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    files: [{ filename: 'src/foo.ts', additions: 10, deletions: 5, status: 'modified' }],
    recentActivity: [],
    checkRuns: [],
    reviewThreads: [],
    linkedIssues: [],
    viewerCanWrite: true,
    headRef: null,
    headRepo: null,
    baseRef: null,
    baseRepo: null,
    ...overrides,
  };
}

function makeDiffFile(overrides: Partial<DiffFileDto> = {}): DiffFileDto {
  return {
    filename: 'src/foo.ts',
    status: 'modified',
    additions: 1,
    deletions: 0,
    patch: '@@ -1,1 +1,1 @@\n+new line',
    ...overrides,
  };
}

function makeCommit(overrides: Partial<PullRequestCommitDto> = {}): PullRequestCommitDto {
  return {
    sha: 'abc123',
    authorLogin: 'octocat',
    authorName: 'Octocat',
    authoredAt: '2026-04-29T11:00:00Z',
    message: 'Update foo',
    ...overrides,
  };
}

function makeThread(overrides: Partial<ReviewThreadDto> = {}): ReviewThreadDto {
  return {
    rootGithubId: 5001,
    filePath: 'src/foo.ts',
    line: 1,
    side: 'RIGHT',
    diffHunk: '@@ -1,1 +1,1 @@\n+new line',
    messages: [{
      githubId: 1001,
      author: 'reviewer',
      body: 'existing thread comment',
      createdAt: '2026-04-29T11:00:00Z',
      reactions: null,
      reviewId: null,
      authorAssociation: 'MEMBER',
    }],
    resolved: false,
    outdated: false,
    startLine: null,
    startSide: null,
    originalLine: null,
    originalStartLine: null,
    ...overrides,
  };
}

function bridgeStub(detail: PullRequestDetailDto, options: {
  files?: DiffFileDto[];
  commits?: PullRequestCommitDto[];
} = {}) {
  return {
    markPrViewed: vi.fn().mockResolvedValue(undefined),
    fetchPullRequestDetail: vi.fn().mockResolvedValue(detail),
    refreshPullRequestDetail: vi.fn().mockResolvedValue(detail),
    fetchPrDiffFiles: vi.fn().mockResolvedValue(options.files ?? []),
    fetchPrCommits: vi.fn().mockResolvedValue(options.commits ?? []),
    fetchPrCommitDiff: vi.fn().mockResolvedValue([]),
    fetchFileBlob: vi.fn().mockResolvedValue({ lines: [] }),
    createInlineReviewComment: vi.fn().mockResolvedValue(undefined),
    replyToReviewThread: vi.fn().mockResolvedValue(undefined),
    setReviewThreadResolved: vi.fn().mockResolvedValue(undefined),
    stageReviewComment: vi.fn(),
    polishCommentText: vi.fn(),
    listAiProviders: vi.fn().mockResolvedValue([]),
    getLatestAiReview: vi.fn().mockResolvedValue(null),
    getAiReviewStatus: vi.fn().mockResolvedValue({ state: 'IDLE', error: null }),
  };
}

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => {
    root.unmount();
  });
  container.remove();
});

async function render(detail = makeDetail(), props: {
  onApprove?: (prId: number, repo: string, number: number) => Promise<void>;
  onBack?: () => void;
  files?: DiffFileDto[];
  commits?: PullRequestCommitDto[];
} = {}) {
  const bridge = bridgeStub(detail, { files: props.files, commits: props.commits });
  (window as unknown as { bridge: ReturnType<typeof bridgeStub> }).bridge = bridge;
  await act(async () => {
    root.render(
      <DiffViewerScreen
        pr={makePr()}
        onBack={props.onBack ?? vi.fn()}
        onApprove={props.onApprove}
      />,
    );
  });
  await act(async () => { await Promise.resolve(); });
  await act(async () => { await Promise.resolve(); });
  await act(async () => { await Promise.resolve(); });
  return bridge;
}

function updateTextarea(textarea: HTMLTextAreaElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
  setter?.call(textarea, value);
  textarea.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('DiffViewerScreen freshness', () => {
  it('uses force-refresh reconciliation after approval', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onBack = vi.fn();
    const bridge = await render(makeDetail(), { onApprove, onBack });

    const approve = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Approve');
    expect(approve).toBeTruthy();

    await act(async () => {
      approve!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(onApprove).toHaveBeenCalledWith(1, 'trinodb/trino', 42);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(onBack).toHaveBeenCalled();
  });

  it('uses force-refresh reconciliation after posting a new inline comment', async () => {
    const bridge = await render(makeDetail(), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
    });

    const row = container.querySelector<HTMLElement>('[data-anchor="src/foo.ts:RIGHT:1"]');
    expect(row).toBeTruthy();

    await act(async () => {
      row!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const textarea = container.querySelector<HTMLTextAreaElement>('.diff-inline-composer__input');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'please adjust this line');
    });

    const post = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Add single comment');
    expect(post).toBeTruthy();

    await act(async () => {
      post!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.createInlineReviewComment).toHaveBeenCalledWith(
      'trinodb/trino',
      42,
      'please adjust this line',
      'src/foo.ts',
      1,
      'RIGHT',
      'abc123',
      null,
      null,
    );
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
  });

  it('keeps review-thread replies optimistic without fetching stale detail', async () => {
    const bridge = await render(makeDetail({ reviewThreads: [makeThread()] }), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
    });

    const openReply = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Write a reply…');
    expect(openReply).toBeTruthy();

    await act(async () => {
      openReply!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const textarea = container.querySelector<HTMLTextAreaElement>('.diff-thread__reply-input');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'thanks for the context');
    });

    const reply = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Reply');
    expect(reply).toBeTruthy();

    await act(async () => {
      reply!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.replyToReviewThread).toHaveBeenCalledWith('trinodb/trino', 42, 5001, 'thanks for the context');
    expect(bridge.refreshPullRequestDetail).not.toHaveBeenCalled();
    expect(bridge.fetchPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(container.innerHTML).toContain('thanks for the context');
  });
});
