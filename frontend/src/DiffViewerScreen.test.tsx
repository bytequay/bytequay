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
import type { PullRequestDetailDto, PullRequestDto } from './types';

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

function bridgeStub(detail: PullRequestDetailDto) {
  return {
    markPrViewed: vi.fn().mockResolvedValue(undefined),
    fetchPullRequestDetail: vi.fn().mockResolvedValue(detail),
    refreshPullRequestDetail: vi.fn().mockResolvedValue(detail),
    fetchPrDiffFiles: vi.fn().mockResolvedValue([]),
    fetchPrCommits: vi.fn().mockResolvedValue([]),
    fetchPrCommitDiff: vi.fn().mockResolvedValue([]),
    listAiProviders: vi.fn().mockResolvedValue([]),
    getLatestAiReview: vi.fn().mockResolvedValue(null),
    getAiReviewStatus: vi.fn().mockResolvedValue({ state: 'IDLE', error: null }),
  };
}

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
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
} = {}) {
  const bridge = bridgeStub(detail);
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
  return bridge;
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
});
