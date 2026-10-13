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
import { useCallback, useRef, useState } from 'react';
import { recordAttentionAction, type AttentionAction } from '../../home/useAttentionVisit';
import { remoteErrorMessage } from '../PullOverview';
import { findingFixPrompt } from '../local-review/conversation';
import { findingReviewText } from './model';
import type { FindingActions } from './ReviewFindingCard';
import type { AgentReviewDto, ReviewFindingDto } from './contracts';

/** A review belongs to its pull request and, for local reviews, the source development task. */
export function recordReviewAttentionAction(review: AgentReviewDto, action: AttentionAction): void {
  if (review.number !== null) recordAttentionAction(`${review.repo}#${review.number}`, action);
  if (review.sourceTaskId != null) recordAttentionAction(`task:${review.sourceTaskId}`, action);
}

/**
 * The private and outgoing actions a finding offers, defined once so the Agent review tab and the
 * Changes projection cannot drift.
 *
 * <p>None of these reuses a GitHub reply, resolve or submit callback: Ask agent and Resolve locally
 * are review-service calls that never reach GitHub, and only Post comment now crosses that line,
 * through the durable draft so a single item can never be sent twice.
 */
export function useFindingActions({
  review,
  onReviewChange,
  onNavigate,
}: {
  review: AgentReviewDto;
  onReviewChange: (review: AgentReviewDto) => void;
  onNavigate: (finding: ReviewFindingDto) => void;
}): { actions: FindingActions; busy: boolean; error: string | null; clearError: () => void } {
  const fixKeys = useRef(new Map<string, string>());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const guard = useCallback(async (action: () => Promise<void>, propagate = false) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    }
    catch (failure) {
      setError(remoteErrorMessage(failure));
      if (propagate) throw failure;
    }
    finally {
      setBusy(false);
    }
  }, []);

  const reload = useCallback(async () => {
    onReviewChange(await window.bridge.getAgentReview(review.id));
  }, [onReviewChange, review.id]);

  const draftVersion = () => review.draft?.version ?? 0;
  const number = () => {
    if (review.number === null) throw new Error('This local review has no pull request');
    return review.number;
  };

  const actions: FindingActions = {
    onRequestFix: review.sourceTaskId == null ? undefined : (finding, instruction) => guard(async () => {
      const sourceTaskId = review.sourceTaskId;
      if (sourceTaskId == null) return;
      const task = await window.bridge.getAgentTask(sourceTaskId, review.workspaceId);
      if (task.state !== 'OPEN' || task.checkoutRemovedAt !== null) throw new Error('The development task is no longer available for changes');
      if (task.developmentContinuationUnfinished) throw new Error('Wait for development automation to finish before requesting a fix');
      const message = findingFixPrompt(review, finding, instruction);
      const identity = `${finding.id}:${finding.revision}:${message}`;
      let idempotencyKey = fixKeys.current.get(identity);
      if (idempotencyKey === undefined) {
        idempotencyKey = `review-fix:${crypto.randomUUID()}`;
        fixKeys.current.set(identity, idempotencyKey);
      }
      if (task.mutationClaim?.kind === 'TURN') {
        await window.bridge.queueAgentInput({ taskId: sourceTaskId, workspaceId: review.workspaceId,
          expectedVersion: task.version, message, idempotencyKey });
      }
      else {
        await window.bridge.startAgentTurn({ taskId: sourceTaskId, expectedTaskVersion: task.version, message, idempotencyKey });
      }
      fixKeys.current.delete(identity);
      recordReviewAttentionAction(review, 'prompt');
    }, true),
    onAsk: (finding, message) => guard(async () => {
      onReviewChange(await window.bridge.askAgentReview(review.id, finding.id, finding.revision, message));
      recordReviewAttentionAction(review, 'prompt');
    }, true),
    onResolve: (finding, reason, remember = false) => guard(async () => {
      onReviewChange(await window.bridge.resolveAgentReviewFinding(review.id, finding.id, finding.version, reason, remember));
      recordReviewAttentionAction(review, 'update');
    }, true),
    onReopen: finding => guard(async () => {
      onReviewChange(await window.bridge.reopenAgentReviewFinding(review.id, finding.id, finding.version));
      recordReviewAttentionAction(review, 'update');
    }),
    onAddToReview: finding => guard(async () => {
      await window.bridge.addReviewDraftFinding(review.repo, number(), draftVersion(), review.id, finding.id);
      await reload();
    }),
    onPostNow: (finding, body) => guard(async () => {
      // One item, one reservation: the finding is promoted first, so Post now and Submit review
      // consume the same draft item and cannot both send it.
      let version = draftVersion();
      const existing = review.draft?.items.find(item => item.findingId === finding.id) ?? null;
      let itemId = existing?.id ?? null;
      if (itemId === null) {
        const draft = await window.bridge.addReviewDraftFinding(review.repo, number(), version, review.id, finding.id);
        version = draft.version;
        itemId = draft.items.find(item => item.findingId === finding.id)?.id ?? null;
      }
      if (itemId === null) throw new Error('The comment could not be prepared for sending');
      if (body !== (existing?.body ?? findingReviewText(finding))) {
        version = (await window.bridge.editReviewDraftComment(review.repo, number(), version, itemId, body, true)).version;
      }
      try {
        await window.bridge.publishReviewDraftComment(review.repo, number(), version, itemId);
      }
      catch (failure) {
        // A failed request can still save an uncertain delivery; preserve the send error if reloading also fails.
        await reload().catch(() => {});
        throw failure;
      }
      recordAttentionAction(`${review.repo}#${number()}`, 'comment');
      await reload();
    }, true),
    onEditDraft: (finding, body) => guard(async () => {
      const item = review.draft?.items.find(candidate => candidate.findingId === finding.id) ?? null;
      if (item !== null) {
        await window.bridge.editReviewDraftComment(review.repo, number(), draftVersion(), item.id, body, item.included);
      }
      else if (finding.path === null && review.draft !== null) {
        await window.bridge.saveReviewDraft(review.repo, number(), draftVersion(), body, review.draft.event);
      }
      else return;
      await reload();
    }, true),
    onRemoveFromReview: finding => guard(async () => {
      const item = review.draft?.items.find(candidate => candidate.findingId === finding.id) ?? null;
      if (item !== null) {
        await window.bridge.removeReviewDraftComment(review.repo, number(), draftVersion(), item.id);
      }
      else if (finding.path === null && review.draft !== null) {
        const text = findingReviewText(finding);
        const body = review.draft.body.replace(review.draft.body.includes(text) ? text : finding.body.trim(), '').trim();
        await window.bridge.saveReviewDraft(review.repo, number(), draftVersion(), body, review.draft.event);
      }
      else return;
      await reload();
    }, true),
    onNavigate,
  };

  return { actions, busy, error, clearError: () => setError(null) };
}
