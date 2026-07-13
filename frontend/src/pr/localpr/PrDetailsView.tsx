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
import { PRView } from './PRView';
import { LocalPrReviewScreen } from './LocalPrReviewScreen';
import { PushDialog } from './PushDialog';
import { useExternalPrActions } from './useExternalPrActions';
import { AgentReviewHeaderAction } from '../../review/AgentReviewHeaderAction';
import { AgentReviewRoundPage } from '../../review/AgentReviewRoundPage';
import { EMPTY_REVIEW_CURSOR, type ReviewCursor } from '../../review/reviewCursor';
import { useAgentReviewState } from '../../review/useAgentReviewState';
import { SubmitReviewPopover } from '../../review/SubmitReviewPopover';
import { isPendingLocalComment } from '../../diff/DiffInlineComments';

/** `PrDetailsView` only ever needs a (repo, number) to bootstrap the
 *  unified fetch, plus its own id back for the dashboard-triage
 *  "Mark handled" callback — every concrete PR row shape in the app
 *  (repo/team-scoped `PullRequestDto`, the personal dashboard's
 *  `DashboardPR`, the live merge-history search rows) satisfies this. */
type DetailsPr = { id: number | string; repo: string; number: number };

/**
 * The standalone PR details page (unified-pr-view.md U10): the same
 * `<PRView>` every other surface renders, plus the full-page Files-changed
 * takeover, replacing the old `PullRequestPreview` rendering path. Entry
 * point is the dashboard card's "Open" — `pr` carries the (repo, number)
 * this component resolves to a unified PR id.
 */
