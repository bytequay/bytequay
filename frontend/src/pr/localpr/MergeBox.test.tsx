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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MergeBox } from './MergeBox';
import { derivePRCapabilities } from '../prCapabilities';
import type { PullRequestDetailDto, ReviewThreadDto } from '../../types';
import type { LocalPR, LocalPRCheck } from '../../types/localPr';

afterEach(cleanup);

function pr(over: Partial<LocalPR> = {}): LocalPR {
  return {
    id: 'pr1', taskId: null, branchName: 'feat/x', baseBranch: 'main', title: 'T',
    description: '', status: 'remote-open', createdAt: 1, pushedAt: null, remotePrNumber: 42,
    remotePrUrl: null, mergedAt: null, closedAt: null,
    origin: 'external', repo: 'acme/widget', author: '@octocat', syncedAt: null,
    syncedAdditions: null, syncedDeletions: null,
    syncedMergeable: null, syncedMergeableState: null,
    syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null, ...over,
  };
}

/** A task-origin PR — direct merge stays behind the lifecycle notification. */
function taskPr(over: Partial<LocalPR> = {}): LocalPR {
  return pr({ origin: 'task', taskId: 't1', ...over });
}

function detail(over: Partial<PullRequestDetailDto> = {}): PullRequestDetailDto {
  return {
    repo: 'acme/widget', number: 42, body: null, labels: [], draft: false,
    mergeable: true, mergeableState: 'clean', additions: 0, deletions: 0, changedFiles: 0,
    approvalCount: 0, changesRequestedCount: 0, pendingReviewerCount: 0,
    requestedReviewers: [], ciStatus: 'PASSING', files: [], recentActivity: [],
    checkRuns: [], reviewThreads: [], linkedIssues: [], viewerCanWrite: true,
    headRef: 'feat/x', headRepo: 'acme/widget', baseRef: 'main', baseRepo: 'acme/widget',
    mergeQueueState: null, mergeQueueEnabled: false, ...over,
  };
}

function check(status: LocalPRCheck['status'], i: number): LocalPRCheck {
  return { id: `c${i}`, localPrId: 'pr1', kind: 'remote', name: `check-${i}`, status, durationMs: null, startedAt: 1, finishedAt: 1, runId: `${i}` };
}

describe('MergeBox checks summary', () => {
  it('splits skipped (neutral) out of successful, matching github.com phrasing', () => {
    const checks = [check('passed', 1), check('passed', 2), check('neutral', 3)];
    render(<MergeBox pr={pr()} capabilities={derivePRCapabilities(pr(), 'details')} localChecks={[]} remoteChecks={checks} openComments={0} pendingStripCount={0} draftCount={0} />);

    expect(screen.getByText('All checks have passed')).toBeTruthy();
    expect(screen.getByText('1 skipped, 2 successful checks')).toBeTruthy();
  });

  it('omits the skipped clause entirely when nothing was skipped', () => {
    const checks = [check('passed', 1)];
    render(<MergeBox pr={pr()} capabilities={derivePRCapabilities(pr(), 'details')} localChecks={[]} remoteChecks={checks} openComments={0} pendingStripCount={0} draftCount={0} />);

    expect(screen.getByText('1 successful check')).toBeTruthy();
  });

  it('collapses the check list by default, matching github.com', () => {
    const checks = [check('passed', 1)];
    const { container } = render(<MergeBox pr={pr()} capabilities={derivePRCapabilities(pr(), 'details')} localChecks={[]} remoteChecks={checks} openComments={0} pendingStripCount={0} draftCount={0} />);

    expect(screen.queryByText('check-1')).toBeNull();
    expect(container.querySelector('.mb-branch-icon svg')).toBeTruthy();
    expect(screen.getByRole('button', { name: /All checks have passed/ }).getAttribute('aria-expanded')).toBe('false');
  });
});

