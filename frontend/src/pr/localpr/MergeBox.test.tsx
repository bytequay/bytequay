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
import { MergeBox } from './MergeBox';
import { derivePRCapabilities } from '../prCapabilities';
import type { LocalPR, LocalPRCheck } from '../../types/localPr';

afterEach(cleanup);

function pr(over: Partial<LocalPR> = {}): LocalPR {
  return {
    id: 'pr1', taskId: null, branchName: 'feat/x', baseBranch: 'main', title: 'T',
    description: '', status: 'remote-open', createdAt: 1, pushedAt: null, remotePrNumber: 42,
    remotePrUrl: null, mergedAt: null, closedAt: null,
    origin: 'external', repo: 'acme/widget', author: '@octocat', syncedAt: null,
    syncedAdditions: null, syncedDeletions: null,
    syncedMergeable: null, syncedMergeableState: null, ...over,
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
    render(<MergeBox pr={pr()} capabilities={derivePRCapabilities(pr(), 'details')} localChecks={[]} remoteChecks={checks} openComments={0} pendingStripCount={0} draftCount={0} />);

    expect(screen.queryByText('check-1')).toBeNull();
  });
});

describe('MergeBox mergeable line', () => {
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
