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
import { renderToStaticMarkup } from 'react-dom/server';
import type { LocalReviewThreadDto } from './local-review/types';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { InlineReviewCommentDto, PullRequestDetailDto, PullRequestDto, PullTimelinePayloadDto } from '../types';
import MarkdownBody, { markdownBlocks } from './MarkdownBody';
import { EMPTY_TIMELINE_PAYLOAD as emptyPayload } from './model';
import PullOverview, { activeMention, appendQuotedReply, applyViewerReaction, canUpdateComment, commentedCodeLines, CommentEditor, displayedMetadataPickerOptions, groupTimelineActivities, githubActionsJobId, isViewerComment, MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS, mentionCandidates, mergePullRequestWriteDetail, MetadataPicker, nextViewerReaction, peoplePickerCandidates, remoteErrorMessage, replaceMention, ReviewThread, reviewThreadSuggestion, submitPullReaction, submitPullRequestComment, updatePullReactionDetail, updateReviewThreadResolution } from './PullOverview';
import { relativeTime } from '../relativeTime';
import PullsScreen, { canSubmitReview, hasMergeConflicts, isReviewRequestedForViewer, MergeDialog, mergeActionLabel, mergeStatus, PullDetailHeader, remotePullRequest, ReviewDialog, unresolvedReviewCount } from './PullsScreen';
import { reviewFixture } from './agent-review/fixtures/review';


/** New lines 41 (context) through 44, as GitHub records a thread's diff_hunk. */
const COMMENTED_HUNK = '@@ -41,4 +41,18 @@ default long getMemoryUsage()\n {\n+    MemoryUsageTracker createTracker();\n+\n+    interface MemoryUsageTracker';

