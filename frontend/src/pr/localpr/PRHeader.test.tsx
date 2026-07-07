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
import { PRHeader } from './PRHeader';
import type { LocalPR } from '../../types/localPr';

afterEach(cleanup);

function pr(): LocalPR {
  return {
    id: 'pr1', taskId: null, branchName: 'feat/x', baseBranch: 'main', title: 'T',
    description: '', status: 'remote-open', createdAt: 1, pushedAt: null, remotePrNumber: 42,
    remotePrUrl: null, mergedAt: null, closedAt: null,
    origin: 'external', repo: 'acme/widget', author: '@octocat', syncedAt: null,
    syncedAdditions: null, syncedDeletions: null, syncedMergeable: null, syncedMergeableState: null, syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null,
  };
}

describe('PRHeader tabs', () => {
  it('marks the active tab and fires onTabChange for the others', () => {
    const onTabChange = vi.fn();
    render(<PRHeader
      pr={pr()} syncedAt={null} syncing={false} onRefresh={() => {}}
      commitCount={10} checkCount={60} conversationCount={9} additions={0} deletions={0}
      activeTab="conversation" onTabChange={onTabChange}
    />);

    const conversationTab = screen.getByRole('tab', { name: /Conversation/ });
    const commitsTab = screen.getByRole('tab', { name: /Commits/ });
    expect(conversationTab.getAttribute('aria-selected')).toBe('true');
    expect(commitsTab.getAttribute('aria-selected')).toBe('false');

    fireEvent.click(commitsTab);
    expect(onTabChange).toHaveBeenCalledWith('commits');

    fireEvent.click(screen.getByRole('tab', { name: /Checks/ }));
    expect(onTabChange).toHaveBeenCalledWith('checks');
  });

  it('shows the counts passed in for each tab', () => {
    render(<PRHeader
      pr={pr()} syncedAt={null} syncing={false} onRefresh={() => {}}
      commitCount={10} checkCount={60} conversationCount={9} additions={0} deletions={0}
      activeTab="conversation" onTabChange={() => {}}
    />);

    expect(screen.getByText('9')).toBeTruthy();
    expect(screen.getByText('10')).toBeTruthy();
    expect(screen.getByText('60')).toBeTruthy();
  });
});
