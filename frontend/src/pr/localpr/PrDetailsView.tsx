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
import { useEffect, useState } from 'react';
import { PRView } from './PRView';
import { LocalPrReviewScreen, type GithubThreadContext } from './LocalPrReviewScreen';
import { useGitHubActivityFeed } from '../useGitHubActivityFeed';
import { PushDialog } from './PushDialog';
import { useExternalPrActions } from './useExternalPrActions';
import { AgentReviewHeaderAction } from '../../review/AgentReviewHeaderAction';
import { AgentReviewRoundPage } from '../../review/AgentReviewRoundPage';
import { EMPTY_REVIEW_CURSOR, type ReviewCursor } from '../../review/reviewCursor';
import { useAgentReviewState } from '../../review/useAgentReviewState';
import { SubmitReviewPopover } from '../../review/SubmitReviewPopover';
import { isPublishableReviewDraft } from '../../diff/DiffInlineComments';

/** `PrDetailsView` only ever needs a (repo, number) to bootstrap the
 *  unified fetch, plus its own id back for the dashboard-triage
 *  "Mark handled" callback — every concrete PR row shape in the app
 *  (repo/team-scoped `PullRequestDto`, the personal dashboard's
 *  `DashboardPR`, the live merge-history search rows) satisfies this. */
type DetailsPr = { id: number | string; repo: string; number: number };

