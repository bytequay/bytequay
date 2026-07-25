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
import type { PullRequestDetailDto } from '../types';
import type { LocalPR } from '../types/localPr';
import PullMergeBox from './PullMergeBox';

afterEach(() => {
  cleanup();
  delete (globalThis as { bridge?: unknown }).bridge;
});

const pr = (over: Partial<LocalPR> = {}): LocalPR => ({
  id: 'pr-1', status: 'remote-open', remotePrNumber: 7, branchName: 'feat/x', baseBranch: 'main',
  syncedMergeable: true, syncedMergeableState: null, syncedMergeQueueEnabled: false,
  syncedMergeQueueState: null, branchDeletedAt: null, ...over,
} as LocalPR);

const detail = (over: Partial<PullRequestDetailDto> = {}): PullRequestDetailDto => ({
  draft: false, viewerCanWrite: true, ciStatus: 'PASSING', changesRequestedCount: 0,
  mergeable: true, reviewThreads: [], ...over,
} as PullRequestDetailDto);

describe('PullMergeBox', () => {
  it('offers a merge action when the PR is clean', () => {
    render(<PullMergeBox pr={pr()} detail={detail()} onDone={() => {}} />);
    expect(screen.getByText('Ready to merge')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Squash and merge' })).toBeTruthy();
  });

  it('lists every blocker and withholds the merge action', () => {
    render(<PullMergeBox
      pr={pr()}
      detail={detail({ ciStatus: 'FAILING', reviewThreads: [{ resolved: false }, { resolved: null }] as PullRequestDetailDto['reviewThreads'] })}
      onDone={() => {}}
    />);
    expect(screen.getByText('Some checks are failing.')).toBeTruthy();
    expect(screen.getByText('2 unresolved conversations.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Squash and merge' })).toBeNull();
  });

  it('stays visible for a draft PR and explains why it cannot merge', () => {
    render(<PullMergeBox
      pr={pr({ status: 'remote-drafted' })}
      detail={detail({ draft: true })}
      onDone={() => {}}
    />);
    expect(screen.getByText('This PR can’t be merged yet')).toBeTruthy();
    expect(screen.getByText('This PR is still a draft.')).toBeTruthy();
  });

  it('confirms then calls the merge bridge with the chosen method', () => {
    const mergeLocalPr = vi.fn().mockResolvedValue({});
    (globalThis as { bridge?: unknown }).bridge = { mergeLocalPr };
    const onDone = vi.fn();
    render(<PullMergeBox pr={pr()} detail={detail()} onDone={onDone} />);
    fireEvent.click(screen.getByRole('button', { name: 'Squash and merge' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm squash and merge' }));
    expect(mergeLocalPr).toHaveBeenCalledWith('pr-1', 'squash');
  });

  it('shows a merge-queue button instead of a method picker', () => {
    render(<PullMergeBox pr={pr({ syncedMergeQueueEnabled: true })} detail={detail()} onDone={() => {}} />);
    expect(screen.getByRole('button', { name: 'Merge when ready' })).toBeTruthy();
  });

  it('offers to delete the branch of a merged PR', () => {
    render(<PullMergeBox pr={pr({ status: 'merged' })} detail={null} onDone={() => {}} />);
    expect(screen.getByRole('button', { name: 'Delete branch' })).toBeTruthy();
  });
});
