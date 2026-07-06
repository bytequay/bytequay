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
import { useState } from 'react';
import type { PullRequestDto } from '../../types';
import { PRView } from './PRView';
import { LocalPrReviewScreen } from './LocalPrReviewScreen';
import { PushDialog } from './PushDialog';
import { MergeDialog } from './MergeDialog';
import { useExternalPrActions } from './useExternalPrActions';

/**
 * The standalone PR details page (unified-pr-view.md U10): the same
 * `<PRView>` every other surface renders, plus the full-page Files-changed
 * takeover, replacing the old `PullRequestPreview` rendering path. Entry
 * point is the dashboard card's "Open" — `pr` carries the (repo, number)
 * this component resolves to a unified PR id.
 */
export function PrDetailsView({
  pr, onStartReview, onOpenReview, onMarkHandled, onBack, backLabel,
}: {
  pr: PullRequestDto;
  /** Fires once the agent review panel is created; the parent owns
   *  navigation to the freshly-created review thread. */
  onStartReview?: (threadId: string) => void;
  /** Opens the embedded github.com review UI (a `WebContentsView`, not
   *  covered by `<PRView>`) — carried over from `PullRequestPreview`. */
  onOpenReview?: () => void;
  /** Marks the PR handled in the local inbox queue — a dashboard-triage
   *  concept, not part of the unified PR aggregate itself. */
  onMarkHandled?: (prId: number) => Promise<void>;
  onBack?: () => void;
  backLabel?: string;
}) {
  const [owner, repoName] = pr.repo.split('/');
  const {
    bundle, refresh, syncing, localPr, capabilities,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, publishReview, publishBusy,
    addLocalLineComment, resolveLocalComment, dismissLocalComment,
    pushOpen, setPushOpen, mergeOpen, setMergeOpen,
    reviewOpen, setReviewOpen, prBusy, reviewFiles,
    runLocalTests, testsBusy,
  } = useExternalPrActions(owner, repoName, pr.number);

  const [reviewStarting, setReviewStarting] = useState(false);
  const [reviewStartError, setReviewStartError] = useState<string | null>(null);
  const [handledState, setHandledState] = useState<'idle' | 'running' | 'done' | 'error'>('idle');

  const markHandled = async () => {
    if (onMarkHandled === undefined || handledState === 'running') return;
    setHandledState('running');
    try {
      await onMarkHandled(pr.id);
      setHandledState('done');
    }
    catch {
      setHandledState('error');
    }
  };

  const startAgentReview = async () => {
    if (reviewStarting || onStartReview === undefined) return;
    setReviewStarting(true);
    setReviewStartError(null);
    try {
      const result = await window.bridge.startReview(pr.repo, pr.number);
      onStartReview(result.pass.threadId);
    }
    catch (e) {
      setReviewStartError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setReviewStarting(false);
    }
  };

  if (reviewOpen && bundle != null) {
    return (
      <LocalPrReviewScreen
        title={`Review · ${bundle.pr.title}`}
        files={reviewFiles}
        comments={bundle.comments}
        allowLocalComments={capabilities?.draftLocalComments === true}
        onAddComment={addLocalLineComment}
        onResolveComment={resolveLocalComment}
        onDismissComment={dismissLocalComment}
        onBack={() => setReviewOpen(false)}
      />
    );
  }

  return (
    <div className="pr-details-view">
      <div className="pr-details-view__actions">
        {onBack && (
          <button type="button" className="button button--secondary" onClick={onBack}>
            ← {backLabel ?? 'Back'}
          </button>
        )}
        <span className="preview__actions-spacer" aria-hidden="true" />
        {onMarkHandled && (
          <button type="button" className="button button--secondary" disabled={handledState === 'running'} onClick={markHandled}>
            {handledState === 'running' ? 'Marking…' : handledState === 'done' ? '✓ Handled' : 'Mark as handled'}
          </button>
        )}
        {onOpenReview && (
          <button type="button" className="button button--remote" onClick={onOpenReview}>
            Open on Remote
          </button>
        )}
      </div>
      {bundle != null && capabilities !== null && (
        <PRView
          bundle={bundle}
          capabilities={capabilities}
          commentValue={localComment}
          onCommentChange={setLocalComment}
          onAddComment={submitLocalComment}
          onPush={() => setPushOpen(true)}
          onMerge={() => setMergeOpen(true)}
          onMergeAnyway={() => setMergeOpen(true)}
          onReviewChanges={() => setReviewOpen(true)}
          onRunTests={runLocalTests}
          runTestsBusy={testsBusy}
          onResolveThread={resolveLocalComment}
          onDismissThread={dismissLocalComment}
          onPublishReview={publishBusy ? undefined : publishReview}
          syncedAt={bundle.pr.syncedAt}
          syncing={syncing}
          onRefresh={refresh}
          headerAction={onStartReview !== undefined ? (
            <button type="button" className="prc-meta-link-btn" disabled={reviewStarting} onClick={startAgentReview}>
              {reviewStarting ? 'Starting…' : 'Review with agent →'}
            </button>
          ) : undefined}
        />
      )}
      {reviewStartError !== null && <div className="pr-details-view__error">{reviewStartError}</div>}
      {pushOpen && bundle != null && (
        <PushDialog
          bundle={bundle}
          repoLabel={pr.repo}
          busy={prBusy}
          onPush={confirmPush}
          onCancel={() => setPushOpen(false)}
        />
      )}
      {mergeOpen && localPr !== null && (
        <MergeDialog
          pr={localPr}
          repoLabel={pr.repo}
          busy={prBusy}
          onMerge={confirmMerge}
          onCancel={() => setMergeOpen(false)}
        />
      )}
    </div>
  );
}