export type AgentReviewNavTarget = {
  threadId: string | null;
  taskId: string | null;
  roundId: string;
  workspaceId: string;
  prId: string;
  repo: string;
  prNumber: number | null;
};

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
export function PrDetailsView<T extends DetailsPr>({
  pr,
  workspaceId,
  initialReviewRoundId,
  onCloseReviewRound,
  onOpenAgentReview,
  onOpenReviewSetup,
}: {
  pr: T;
  /** Fires once the agent review panel is created; the parent owns
   *  navigation to the freshly-created review thread. */
  onStartReview?: (threadId: string | null, reviewId?: string) => void;
  /** Opens the embedded github.com review UI (a `WebContentsView`, not
   *  covered by `<PRView>`) — carried over from `PullRequestPreview`. */
  onOpenReview?: () => void;
  /** Marks the PR handled in the local inbox queue — a dashboard-triage
   *  concept, not part of the unified PR aggregate itself. */
  onMarkHandled?: (prId: T['id']) => Promise<void>;
  onBack?: () => void;
  backLabel?: string;
  /** Workspace that owns a standalone external-PR agent review. */
  workspaceId?: string | null;
  /** Opens this round immediately when the page is used as the durable
   *  review thread's own route rather than as an embedded PR pane. */
  initialReviewRoundId?: string;
  onCloseReviewRound?: () => void;
  /** Promotes a round from a PR surface into its durable owner: the task
   *  page for development PRs or the review-thread route for external PRs.
   *  Task pages omit this callback, so their own review remains inline. */
  onOpenAgentReview?: (target: AgentReviewNavTarget) => void;
  /** Unwatched external PRs use the canonical Pulls quick/watch flow instead
   *  of trying to start a workspace-less AgentReview here. */
  onOpenReviewSetup?: (action: 'quick' | 'watch', repo: string, number: number) => void;
}) {
  const [owner, repoName] = pr.repo.split('/');
  const {
    bundle, refresh, syncing, capabilities,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, dequeuePr, deleteBranch, publishReview, publishBusy,
    addLocalLineComment, replyLocalLineComment, replyLocalPrComment, resolveLocalComment, deleteLocalComment,
    pushOpen, setPushOpen,
    reviewOpen, setReviewOpen, prBusy, reviewFiles, reviewError,
    runLocalTests, testsBusy,
  } = useExternalPrActions(owner, repoName, pr.number);

  const reviewRepo = bundle?.pr.repo ?? pr.repo;
  const [reviewWorkspaceId, setReviewWorkspaceId] = useState<string | null | undefined>();
  useEffect(() => {
    let cancelled = false;
    const listWorkspaces = window.bridge?.listWorkspaces;
    if (listWorkspaces === undefined) {
      setReviewWorkspaceId(null);
      return () => { cancelled = true; };
    }
    setReviewWorkspaceId(undefined);
    void listWorkspaces()
      .then(workspaces => {
        if (cancelled) return;
        const match = workspaces.find(candidate =>
          candidate.repository?.fullName.toLowerCase() === reviewRepo.toLowerCase());
        setReviewWorkspaceId(match?.id ?? null);
      })
      .catch(() => {
        // Fail closed: the workspace passed by a Team page may belong to a
        // different repository. Never start a full review against it unless
        // the repository lookup positively matched.
        if (!cancelled) setReviewWorkspaceId(null);
      });
    return () => { cancelled = true; };
  }, [reviewRepo, workspaceId]);

  const {
    data: reviewData, displayedBundle, excludedFindings, pendingComments, latestRound, latestRoundNumber, headerState,
    startReview, updateComment, dismissComment: dismissAgentComment, submitReview: submitAgentReview,
    startRound, sendRoundMessage, updateRoundBudget,
    answerFinding, roundAction, cancelRound, reopenFinding, setFindingResolved, toggleFinding, hasAgentComment,
    loading: agentReviewBusy, error: agentReviewError,
  } = useAgentReviewState(bundle, refresh, undefined, reviewWorkspaceId);
  // Live GitHub review threads for the diff (reply / resolve / unresolve).
  // The feed no-ops until the PR has a remote number; the same feed powers
  // the conversation timeline inside <PRView>, and the ETag probe dedupes the
  // extra fetch.
  const remotePrNumber = displayedBundle?.pr.remotePrNumber ?? null;
  const { reviewThreads, refresh: refreshGithubFeed } = useGitHubActivityFeed(pr.repo, remotePrNumber);
  const githubThreads: GithubThreadContext | undefined = remotePrNumber === null ? undefined : {
    threads: reviewThreads,
    repo: displayedBundle?.pr.repo ?? pr.repo,
    prNumber: remotePrNumber,
    // The resolve path prefers this legacy id but falls back to deriving it
    // from the thread's own comment id, so a best-effort value is fine.
    prId: typeof pr.id === 'number' ? pr.id : Number(pr.id) || 0,
    prAuthor: displayedBundle?.pr.author ?? null,
    onChanged: () => refreshGithubFeed(true),
  };

  const [roundOpen, setRoundOpen] = useState(false);
  const [selectedRoundId, setSelectedRoundId] = useState<string | null>(null);
  const [reviewCursor, setReviewCursor] = useState<ReviewCursor>(EMPTY_REVIEW_CURSOR);
  const [reviewTabRequest, setReviewTabRequest] = useState<{ tab: 'files' | 'review'; token: number }>();
  const manualPendingComments = displayedBundle?.comments.filter(comment =>
    comment.findingId == null && isPublishableReviewDraft(comment)) ?? [];
  const submitComments = reviewData === null ? manualPendingComments : pendingComments;
  const submitPendingReview = (verdict: Parameters<typeof submitAgentReview>[0]) => {
    if (reviewData === null) {
      void publishReview(verdict);
      return;
    }
    submitAgentReview(verdict);
  };

  useEffect(() => {
    setRoundOpen(false);
    setSelectedRoundId(null);
    setReviewCursor(EMPTY_REVIEW_CURSOR);
    setReviewTabRequest(undefined);
    setReviewOpen(false);
    if (initialReviewRoundId !== undefined) {
      setReviewOpen(true);
      setSelectedRoundId(initialReviewRoundId);
      setRoundOpen(true);
    }
  }, [pr.repo, pr.number, initialReviewRoundId, setReviewOpen]);

  const dismissComment = (commentId: string) => {
    if (hasAgentComment(commentId)) dismissAgentComment(commentId);
    else deleteLocalComment(commentId);
  };

  const openRound = (roundId?: string) => {
    if (reviewData == null) return;
    const selected = roundId ?? latestRound?.id;
    if (selected === undefined) return;
    const ownerWorkspaceId = reviewData.review.workspace_id;
    const reviewPr = displayedBundle?.pr;
    if (onOpenAgentReview !== undefined && ownerWorkspaceId !== null && reviewPr !== undefined) {
      onOpenAgentReview({
        threadId: reviewData.review.owner_thread_id,
        taskId: reviewData.review.owner_task_id,
        roundId: selected,
        workspaceId: ownerWorkspaceId,
        prId: reviewPr.id,
        repo: reviewPr.repo ?? pr.repo,
        prNumber: reviewPr.remotePrNumber,
      });
      return;
    }
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
    if (filePath !== null) {
      setReviewTabRequest(current => ({ tab: 'files', token: (current?.token ?? 0) + 1 }));
    }
  };

  const openReviewList = (findingId: string) => {
    setReviewCursor(current => ({
      selectedFinding: findingId,
      anchoredFile: null,
      anchoredLine: null,
      activeTab: 'changes',
      token: current.token + 1,
    }));
    setReviewTabRequest(current => ({ tab: 'review', token: (current?.token ?? 0) + 1 }));
  };


  const fullReviewHeaderAction = (
    <AgentReviewHeaderAction
      state={headerState}
      round={latestRoundNumber}
      spendCents={latestRound?.cost_cents ?? 0}
      comments={submitComments}
      excluded={excludedFindings}
      error={agentReviewError}
      onStart={startReview}
      onOpenRound={() => openRound()}
      onToggle={toggleFinding}
      onEdit={updateComment}
      onRemove={dismissComment}
      onSubmit={submitPendingReview}
    />
  );
  const headerAction = reviewData !== null || typeof reviewWorkspaceId === 'string' ? fullReviewHeaderAction : (
    <span className="agent-review-header-action">
      <span className="agent-review-entry-wrap">
        <span className="agent-review-entry-split">
          {reviewWorkspaceId === undefined ? (
            <button type="button" className="agent-review-entry" disabled>Review options…</button>
          ) : (
            <>
              <button
                type="button"
                className="agent-review-entry"
                disabled={onOpenReviewSetup === undefined}
                onClick={() => onOpenReviewSetup?.('quick', pr.repo, pr.number)}
              >
                Run quick review
              </button>
              <button
                type="button"
                className="agent-review-entry"
                disabled={onOpenReviewSetup === undefined}
                onClick={() => onOpenReviewSetup?.('watch', pr.repo, pr.number)}
              >
                Watch repo · Full review
              </button>
            </>
          )}
        </span>
      </span>
      {submitComments.length > 0 && (
        <SubmitReviewPopover
          comments={submitComments}
          excluded={excludedFindings}
          onToggle={toggleFinding}
          onEdit={updateComment}
          onRemove={dismissComment}
          onSubmit={submitPendingReview}
        />
      )}
    </span>
  );

  const reviewHeadSha = displayedBundle?.commits.at(-1)?.sha ?? null;
  const reviewBlobRepo = displayedBundle?.pr.repo ?? pr.repo;
  const embeddedChanges = displayedBundle == null ? undefined : (
    <LocalPrReviewScreen
      embedded
      title={`Review · ${displayedBundle.pr.title}`}
      files={reviewFiles}
      error={reviewError}
      comments={displayedBundle.comments}
      commits={displayedBundle.commits}
      allowLocalComments={capabilities?.draftLocalComments === true}
      github={githubThreads}
      fetchFileBlob={reviewHeadSha === null
        ? undefined
        : (path) => window.bridge.fetchFileBlob(reviewBlobRepo, path, reviewHeadSha)}
      onAddComment={addLocalLineComment}
      onReplyComment={replyLocalLineComment}
      onResolveComment={resolveLocalComment}
      onDismissComment={dismissComment}
      onAnswerFinding={answerFinding}
      onSetFindingResolved={setFindingResolved}
      onToggleFindingPromotion={toggleFinding}
      canPromoteFindings={capabilities?.publishReview === true}
      onBack={() => {}}
      reviewData={reviewData ?? undefined}
      selectedFindingId={reviewCursor.selectedFinding}
      selectedFindingRequestToken={reviewCursor.token}
      selectedFindingFilePath={reviewCursor.anchoredFile}
      selectedFindingLineNumber={reviewCursor.anchoredLine}
      onSelectFinding={openFinding}
      onStartAgentReview={startReview}
      openTabRequest={reviewTabRequest}
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
      onReplyThread={replyLocalPrComment}
      onReplyLineThread={replyLocalLineComment}
      onPublishReview={publishBusy ? undefined : publishReview}
      syncedAt={displayedBundle.pr.syncedAt}
      syncing={syncing}
      onRefresh={refresh}
      headerAction={headerAction}
      reviewData={reviewData ?? undefined}
      onOpenReviewRound={openRound}
      onAnswerFinding={answerFinding}
      onReviewRoundAction={roundAction}
      onSetFindingResolved={setFindingResolved}
      onToggleFindingPromotion={toggleFinding}
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
          prTitle={`${displayedBundle.pr.title} · #${displayedBundle.pr.remotePrNumber ?? pr.number}`}
          onBack={() => {
            if (onCloseReviewRound !== undefined) onCloseReviewRound();
            else {
              setRoundOpen(false);
              setReviewOpen(false);
              setReviewCursor(EMPTY_REVIEW_CURSOR);
              setReviewTabRequest(undefined);
            }
          }}
          onSelectRound={setSelectedRoundId}
          onOpenFinding={openFinding}
          onOpenReviewList={openReviewList}
          onReopenFinding={reopenFinding}
          onStopRound={cancelRound}
          onStartRound={startRound}
          onSendMessage={sendRoundMessage}
          onUpdateBudget={updateRoundBudget}
          busy={agentReviewBusy}
          error={agentReviewError}
        />
      );
    }
  }

  if (reviewOpen && displayedBundle != null) {
    return (
      <LocalPrReviewScreen
        title={`Review · ${displayedBundle.pr.title}`}
        files={reviewFiles}
        error={reviewError}
        comments={displayedBundle.comments}
        commits={displayedBundle.commits}
        allowLocalComments={capabilities?.draftLocalComments === true}
        github={githubThreads}
        fetchFileBlob={reviewHeadSha === null
          ? undefined
          : (path) => window.bridge.fetchFileBlob(reviewBlobRepo, path, reviewHeadSha)}
        onAddComment={addLocalLineComment}
        onReplyComment={replyLocalLineComment}
        onResolveComment={resolveLocalComment}
        onDismissComment={dismissComment}
        onAnswerFinding={answerFinding}
        onSetFindingResolved={setFindingResolved}
        onToggleFindingPromotion={toggleFinding}
        canPromoteFindings={capabilities?.publishReview === true}
        onBack={() => setReviewOpen(false)}
        reviewData={reviewData ?? undefined}
        selectedFindingId={reviewCursor.selectedFinding}
        selectedFindingRequestToken={reviewCursor.token}
        selectedFindingFilePath={reviewCursor.anchoredFile}
        selectedFindingLineNumber={reviewCursor.anchoredLine}
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