describe('PullOverview', () => {
  it('places private local review cards among GitHub activity without changing remote comments', () => {
    const pr = pullRequest();
    const local: LocalReviewThreadDto = {
      id: 'local-comment', taskId: 'task', taskVersion: 1, workspaceId: 'workspace', repo: pr.repo, number: pr.number,
      path: 'src/Example.java', side: 'RIGHT' as const, startLine: 10, line: 10, baseSha: null, headSha: 'a'.repeat(40),
      code: 'private Result result() {}', resolved: false, version: 1,
      createdAt: '2026-08-20T09:15:00Z', updatedAt: '2026-08-20T09:15:00Z',
      messages: [{ id: 'local-message', author: 'USER' as const, body: 'Private implementation question',
        createdAt: '2026-08-20T09:15:00Z', turnId: null, queuedInputId: null, messageEventId: null, deliveryState: 'UNSENT', deliveryError: null }],
    };
    const value = { ...detail(), recentActivity: [
      { actor: 'bob', eventType: 'commented', timestamp: '2026-08-20T09:10:00Z', body: 'Earlier public discussion', githubId: 71, payload: emptyPayload },
      { actor: 'bob', eventType: 'commented', timestamp: '2026-08-20T09:20:00Z', body: 'Later public discussion', githubId: 72, payload: emptyPayload },
    ] };
    const html = renderToStaticMarkup(<PullOverview pr={pr} detail={value} onRefresh={async () => {}}
      localReview={{ threads: [local], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} }} />);
    expect(html.indexOf('Earlier public discussion')).toBeLessThan(html.indexOf('Private implementation question'));
    expect(html.indexOf('Private implementation question')).toBeLessThan(html.indexOf('Later public discussion'));
    expect(html).toContain('is-local-review');
    expect(html).toContain('data-local-review-thread="local-comment"');
    expect(value.recentActivity).toHaveLength(2);
  });

  it.each(['PENDING', 'FAILING', 'PASSING'] as const)('offers draft promotion in the checks popup with %s checks', ciStatus => {
    const value: PullRequestDetailDto = {
      ...detail(),
      draft: true,
      ciStatus,
      checkRuns: [{
        ...detail().checkRuns[0],
        status: ciStatus === 'PENDING' ? 'in_progress' : 'completed',
        category: ciStatus === 'PENDING' ? 'pending' : ciStatus === 'FAILING' ? 'failing' : 'successful',
      }],
    };
    const overview = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    const popup = renderToStaticMarkup(<MergeDialog pr={pullRequest()} detail={value} mergeQueue
      onClose={() => {}} onRefresh={async () => {}} />);

    expect(overview).not.toContain('Ready for review');
    expect(popup).toContain('Ready for review</button>');
    expect(popup).toContain('Merge when ready</button>');
    const actions = popup.match(/<button class="is-primary"[^>]*>/g) ?? [];
    expect(actions).toHaveLength(2);
    expect(actions[0]).not.toContain('disabled');
    expect(actions[1]).toContain('disabled=""');
  });

  it.each([true, false, undefined])('keeps ready and merge actions adjacent on an open PR (queue: %s)', mergeQueue => {
    const popup = renderToStaticMarkup(<MergeDialog pr={pullRequest()}
      detail={{ ...detail(), viewerCanWrite: true, checkRuns: [], ciStatus: 'PASSING' }} mergeQueue={mergeQueue}
      onClose={() => {}} onRefresh={async () => {}} />);
    const actions = popup.match(/<button class="is-primary"[^>]*>/g) ?? [];

    expect(actions).toHaveLength(2);
    expect(popup).toContain('Ready for review</button>');
    expect(popup).toContain(`${mergeQueue === false ? 'Rebase and merge' : 'Merge when ready'}</button>`);
    expect(actions[0]).toContain('disabled=""');
    expect(actions[1].includes('disabled=""')).toBe(mergeQueue === undefined);
  });

  it.each([true, false])('does not route a disabled popup action to another endpoint (draft: %s)', draft => {
    const markPrReadyForReview = vi.fn();
    const mergePr = vi.fn();
    const submit = captureMergeSubmit({
      pr: pullRequest(), detail: { ...detail(), draft, viewerCanWrite: true, checkRuns: [], ciStatus: 'PASSING' },
      mergeQueue: true, onClose: () => {}, onRefresh: async () => {},
    }, draft ? 'merge' : 'ready');
    vi.stubGlobal('window', { bridge: { markPrReadyForReview, mergePr } });
    try {
      submit();
      expect(markPrReadyForReview).not.toHaveBeenCalled();
      expect(mergePr).not.toHaveBeenCalled();
    }
    finally {
      vi.unstubAllGlobals();
    }
  });

  it.each([true, false])('keeps both popup actions disabled without write permission (draft: %s)', draft => {
    const props: Parameters<typeof MergeDialog>[0] = {
      pr: { ...pullRequest(), origin: 'OTHER' as const },
      detail: { ...detail(), draft, viewerCanWrite: false, checkRuns: [], ciStatus: 'PASSING' as const },
      mergeQueue: true, onClose: () => {}, onRefresh: async () => {},
    };
    const popup = renderToStaticMarkup(<MergeDialog {...props} />);
    const actions: string[] = popup.match(/<button class="is-primary"[^>]*>/g) ?? [];
    const markPrReadyForReview = vi.fn();
    const mergePr = vi.fn();
    const ready = captureMergeSubmit(props, 'ready');
    const merge = captureMergeSubmit(props, 'merge');

    expect(actions).toHaveLength(2);
    expect(actions.every(action => action.includes('disabled=""'))).toBe(true);
    vi.stubGlobal('window', { bridge: { markPrReadyForReview, mergePr } });
    try {
      ready();
      merge();
      expect(markPrReadyForReview).not.toHaveBeenCalled();
      expect(mergePr).not.toHaveBeenCalled();
    }
    finally {
      vi.unstubAllGlobals();
    }
  });

  it('promotes a draft once from the checks popup and applies the acknowledged state before refreshing', async () => {
    const write = Promise.withResolvers<void>();
    const refresh = Promise.withResolvers<void>();
    const markPrReadyForReview = vi.fn(() => write.promise);
    const mergePr = vi.fn();
    const onDetailUpdate = vi.fn();
    const onClose = vi.fn();
    const onRefresh = vi.fn(() => {
      expect(onDetailUpdate).toHaveBeenCalledOnce();
      expect(onClose).toHaveBeenCalledOnce();
      return refresh.promise;
    });
    const submit = captureMergeSubmit({
      pr: pullRequest(), detail: { ...detail(), draft: true }, mergeQueue: true, onClose, onRefresh, onDetailUpdate,
    }, 'ready');
    vi.stubGlobal('window', { bridge: { markPrReadyForReview, mergePr } });
    try {
      expect(markPrReadyForReview).not.toHaveBeenCalled();
      submit();
      submit();
      expect(markPrReadyForReview).toHaveBeenCalledOnce();
      expect(markPrReadyForReview).toHaveBeenCalledWith('owner/repo', 42);
      expect(onDetailUpdate).not.toHaveBeenCalled();
      expect(onClose).not.toHaveBeenCalled();
      write.resolve();
      await vi.waitFor(() => expect(onRefresh).toHaveBeenCalledOnce());

      const [repo, number, update] = onDetailUpdate.mock.calls[0];
      expect([repo, number]).toEqual(['owner/repo', 42]);
      expect(update({ ...detail(), draft: true }).draft).toBe(false);
      expect(mergePr).not.toHaveBeenCalled();
    }
    finally {
      refresh.resolve();
      vi.unstubAllGlobals();
    }
  });

  it('keeps the checks popup and draft unchanged when GitHub rejects ready for review', async () => {
    const write = Promise.withResolvers<void>();
    const markPrReadyForReview = vi.fn(() => write.promise);
    const mergePr = vi.fn();
    const onDetailUpdate = vi.fn();
    const onClose = vi.fn();
    const onRefresh = vi.fn(async () => {});
    const submit = captureMergeSubmit({
      pr: pullRequest(), detail: { ...detail(), draft: true }, mergeQueue: false, onClose, onRefresh, onDetailUpdate,
    }, 'ready');
    vi.stubGlobal('window', { bridge: { markPrReadyForReview, mergePr } });
    try {
      submit();
      write.reject(new Error('Write access is required'));
      await write.promise.catch(() => {});
      expect(markPrReadyForReview).toHaveBeenCalledOnce();
      expect(onDetailUpdate).not.toHaveBeenCalled();
      expect(onClose).not.toHaveBeenCalled();
      expect(onRefresh).not.toHaveBeenCalled();
      expect(mergePr).not.toHaveBeenCalled();
    }
    finally {
      vi.unstubAllGlobals();
    }
  });

  it('shows the backend message without Electron IPC noise', () => {
    expect(remoteErrorMessage(new Error(
      "Error invoking remote method 'prs:metadata:update': Error: Grant it access to this repository",
    ))).toBe('Grant it access to this repository');
  });

  it('sends the known desired reaction state without requesting a refresh', async () => {
    const setPrReaction = vi.fn(async () => ({ viewerHasReacted: true }));

    await submitPullReaction({ setPrReaction }, {
      repo: 'owner/repo',
      number: 42,
      target: 'issue_comment',
      commentId: 71,
      reactions: [],
    }, 'eyes');

    expect(setPrReaction).toHaveBeenCalledWith('owner/repo', 42, 'issue_comment', 71, 'eyes', true);
  });

  it('falls back to server-side toggling when viewer reaction state is unknown', async () => {
    const setPrReaction = vi.fn(async () => ({ viewerHasReacted: false }));

    await submitPullReaction({ setPrReaction }, {
      repo: 'owner/repo',
      number: 42,
      target: 'pull_request',
      commentId: null,
      reactions: [{ content: 'heart', count: 2, viewerHasReacted: null }],
    }, 'heart');

    expect(setPrReaction).toHaveBeenCalledWith('owner/repo', 42, 'pull_request', null, 'heart', null);
  });

  it('computes optimistic reaction counts and removes an empty group', () => {
    expect(nextViewerReaction([], 'eyes')).toBe(true);
    expect(nextViewerReaction([{ content: 'eyes', count: 2, viewerHasReacted: true }], 'eyes')).toBe(false);
    expect(nextViewerReaction([{ content: 'eyes', count: 2, viewerHasReacted: null }], 'eyes')).toBeNull();
    expect(applyViewerReaction([{ content: 'eyes', count: 1, viewerHasReacted: true }], 'eyes', false)).toEqual([]);
    expect(applyViewerReaction([{ content: 'eyes', count: 2, viewerHasReacted: false }], 'eyes', true))
      .toEqual([{ content: 'eyes', count: 3, viewerHasReacted: true }]);
  });

  it('keeps optimistic description reactions in the detail and guards rollback', () => {
    const optimistic = [{ content: 'eyes' as const, count: 1, viewerHasReacted: true }];
    const withReaction = updatePullReactionDetail(detail(), 'pull_request', null, optimistic);
    const external = [{ content: 'heart' as const, count: 2, viewerHasReacted: false }];
    const externallyChanged = updatePullReactionDetail(withReaction, 'pull_request', null, external);
    const skippedRollback = updatePullReactionDetail(
      externallyChanged, 'pull_request', null, [], optimistic);

    expect(withReaction.recentActivity.find(activity => activity.eventType === 'description_reactions')
      ?.payload.reactions).toBe(optimistic);
    expect(skippedRollback.recentActivity.find(activity => activity.eventType === 'description_reactions')
      ?.payload.reactions).toBe(external);
  });

  it('merges a write response without discarding independent optimistic fields', () => {
    const current = updateReviewThreadResolution(detail(), 3, 99, false);
    current.body = 'Optimistic description';
    current.labels = [{ name: 'optimistic', color: 'abcdef' }];
    const reactions = [{ content: 'eyes' as const, count: 1, viewerHasReacted: true }];
    const optimistic = updatePullReactionDetail(current, 'pull_request', null, reactions);
    const response = detail();
    response.recentActivity.push({
      actor: 'alice', eventType: 'commented', timestamp: '2026-08-20T10:10:00Z', body: 'New', githubId: 80,
      payload: emptyPayload,
    });

    const merged = mergePullRequestWriteDetail(optimistic, response);

    expect(merged.body).toBe('Optimistic description');
    expect(merged.labels).toEqual([{ name: 'optimistic', color: 'abcdef' }]);
    expect(merged.recentActivity.find(activity => activity.githubId === 3)?.payload.resolved).toBe(false);
    expect(merged.recentActivity.find(activity => activity.eventType === 'description_reactions')
      ?.payload.reactions).toBe(reactions);
    expect(merged.recentActivity.some(activity => activity.githubId === 80)).toBe(true);
  });

  it('applies the successful comment response without requesting a full refresh', async () => {
    const updated = detail();
    const commentPr = vi.fn(async () => updated);
    const onDetailChange = vi.fn();

    await submitPullRequestComment(
      { commentPr }, 'owner/repo', 42, 'Looks good to me', onDetailChange);

    expect(commentPr).toHaveBeenCalledWith('owner/repo', 42, 'Looks good to me');
    expect(onDetailChange).toHaveBeenCalledWith(updated);
  });

  it('wires reactions only to supported pull request targets', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'bob', eventType: 'commented', timestamp: '2026-08-20T09:10:00Z', body: 'Issue comment', githubId: 71,
        payload: { ...emptyPayload, reactions: [{ content: '+1', count: 1, viewerHasReacted: true }] },
      },
      {
        actor: 'carol', eventType: 'reviewed', timestamp: '2026-08-20T09:20:00Z', body: 'Review body', githubId: 72,
        payload: { ...emptyPayload, reactions: [{ content: 'heart', count: 1 }] },
      },
      {
        actor: 'dave', eventType: 'review_thread', timestamp: '2026-08-20T09:30:00Z', body: null, githubId: 74,
        payload: {
          ...emptyPayload,
          comments: [{
            githubId: 73,
            actor: 'dave',
            actorAvatarUrl: null,
            actorBot: false,
            timestamp: '2026-08-20T09:30:00Z',
            body: 'Inline review comment',
            htmlUrl: null,
            reviewId: 9,
            inReplyToId: null,
            reactions: [{ content: 'eyes', count: 1 }],
          }],
        },
      },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html.match(/data-reaction-target="pull_request"/g)).toHaveLength(2);
    expect(html.match(/data-reaction-target="issue_comment" data-reaction-comment-id="71"/g)).toHaveLength(2);
    expect(html.match(/data-reaction-target="review_comment" data-reaction-comment-id="73"/g)).toHaveLength(2);
    expect(html).not.toContain('data-reaction-comment-id="72"');
    expect(html).toContain('<button type="button" class="pr-reaction is-active" aria-pressed="true" aria-label="Add or remove thumbs up reaction"');
    expect(html).toContain('title="Add or remove eyes reaction"');
    expect(html).toContain('<span>❤️ 1</span>');
    expect(html).toContain('role="menuitemcheckbox" class="is-active" aria-checked="true" aria-label="Add or remove thumbs up reaction"');
  });

  it('renders review submission as an overlay without replacing the pull request', () => {
    const html = renderToStaticMarkup(<ReviewDialog
      pr={pullRequest()}
      detail={detail()}
      onClose={() => {}}
      onReviewSubmitted={() => {}} />);

    expect(html).toContain('class="pr-sheet-overlay"');
    expect(html).toContain('class="pr-review-sheet" role="dialog"');
    expect(html).toContain('class="pr-better-words" type="button" disabled=""');
    expect(html).toContain('>Better words</button>');
    expect(html).toContain('</textarea><button class="pr-better-words"');
    expect(html.split('<footer class="pr-review-sheet__footer">')[1]).not.toContain('pr-better-words');
  });

  it('restores whole-PR review prose and the saved verdict in the submit sheet', () => {
    const review = reviewFixture();
    review.draft = { repo: 'owner/repo', number: 42, body: 'Clarify the cancellation commit message.',
      event: 'REQUEST_CHANGES', items: [], version: 7, updatedAt: review.createdAt };
    const html = renderToStaticMarkup(<ReviewDialog pr={{ ...pullRequest(), origin: 'OTHER' }} detail={detail()}
      review={review} onClose={() => {}} onReviewSubmitted={() => {}} />);

    expect(html).toContain('>Clarify the cancellation commit message.</textarea>');
    expect(html).toContain('<input type="radio" name="review-event" checked=""/><span><strong>Request Changes</strong>');
    expect(html).toContain('class="pr-better-words" type="button" aria-busy="false"');
  });

  it('submits body-only findings through the durable draft and reloads its cleared prose', async () => {
    const review = reviewFixture();
    review.draft = { repo: 'owner/repo', number: 42, body: 'Clarify the cancellation commit message.',
      event: 'COMMENT', items: [], version: 7, updatedAt: review.createdAt };
    const cleared = { ...review, draft: { ...review.draft, body: '', version: 9 } };
    const saveReviewDraft = vi.fn(async () => ({ ...review.draft, version: 8 }));
    const submitReviewDraft = vi.fn(async () => {});
    const submitReview = vi.fn();
    const onReviewChange = vi.fn();
    const onClose = vi.fn();
    vi.stubGlobal('window', { bridge: { saveReviewDraft, submitReviewDraft, submitReview,
      getAgentReview: vi.fn(async () => cleared), fetchPullRequestDetailByRef: vi.fn(async () => detail()) } });
    try {
      const controls = captureReviewControls({ pr: pullRequest(), detail: detail(), review,
        onReviewChange, onClose, onReviewSubmitted: () => {} });
      controls.submit();
      await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
      expect(saveReviewDraft).toHaveBeenCalledWith('owner/repo', 42, 7, review.draft.body, 'COMMENT');
      expect(submitReviewDraft).toHaveBeenCalledWith('owner/repo', 42, 8, review.draft.body, 'COMMENT');
      expect(submitReview).not.toHaveBeenCalled();
      expect(onReviewChange).toHaveBeenCalledWith(cleared);
    }
    finally { vi.unstubAllGlobals(); }
  });

  it.each([false, true])('saves changed draft verdict on dismissal and keeps a stale sheet open (conflict: %s)', async conflict => {
    const review = reviewFixture();
    review.draft = { repo: 'owner/repo', number: 42, body: 'Keep these review words.',
      event: 'APPROVE', items: [], version: 7, updatedAt: review.createdAt };
    const saved = { ...review, draft: { ...review.draft, event: 'COMMENT' as const, version: 8 } };
    const saveReviewDraft = vi.fn(async () => {
      if (conflict) throw new Error('Review draft changed');
      return saved.draft;
    });
    const getAgentReview = vi.fn(async () => saved);
    const onClose = vi.fn();
    vi.stubGlobal('window', { bridge: { saveReviewDraft, getAgentReview } });
    try {
      const controls = captureReviewControls({ pr: pullRequest(), detail: detail(), review,
        onClose, onReviewSubmitted: () => {} });
      controls.close();
      await vi.waitFor(() => expect(getAgentReview).toHaveBeenCalledOnce());
      expect(saveReviewDraft).toHaveBeenCalledWith('owner/repo', 42, 7, review.draft.body, 'COMMENT');
      expect(onClose).toHaveBeenCalledTimes(conflict ? 0 : 1);
    }
    finally { vi.unstubAllGlobals(); }
  });

  it.each(['A review summary.', ''])('preserves selected-commit comments with review prose %j', async body => {
    const review = reviewFixture();
    review.draft = { repo: 'owner/repo', number: 42, body,
      event: 'COMMENT', items: [], version: 7, updatedAt: review.createdAt };
    const selected: InlineReviewCommentDto = { path: 'Example.java', side: 'RIGHT', line: 10,
      startLine: null, startSide: null, commitId: 'old-head', body: 'An earlier commit comment.' };
    const saveReviewDraft = vi.fn(async () => ({ ...review.draft, version: 8 }));
    const submitReview = vi.fn(async () => detail());
    const addReviewDraftComment = vi.fn(async () => ({ ...review.draft, version: 9 }));
    const submitReviewDraft = vi.fn(async () => {});
    const onPendingCommentsChange = vi.fn();
    const onClose = vi.fn();
    vi.stubGlobal('window', { bridge: { saveReviewDraft, addReviewDraftComment, submitReview, submitReviewDraft,
      getAgentReview: vi.fn(async () => review), fetchPullRequestDetailByRef: vi.fn(async () => detail()) } });
    try {
      captureReviewControls({ pr: pullRequest(), detail: detail(), review, pendingComments: [selected],
        onPendingCommentsChange, onClose, onReviewSubmitted: () => {} }).submit();
      await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
      expect(submitReview).not.toHaveBeenCalled();
      expect(addReviewDraftComment).toHaveBeenCalledWith('owner/repo', 42, 8, {
        path: selected.path, side: selected.side, line: selected.line, startLine: selected.startLine, body: selected.body, commitId: 'old-head',
      });
      expect(onPendingCommentsChange).toHaveBeenCalledWith([]);
      expect(submitReviewDraft).toHaveBeenCalledWith('owner/repo', 42, 9, body, 'COMMENT');
    }
    finally { vi.unstubAllGlobals(); }
  });

  it('limits an authored pull request review to a comment', () => {
    const html = renderToStaticMarkup(<ReviewDialog pr={pullRequest()} detail={detail()}
      onClose={() => {}} onReviewSubmitted={() => {}} />);

    expect(html).toContain('<strong>Comment</strong>');
    expect(html).not.toContain('<strong>Approve</strong>');
    expect(html).not.toContain('<strong>Request Changes</strong>');
    expect(html).toContain('<button class="is-primary" type="button" disabled="">Submit review</button>');
  });

  it('stops showing the progress ring after every merge check passes', () => {
    const value = detail();
    value.checkRuns = value.checkRuns.map(check => ({
      ...check,
      conclusion: 'success',
      category: 'successful',
    }));
    const html = renderToStaticMarkup(<MergeDialog
      pr={pullRequest()}
      detail={value}
      mergeQueue
      onClose={() => {}}
      onRefresh={async () => {}} />);

    expect(html).toContain('All checks have passed');
    expect(html).toContain('class="pr-merge-success-icon"');
    expect(html).not.toContain('class="pr-merge-ready-icon"');
  });

  it.each([true, false])('closes an accepted merge before the detail refresh finishes (queue: %s)', async mergeQueue => {
    const merge = Promise.withResolvers<void>();
    const refresh = Promise.withResolvers<void>();
    const mergePr = vi.fn(() => merge.promise);
    const onClose = vi.fn();
    const onRefresh = vi.fn(() => {
      expect(onClose).toHaveBeenCalledOnce();
      return refresh.promise;
    });
    const value: PullRequestDetailDto = { ...detail(), viewerCanWrite: true, checkRuns: [], ciStatus: 'PASSING' };
    const submit = captureMergeSubmit({ pr: pullRequest(), detail: value, mergeQueue, onClose, onRefresh });
    vi.stubGlobal('window', { bridge: { mergePr } });
    try {
      submit();
      expect(mergePr).toHaveBeenCalledWith(1, 'owner/repo', 42, mergeQueue ? 'queue' : 'rebase');
      expect(onClose).not.toHaveBeenCalled();
      expect(onRefresh).not.toHaveBeenCalled();

      merge.resolve();
      await merge.promise;

      await vi.waitFor(() => {
        expect(onClose).toHaveBeenCalledOnce();
        expect(onRefresh).toHaveBeenCalledOnce();
      });
    }
    finally {
      refresh.resolve();
      vi.unstubAllGlobals();
    }
  });

  it('keeps a rejected merge open without starting a refresh', async () => {
    const merge = Promise.withResolvers<void>();
    const mergePr = vi.fn(() => merge.promise);
    const onClose = vi.fn();
    const onRefresh = vi.fn(async () => {});
    const submit = captureMergeSubmit({
      pr: pullRequest(), detail: { ...detail(), viewerCanWrite: true, checkRuns: [], ciStatus: 'PASSING' }, mergeQueue: true, onClose, onRefresh,
    });
    vi.stubGlobal('window', { bridge: { mergePr } });
    try {
      submit();
      merge.reject(new Error('Pull request cannot be queued'));
      await merge.promise.catch(() => {});

      expect(mergePr).toHaveBeenCalledOnce();
      expect(onClose).not.toHaveBeenCalled();
      expect(onRefresh).not.toHaveBeenCalled();
    }
    finally {
      vi.unstubAllGlobals();
    }
  });

  it('blocks merging without a progress ring after checks fail', () => {
    const value = { ...detail(), viewerCanWrite: true };
    const html = renderToStaticMarkup(<MergeDialog
      pr={pullRequest()}
      detail={value}
      mergeQueue={false}
      onClose={() => {}}
      onRefresh={async () => {}} />);

    expect(mergeStatus(value).state).toBe('failed');
    expect(html).toContain('Merge blocked');
    expect(html).toContain('Some checks were not successful');
    expect(html).not.toContain('class="pr-merge-ready-icon"');
    expect(html).toContain('<button class="is-primary" type="button" disabled="">');
  });

  it('renders the open header, chronological variants, resolved thread and grouped checks', () => {
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={detail()} onRefresh={async () => {}} />);

    expect(html).toContain('<div class="pr-overview__title"><h1>Example pull request</h1><div class="pr-overview__number"><span>#42</span>');
    expect(html).toContain(' Open</span>');
    expect(html).toContain('main');
    expect(html).toContain('feature/overview');
    expect(html).toContain('class="pr-copy-button" aria-label="Copy head branch" title="Copy head branch"');
    expect(html).toContain('aria-label="Open on GitHub" title="Open on GitHub"');
    expect(html).toContain('src="https://github.com/github.png?size=36"');
    expect(html.indexOf('feature/overview')).toBeLessThan(html.indexOf('aria-label="Open on GitHub"'));
    expect(html.indexOf('added <span')).toBeLessThan(html.indexOf('changed the title from'));
    expect(html).toContain('src/Example.java:L12');
    expect(html).toContain('Resolved');
    expect(html).toContain('Failing (1)');
    expect(html).toContain('Successful (1)');
    expect(html).toContain('class="pr-reaction-picker"');
    expect(html).toContain('class="pr-reaction-picker is-compact"');
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain('aria-label="Add or remove thumbs up reaction"');
    expect(html).toContain('aria-label="More actions"');
    expect(html).toContain('>Close pull request</button>');
    expect(html).toContain('>Better words</button>');
    expect(html).not.toContain('pr-composer__tools');
    expect(html).toContain('>Copy link</button>');
    expect(html).toContain('>Copy Markdown</button>');
    expect(html).toContain('>Quote reply</button>');
    expect(html).toContain('lucide-git-commit-horizontal');
  });

  it('makes authored descriptions editable and their Markdown tasks interactive', () => {
    const value = detail();
    value.body = '- [ ] Update release notes';

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('>Edit description</button>');
    expect(html).toContain('data-markdown-task=""');
    expect(html).not.toContain('data-markdown-task="" disabled=""');
  });

  it('keeps another author\'s description tasks read-only without write access', () => {
    const value = detail();
    value.body = '- [ ] Update release notes';

    const html = renderToStaticMarkup(<PullOverview
      pr={{ ...pullRequest(), origin: 'OTHER' }} detail={value} onRefresh={async () => {}} />);

    expect(html).not.toContain('>Edit description</button>');
    expect(html).toContain('disabled="" readOnly=""');
    expect(html).not.toContain('data-markdown-task=""');
  });

  it('shows commits and force pushes in the timeline', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'Yuri Ebbing', eventType: 'committed', timestamp: '2026-08-20T10:00:00Z', body: null, githubId: null,
        payload: { ...emptyPayload, commitSha: '4f81d3193402', commitMessage: 'Retry BigQuery Storage Write API' },
      },
      {
        actor: 'ezra-b', eventType: 'head_ref_force_pushed', timestamp: '2026-08-20T10:02:00Z', body: null, githubId: 21,
        payload: emptyPayload,
      },
      {
        actor: 'ezra-b', eventType: 'head_ref_force_pushed', timestamp: '2026-08-20T10:03:00Z', body: null, githubId: 22,
        payload: emptyPayload,
      },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(groupTimelineActivities(value.recentActivity).map(group => group[0].eventType))
      .toEqual(['committed', 'head_ref_force_pushed']);
    expect(groupTimelineActivities(value.recentActivity)[1]).toHaveLength(2);
    expect(html).toContain('Retry BigQuery Storage Write API');
    expect(html).toContain('<code>4f81d31</code>');
    expect(html).toContain('force-pushed the head branch 2 times');
    expect(html.match(/force-pushed the head branch/g)).toHaveLength(1);
    expect(html.indexOf('Retry BigQuery Storage Write API')).toBeLessThan(html.indexOf('force-pushed the head branch'));
  });

  it('groups review requests and names only the first three requested reviewers', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-03T03:03:15Z'));
    const value = detail();
    value.recentActivity = [
      {
        actor: 'ravi-moreau', eventType: 'review_requested', timestamp: '2026-09-02T12:03:15Z', body: null, githubId: 1,
        payload: { ...emptyPayload, requestedReviewer: 'wren-d' },
      },
      {
        actor: 'ravi-moreau', eventType: 'review_requested', timestamp: '2026-09-02T12:03:18Z', body: null, githubId: 2,
        payload: { ...emptyPayload, requestedReviewer: 'dale' },
      },
      {
        actor: 'ravi-moreau', eventType: 'review_requested', timestamp: '2026-09-02T12:03:31Z', body: null, githubId: 3,
        payload: { ...emptyPayload, requestedReviewer: 'ezra-b' },
      },
      {
        actor: 'ravi-moreau', eventType: 'review_requested', timestamp: '2026-09-02T12:05:09Z', body: null, githubId: 4,
        payload: { ...emptyPayload, requestedReviewer: 'mwade77' },
      },
    ];

    const groups = groupTimelineActivities(value.recentActivity);
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    now.mockRestore();

    expect(groups).toHaveLength(1);
    expect(groups[0]).toHaveLength(4);
    expect(html.match(/requested review from/g)).toHaveLength(1);
    expect(html.replace(/<[^>]+>/g, '')).toContain('requested review from dale, ezra-b, mwade77 and 1 other person');
    expect(html).not.toContain('wren-d');
    expect(html).toContain('class="pr-review-group__marker"');
    expect(html).toContain('<time dateTime="2026-09-02T12:03:15Z">15 hours ago</time>');
    expect(html).not.toContain('· 15 hours ago');

    expect(groupTimelineActivities([...value.recentActivity, {
      ...value.recentActivity[3],
      githubId: 5,
      timestamp: '2026-09-02T13:16:10Z',
      payload: { ...emptyPayload, requestedReviewer: 'Prentice2112' },
    }])).toHaveLength(2);
  });

  it('distinguishes queued and running checks and shows check times', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-08-20T10:00:00Z'));
    const value = detail();
    value.ciStatus = 'PENDING';
    value.checkRuns = [
      { ...value.checkRuns[0], status: 'in_progress', conclusion: null, startedAt: '2026-08-20T09:55:00Z', completedAt: null, category: 'pending' },
      { ...value.checkRuns[0], githubId: 12, name: 'queued', status: 'queued', conclusion: null, startedAt: '2026-08-20T09:57:00Z', completedAt: null, category: 'pending' },
      { ...value.checkRuns[0], githubId: 13, name: 'pending', status: null, conclusion: null, completedAt: null, category: 'pending' },
      { ...value.checkRuns[1], startedAt: '2026-08-20T09:50:00Z', completedAt: '2026-08-20T09:58:00Z' },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    now.mockRestore();

    expect(html.match(/lucide-loader-circle/g)).toHaveLength(2);
    expect(html).toContain('lucide-clock-3 pr-check__icon is-pending');
    expect(html).toContain('aria-label="Running"');
    expect(html).toContain('aria-label="Queued"');
    expect(html).toContain('aria-label="Pending"');
    expect(html).not.toContain('<header><span class="is-failure">');
    expect(html).toContain('<time dateTime="2026-08-20T09:58:00Z">Finished 2m ago</time>');
    expect(html).toContain('<time dateTime="2026-08-20T09:55:00Z">Started 5m ago</time>');
    expect(html).toContain('<time dateTime="2026-08-20T09:57:00Z">Queued 3m ago</time>');
    expect(html).not.toContain('10m ago');
  });

  it('uses distinct Lucide icons for completed check states', () => {
    const value = detail();
    const check = value.checkRuns[0];
    value.checkRuns = [
      { ...check, githubId: 20, name: 'success', conclusion: 'success', category: 'successful' },
      { ...check, githubId: 21, name: 'failure', conclusion: 'failure', category: 'failing' },
      { ...check, githubId: 22, name: 'neutral', conclusion: 'neutral', category: 'neutral' },
      { ...check, githubId: 23, name: 'skipped', conclusion: 'skipped', category: 'skipped' },
      { ...check, githubId: 24, name: 'cancelled', conclusion: 'cancelled', category: 'cancelled' },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('lucide-circle-check pr-check__icon is-successful');
    expect(html).toContain('lucide-circle-x pr-check__icon is-failing');
    expect(html).toContain('lucide-circle-dot pr-check__icon is-neutral');
    expect(html).toContain('lucide-circle-minus pr-check__icon is-skipped');
    expect(html).toContain('lucide-circle-slash-2 pr-check__icon is-cancelled');
    expect(html).toContain('aria-label="Successful"');
    expect(html).toContain('aria-label="Failed"');
    expect(html).toContain('aria-label="Neutral"');
    expect(html).toContain('aria-label="Skipped"');
    expect(html).toContain('aria-label="Cancelled"');
  });

  it('offers browser and inline log actions only on failed checks, using the Actions job ID', () => {
    const value = detail();
    const check = { ...value.checkRuns[0], githubId: 999, detailsUrl: 'https://github.com/owner/repo/actions/runs/101/job/301' };
    value.checkRuns = [check, { ...check, githubId: 2, category: 'successful' }, { ...check, githubId: 3, source: 'status', detailsUrl: 'https://ci.example/job/3' }];
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    expect(html.match(/aria-label="Open in GitHub"/g)).toHaveLength(2);
    expect(html.match(/aria-label="Show log"/g)).toHaveLength(2);
    expect(html).toContain('Inline logs are available for GitHub Actions jobs');
    expect(html).not.toContain('pr-check__log');
    expect(githubActionsJobId(check)).toBe(301);
    for (const url of [null, 'invalid', 'https://ci.example/actions/runs/101/job/301', 'https://github.com/owner/repo/actions/runs/101',
      'https://github.com/owner/repo/actions/runs/101/job/0', 'https://github.com/owner/repo/actions/runs/101/job/99999999999999999']) {
      expect(githubActionsJobId({ ...check, detailsUrl: url })).toBeNull();
    }
    expect(githubActionsJobId({ ...check, source: 'status' })).toBeNull();
  });

  it('offers failed GitHub Actions reruns to viewers with write access', () => {
    const value = detail();
    value.viewerCanWrite = true;
    value.checkRuns[0].detailsUrl = 'https://github.com/owner/repo/actions/runs/101/job/301';

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('Rerun failed tests');
  });

  it('groups adjacent label events from the same actor into one GitHub timeline row', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'github-actions[bot]', eventType: 'labeled', timestamp: '2026-08-20T09:10:00Z', body: null, githubId: 1,
        payload: { ...emptyPayload, actorBot: true, label: 'snowflake', labelColor: 'd4c5f9' },
      },
      {
        actor: 'github-actions[bot]', eventType: 'labeled', timestamp: '2026-08-20T09:11:00Z', body: null, githubId: 2,
        payload: { ...emptyPayload, actorBot: true, label: 'clickhouse', labelColor: 'd4c5f9' },
      },
      {
        actor: 'alice', eventType: 'renamed', timestamp: '2026-08-20T09:12:00Z', body: null, githubId: 3,
        payload: { ...emptyPayload, renameFrom: 'Old title', renameTo: 'Example pull request' },
      },
    ];

    const groups = groupTimelineActivities(value.recentActivity);
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(groups).toHaveLength(2);
    expect(groups[0].map(activity => activity.payload.label)).toEqual(['snowflake', 'clickhouse']);
    expect(html.match(/class="pr-event pr-label-event"/g)).toHaveLength(1);
    expect(html).toContain('class="pr-label-event__marker"');
    expect(html).toContain('snowflake');
    expect(html).toContain('clickhouse');
    expect(html).toContain('<span>labels</span>');
  });

  it('renders an unresolved review thread open and foldable', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
      payload: {
        ...emptyPayload,
        path: 'src/Example.java',
        line: 18,
        htmlUrl: 'https://github.com/owner/repo/pull/42#discussion_r99',
        comments: [{
          githubId: 99,
          actor: 'bob',
          actorAvatarUrl: null,
          actorBot: false,
          timestamp: '2026-08-20T09:35:00Z',
          body: 'Please update this.',
          htmlUrl: null,
          reviewId: 9,
          inReplyToId: null,
          reactions: [],
        }],
      },
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('<details class="pr-inline-thread" open=""><summary><span class="pr-thread-chevron" aria-hidden="true">›</span>');
    expect(html).toContain('class="pr-thread-reply__open"');
    expect(html).toContain('class="pr-thread-comment__menu"');
    expect(html).toContain('>Reply</button><button class="pr-thread-resolve"');
    expect(html).toContain('>Resolve</button>');
    expect(html).not.toContain('<strong>Reply</strong>');
  });

  it('updates only the matching review thread and supports a guarded rollback', () => {
    const value = detail();
    const optimistic = updateReviewThreadResolution(value, 3, 99, false);
    const rolledBack = updateReviewThreadResolution(optimistic, 3, 99, true, false);
    const externallyChanged = updateReviewThreadResolution(optimistic, 3, 99, true);
    const skippedRollback = updateReviewThreadResolution(externallyChanged, 3, 99, true, false);

    expect(optimistic.recentActivity.find(activity => activity.githubId === 3)?.payload.resolved).toBe(false);
    expect(rolledBack.recentActivity.find(activity => activity.githubId === 3)?.payload.resolved).toBe(true);
    expect(skippedRollback.recentActivity.find(activity => activity.githubId === 3)?.payload.resolved).toBe(true);
  });

  it('shows the commented diff lines with their numbers and state', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
      payload: {
        ...emptyPayload,
        path: 'src/PageSourceProvider.java',
        diffHunk: COMMENTED_HUNK,
        originalLine: 44,
        side: 'RIGHT',
        outdated: true,
      },
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    const patch = '@@ -1,2 +1,2 @@\n one\n-two\n+2\n@@ -40,2 +40,3 @@\n forty\n+added\n-gone';
    expect(commentedCodeLines(patch, 'RIGHT', null, 41)).toEqual([{ lineNumber: 41, prefix: '+', text: 'added' }]);
    expect(commentedCodeLines(patch, 'LEFT', 40, 41)).toEqual([
      { lineNumber: 40, prefix: ' ', text: 'forty' },
      { lineNumber: 41, prefix: '-', text: 'gone' },
    ]);
    expect(commentedCodeLines(patch, 'RIGHT', null, 3)).toEqual([]);
    expect(html).toContain('<code>src/PageSourceProvider.java</code><span class="pr-thread-outdated">Outdated</span>');
    expect(html).toContain('class="pr-commented-code-line is-add"');
    expect(html).toContain('pr-commented-code-line__number">44</span>');
    expect(html).toContain('<code>    interface MemoryUsageTracker</code>');
    expect(html).not.toContain('createTracker');
  });

  it('shows every line of a multi-line review comment', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
      payload: {
        ...emptyPayload,
        path: 'src/PageSourceProvider.java',
        diffHunk: COMMENTED_HUNK,
        line: 44,
        originalLine: 44,
        startLine: 42,
        originalStartLine: 42,
        side: 'RIGHT',
      },
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('pr-commented-code-line__number">42</span>');
    expect(html).toContain('<code>    MemoryUsageTracker createTracker();</code>');
    expect(html).toContain('pr-commented-code-line__number">44</span>');
    expect(html).toContain('<code>    interface MemoryUsageTracker</code>');
  });

  it('offers reply and unresolve actions inside a resolved review thread', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
      payload: {
        ...emptyPayload,
        resolved: true,
        path: 'src/Example.java',
        line: 18,
        comments: [{
          githubId: 99,
          actor: 'bob',
          actorAvatarUrl: null,
          actorBot: false,
          timestamp: '2026-08-20T09:35:00Z',
          body: 'Please update this.',
          htmlUrl: null,
          reviewId: 9,
          inReplyToId: null,
          reactions: [],
        }],
      },
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('<details class="pr-thread-row"><summary><span class="pr-thread-chevron" aria-hidden="true">›</span>');
    expect(html).not.toContain('<details class="pr-thread-row" open="">');
    expect(html).toContain('class="pr-thread-row__comments"');
    expect(html).toContain('class="pr-thread-reply__open"');
    expect(html).toContain('>Unresolve</button>');
  });

  it.each([false, true])('shows only the original line range in a diff thread when resolved is %s', resolved => {
    const html = renderToStaticMarkup(<ReviewThread pr={pullRequest()} showCode={false}
      onRefresh={async () => {}} onDetailUpdate={() => {}} activity={{
        actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
        payload: {
          ...emptyPayload, path: 'src/Example.java', originalLine: 44, originalStartLine: 42,
          startLine: 39, side: 'RIGHT', outdated: true, resolved,
        },
      }} />);

    expect(html).toContain('<span>Comment on lines <strong>R42–R44</strong></span>');
    expect(html).not.toContain('src/Example.java');
  });

  it('reads a new-side thread suggestion base from its original hunk lines', () => {
    const diffHunk = '@@ -10,3 +10,4 @@ class Example\n a\n-b\n+c\n+d';

    expect(reviewThreadSuggestion({ ...emptyPayload, side: 'RIGHT', diffHunk, line: 12, originalLine: 12 }))
      .toEqual({ startLine: 12, lines: ['d'] });
    expect(reviewThreadSuggestion({ ...emptyPayload, side: 'right', diffHunk, line: 20, startLine: 19, originalLine: 12, originalStartLine: 11 }))
      .toEqual({ startLine: 19, lines: ['c', 'd'] });
    expect(reviewThreadSuggestion({ ...emptyPayload, side: 'RIGHT', diffHunk, line: null, startLine: 30, originalLine: 12, originalStartLine: 10 }))
      .toEqual({ startLine: 10, lines: ['a', 'c', 'd'] });
    expect(reviewThreadSuggestion({ ...emptyPayload, side: 'RIGHT', diffHunk, line: 11, originalLine: 11, originalStartLine: 8 }))
      .toEqual({ startLine: 11, lines: null });
    expect(reviewThreadSuggestion({ ...emptyPayload, side: 'LEFT', diffHunk, line: 11, originalLine: 11 })).toBeUndefined();
    expect(reviewThreadSuggestion({ ...emptyPayload, side: 'RIGHT', line: 11, originalLine: 11 })).toBeUndefined();
  });

  it('renders a review-thread suggestion as a suggested change', () => {
    const html = renderToStaticMarkup(<ReviewThread pr={pullRequest()} showCode={false}
      onRefresh={async () => {}} onDetailUpdate={() => {}} activity={{
        actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
        payload: {
          ...emptyPayload, path: 'src/Example.java', side: 'RIGHT', line: 12, originalLine: 12,
          diffHunk: '@@ -10,3 +10,4 @@\n a\n-b\n+c\n+        .collect(toImmutableSet())',
          comments: [{
            githubId: 99, actor: 'bob', actorAvatarUrl: null, actorBot: false, timestamp: '2026-08-20T09:35:00Z',
            body: '```suggestion\n        .distinct()\n```', htmlUrl: null, reviewId: 9, inReplyToId: null, reactions: [],
          }],
        },
      }} />);

    expect(html).toContain('<div class="md-suggestion__header">Suggested change</div>');
    expect(html).toContain('<span>−</span>        .<mark>collect(toImmutableSet()</mark>)</td>');
    expect(html).toContain('<span>+</span>        .<mark>distinct(</mark>)</td>');
  });

  it('matches a comment author to the signed-in viewer case-insensitively', () => {
    expect(isViewerComment('Bob', 'bob')).toBe(true);
    expect(isViewerComment('bob', 'carol')).toBe(false);
    expect(isViewerComment(null, 'bob')).toBe(false);
    expect(isViewerComment('bob', undefined)).toBe(false);
    expect(isViewerComment('', '')).toBe(false);
  });

  it('offers Edit only on the viewer\'s own review comments', () => {
    const comment = (githubId: number, actor: string): PullTimelinePayloadDto['comments'][number] => ({
      githubId, actor, actorAvatarUrl: null, actorBot: false, timestamp: '2026-08-20T09:35:00Z',
      body: `Comment by ${actor}`, htmlUrl: null, reviewId: 9, inReplyToId: githubId === 99 ? null : 99, reactions: [],
    });
    const html = renderToStaticMarkup(<ReviewThread pr={pullRequest()} showCode={false} viewerLogin="Bob"
      onRefresh={async () => {}} onDetailUpdate={() => {}} activity={{
        actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
        payload: { ...emptyPayload, path: 'src/Example.java', side: 'RIGHT', line: 12, comments: [comment(99, 'bob'), comment(100, 'carol')] },
      }} />);
    const [own, other] = html.split('class="pr-thread-comment"').slice(1);

    expect(own).toContain('>Edit</button>');
    expect(other).toContain('Comment by carol');
    expect(other).not.toContain('>Edit</button>');
  });

  it('keeps the description edit label and offers no comment edit before the viewer is known', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'alice', eventType: 'commented', timestamp: '2026-08-20T09:40:00Z', body: 'Looks good', githubId: 71,
      payload: emptyPayload,
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('>Edit description</button>');
    expect(html).not.toContain('>Edit</button>');
  });

  it('enables a comment update only for a non-blank change beyond line endings', () => {
    expect(canUpdateComment('First\nSecond', 'First\r\nSecond')).toBe(false);
    expect(canUpdateComment('First\nThird', 'First\r\nSecond')).toBe(true);
    expect(canUpdateComment('  \n', 'First')).toBe(false);

    const html = renderToStaticMarkup(<CommentEditor pr={pullRequest()} target="review_comment" commentId={99}
      body="First draft" draft="First draft" onDraftChange={() => {}} onDetailUpdate={() => {}} onClose={() => {}} />);

    expect(html).toContain('aria-label="Edit comment" rows="4">First draft</textarea>');
    expect(html).toContain('<button class="is-primary" type="button" disabled="">Update comment</button>');
  });

  it('parses the markdown blocks used by GitHub descriptions', () => {
    expect(markdownBlocks('# Heading\n\nText with `code`.\n\n- one\n- two\n\n```java\nrun();\n```'))
      .toEqual([
        { kind: 'heading', level: 1, text: 'Heading' },
        { kind: 'paragraph', text: 'Text with `code`.' },
        { kind: 'list', ordered: false, items: ['one', 'two'] },
        { kind: 'code', language: 'java', text: 'run();' },
      ]);
  });

  it('renders GitHub Markdown tables instead of exposing their pipe syntax', () => {
    const body = '| Area | Existing bundled job | New longest job | Improvement |\n'
      + '|---|---:|---:|---:|\n'
      + '| Overall critical path (Iceberg) | 38:05 | 24:35 | 13:30 (35%) |\n'
      + '| Authentication and clients | 32:18 | 12:57 | 19:21 (60%) |';

    expect(markdownBlocks(body)[0]).toEqual({
      kind: 'table',
      headers: ['Area', 'Existing bundled job', 'New longest job', 'Improvement'],
      aligns: ['left', 'right', 'right', 'right'],
      rows: [
        ['Overall critical path (Iceberg)', '38:05', '24:35', '13:30 (35%)'],
        ['Authentication and clients', '32:18', '12:57', '19:21 (60%)'],
      ],
    });

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);
    expect(html).toContain('<div class="md-table-scroll"><table><thead>');
    expect(html).toContain('<th style="text-align:right">Improvement</th>');
    expect(html).toContain('<td style="text-align:right">13:30 (35%)</td>');
    expect(html).not.toContain('|---|');
  });

  it('renders GitHub details and anchor HTML without exposing raw tags', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'> [!NOTE]\n> Review coverage is partial.\n\n<details><summary>Show files</summary>hidden table</details>\n<a href="/owner/repo">Learn more</a>'} />);

    expect(html).toContain('<aside class="md-alert is-note"><strong>');
    expect(html).toContain('Note</strong><p>Review coverage is partial.</p></aside>');
    expect(html).toContain('<details class="md-details"><summary>Show files</summary>');
    expect(html).toContain('href="https://github.com/owner/repo"');
    expect(html).not.toContain('&lt;details');
    expect(html).not.toContain('&lt;a href');
  });

  it('renders headings and disclosures that a bot nests inside an alert', () => {
    const body = '> [!IMPORTANT]\n> ## Review skipped\n>\n> No reviewable changes.\n>\n>\n> <details>\n> <summary>Run configuration</summary>\n>\n> **Plan**: Enterprise\n>\n> </details>';
    const html = renderToStaticMarkup(<MarkdownBody body={body} />);

    expect(html).toContain('<aside class="md-alert is-important">');
    expect(html).toContain('<h2>Review skipped</h2><p>No reviewable changes.</p>');
    expect(html).toContain('<details class="md-details"><summary>Run configuration</summary>');
    expect(html).toContain('<strong>Plan</strong>: Enterprise');
    expect(html).not.toContain('## Review skipped');
  });

  it('renders nested details, linked image badges, and underscore emphasis', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'[![Review Change Stack](https://example.com/badge.svg)](https://example.com/review)\n\n<details><summary>Recent review info</summary><details><summary>Commits</summary>hidden commit</details>hidden availability</details>\n\n**Merge Risk:** _⚪ Minimal_'} />);

    expect(html).toContain('<a class="md-image-link" href="https://example.com/review"><img src="https://example.com/badge.svg" alt="Review Change Stack"/></a>');
    expect(html).toContain('<summary>Recent review info</summary><div class="markdown-body"><details class="md-details"><summary>Commits</summary>');
    expect(html).toContain('<strong>Merge Risk:</strong> <em>⚪ Minimal</em>');
  });

  it('renders HTML image anchors as image links and drops dark-mode-only assets', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'<a href="https://app.coderabbit.ai/stack#gh-light-mode-only"><img src="https://example.com/light.svg" alt="Review Change Stack" width="202"></a><a href="https://app.coderabbit.ai/stack#gh-dark-mode-only"><img src="https://example.com/dark.svg" alt="Review Change Stack"></a>\n\n<img src="https://example.com/shot.png" alt="Screenshot">'} />);

    expect(html).toContain('<a class="md-image-link" href="https://app.coderabbit.ai/stack#gh-light-mode-only"><img src="https://example.com/light.svg" alt="Review Change Stack"/></a>');
    expect(html).toContain('<img class="md-image" src="https://example.com/shot.png" alt="Screenshot"/>');
    expect(html).not.toContain('[](');
  });

  it('suppresses empty comment-review wrappers and keeps verdict-only reviews compact', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'fion-path', eventType: 'reviewed', timestamp: '2026-08-20T09:10:00Z', body: '', githubId: 10,
        payload: { ...emptyPayload, state: 'commented' },
      },
      {
        actor: 'ezra-b', eventType: 'reviewed', timestamp: '2026-08-20T09:20:00Z', body: '', githubId: 11,
        payload: { ...emptyPayload, state: 'approved' },
      },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).not.toContain('Reviewed these changes.');
    expect(html).not.toContain('fion-path');
    expect(html).toContain('ezra-b');
    expect(html).toContain('Approved');
    expect(html).toContain('pr-review-event');
  });

  it('attaches review threads to their matching standalone review event', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'chenjian2664', eventType: 'review_thread', timestamp: '2026-08-13T08:36:16Z', body: null, githubId: 3773729721,
        payload: {
          ...emptyPayload,
          path: 'plugin/trino-oracle/src/main/java/io/trino/plugin/oracle/RewriteCast.java',
          line: 67,
          side: 'RIGHT',
          resolved: true,
          outdated: true,
          comments: [
            {
              githubId: 3773729721,
              actor: 'chenjian2664',
              actorAvatarUrl: 'https://example.com/chenjian2664.png',
              actorBot: false,
              timestamp: '2026-08-13T08:36:16Z',
              body: 'The legacy behavior needs another condition.',
              htmlUrl: null,
              reviewId: 4925022844,
              inReplyToId: null,
              reactions: [],
            },
            {
              githubId: 3773740000,
              actor: 'finn-ep',
              actorAvatarUrl: 'https://example.com/finn-ep.png',
              actorBot: false,
              timestamp: '2026-08-13T09:00:00Z',
              body: 'Can you add a test?',
              htmlUrl: null,
              reviewId: 4925100000,
              inReplyToId: 3773729721,
              reactions: [],
            },
          ],
        },
      },
      {
        actor: 'chenjian2664', eventType: 'reviewed', timestamp: '2026-08-13T08:49:10Z', body: '', githubId: 4925022844,
        payload: { ...emptyPayload, actorAvatarUrl: 'https://example.com/chenjian2664.png', state: 'commented' },
      },
    ];

    const groups = groupTimelineActivities(value.recentActivity);
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(groups).toHaveLength(1);
    expect(groups[0].map(activity => activity.eventType)).toEqual(['reviewed', 'review_thread']);
    expect(html).toContain('class="pr-review-group"');
    expect(html).toContain('chenjian2664</strong></button><span>reviewed</span>');
    expect(html).toContain('src="https://example.com/chenjian2664.png"');
    expect(html).toContain('RewriteCast.java:R67');
    expect(html).toContain('Can you add a test?');
  });

  it('omits the Markdown fence label and highlights its syntax', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'```\nplain\n```\n\n```markdown\n## Section\n* Fix some things. ({issue}`issuenumber`)\n```'} />);

    expect(html).toContain('<span>Plain text</span>');
    expect(html).not.toContain('<span>markdown</span>');
    expect(html).toContain('<span class="hljs-section">## Section</span>');
    expect(html).toContain('<span class="hljs-code">`issuenumber`</span>');
  });

  it('renders queued state, enriched GitHub references, and description reactions', () => {
    const value = detail();
    value.body = '- [Linked issue #7](https://github.com/owner/repo/issues/7)\n- [Linked pull #8](https://github.com/owner/repo/pull/8)';
    value.recentActivity = [
      { actor: 'alice', eventType: 'added_to_merge_queue', timestamp: '2026-08-20T09:00:00Z', body: null, githubId: 4, payload: emptyPayload },
      { actor: '', eventType: 'description_reactions', timestamp: null, body: null, githubId: null, payload: { ...emptyPayload, reactions: [{ content: '+1', count: 2 }, { content: 'eyes', count: 1 }] } },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('is-queued');
    expect(html).toContain('Queued');
    expect(html).toContain('md-reference is-issue');
    expect(html).toContain('Linked issue <small>#7</small>');
    expect(html).toContain('md-reference is-pull');
    expect(html).toContain('href="https://github.com/owner/repo/pull/8"');
    expect(html).toContain('Linked pull <small>#8</small>');
    expect(html).toContain('👍 2');
    expect(html).toContain('👀 1');
    expect(html).not.toContain('description reactions');
  });

  it('turns bare GitHub URLs into local references that can resolve a title', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'follows https://github.com/owner/repo/issues/42'} />);

    expect(html).toContain('class="md-reference is-issue"');
    expect(html).toContain('href="https://github.com/owner/repo/issues/42"');
    expect(html).toContain('owner/repo <small>#42</small>');
  });

  it('links cross-referenced timeline events to the source preview', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'cross-referenced', timestamp: '2026-08-20T09:10:00Z', body: null, githubId: 21,
      payload: {
        ...emptyPayload,
        sourceTitle: 'Related issue',
        sourceUrl: 'https://github.com/owner/repo/issues/7',
        sourceNumber: 7,
      },
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('mentioned this in <a class="md-reference is-issue"');
    expect(html).toContain('href="https://github.com/owner/repo/issues/7"');
    expect(html).toContain('Related issue</a>');
  });

  it('shows reviewer identities below three people and a count from three people', () => {
    const value = detail();
    value.reviewers = [
      { login: 'bob', avatarUrl: 'https://example.com/bob.png', state: 'APPROVED' },
      { login: 'carol', avatarUrl: 'https://example.com/carol.png', state: 'PENDING' },
    ];

    const twoReviewers = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    expect(twoReviewers).toContain('alt="bob"');
    expect(twoReviewers).toContain('alt="carol"');
    expect(twoReviewers).not.toContain('2 people');

    value.reviewers.push({ login: 'dave', avatarUrl: 'https://example.com/dave.png', state: 'PENDING' });
    const threeReviewers = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    expect(threeReviewers).toContain('<small>Reviewers</small>3 people');
    expect(threeReviewers).not.toContain('alt="bob"');
  });

  it('pins selected reviewers and assignees before repository users', () => {
    const value = detail();
    value.reviewers[0] = { ...value.reviewers[0], avatarUrl: 'https://example.com/remote-bob.png' };
    value.assignees = ['carol'];
    const users = [
      { login: 'alice', avatarUrl: 'https://example.com/alice.png', bot: false },
      { login: 'bob', avatarUrl: '/api/avatars/bob', bot: true },
      { login: 'carol', avatarUrl: 'https://example.com/carol.png', bot: false },
    ];

    expect(peoplePickerCandidates(value, users, 'reviewers').map(user => `${user.login}:${user.selected}:${user.bot}`))
      .toEqual(['bob:true:true', 'alice:false:false', 'carol:false:false']);
    expect(peoplePickerCandidates(value, users, 'reviewers')[0]?.avatarUrl).toBe('/api/avatars/bob');
    expect(peoplePickerCandidates(value, users, 'assignees').map(user => `${user.login}:${user.selected}`))
      .toEqual(['carol:true', 'alice:false', 'bob:false']);
  });

  it('does not fetch a remote fallback when a cached picker avatar is missing', () => {
    const html = renderToStaticMarkup(<MetadataPicker ariaLabel="reviewers" placeholder="Search users..."
      query="" setQuery={() => {}} options={[{ key: 'alice', label: 'alice', login: 'alice', avatarUrl: null, selected: false }]}
      loading={false} updating={false} loadingText="Loading…" emptyText="No users" onToggle={() => {}} />);

    expect(html).toContain('avatar--fallback');
    expect(html).not.toContain('github.com');
  });

  it('caps unselected people while retaining every selected match', () => {
    const options = Array.from({ length: MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 5 }, (_, index) => ({
      key: `user-${index}`,
      label: `user-${index}`,
      login: `user-${index}`,
      selected: index >= MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 3,
    }));

    const displayed = displayedMetadataPickerOptions(options);

    expect(displayed.filter(option => !option.selected)).toHaveLength(MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS);
    expect(displayed.filter(option => option.selected).map(option => option.login))
      .toEqual([`user-${MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 3}`, `user-${MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 4}`]);
    expect(displayed.some(option => option.login === `user-${MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 2}`)).toBe(false);
  });

  it('does not cap label options', () => {
    const options = Array.from({ length: MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 5 }, (_, index) => ({
      key: `label-${index}`,
      label: `label-${index}`,
      selected: false,
    }));

    expect(displayedMetadataPickerOptions(options)).toHaveLength(options.length);
  });

  it('formats compact relative timestamps', () => {
    expect(relativeTime('2026-08-20T09:58:00Z', { now: Date.parse('2026-08-20T10:00:00Z') })).toBe('2m ago');
  });

  it('suggests unique pull-request participants and replaces the active mention', () => {
    const value = detail();
    value.recentActivity.push({
      actor: 'carol', eventType: 'commented', timestamp: null, body: 'hello', githubId: 12,
      payload: { ...emptyPayload, actorAvatarUrl: 'https://example.com/carol.png' },
    });
    value.recentActivity.push({
      actor: 'alice', eventType: 'commented', timestamp: null, body: 'again', githubId: 13,
      payload: emptyPayload,
    });
    value.recentActivity.push({
      actor: 'Pia Fenwick', eventType: 'commented', timestamp: null, body: 'display name', githubId: 14,
      payload: emptyPayload,
    });

    expect(mentionCandidates(value).map(candidate => candidate.login)).toEqual(['alice', 'bob', 'github-actions', 'carol']);
    const mention = activeMention('Thanks @ca', 10);
    expect(mention).toEqual({ start: 7, query: 'ca' });
    if (!mention) throw new Error('Expected an active mention');
    expect(replaceMention('Thanks @ca!', mention, 'carol')).toEqual({ value: 'Thanks @carol !', caret: 14 });
    expect(activeMention('mail@example.com', 16)).toBeNull();
  });

  it('appends a GitHub-style quote without replacing a draft', () => {
    expect(appendQuotedReply('', 'First line\n\nLast line')).toBe('> First line\n> \n> Last line\n\n');
    expect(appendQuotedReply('Already writing  \n', 'Quoted text')).toBe('Already writing\n\n> Quoted text\n\n');
  });

  it('selects the merge action and counts unresolved review threads', () => {
    const value = detail();
    value.recentActivity.push(
      { actor: 'bob', eventType: 'review_thread', timestamp: null, body: null, githubId: 20, payload: emptyPayload },
      { actor: 'carol', eventType: 'review_thread', timestamp: null, body: null, githubId: 21, payload: { ...emptyPayload, resolved: true } },
    );

    expect(mergeActionLabel(true)).toBe('Merge when ready');
    expect(mergeActionLabel(false)).toBe('Rebase and merge');
    expect(mergeActionLabel(undefined)).toBe('Loading merge status');
    expect(mergeActionLabel(null)).toBe('Merge status unavailable');
    expect(mergeActionLabel(true, true)).toBe('Conflicts');
    expect(unresolvedReviewCount(value)).toBe(1);
  });

  it('offers inline review actions only for another author\'s open pull request', () => {
    const pr = pullRequest();
    const value = detail();

    expect(canSubmitReview(pr, value)).toBe(false);
    expect(canSubmitReview({ ...pr, origin: 'REVIEW_REQUESTED' }, value)).toBe(true);
    expect(canSubmitReview({ ...pr, origin: 'OTHER', handledAction: 'COMMENTED' }, value)).toBe(true);
    expect(canSubmitReview({ ...pr, origin: 'REVIEW_REQUESTED' }, { ...value, state: 'merged' })).toBe(false);
    expect(hasMergeConflicts({ ...value, mergeable: false, mergeableState: 'dirty' })).toBe(true);
    expect(hasMergeConflicts({ ...value, mergeable: false, mergeableState: 'blocked' })).toBe(false);
    expect(remotePullRequest(value, 'bob').origin).toBe('REVIEW_REQUESTED');
    expect(remotePullRequest(value, 'carol').origin).toBe('OTHER');
  });

  it('keeps icon, review and merge actions in the compact detail header', () => {
    const value = detail();
    value.viewerCanWrite = true;
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={{ ...pullRequest(), origin: 'REVIEW_REQUESTED' }}
      detail={value}
      tab="changes"
      onTab={() => {}}
      onRefresh={async () => {}}
      onReviewSubmitted={() => {}}
      refreshing={false}
      pendingComments={[]}
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain('class="pull-detail__review-action"');
    expect(html).toContain('>Submit review</button>');
    expect(html).toContain('class="pull-detail__merge-action is-conflicts"');
    expect(html).toContain('class="pull-detail__pr-tab is-open"');
    expect(html).toContain('PR #42');
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain('aria-pressed="true"');
    expect(html).toContain('class="pull-detail__change-count is-addition">+10</span>');
    expect(html).toContain('class="pull-detail__change-count is-deletion">−2</span>');
    expect(html).toContain('aria-label="Zoom pull request details"');
    expect(html).toContain('aria-label="Refresh pull request"');
    expect(html).not.toContain('requested your review');
    expect(html).not.toContain('Open on GitHub');
  });

  it('shows when the backend refresh is running', () => {
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={pullRequest()}
      detail={detail()}
      tab="overview"
      onTab={() => {}}
      onRefresh={async () => {}}
      onReviewSubmitted={() => {}}
      refreshing
      pendingComments={[]}
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).toMatch(/<button class="pull-detail__icon-action is-refreshing"[^>]*aria-busy="true"[^>]*disabled=""/);
    expect(html).toContain('title="Refreshing pull request…"');
    expect(html).toContain('aria-label="Refresh pull request"');
  });

  it('keeps Submit review and checks available for an authored pull request', () => {
    const value: PullRequestDetailDto = {
      ...detail(), draft: true, ciStatus: 'PENDING',
      checkRuns: [{ ...detail().checkRuns[0], category: 'pending', status: 'in_progress' }],
    };
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={pullRequest()}
      detail={value}
      tab="overview"
      onTab={() => {}}
      onRefresh={async () => {}}
      onReviewSubmitted={() => {}}
      refreshing={false}
      pendingComments={[]}
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain('class="pull-detail__review-action"');
    expect(html).toContain('class="pull-detail__merge-action');
    expect(html).toContain('Checks running</button>');
    expect(html).not.toContain('Ready for review</button>');
  });

  it('shows the pending comment count in the submit review action', () => {
    const pendingComment: InlineReviewCommentDto = {
      path: 'src/Example.java',
      body: 'Please simplify this',
      line: 18,
      side: 'RIGHT',
      startLine: null,
      startSide: null,
      commitId: 'head-sha',
    };
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={{ ...pullRequest(), origin: 'REVIEW_REQUESTED' }}
      detail={detail()}
      tab="changes"
      onTab={() => {}}
      onRefresh={async () => {}}
      onReviewSubmitted={() => {}}
      refreshing={false}
      pendingComments={[pendingComment]}
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain('aria-label="Submit review, 1 pending review comment"');
    expect(html).toContain('class="pull-detail__review-count" aria-hidden="true">1</span>');
  });

  it('offers full screen and close controls inside the zoom layer', () => {
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={pullRequest()}
      detail={detail()}
      tab="overview"
      onTab={() => {}}
      onRefresh={async () => {}}
      onReviewSubmitted={() => {}}
      refreshing={false}
      pendingComments={[]}
      zoom={{ full: true, toggle: () => {}, close: () => {} }}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain('aria-label="Exit full screen" aria-pressed="true"');
    expect(html).toContain('aria-label="Close pull request details"');
    expect(html).not.toContain('aria-label="Zoom pull request details"');
  });

  it('opens a dismissible details overlay while its PR is loading', () => {
    const html = renderToStaticMarkup(<PullsScreen
      initialPr={{ repo: 'owner/repo', number: 42 }}
      onClose={() => {}} />);

    expect(html).toContain('class="pulls-page pulls-page--overlay"');
    expect(html).toContain('role="dialog" aria-modal="true" aria-label="Pull request owner/repo #42"');
    expect(html).toContain('aria-label="Close pull request details"');
    expect(html).toContain('role="status"><span class="pr-skeleton__label">Loading details…</span>');
    // The wait wears the shape of the page it is about to show, not a line of text.
    expect(html.match(/class="pr-card"/g)).toHaveLength(4);
    expect(html).not.toContain('class="pulls-list"');
    expect(html).not.toContain('Search pull requests');
  });

  it('shows a review conversation beside the PR in its workspace without a PR list or modal', () => {
    const html = renderToStaticMarkup(<PullsScreen initialPr={{ repo: 'owner/repo', number: 42 }}
      initialReviewId="review-42" repositoryScope={{ workspaceId: 'workspace-1', repository: 'owner/repo' }}
      reviewWorkspace={{ onBack: () => {} }} />);
    expect(html).toContain('class="pulls-page pulls-page--review-workspace"');
    expect(html).toContain('aria-label="Review agent conversation"');
    expect(html).toContain('aria-label="Back to tasks"');
    expect(html).toContain('Loading review conversation…');
    expect(html).not.toContain('class="pulls-list"');
    expect(html).not.toContain('aria-modal="true"');
  });

  it('offers full screen and close instead of zoom in a PR preview header', () => {
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={pullRequest()}
      detail={detail()}
      tab="overview"
      onTab={() => {}}
      onRefresh={async () => {}}
      onReviewSubmitted={() => {}}
      refreshing={false}
      pendingComments={[]}
      zoom={{ full: false, toggle: () => {}, close: () => {} }}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain('aria-label="Close pull request details"');
    expect(html).toContain('aria-label="Enter full screen" aria-pressed="false"');
    expect(html).not.toContain('aria-label="Zoom pull request details"');
    expect(html).toContain('aria-label="Refresh pull request"');
    expect(html).toContain('> Changes');
  });

  it('moves focus into review and merge dialogs when they open', () => {
    const review = renderToStaticMarkup(<ReviewDialog
      pr={pullRequest()} detail={detail()} onClose={() => {}} onReviewSubmitted={() => {}} />);
    const merge = renderToStaticMarkup(<MergeDialog
      pr={pullRequest()} detail={detail()} mergeQueue={false} onClose={() => {}} onRefresh={async () => {}} />);

    expect(review).toContain('<textarea autofocus=""');
    expect(merge).toContain('aria-label="Close merge dialog" autofocus=""');
  });

  it.each([
    ['open', false, 'open'],
    ['open', true, 'draft'],
    ['closed', false, 'closed'],
    ['merged', false, 'merged'],
  ] as const)('uses the %s pull request icon state', (state, draft, iconState) => {
    const value = { ...detail(), state, draft };
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={pullRequest()}
      detail={value}
      tab="overview"
      onTab={() => {}}
      onRefresh={async () => {}}
      onReviewSubmitted={() => {}}
      refreshing={false}
      pendingComments={[]}
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain(`pull-detail__pr-tab is-${iconState} is-active`);
  });

  it('identifies when the signed-in user is requested for review', () => {
    const value = detail();

    expect(isReviewRequestedForViewer(value, 'bob')).toBe(true);
    expect(isReviewRequestedForViewer(value, 'carol')).toBe(false);
    expect(isReviewRequestedForViewer({
      ...value,
      reviewers: [{ login: 'bob', avatarUrl: null, state: 'APPROVED' }],
    }, 'bob')).toBe(false);
  });
});

