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
import { PrDetailsView } from './PrDetailsView';
import { useExternalPrActions } from './useExternalPrActions';
import type { LocalPRBundle } from '../../types/localPr';

vi.mock('./useExternalPrActions', () => ({
  useExternalPrActions: vi.fn(),
}));

vi.mock('./LocalPrReviewScreen', () => ({
  LocalPrReviewScreen: ({ fetchFileBlob }: { fetchFileBlob?: (path: string) => Promise<{ lines: string[] }> }) => (
    <button type="button" onClick={() => { void fetchFileBlob?.('src/Foo.ts'); }}>
      expand
    </button>
  ),
}));

vi.mock('./PRView', () => ({
  PRView: () => <div />,
}));

vi.mock('./PushDialog', () => ({
  PushDialog: () => <div />,
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, 'bridge');
});

function bundle(): LocalPRBundle {
  return {
    pr: {
      id: 'pr-1',
      taskId: null,
      branchName: 'feature',
      baseBranch: 'main',
      title: 'Test PR',
      description: '',
      status: 'remote-open',
      createdAt: 1,
      pushedAt: null,
      remotePrNumber: 42,
      remotePrUrl: null,
      mergedAt: null,
      closedAt: null,
      origin: 'external',
      repo: 'owner/repo',
      author: 'octocat',
      syncedAt: 1,
      syncedAdditions: 1,
      syncedDeletions: 1,
      syncedMergeable: true,
      syncedMergeableState: 'clean',
      syncedMergeQueueEnabled: false,
      syncedMergeQueueState: null,
      branchDeletedAt: null,
    },
    commits: [
      { id: 'c1', localPrId: 'pr-1', sha: 'oldsha', message: 'old', additions: 0, deletions: 0, authoredAt: 1, pushedAt: null },
      { id: 'c2', localPrId: 'pr-1', sha: 'headsha', message: 'head', additions: 0, deletions: 0, authoredAt: 2, pushedAt: null },
    ],
    timeline: [],
    checks: [],
    comments: [],
  };
}

describe('PrDetailsView', () => {
  it('passes a PR-head blob fetcher to the review diff', async () => {
    const fetchFileBlob = vi.fn().mockResolvedValue({ lines: [] });
    (window as unknown as { bridge: { fetchFileBlob: typeof fetchFileBlob } }).bridge = { fetchFileBlob };
    const b = bundle();
    vi.mocked(useExternalPrActions).mockReturnValue({
      bundle: b,
      refresh: vi.fn(),
      syncing: false,
      localPr: b.pr,
      capabilities: {
        draftLocalComments: true,
        publishReview: true,
        push: false,
        merge: true,
        chatAgent: false,
        postRemoteComment: true,
      },
      localComment: '',
      setLocalComment: vi.fn(),
      submitLocalComment: vi.fn(),
      confirmPush: vi.fn(),
      confirmMerge: vi.fn(),
      dequeuePr: vi.fn(),
      deleteBranch: vi.fn(),
      publishReview: vi.fn(),
      publishBusy: false,
      addLocalLineComment: vi.fn(),
      replyLocalLineComment: vi.fn(),
      resolveLocalComment: vi.fn(),
      dismissLocalComment: vi.fn(),
      pushOpen: false,
      setPushOpen: vi.fn(),
      reviewOpen: true,
      setReviewOpen: vi.fn(),
      prBusy: false,
      reviewFiles: [],
      reviewError: null,
      runLocalTests: vi.fn(),
      testsBusy: false,
    });

    render(<PrDetailsView pr={{ id: 1, repo: 'owner/repo', number: 42 }} />);
    fireEvent.click(screen.getByRole('button', { name: 'expand' }));

    await waitFor(() => expect(fetchFileBlob).toHaveBeenCalledWith('owner/repo', 'src/Foo.ts', 'headsha'));
  });
});
