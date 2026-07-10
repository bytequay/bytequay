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
import { PRView } from './PRView';
import { LocalPrReviewScreen } from './LocalPrReviewScreen';
import { PushDialog } from './PushDialog';
import { useExternalPrActions } from './useExternalPrActions';

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
export function PrDetailsView<T extends DetailsPr>({
  pr, onStartReview, onOpenReview, onMarkHandled, onBack, backLabel,
}: {
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
    addLocalLineComment, replyLocalLineComment, resolveLocalComment, dismissLocalComment,
    pushOpen, setPushOpen,
    reviewOpen, setReviewOpen, prBusy, reviewFiles, reviewError,
    runLocalTests, testsBusy,
  } = useExternalPrActions(owner, repoName, pr.number);

  if (reviewOpen && bundle != null) {
    const headSha = bundle.commits.length > 0
      ? bundle.commits[bundle.commits.length - 1].sha
      : null;
    const blobRepo = bundle.pr.repo ?? pr.repo;
    return (
      <LocalPrReviewScreen
        title={`Review · ${bundle.pr.title}`}
        files={reviewFiles}
        error={reviewError}
        comments={bundle.comments}
        allowLocalComments={capabilities?.draftLocalComments === true}
        fetchFileBlob={headSha === null
          ? undefined
          : (path) => window.bridge.fetchFileBlob(blobRepo, path, headSha)}
        onAddComment={addLocalLineComment}
        onReplyComment={replyLocalLineComment}
        onResolveComment={resolveLocalComment}
        onDismissComment={dismissLocalComment}
        onBack={() => setReviewOpen(false)}
      />
    );
  }

  return (
    <div className="pr-details-view">
      {bundle != null && capabilities !== null && (
        <PRView
          bundle={bundle}
          capabilities={capabilities}
          commentValue={localComment}
          onCommentChange={setLocalComment}
          onAddComment={submitLocalComment}
          onPush={() => setPushOpen(true)}
          onMerge={confirmMerge}
          onDequeue={dequeuePr}
          onDeleteBranch={deleteBranch}
          onReviewChanges={() => setReviewOpen(true)}
          onRunTests={runLocalTests}
          runTestsBusy={testsBusy}
          onResolveThread={resolveLocalComment}
          onDismissThread={dismissLocalComment}
          onPublishReview={publishBusy ? undefined : publishReview}
          syncedAt={bundle.pr.syncedAt}
          syncing={syncing}
          onRefresh={refresh}
        />
      )}
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
