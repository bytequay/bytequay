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
import type { ReviewVerdict } from '../pages/SubmitReviewDrawer';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import { createAgentReviewFixture } from './agentReviewTestData';
import { useAgentReviewState } from './useAgentReviewState';

function bundle(id = 'pr-live'): LocalPRBundle {
  return {
    pr: {
      id, taskId: null, branchName: 'feature', baseBranch: 'main', title: 'Review live data',
      description: '', status: 'remote-open', createdAt: 1, pushedAt: 1, remotePrNumber: 9,
      remotePrUrl: 'https://example.test/9', mergedAt: null, closedAt: null, origin: 'external',
      repo: 'acme/widget', author: 'maria', syncedAt: 1, syncedAdditions: 2, syncedDeletions: 1,
      syncedMergeable: true, syncedMergeableState: 'clean', syncedMergeQueueEnabled: false,
      syncedMergeQueueState: null, branchDeletedAt: null,
    },
    commits: [{ id: 'commit', localPrId: id, sha: 'head123', message: 'change', additions: 2, deletions: 1, authoredAt: 1, pushedAt: 1 }],
    timeline: [], checks: [], comments: [],
  };
}

function data() {
  return createAgentReviewFixture(bundle(), [{
    filename: 'src/A.ts', status: 'modified', additions: 2, deletions: 1,
    patch: '@@ -1 +1 @@\n-old\n+new',
  }]);
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

afterEach(() => { vi.restoreAllMocks(); });

describe('useAgentReviewState', () => {
  it('keeps polling an expected review until an optimistic start becomes visible', async () => {
    const live = data();
    const getAgentReview = vi.fn()
      .mockResolvedValueOnce(null)
      .mockResolvedValue(live);
    let poll: (() => void) | null = null;
    vi.spyOn(window, 'setInterval').mockImplementation((handler, delay) => {
      if (delay === 1_000) poll = () => handler(undefined);
      return 1 as unknown as ReturnType<typeof window.setInterval>;
    });
    window.bridge = { getAgentReview } as unknown as typeof window.bridge;

    const { result } = renderHook(() =>
      useAgentReviewState(bundle(), vi.fn(), undefined, null, true));
    await waitFor(() => expect(poll).not.toBeNull());

    await act(async () => { poll?.(); });

    await waitFor(() => expect(result.current.data?.review.id).toBe(live.review.id));
    expect(getAgentReview).toHaveBeenCalledTimes(2);
  });

  it('starts, mutates, and publishes through the persisted review bridge', async () => {
    const live = data();
    const source = bundle();
    source.comments.push({
      id: 'manual-comment', localPrId: source.pr.id, origin: 'local', scope: 'file-line',
      filePath: 'src/A.ts', lineNumber: 1, side: 'RIGHT', startLine: null, startSide: null,
      author: 'you', body: 'Manual reviewer draft', createdAt: 2, resolvedAt: null,
      dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, publishedAt: null,
    });
    const getAgentReview = vi.fn(async (): Promise<ReturnType<typeof data> | null> => null);
    const startAgentReview = vi.fn(async () => live);
    const mutateAgentReviewFinding = vi.fn(async () => live);
    const cancelAgentReviewRound = vi.fn(async () => live);
    const publishLocalPrReview = vi.fn(async () => bundle().pr);
    window.bridge = {
      getAgentReview, startAgentReview, mutateAgentReviewFinding,
      publishLocalPrReview, cancelAgentReviewRound,
    } as unknown as typeof window.bridge;
    const refresh = vi.fn();
    const { result } = renderHook(() => useAgentReviewState(source, refresh));
    await waitFor(() => expect(getAgentReview).toHaveBeenCalledWith('pr-live'));
    expect(result.current.pendingComments).toHaveLength(0);

    act(() => result.current.startReview());
    await waitFor(() => expect(result.current.data?.review.id).toBe(live.review.id));
    expect(result.current.pendingComments.map(comment => comment.id)).toContain('manual-comment');
    expect(startAgentReview).toHaveBeenCalledWith('pr-live', { workspaceId: undefined });

    act(() => result.current.toggleFinding('finding-1'));
    await waitFor(() => expect(mutateAgentReviewFinding).toHaveBeenCalledWith(
      'finding-1', { action: 'include' },
    ));

    act(() => result.current.reopenFinding('finding-1'));
    await waitFor(() => expect(mutateAgentReviewFinding).toHaveBeenCalledWith(
      'finding-1', { action: 'reopen' },
    ));

    await act(async () => { await result.current.setFindingResolved('finding-1', true); });
    expect(mutateAgentReviewFinding).toHaveBeenCalledWith('finding-1', { action: 'resolve' });

    act(() => result.current.cancelRound(live.rounds[0].id));
    await waitFor(() => expect(cancelAgentReviewRound).toHaveBeenCalledWith(live.rounds[0].id));

    act(() => result.current.submitReview('REQUEST_CHANGES'));
    await waitFor(() => expect(publishLocalPrReview).toHaveBeenCalled());
    expect(publishLocalPrReview).toHaveBeenCalledWith('pr-live', expect.objectContaining({
      verdict: 'REQUEST_CHANGES',
      findingIds: [],
      comments: ['manual-comment'],
    }));
    await waitFor(() => expect(result.current.pendingComments.map(comment => comment.id).sort())
      .toEqual(['fixture-comment-1', 'fixture-comment-2']));
  });

  it('reconciles only overlapping writes and keeps a superseded successful send successful', async () => {
    const live = data();
    const reconciled = {
      ...live,
      pr_comments: live.pr_comments.map(comment => comment.id === 'fixture-comment-1'
        ? { ...comment, body: 'reconciled body' }
        : comment),
    };
    const first = deferred<typeof live>();
    const second = deferred<typeof live>();
    const send = deferred<typeof live>();
    const getAgentReview = vi.fn()
      .mockResolvedValueOnce(live)
      .mockResolvedValue(reconciled);
    const mutateAgentReviewFinding = vi.fn()
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(() => second.promise)
      .mockResolvedValue(live);
    window.bridge = {
      getAgentReview,
      mutateAgentReviewFinding,
      sendAgentReviewRoundMessage: vi.fn(() => send.promise),
    } as unknown as typeof window.bridge;
    const { result } = renderHook(() => useAgentReviewState(bundle(), vi.fn()));
    await waitFor(() => expect(result.current.data).not.toBeNull());

    act(() => {
      result.current.toggleFinding('finding-1');
      result.current.reopenFinding('finding-1');
    });
    second.resolve(live);
    first.resolve(live);
    await waitFor(() => expect(getAgentReview).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.data?.pr_comments[0].body).toBe('reconciled body'));

    let sent!: Promise<boolean>;
    act(() => { sent = result.current.sendRoundMessage(live.rounds[0].id, 'planner', 'Check teardown'); });
    act(() => result.current.toggleFinding('finding-1'));
    await waitFor(() => expect(mutateAgentReviewFinding).toHaveBeenCalledTimes(3));
    send.resolve(live);
    expect(await sent).toBe(true);
  });

  it('flushes the latest debounced draft before publishing and cancels it before dismissing', async () => {
    const live = data();
    live.findings[0] = { ...live.findings[0], lifecycle_status: 'included' };
    const source = bundle();
    const getAgentReview = vi.fn(async () => live);
    const mutateAgentReviewFinding = vi.fn(async () => live);
    const publishLocalPrReview = vi.fn(async () => source.pr);
    const beforePublish = vi.fn(async (_verdict: ReviewVerdict, _comments: LocalPRComment[]) => {});
    window.bridge = {
      getAgentReview, mutateAgentReviewFinding, publishLocalPrReview,
    } as unknown as typeof window.bridge;
    const { result } = renderHook(() => useAgentReviewState(source, vi.fn(), beforePublish));
    await waitFor(() => expect(result.current.data).not.toBeNull());

    act(() => result.current.updateComment('fixture-comment-1', 'Latest unsaved edit'));
    act(() => result.current.submitReview('REQUEST_CHANGES'));
    await waitFor(() => expect(publishLocalPrReview).toHaveBeenCalledOnce());
    expect(mutateAgentReviewFinding).toHaveBeenCalledWith(
      'finding-1', { action: 'editDraft', text: 'Latest unsaved edit' },
    );
    expect(mutateAgentReviewFinding.mock.invocationCallOrder[0])
      .toBeLessThan(publishLocalPrReview.mock.invocationCallOrder[0]);
    expect(publishLocalPrReview).toHaveBeenCalledWith('pr-live', expect.objectContaining({
      findingIds: ['finding-1'],
      comments: ['fixture-comment-1'],
    }));
    expect(beforePublish.mock.calls[0][1].find(comment => comment.id === 'fixture-comment-1')?.body)
      .toBe('Latest unsaved edit');

    act(() => result.current.updateComment('fixture-comment-2', 'Discard this edit'));
    act(() => result.current.dismissComment('fixture-comment-2'));
    await new Promise(resolve => window.setTimeout(resolve, 400));
    expect(mutateAgentReviewFinding).toHaveBeenCalledWith('finding-2', { action: 'dismiss' });
    expect(mutateAgentReviewFinding).not.toHaveBeenCalledWith(
      'finding-2', { action: 'editDraft', text: 'Discard this edit' },
    );
  });

  it('publishes exactly the visible pending set, excluding resolved and dropped findings', async () => {
    const live = data();
    live.pr_comments[0] = { ...live.pr_comments[0], resolvedAt: 9 };
    live.findings[1] = { ...live.findings[1], lifecycle_status: 'dropped' };
    const source = bundle();
    source.comments.push({
      ...live.pr_comments[0], id: 'manual-comment', findingId: null, resolvedAt: null,
      body: 'Manual reviewer draft',
    }, {
      ...live.pr_comments[0], id: 'manual-reply', findingId: null, resolvedAt: null,
      body: 'A local thread reply', parentCommentId: 'manual-comment',
    });
    const publishLocalPrReview = vi.fn(async () => source.pr);
    window.bridge = {
      getAgentReview: vi.fn(async () => live),
      publishLocalPrReview,
    } as unknown as typeof window.bridge;
    const { result } = renderHook(() => useAgentReviewState(source, vi.fn()));
    await waitFor(() => expect(result.current.data).not.toBeNull());
    expect(result.current.pendingComments.map(comment => comment.id)).toEqual(['manual-comment']);

    act(() => result.current.submitReview('COMMENT'));
    await waitFor(() => expect(publishLocalPrReview).toHaveBeenCalledWith('pr-live', {
      verdict: 'COMMENT', findingIds: [], comments: ['manual-comment'],
    }));
  });

  it('discards a completed mutation when the hook has moved to another pull request', async () => {
    const started = deferred<ReturnType<typeof data>>();
    const getAgentReview = vi.fn(async (): Promise<ReturnType<typeof data> | null> => null);
    window.bridge = {
      getAgentReview,
      startAgentReview: vi.fn(() => started.promise),
    } as unknown as typeof window.bridge;
    const { result, rerender } = renderHook(
      ({ source }) => useAgentReviewState(source, vi.fn()),
      { initialProps: { source: bundle() } },
    );
    await waitFor(() => expect(getAgentReview).toHaveBeenCalledWith('pr-live'));
    act(() => result.current.startReview());

    rerender({ source: bundle('pr-next') });
    await waitFor(() => expect(getAgentReview).toHaveBeenCalledWith('pr-next'));
    started.resolve(data());
    await act(async () => { await started.promise; });

    expect(result.current.data).toBeNull();
    expect(result.current.headerState).toBe('never');
  });

  it('never exposes one pull request review under another pull request identity', async () => {
    const live = data();
    const next = deferred<ReturnType<typeof data> | null>();
    const getAgentReview = vi.fn((prId: string) => prId === 'pr-live'
      ? Promise.resolve(live)
      : next.promise);
    window.bridge = { getAgentReview } as unknown as typeof window.bridge;
    const rendered: string[] = [];
    const { rerender } = renderHook(
      ({ source }) => {
        const review = useAgentReviewState(source, vi.fn());
        rendered.push(`${source.pr.id}:${review.data?.review.pr_id ?? 'none'}`);
        return review;
      },
      { initialProps: { source: bundle() } },
    );
    await waitFor(() => expect(rendered).toContain('pr-live:pr-live'));

    rerender({ source: bundle('pr-next') });
    expect(rendered).not.toContain('pr-next:pr-live');

    await act(async () => {
      next.resolve(null);
      await next.promise;
    });
    await waitFor(() => expect(getAgentReview).toHaveBeenCalledWith('pr-next'));
  });
});