describe('MergeBox Local Review gate', () => {
  it('fails closed when authoritative task/validation/Brain state is absent', () => {
    const p = taskPr({ status: 'local-open', remotePrNumber: null });
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'task')} localChecks={[]} remoteChecks={[]}
      openComments={0} pendingStripCount={0} draftCount={0} onPush={vi.fn()} />);
    expect(screen.getByText('◆ Brain review pending')).toBeTruthy();
    expect((screen.getByRole('button', { name: /Approve & push/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('keeps Brain budget exhaustion as an enabled amber human gate', () => {
    const onPush = vi.fn();
    const p = taskPr({ status: 'local-open', remotePrNumber: null });
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'task')} localChecks={[]} remoteChecks={[]}
      openComments={0} pendingStripCount={0} draftCount={0} onPush={onPush}
      localReviewGate={{
        eligible: true,
        reason: 'Brain review exhausted its budget with unresolved findings; human approval is required.',
        brainReview: { state: 'unresolved', unresolved: 2 },
      }} />);
    expect(screen.getByText('Human approval required before shipping')).toBeTruthy();
    const button = screen.getByRole('button', { name: /Approve & push/ });
    expect((button as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(button);
    expect(onPush).toHaveBeenCalledOnce();
  });
});

describe('MergeBox mergeable line', () => {
  it('still renders the box for the mergeable line alone when there are no checks and no gate applies', () => {
    render(<MergeBox pr={pr({ syncedMergeable: true, syncedMergeableState: 'clean' })} capabilities={derivePRCapabilities(pr(), 'details')} localChecks={[]} remoteChecks={[]} openComments={0} pendingStripCount={0} draftCount={0} />);

    expect(screen.getByText('No conflicts with base branch')).toBeTruthy();
  });

  it('shows the no-conflicts line when GitHub reports the PR mergeable', () => {
    render(<MergeBox pr={pr({ syncedMergeable: true, syncedMergeableState: 'clean' })} capabilities={derivePRCapabilities(pr(), 'details')} localChecks={[]} remoteChecks={[check('passed', 1)]} openComments={0} pendingStripCount={0} draftCount={0} />);

    expect(screen.getByText('No conflicts with base branch')).toBeTruthy();
  });

  it('shows a conflict warning when GitHub reports the PR not mergeable', () => {
    render(<MergeBox pr={pr({ syncedMergeable: false, syncedMergeableState: 'dirty' })} capabilities={derivePRCapabilities(pr(), 'details')} localChecks={[]} remoteChecks={[check('passed', 1)]} openComments={0} pendingStripCount={0} draftCount={0} />);

    expect(screen.getByText('This branch has conflicts that must be resolved')).toBeTruthy();
  });

  it('shows no mergeable line while GitHub has not computed it yet', () => {
    render(<MergeBox pr={pr({ syncedMergeable: null })} capabilities={derivePRCapabilities(pr(), 'details')} localChecks={[]} remoteChecks={[check('passed', 1)]} openComments={0} pendingStripCount={0} draftCount={0} />);

    expect(screen.queryByText(/conflicts/)).toBeNull();
  });
});

describe('MergeBox direct merge flow (external, remote-open)', () => {
  it('hides the merge action when GitHub reports branch conflicts', () => {
    const p = pr({ syncedMergeable: false, syncedMergeableState: 'dirty' });
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'details')} localChecks={[]} remoteChecks={[]} remoteDetail={detail({ mergeable: false })} openComments={0} pendingStripCount={0} draftCount={0} onMerge={vi.fn()} />);

    expect(screen.getByText('This branch has conflicts that must be resolved')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Squash and merge' })).toBeNull();
  });

  it('shows a method picker and command-line hint when the repo has no merge queue', () => {
    const p = pr();
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'details')} localChecks={[]} remoteChecks={[]} remoteDetail={detail()} openComments={0} pendingStripCount={0} draftCount={0} onMerge={vi.fn()} />);

    expect(screen.getByText('Squash and merge')).toBeTruthy();
    expect(screen.getByText(/command line/)).toBeTruthy();
    expect(screen.queryByText('Merge when ready')).toBeNull();
  });

  it('shows "Merge when ready" and the queue caption when the repo has a merge queue', () => {
    const p = pr({ syncedMergeQueueEnabled: true });
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'details')} localChecks={[]} remoteChecks={[]} remoteDetail={detail()} openComments={0} pendingStripCount={0} draftCount={0} onMerge={vi.fn()} />);

    expect(screen.getByText('Merge when ready')).toBeTruthy();
    expect(screen.getByText(/uses the merge queue/)).toBeTruthy();
    expect(screen.queryByText('Squash and merge')).toBeNull();
  });

  it('swaps to an inline confirm step instead of merging immediately, then calls onMerge with the chosen method', () => {
    const onMerge = vi.fn();
    const p = pr();
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'details')} localChecks={[]} remoteChecks={[]} remoteDetail={detail()} openComments={0} pendingStripCount={0} draftCount={0} onMerge={onMerge} />);

    fireEvent.click(screen.getByText('Squash and merge'));
    expect(onMerge).not.toHaveBeenCalled();
    expect(screen.getByText(/This will squash your changes and merge them into main/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Confirm squash and merge/ }));
    expect(onMerge).toHaveBeenCalledWith('squash');
  });

  it('cancel returns to the idle state without calling onMerge', () => {
    const onMerge = vi.fn();
    const p = pr();
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'details')} localChecks={[]} remoteChecks={[]} remoteDetail={detail()} openComments={0} pendingStripCount={0} draftCount={0} onMerge={onMerge} />);

    fireEvent.click(screen.getByText('Squash and merge'));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onMerge).not.toHaveBeenCalled();
    expect(screen.getByText('Squash and merge')).toBeTruthy();
  });

  it('hides direct merge while CI is red', () => {
    const p = pr();
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'details')} localChecks={[]} remoteChecks={[]} remoteDetail={detail({ ciStatus: 'FAILING' })} openComments={0} pendingStripCount={0} draftCount={0} onMerge={vi.fn()} />);

    expect(screen.queryByRole('button', { name: 'Squash and merge' })).toBeNull();
  });

  it('hides direct merge while a live GitHub thread is unresolved', () => {
    const p = pr();
    const unresolved: ReviewThreadDto = {
      rootGithubId: 1, filePath: 'A.java', line: 7, side: 'RIGHT', diffHunk: null,
      messages: [], resolved: false, outdated: false, startLine: null, startSide: null,
      originalLine: 7, originalStartLine: null,
    };
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'details')} localChecks={[]} remoteChecks={[]} remoteDetail={detail({ reviewThreads: [unresolved] })} openComments={0} pendingStripCount={0} draftCount={0} onMerge={vi.fn()} />);

    expect(screen.queryByRole('button', { name: 'Squash and merge' })).toBeNull();
  });

  it('keeps task-origin merge behind the lifecycle notification gate', () => {
    const p = taskPr();
    render(<MergeBox pr={p} capabilities={derivePRCapabilities(p, 'task')} localChecks={[]} remoteChecks={[]} remoteDetail={detail()} openComments={0} pendingStripCount={0} draftCount={0} onMerge={vi.fn()} />);

    expect(screen.queryByRole('button', { name: 'Squash and merge' })).toBeNull();
  });

  it('shows the queued state with a Remove from queue button, hiding the checks summary', () => {
    const onDequeue = vi.fn();
    const p = taskPr({ syncedMergeQueueState: 'QUEUED' });
    render(
      <MergeBox
        pr={p}
        capabilities={derivePRCapabilities(p, 'task')}
        localChecks={[]}
        remoteChecks={[check('passed', 1)]}
        openComments={0}
        pendingStripCount={0}
        draftCount={0}
        onDequeue={onDequeue}
      />,
    );

    expect(screen.getByText('Queued to merge…')).toBeTruthy();
    expect(screen.getByText(/next up in the merge queue/)).toBeTruthy();
    expect(screen.queryByText('All checks have passed')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Remove from queue' }));
    expect(onDequeue).toHaveBeenCalledOnce();
  });

  it('shows the merged state with a Delete branch button once merged and not yet deleted', () => {
    const onDeleteBranch = vi.fn();
    const p = taskPr({ status: 'merged', mergedAt: 1 });
    render(
      <MergeBox
        pr={p}
        capabilities={derivePRCapabilities(p, 'task')}
        localChecks={[]}
        remoteChecks={[]}
        openComments={0}
        pendingStripCount={0}
        draftCount={0}
        onDeleteBranch={onDeleteBranch}
      />,
    );

    expect(screen.getByText('Pull request successfully merged and closed')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Delete branch' }));
    expect(onDeleteBranch).toHaveBeenCalledOnce();
  });

  it('keeps the GitHub-style merged state after the branch has been deleted', () => {
    const p = taskPr({ status: 'merged', mergedAt: 1, branchDeletedAt: 2 });
    render(
      <MergeBox
        pr={p}
        capabilities={derivePRCapabilities(p, 'task')}
        localChecks={[check('passed', 1)]}
        remoteChecks={[check('passed', 2)]}
        openComments={0}
        pendingStripCount={0}
        draftCount={0}
        onDeleteBranch={vi.fn()}
        onRunTests={vi.fn()}
      />,
    );

    expect(screen.getByText('Pull request successfully merged and closed')).toBeTruthy();
    expect(screen.getByText("You're all set — the branch has been merged.")).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Delete branch' })).toBeNull();
    expect(screen.queryByText('All checks have passed')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Run tests' })).toBeNull();
  });
});
