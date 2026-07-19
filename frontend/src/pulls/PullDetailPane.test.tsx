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
import type { LocalPRBundle } from '../types/localPr';
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
    } as unknown as typeof window.bridge;
    const dto: DashboardPR = {
      id: 'pr-github-link', repo: 'trinodb/trino', number: 1, title: 'A change', author: 'octocat', htmlUrl: url,
      createdAt: null, updatedAt: null, origin: 'AUTHORED', labels: [], labelColors: null, draft: false,
      viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: null,
      additions: 1, deletions: 1, commentCount: 0, attentionReason: null, state: 'open', closedAt: null,
      mergedAt: null, mergeable: null, mergeableState: null, headPushedAt: null, reviewerVerdicts: null,
      snoozedUntil: null, snoozeWakeReason: null,
    };

    render(<PullDetailPane row={toRow(dto)} />);

    expect(screen.getByText('A change')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Open pull request on GitHub' })).toBeNull();
  });

  it('renders an already-loaded task bundle and delegates comments to its host', async () => {
    const getLocalPrBundle = vi.fn();
    window.bridge = { getLocalPrBundle } as unknown as typeof window.bridge;
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
        id: 'task-pr', taskId: 'task-1', branchName: 'codex/fix-it', baseBranch: 'master',
        title: 'Task bundle title', description: 'Loaded by the task route', status: 'local-open',
        createdAt: 0, pushedAt: null, remotePrNumber: null, remotePrUrl: null, mergedAt: null,
        closedAt: null, origin: 'task', repo: 'trinodb/trino', author: 'octocat', syncedAt: null,
        syncedAdditions: null, syncedDeletions: null, syncedMergeable: null, syncedMergeableState: null,
        syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null,
      },
      commits: [], timeline: [], checks: [], comments: [],
    } as LocalPRBundle;
    const onComment = vi.fn().mockResolvedValue(undefined);

    render(
      <PullDetailBody
        row={toRow(dto)}
        bundle={bundle}
        refresh={vi.fn()}
        onComment={onComment}
      />,
    );

    expect(screen.getByText(/Task bundle title/)).not.toBeNull();
    expect(screen.getByText('Loaded by the task route')).not.toBeNull();
    expect(getLocalPrBundle).not.toHaveBeenCalled();

    fireEvent.change(screen.getByPlaceholderText('Add a comment'), { target: { value: 'Looks good' } });
    fireEvent.click(screen.getByRole('button', { name: /Comment/ }));
    await waitFor(() => expect(onComment).toHaveBeenCalledWith('Looks good'));
  });
});
