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
import { useCallback, useState } from 'react';
import { remoteErrorMessage } from '../PullOverview';
import type { FindingActions } from './ReviewFindingCard';
import type { AgentReviewDto, ReviewFindingDto } from './contracts';

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

  const actions: FindingActions = {
    onAsk: (finding, message) => guard(async () => {
      onReviewChange(await window.bridge.askAgentReview(review.id, finding.id, finding.revision, message));
    }, true),
    onResolve: (finding, reason) => guard(async () => {
      onReviewChange(await window.bridge.resolveAgentReviewFinding(review.id, finding.id, finding.version, reason));
    }, true),
    onReopen: finding => guard(async () => {
      onReviewChange(await window.bridge.reopenAgentReviewFinding(review.id, finding.id, finding.version));
    }),
    onAddToReview: finding => guard(async () => {
      await window.bridge.addReviewDraftFinding(review.repo, review.number, draftVersion(), review.id, finding.id);
      await reload();
    }),
    onPostNow: (finding, body) => guard(async () => {
      // One item, one reservation: the finding is promoted first, so Post now and Submit review
      // consume the same draft item and cannot both send it.
      let version = draftVersion();
      const existing = review.draft?.items.find(item => item.findingId === finding.id) ?? null;
      let itemId = existing?.id ?? null;
      if (itemId === null) {
        const draft = await window.bridge.addReviewDraftFinding(review.repo, review.number, version, review.id, finding.id);
        version = draft.version;
        itemId = draft.items.find(item => item.findingId === finding.id)?.id ?? null;
      }
      if (itemId === null) throw new Error('The comment could not be prepared for sending');
      if (body !== (existing?.body ?? finding.body)) {
        version = (await window.bridge.editReviewDraftComment(review.repo, review.number, version, itemId, body, true)).version;
      }
      await window.bridge.publishReviewDraftComment(review.repo, review.number, version, itemId);
      await reload();
    }, true),
    onEditDraft: (finding, body) => guard(async () => {
      const item = review.draft?.items.find(candidate => candidate.findingId === finding.id) ?? null;
      if (item === null) return;
      await window.bridge.editReviewDraftComment(review.repo, review.number, draftVersion(), item.id, body, item.included);
      await reload();
    }, true),
    onNavigate,
  };

  return { actions, busy, error, clearError: () => setError(null) };
}