function captureReviewControls(props: Parameters<typeof ReviewDialog>[0]): { submit: () => void; close: () => void } {
  let controls: { submit: () => void; close: () => void };
  function CaptureDialog() {
    const view = ReviewDialog(props);
    const footer = view.props.children.props.children.find((child: ReactElement) => child.type === 'footer');
    controls = {
      submit: footer.props.children.find((child: ReactElement<{ className?: string }>) => child && child.props.className === 'is-primary').props.onClick,
      close: footer.props.children.find((child: ReactElement<{ children?: unknown }>) => child && child.props.children === 'Cancel').props.onClick,
    };
    return view;
  }
  renderToStaticMarkup(<CaptureDialog />);
  return controls;
}

function captureMergeSubmit(props: Parameters<typeof MergeDialog>[0], action: 'ready' | 'merge' = 'merge'): () => void {
  let submit: () => void;
  function CaptureDialog() {
    const view = MergeDialog(props);
    const footer = view.props.children.props.children.find((child: ReactElement) => child.type === 'footer');
    submit = footer.props.children[action === 'ready' ? 1 : 2].props.onClick;
    return view;
  }
  renderToStaticMarkup(<CaptureDialog />);
  return submit;
}

function pullRequest(): PullRequestDto {
  return {
    id: 1,
    repo: 'owner/repo',
    number: 42,
    title: 'Example pull request',
    author: 'alice',
    htmlUrl: 'https://github.com/owner/repo/pull/42',
    createdAt: '2026-08-20T09:00:00Z',
    updatedAt: '2026-08-20T10:00:00Z',
    origin: 'AUTHORED',
    labels: ['docs'],
    labelColors: { docs: 'd4c5f9' },
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: ['bob'],
    ciStatus: 'FAILING',
    additions: 10,
    deletions: 2,
    commentCount: 2,
    attentionReason: null,
    state: 'open',
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: null,
    headRef: 'feature/overview',
  };
}

