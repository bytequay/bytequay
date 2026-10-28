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
import { requestTaskFix } from '../../agents/taskFix';
import { recordAttentionAction, recordPullRequestAttentionAction, type AttentionAction } from '../../home/useAttentionVisit';
import { remoteErrorMessage } from '../../remoteError';
import { findingFixPrompt } from '../local-review/conversation';
import { workspacePullRequestBridge } from '../PullRequestWorkspace';
import { findingReviewText } from './model';
import type { FindingActions } from './ReviewFindingCard';
import type { AgentReviewDto, ReviewFindingDto } from './contracts';

/** A review belongs to its pull request and, for local reviews, the source development task. */
export function recordReviewAttentionAction(review: AgentReviewDto, action: AttentionAction): void {
  if (review.number !== null) recordPullRequestAttentionAction(review.repo, review.number, action, review.workspaceId);
  if (review.sourceTaskId != null) recordAttentionAction(`task:${review.sourceTaskId}`, action);
}

/**
 * The private and outgoing actions a finding offers, defined once so the Agent review tab and the
 * Changes projection cannot drift.
 *
 * <p>None of these reuses a GitHub reply, resolve or submit callback: Ask agent and Resolve locally
 * are review-service calls that never reach GitHub, and only Post now crosses that line,
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
  const bridge = workspacePullRequestBridge(globalThis.window?.bridge, review.workspaceId);
  const fixKeys = useRef(new Map<string, string>());
  const askKeys = useRef(new Map<string, string>());
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
      const message = findingFixPrompt(review, finding, instruction);
      await requestTaskFix(window.bridge, fixKeys.current, `${finding.id}:${finding.revision}:${message}`, sourceTaskId, review.workspaceId, message);
      recordReviewAttentionAction(review, 'prompt');
    }, true),
    onAsk: (finding, message) => guard(async () => {
      // A retry keeps the question's key, so an answer lost on the way back cannot ask the reviewer twice.
      const identity = `${finding.id}:${finding.revision}:${message}`;
      const idempotencyKey = askKeys.current.get(identity) ?? `review-ask:${crypto.randomUUID()}`;
      askKeys.current.set(identity, idempotencyKey);
      onReviewChange(await window.bridge.askAgentReview(review.id, finding.id, finding.revision, message, idempotencyKey));
      askKeys.current.delete(identity);
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
      await bridge.addReviewDraftFinding(review.repo, number(), draftVersion(), review.id, finding.id);
      await reload();
    }),
    onPostNow: (finding, body) => guard(async () => {
      // One item, one reservation: the finding is promoted first, so Post now and Submit review
      // consume the same draft item and cannot both send it.
      let version = draftVersion();
      const existing = review.draft?.items.find(item => item.findingId === finding.id) ?? null;
      let itemId = existing?.id ?? null;
      if (itemId === null) {
        const draft = await bridge.addReviewDraftFinding(review.repo, number(), version, review.id, finding.id);
        version = draft.version;
        itemId = draft.items.find(item => item.findingId === finding.id)?.id ?? null;
      }
      if (itemId === null) throw new Error('The comment could not be prepared for sending');
      if (body !== (existing?.body ?? findingReviewText(finding))) {
        version = (await bridge.editReviewDraftComment(review.repo, number(), version, itemId, body, true)).version;
      }
      try {
        await bridge.publishReviewDraftComment(review.repo, number(), version, itemId);
      }
      catch (failure) {
        // A failed request can still save an uncertain delivery; preserve the send error if reloading also fails.
        await reload().catch(() => {});
        throw failure;
      }
      recordPullRequestAttentionAction(review.repo, number(), 'comment', review.workspaceId);
      await reload();
    }, true),
    onEditDraft: (finding, body, expectedVersion) => guard(async () => {
      const item = review.draft?.items.find(candidate => candidate.findingId === finding.id) ?? null;
      if (item !== null) {
        await bridge.editReviewDraftComment(review.repo, number(), expectedVersion, item.id, body, item.included);
      }
      else if (finding.path === null && review.draft !== null) {
        await bridge.saveReviewDraft(review.repo, number(), expectedVersion, body, review.draft.event);
      }
      else return;
      await reload();
    }, true),
    onRemoveFromReview: finding => guard(async () => {
      const item = review.draft?.items.find(candidate => candidate.findingId === finding.id) ?? null;
      if (item !== null) {
        await bridge.removeReviewDraftComment(review.repo, number(), draftVersion(), item.id);
      }
      else if (finding.path === null && review.draft !== null) {
        const text = findingReviewText(finding);
        const body = review.draft.body.replace(review.draft.body.includes(text) ? text : finding.body.trim(), '').trim();
        await bridge.saveReviewDraft(review.repo, number(), draftVersion(), body, review.draft.event);
      }
      else return;
      await reload();
    }, true),
    onNavigate,
  };

  return { actions, busy, error, clearError: () => setError(null) };
}
