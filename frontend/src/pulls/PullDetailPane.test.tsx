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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AiReviewDraftDto } from '../types';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import type { DashboardPR } from '../types/dashboardPr';
import PullDetailPane, { PullDetailBody } from './PullDetailPane';
import { toRow } from './model';

afterEach(() => {
  cleanup();
  delete (globalThis as { bridge?: unknown }).bridge;
});

describe('PullDetailPane', () => {
  it('keeps the standalone row API and omits the non-design GitHub tab button', () => {
    const url = 'https://github.com/trinodb/trino/pull/1';
    window.bridge = {
      getLocalPrBundle: vi.fn().mockResolvedValue(null),
      fetchPrDiffFiles: vi.fn().mockResolvedValue([]),
    } as unknown as typeof window.bridge;
    const dto: DashboardPR = {
      id: 'pr-github-link', repo: 'trinodb/trino', number: 1, title: 'A change', author: 'octocat', htmlUrl: url,
      createdAt: null, updatedAt: null, origin: 'AUTHORED', labels: [], labelColors: null, draft: false,
      viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: null,
      additions: 1, deletions: 1, commentCount: 0, attentionReason: null, state: 'open', closedAt: null,
      mergedAt: null, mergeable: null, mergeableState: null, headPushedAt: null, reviewerVerdicts: null,
      snoozedUntil: null, snoozeWakeReason: null,
    };

    const onToggleZoom = vi.fn();
    const view = render(<PullDetailPane row={toRow(dto)} onToggleZoom={onToggleZoom} />);

    expect(screen.getByText('A change')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Open pull request on GitHub' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Maximize pull request details' }));
    expect(onToggleZoom).toHaveBeenCalledOnce();

    const changes = screen.getByRole('button', { name: /Changes/ });
    expect(changes.style.fontWeight).toBe('500');
    view.rerender(<PullDetailPane row={toRow(dto)} openChangesToken={1} onToggleZoom={onToggleZoom} zoomed />);
    expect(changes.style.fontWeight).toBe('600');
    fireEvent.click(screen.getByRole('button', { name: 'Close pull request details' }));
    expect(onToggleZoom).toHaveBeenCalledTimes(2);
  });

  it('keeps one counted submit action in the shared PR header across tabs', async () => {
    const getLocalPrBundle = vi.fn();
    const publishLocalPrReview = vi.fn().mockResolvedValue({});
    window.bridge = {
      getLocalPrBundle,
      publishLocalPrReview,
      fetchPrDiffFiles: vi.fn().mockResolvedValue([]),
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
    } as unknown as typeof window.bridge;
    const dto: DashboardPR = {
      id: 'task-pr', repo: 'trinodb/trino', number: 42, title: 'Dashboard title', author: 'octocat',
      htmlUrl: '', createdAt: null, updatedAt: null, origin: 'AUTHORED', labels: [], labelColors: null,
      draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
      ciStatus: null, additions: 3, deletions: 1, commentCount: 0, attentionReason: null,
      state: 'open', closedAt: null, mergedAt: null, mergeable: null, mergeableState: null,
      headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
    };
    const bundle = {
      pr: {
        id: 'task-pr', taskId: null, branchName: 'codex/fix-it', baseBranch: 'master',
        title: 'Task bundle title', description: 'Loaded by the task route', status: 'remote-open',
        createdAt: 0, pushedAt: 0, remotePrNumber: 42, remotePrUrl: 'https://example.test/42', mergedAt: null,
        closedAt: null, origin: 'external', repo: 'trinodb/trino', author: 'octocat', syncedAt: null,
        syncedAdditions: null, syncedDeletions: null, syncedMergeable: null, syncedMergeableState: null,
        syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null,
      },
      commits: [], timeline: [], checks: [],
      comments: Array.from({ length: 8 }, (_, index): LocalPRComment => ({
        id: `comment-${index}`, localPrId: 'task-pr', origin: 'local' as const,
        scope: 'file-line' as const, filePath: 'src/Foo.java', lineNumber: index + 1,
        side: 'RIGHT' as const, startLine: null, startSide: null, author: index === 0 ? 'you' : 'agent',
        body: `Finding ${index + 1}`, createdAt: index, resolvedAt: null, dismissedAt: null,
        strippedOnPushAt: null, parentCommentId: null, publishedAt: null,
      })),
    } as LocalPRBundle;
    const onComment = vi.fn().mockResolvedValue(undefined);
    const onOpenInWorkspace = vi.fn();
    const refresh = vi.fn();

    const view = render(
      <PullDetailBody
        row={toRow(dto)}
        bundle={bundle}
        refresh={refresh}
        onComment={onComment}
        onOpenInWorkspace={onOpenInWorkspace}
      />,
    );

    expect(screen.getByText(/Task bundle title/)).not.toBeNull();
    expect(screen.getByText('Loaded by the task route')).not.toBeNull();
    expect(getLocalPrBundle).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Open in workspace' }));
    expect(onOpenInWorkspace).toHaveBeenCalledOnce();

    fireEvent.change(screen.getByPlaceholderText('Add a comment'), { target: { value: 'Looks good' } });
    fireEvent.click(screen.getByRole('button', { name: /Comment/ }));
    await waitFor(() => expect(onComment).toHaveBeenCalledWith('Looks good'));

    fireEvent.click(screen.getByRole('button', { name: 'Submit review • 1' }));
    expect(screen.getByRole('dialog', { name: 'Submit review' }).textContent).toContain('1 pending');
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));

    fireEvent.click(screen.getByRole('button', { name: /Changes/ }));
    expect(screen.getAllByRole('button', { name: 'Submit review • 1' })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Submit comments' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Submit review • 1' }));
    fireEvent.click(screen.getByRole('radio', { name: /Approve/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));

    expect((await screen.findByRole('status')).textContent).toContain(
      'Review approved on GitHub. The timeline may take a moment to update.',
    );
    expect(screen.queryByRole('dialog', { name: 'Submit review' })).toBeNull();
    expect(publishLocalPrReview).toHaveBeenCalledWith('task-pr', {
      verdict: 'APPROVE', findingIds: [], comments: ['comment-0'], body: null,
    });
    expect(refresh).toHaveBeenCalledOnce();

    view.rerender(
      <PullDetailBody
        row={toRow(dto)}
        bundle={{ ...bundle, comments: [] }}
        refresh={refresh}
        onComment={onComment}
        onOpenInWorkspace={onOpenInWorkspace}
      />,
    );
    expect(screen.getByRole('button', { name: 'Submit review' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Submit review • 0' })).toBeNull();
  });

  it('renders pushed task-local review threads as read-only history', () => {
    window.bridge = {
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
    } as unknown as typeof window.bridge;
    const dto: DashboardPR = {
      id: 'task-pushed', repo: 'acme/widget', number: 42, title: 'Pushed task', author: 'octocat',
      htmlUrl: '', createdAt: null, updatedAt: null, origin: 'AUTHORED', labels: [], labelColors: null,
      draft: true, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
      ciStatus: null, additions: 1, deletions: 0, commentCount: 1, attentionReason: null,
      state: 'open', closedAt: null, mergedAt: null, mergeable: null, mergeableState: null,
      headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
    };
    const pushed: LocalPRBundle = {
      pr: {
        id: 'task-pushed', taskId: 'task-1', branchName: 'dev/task', baseBranch: 'main',
        title: 'Pushed task', description: '', status: 'remote-drafted', createdAt: 1, pushedAt: 2,
        remotePrNumber: 42, remotePrUrl: 'https://example.test/42', mergedAt: null, closedAt: null,
        origin: 'task', repo: 'acme/widget', author: 'octocat', syncedAt: null,
        syncedAdditions: 1, syncedDeletions: 0, syncedMergeable: null, syncedMergeableState: null,
        syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null,
      },
      commits: [], timeline: [], checks: [], comments: [{
        id: 'historical', localPrId: 'task-pushed', origin: 'local', scope: 'file-line',
        filePath: 'src/A.ts', lineNumber: 8, side: 'RIGHT', startLine: null, startSide: null,
        author: 'you', body: 'Historical local concern', createdAt: 1, resolvedAt: null,
        dismissedAt: null, strippedOnPushAt: 2, parentCommentId: null, publishedAt: null,
      }],
    };

    render(<PullDetailBody row={toRow(dto)} bundle={pushed} refresh={vi.fn()} />);

    expect(screen.getByText('Historical local concern')).toBeTruthy();
    expect(screen.queryByText('PENDING REVIEW')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Reply' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Resolve' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Send to dev' })).toBeNull();
  });

  it('offers non-navigating quick review and watch-plus-full-review actions for an unwatched repo', () => {
    const quickReview = vi.fn();
    const watchForFullReview = vi.fn();
    const openAgent = vi.fn();
    const dto: DashboardPR = {
      id: 'external-pr', repo: 'external/project', number: 7, title: 'External change', author: 'octocat',
      htmlUrl: '', createdAt: null, updatedAt: null, origin: 'REVIEW_REQUESTED', labels: [], labelColors: null,
      draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
      ciStatus: null, additions: 2, deletions: 1, commentCount: 0, attentionReason: null,
      state: 'open', closedAt: null, mergedAt: null, mergeable: null, mergeableState: null,
      headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
      reviewState: 'none',
    };

    render(
      <PullDetailBody
        row={toRow(dto)}
        bundle={null}
        refresh={vi.fn()}
        noWorkspace
        onRunQuickReview={quickReview}
        onWatchRepoForFullReview={watchForFullReview}
        onWorkWithAgent={openAgent}
      />,
    );

    const quickButton = screen.getByRole('button', { name: 'Run quick review' });
    expect(quickButton.classList.contains('pl-review-action')).toBe(true);
    expect(screen.queryByRole('menuitem', { name: 'Watch repo · Full review' })).toBeNull();

    fireEvent.click(quickButton);
    expect(quickReview).toHaveBeenCalledOnce();
    expect(openAgent).not.toHaveBeenCalled();
    expect(watchForFullReview).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'More review options' }));
    const watchButton = screen.getByRole('menuitem', { name: 'Watch repo · Full review' });
    fireEvent.click(watchButton);
    expect(watchForFullReview).toHaveBeenCalledOnce();
    expect(openAgent).not.toHaveBeenCalled();
    expect(screen.queryByRole('menuitem', { name: 'Watch repo · Full review' })).toBeNull();
  });

  it('keeps completed quick review non-navigable and renders its diff-only findings inline', () => {
    const openAgent = vi.fn();
    const quickReview = vi.fn();
    const dto: DashboardPR = {
      id: 'quick-pr', repo: 'external/project', number: 8, title: 'Quick change', author: 'octocat',
      htmlUrl: '', createdAt: null, updatedAt: null, origin: 'REVIEW_REQUESTED', labels: [], labelColors: null,
      draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
      ciStatus: null, additions: 2, deletions: 1, commentCount: 0, attentionReason: null,
      state: 'open', closedAt: null, mergedAt: null, mergeable: null, mergeableState: null,
      headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
      reviewState: 'none',
    };
    const result: AiReviewDraftDto = {
      id: 2, prId: 1, summary: 'The supplied diff changes retry behavior.', providerId: 'openai',
      model: 'review-model', headSha: 'abc', status: 'DRAFT', createdAt: '2026-07-19T00:00:00Z',
      updatedAt: '2026-07-19T00:00:00Z',
      comments: [{
        id: 3, filePath: 'src/retry.ts', lineNumber: 21, body: 'This retry can loop forever.',
        editedBody: null, severity: 'warning', dismissed: false, source: 'AI', side: 'RIGHT',
        startLine: null, startSide: null,
      }],
    };

    render(
      <PullDetailBody
        row={toRow(dto)}
        bundle={null}
        refresh={vi.fn()}
        noWorkspace
        onRunQuickReview={quickReview}
        onWorkWithAgent={openAgent}
        quickReview={{ state: 'done', result, error: null }}
      />,
    );

    const done = screen.getByRole('button', { name: 'Run quick review' }) as HTMLButtonElement;
    expect(done.disabled).toBe(true);
    expect(done.title).toBe('Quick review completed');
    fireEvent.click(done);
    expect(quickReview).not.toHaveBeenCalled();
    expect(openAgent).not.toHaveBeenCalled();
    expect(screen.getByText('Diff only · no repository exploration')).not.toBeNull();
    expect(screen.getByText(result.summary as string)).not.toBeNull();
    expect(screen.getByText('src/retry.ts:21')).not.toBeNull();
    expect(screen.getByText('This retry can loop forever.')).not.toBeNull();
  });

  it('shows running, retry, and repository-preparation button states without opening an agent window', () => {
    const retry = vi.fn();
    const openAgent = vi.fn();
    const dto: DashboardPR = {
      id: 'quick-state-pr', repo: 'external/project', number: 9, title: 'State change', author: 'octocat',
      htmlUrl: '', createdAt: null, updatedAt: null, origin: 'REVIEW_REQUESTED', labels: [], labelColors: null,
      draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
      ciStatus: null, additions: 2, deletions: 1, commentCount: 0, attentionReason: null,
      state: 'open', closedAt: null, mergedAt: null, mergeable: null, mergeableState: null,
      headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
      reviewState: 'none',
    };
    const base = {
      row: toRow(dto), bundle: null as LocalPRBundle | null, refresh: vi.fn(), noWorkspace: true,
      onRunQuickReview: retry, onWatchRepoForFullReview: vi.fn(), onWorkWithAgent: openAgent,
    };
    const { rerender } = render(
      <PullDetailBody
        {...base}
        quickReview={{ state: 'running', result: null, error: null }}
        fullReviewPreparation={{ state: 'preparing', error: null }}
      />,
    );

    const running = screen.getByRole('button', { name: 'Run quick review' }) as HTMLButtonElement;
    expect(running.disabled).toBe(true);
    expect(running.title).toBe('Quick review is running');
    fireEvent.click(screen.getByRole('button', { name: 'More review options' }));
    expect((screen.getByRole('menuitem', { name: 'Preparing repo…' }) as HTMLButtonElement).disabled).toBe(true);

    rerender(
      <PullDetailBody
        {...base}
        quickReview={{ state: 'failed', result: null, error: 'Diff is too large for quick review.' }}
        fullReviewPreparation={{ state: 'idle', error: null }}
      />,
    );
    expect(screen.getByRole('alert').textContent).toContain('Diff is too large');
    const retryButton = screen.getByRole('button', { name: 'Run quick review' });
    expect(retryButton.title).toBe('Retry quick review');
    fireEvent.click(retryButton);
    expect(retry).toHaveBeenCalledOnce();
    expect(openAgent).not.toHaveBeenCalled();
  });

  it('starts an idle full review and opens the agent window for running or completed reviews', () => {
    const assignAgent = vi.fn();
    const openAgent = vi.fn();
    const base: DashboardPR = {
      id: 'watched-pr', repo: 'trinodb/trino', number: 42, title: 'Watched change', author: 'octocat',
      htmlUrl: '', createdAt: null, updatedAt: null, origin: 'REVIEW_REQUESTED', labels: [], labelColors: null,
      draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
      ciStatus: null, additions: 2, deletions: 1, commentCount: 0, attentionReason: null,
      state: 'open', closedAt: null, mergedAt: null, mergeable: null, mergeableState: null,
      headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
    };
    const props = {
      bundle: null as LocalPRBundle | null,
      refresh: vi.fn(),
      onAssignAgent: assignAgent,
      onWorkWithAgent: openAgent,
    };
    const { rerender } = render(
      <PullDetailBody row={toRow({ ...base, reviewState: 'none' })} {...props} />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Full review' }));
    expect(assignAgent).toHaveBeenCalledOnce();
    expect(openAgent).not.toHaveBeenCalled();

    rerender(<PullDetailBody row={toRow({ ...base, reviewState: 'running' })} {...props} />);
    fireEvent.click(screen.getByRole('button', { name: 'Full review • running' }));
    expect(openAgent).toHaveBeenCalledOnce();

    rerender(<PullDetailBody row={toRow({ ...base, reviewState: 'done' })} {...props} />);
    fireEvent.click(screen.getByRole('button', { name: 'Full review • completed' }));
    expect(openAgent).toHaveBeenCalledTimes(2);
    expect(assignAgent).toHaveBeenCalledOnce();
  });
});