function detail(): PullRequestDetailDto {
  return {
    id: 12,
    repo: 'owner/repo',
    number: 42,
    title: 'Example pull request',
    htmlUrl: 'https://github.com/owner/repo/pull/42',
    state: 'open',
    draft: false,
    mergeable: true,
    mergeableState: 'clean',
    author: 'alice',
    authorAvatarUrl: null,
    createdAt: '2026-08-20T09:00:00Z',
    updatedAt: '2026-08-20T10:00:00Z',
    mergedAt: null,
    baseRef: 'main',
    headRef: 'feature/overview',
    body: '# Description\n\nAdds the overview.',
    labels: [{ name: 'docs', color: 'd4c5f9' }],
    assignees: [],
    milestone: null,
    reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }],
    additions: 10,
    deletions: 2,
    changedFiles: 2,
    approvalCount: 0,
    ciStatus: 'FAILING',
    recentActivity: [
      {
        actor: 'github-actions[bot]', eventType: 'labeled', timestamp: '2026-08-20T09:10:00Z', body: null, githubId: 1,
        payload: { ...emptyPayload, actorBot: true, label: 'docs', labelColor: 'd4c5f9' },
      },
      {
        actor: 'alice', eventType: 'renamed', timestamp: '2026-08-20T09:20:00Z', body: null, githubId: 2,
        payload: { ...emptyPayload, renameFrom: 'Old title', renameTo: 'Example pull request' },
      },
      {
        actor: 'alice', eventType: 'committed', timestamp: '2026-08-20T09:25:00Z', body: null, githubId: 20,
        payload: { ...emptyPayload, commitSha: 'cd75ed1', commitMessage: 'Update pull request overview' },
      },
      {
        actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:30:00Z', body: null, githubId: 3,
        payload: { ...emptyPayload, resolved: true, path: 'src/Example.java', line: 12 },
      },
    ],
    checkRuns: [
      { githubId: 10, name: 'build', status: 'completed', conclusion: 'failure', detailsUrl: null, source: 'check_run', startedAt: null, completedAt: null, category: 'failing' },
      { githubId: 11, name: 'license', status: 'completed', conclusion: 'success', detailsUrl: null, source: 'status', startedAt: null, completedAt: null, category: 'successful' },
    ],
    unavailableSections: [],
    viewerCanWrite: false,
  };
}
