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
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LocalPRBundle } from '../types/localPr';
import { createAgentReviewFixture } from './agentReviewTestData';
import { useAgentReviewState } from './useAgentReviewState';

function bundle(): LocalPRBundle {
  return {
    pr: {
      id: 'pr-live', taskId: null, branchName: 'feature', baseBranch: 'main', title: 'Review live data',
      description: '', status: 'remote-open', createdAt: 1, pushedAt: 1, remotePrNumber: 9,
      remotePrUrl: 'https://example.test/9', mergedAt: null, closedAt: null, origin: 'external',
      repo: 'acme/widget', author: 'maria', syncedAt: 1, syncedAdditions: 2, syncedDeletions: 1,
      syncedMergeable: true, syncedMergeableState: 'clean', syncedMergeQueueEnabled: false,
      syncedMergeQueueState: null, branchDeletedAt: null,
    },
    commits: [{ id: 'commit', localPrId: 'pr-live', sha: 'head123', message: 'change', additions: 2, deletions: 1, authoredAt: 1, pushedAt: 1 }],
    timeline: [], checks: [], comments: [],
  };
}

function data() {
  return createAgentReviewFixture(bundle(), [{
    filename: 'src/A.ts', status: 'modified', additions: 2, deletions: 1,
    patch: '@@ -1 +1 @@\n-old\n+new',
  }]);
}

afterEach(() => { vi.restoreAllMocks(); });

describe('useAgentReviewState', () => {
  it('starts, mutates, and publishes through the persisted review bridge', async () => {
    const live = data();
    const source = bundle();
    source.comments.push({
      id: 'manual-comment', localPrId: source.pr.id, origin: 'local', scope: 'file-line',
      filePath: 'src/A.ts', lineNumber: 1, side: 'RIGHT', startLine: null, startSide: null,
      author: 'you', body: 'Manual reviewer draft', createdAt: 2, resolvedAt: null,
      dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, publishedAt: null,
    });
    const getAgentReviewSession = vi.fn(async (): Promise<ReturnType<typeof data> | null> => null);
    const startAgentReviewSession = vi.fn(async () => live);
    const mutateAgentReviewFinding = vi.fn(async () => live);
    const cancelAgentReviewRound = vi.fn(async () => live);
    const publishLocalPrReview = vi.fn(async () => bundle().pr);
    window.bridge = {
      getAgentReviewSession, startAgentReviewSession, mutateAgentReviewFinding,
      publishLocalPrReview, cancelAgentReviewRound,
    } as unknown as typeof window.bridge;
    const refresh = vi.fn();
    const { result } = renderHook(() => useAgentReviewState(source, refresh));
    await waitFor(() => expect(getAgentReviewSession).toHaveBeenCalledWith('pr-live'));

    act(() => result.current.startReview());
    await waitFor(() => expect(result.current.data?.session.id).toBe(live.session.id));
    expect(startAgentReviewSession).toHaveBeenCalledWith('pr-live');

    act(() => result.current.toggleFinding('finding-1'));
    await waitFor(() => expect(mutateAgentReviewFinding).toHaveBeenCalledWith(
      'finding-1', { action: 'exclude' },
    ));

    act(() => result.current.reopenFinding('finding-1'));
    await waitFor(() => expect(mutateAgentReviewFinding).toHaveBeenCalledWith(
      'finding-1', { action: 'reopen' },
    ));

    act(() => result.current.cancelRound(live.rounds[0].id));
    await waitFor(() => expect(cancelAgentReviewRound).toHaveBeenCalledWith(live.rounds[0].id));

    act(() => result.current.submitReview('REQUEST_CHANGES'));
    await waitFor(() => expect(publishLocalPrReview).toHaveBeenCalled());
    expect(publishLocalPrReview).toHaveBeenCalledWith('pr-live', expect.objectContaining({
      verdict: 'REQUEST_CHANGES',
      findingIds: expect.arrayContaining(['finding-1', 'finding-2']),
      comments: expect.arrayContaining(['fixture-comment-1', 'fixture-comment-2', 'manual-comment']),
    }));
  });
});