// ponytail: onOpenReview/onMarkHandled/onBack/backLabel are still accepted
// (callers still pass them) but no longer rendered — the top action bar
// they drove was removed. Re-wire them into the UI if/when those actions
// need a new home; drop them from the type if they end up genuinely dead.
export function PrDetailsView<T extends DetailsPr>({ pr }: {
  pr: T;
  /** Fires once the agent review panel is created; the parent owns
   *  navigation to the freshly-created review thread. */
  onStartReview?: (threadId: string) => void;
  /** Opens the embedded github.com review UI (a `WebContentsView`, not
   *  covered by `<PRView>`) — carried over from `PullRequestPreview`. */
  onOpenReview?: () => void;
  /** Marks the PR handled in the local inbox queue — a dashboard-triage
   *  concept, not part of the unified PR aggregate itself. */
  onMarkHandled?: (prId: T['id']) => Promise<void>;
  onBack?: () => void;
  backLabel?: string;
}) {
  const [owner, repoName] = pr.repo.split('/');
  const {
    bundle, refresh, syncing, capabilities,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, dequeuePr, deleteBranch, publishReview, publishBusy,
    addLocalLineComment, replyLocalLineComment, resolveLocalComment, deleteLocalComment,
    pushOpen, setPushOpen,
    reviewOpen, setReviewOpen, prBusy, reviewFiles, reviewError,
    runLocalTests, testsBusy,
  } = useExternalPrActions(owner, repoName, pr.number);

  const {
    data: reviewData, displayedBundle, excludedFindings, pendingComments, latestRound, headerState,
    startReview, updateComment, dismissComment: dismissAgentComment, submitReview: submitAgentReview,
    answerFinding, roundAction, cancelRound, reopenFinding, toggleFinding, hasAgentComment, error: agentReviewError,
  } = useAgentReviewState(bundle, refresh);
  const [roundOpen, setRoundOpen] = useState(false);
  const [selectedRoundId, setSelectedRoundId] = useState<string | null>(null);
  const [reviewCursor, setReviewCursor] = useState<ReviewCursor>(EMPTY_REVIEW_CURSOR);
  const submitComments = reviewData === null
    ? pendingComments
    : displayedBundle?.comments.filter(isPendingLocalComment) ?? pendingComments;

  const dismissComment = (commentId: string) => {
    if (hasAgentComment(commentId)) dismissAgentComment(commentId);
    else deleteLocalComment(commentId);
  };

  const openRound = (roundId?: string) => {
    if (reviewData == null) return;
    const selected = roundId ?? reviewData.rounds.at(-1)?.id;
    if (selected === undefined) return;
    setReviewOpen(true);
    setSelectedRoundId(selected);
    setRoundOpen(true);
  };

  const openFinding = (findingId: string, filePath: string | null, lineNumber: number | null) => {
    setReviewCursor(current => ({
      selectedFinding: findingId,
      anchoredFile: filePath,
      anchoredLine: lineNumber,
      activeTab: filePath === null ? 'conversation' : 'changes',
      token: current.token + 1,
    }));
  };

  const openReviewList = (findingId: string) => {
    setReviewCursor(current => ({
      selectedFinding: findingId,
      anchoredFile: null,
      anchoredLine: null,
      activeTab: 'changes',
      token: current.token + 1,
    }));
  };


  const headerAction = (
    <AgentReviewHeaderAction
      state={headerState}
      round={reviewData?.rounds.length ?? 1}
      spendCents={latestRound?.cost_cents ?? 0}
      comments={submitComments}
      excluded={excludedFindings}
      error={agentReviewError}
      onStart={startReview}
      onOpenRound={() => openRound()}
      onToggle={toggleFinding}
      onEdit={updateComment}
      onRemove={dismissComment}
      onSubmit={submitAgentReview}
    />
  );

  const submitReviewControl = reviewData === null || submitComments.length === 0 ? undefined : (
    <SubmitReviewPopover
      comments={submitComments}
      excluded={excludedFindings}
      onToggle={toggleFinding}
      onEdit={updateComment}
      onRemove={dismissComment}
      onSubmit={submitAgentReview}
    />
  );

  const embeddedChanges = displayedBundle == null ? undefined : (
    <LocalPrReviewScreen
      embedded
      title={`Review · ${displayedBundle.pr.title}`}
      files={reviewFiles}
      error={reviewError}
      comments={displayedBundle.comments}
      commits={displayedBundle.commits}
      allowLocalComments={capabilities?.draftLocalComments === true}
      onAddComment={addLocalLineComment}
      onReplyComment={replyLocalLineComment}
      onResolveComment={resolveLocalComment}
      onDismissComment={dismissComment}
      onBack={() => {}}
      reviewData={reviewData ?? undefined}
      selectedFindingId={reviewCursor.selectedFinding}
      onSelectFinding={openFinding}
      onStartAgentReview={startReview}
    />
  );

  const renderPRView = (inline: boolean) => displayedBundle != null && capabilities !== null ? (
    <PRView
      bundle={displayedBundle}
      capabilities={capabilities}
      commentValue={localComment}
      onCommentChange={setLocalComment}
      onAddComment={submitLocalComment}
      onPush={() => setPushOpen(true)}
      onMerge={confirmMerge}
      onDequeue={dequeuePr}
      onDeleteBranch={deleteBranch}
      onReviewChanges={() => inline ? undefined : setReviewOpen(true)}
      onRunTests={runLocalTests}
      runTestsBusy={testsBusy}
      onResolveThread={resolveLocalComment}
      onDismissThread={dismissComment}
      onPublishReview={publishBusy ? undefined : publishReview}
      syncedAt={displayedBundle.pr.syncedAt}
      syncing={syncing}
      onRefresh={refresh}
      headerAction={headerAction}
      reviewData={reviewData ?? undefined}
      onOpenReviewRound={openRound}
      onAnswerFinding={answerFinding}
      onReviewRoundAction={roundAction}
      changesContent={inline ? embeddedChanges : undefined}
      openSubTabRequest={inline
        ? { subTab: reviewCursor.activeTab, token: reviewCursor.token }
        : undefined}
    />
  ) : null;

  if (roundOpen && reviewData != null && selectedRoundId != null) {
    const prView = renderPRView(true);
    if (prView !== null) {
      return (
        <AgentReviewRoundPage
          data={reviewData}
          roundId={selectedRoundId}
          prView={prView}
          onBack={() => { setRoundOpen(false); setReviewOpen(false); }}
          onOpenFinding={openFinding}
          onOpenReviewList={openReviewList}
          onReopenFinding={reopenFinding}
          onStopRound={cancelRound}
        />
      );
    }
  }

  if (reviewOpen && displayedBundle != null) {
    const headSha = displayedBundle.commits.length > 0
      ? displayedBundle.commits[displayedBundle.commits.length - 1].sha
      : null;
    const blobRepo = displayedBundle.pr.repo ?? pr.repo;
    return (
      <LocalPrReviewScreen
        title={`Review · ${displayedBundle.pr.title}`}
        files={reviewFiles}
        error={reviewError}
        comments={displayedBundle.comments}
        commits={displayedBundle.commits}
        allowLocalComments={capabilities?.draftLocalComments === true}
        fetchFileBlob={headSha === null
          ? undefined
          : (path) => window.bridge.fetchFileBlob(blobRepo, path, headSha)}
        onAddComment={addLocalLineComment}
        onReplyComment={replyLocalLineComment}
        onResolveComment={resolveLocalComment}
        onDismissComment={dismissComment}
        onBack={() => setReviewOpen(false)}
        onSubmitReview={pendingComments.length > 0 ? undefined : () => publishReview()}
        submittingReview={publishBusy}
        submitReviewControl={submitReviewControl}
        reviewData={reviewData ?? undefined}
        selectedFindingId={reviewCursor.selectedFinding}
        onSelectFinding={openFinding}
        onStartAgentReview={startReview}
      />
    );
  }

  return (
    <div className="pr-details-view">
      {renderPRView(false)}
      {pushOpen && bundle != null && (
        <PushDialog
          bundle={bundle}
          repoLabel={pr.repo}
          busy={prBusy}
          onPush={confirmPush}
          onCancel={() => setPushOpen(false)}
        />
      )}
    </div>
  );
}
