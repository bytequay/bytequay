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
import type { ReactNode } from 'react';
import type { LocalPRBundle } from '../../types/localPr';
import { isLocalStatus } from '../../types/localPr';
import type { PRCapabilities } from '../prCapabilities';
import { PRHeader } from './PRHeader';
import { PRTimeline } from './PRTimeline';
import { MergeBox } from './MergeBox';
import { PRCommentComposer } from './PRCommentComposer';

/**
 * The unified PR renderer (U7) — ONE component driven by a `capabilities`
 * object, not a `mode`/`allowLocalComments` prop. Layout: fixed header
 * (`<PRHeader>`) + a scrollable rail (`<PRTimeline>`, description-first) +
 * the merge-box gate (`<MergeBox>`, only rendered when a gate or checks
 * exist) + the comment composer.
 *
 * Presentational: the host resolves the {@link LocalPRBundle} (via
 * `usePR`/`useLocalPr`) and supplies the user-gated callbacks. Push, merge,
 * and publish-review are never auto-invoked here — they open the host's
 * dialogs or fire the host's mutation.
 */
export function PRView({
  bundle, capabilities, commentValue, onCommentChange, username,
  onAddComment, onPush, onAskAgent, onMerge, onMergeAnyway, onReviewChanges,
  onRunTests, runTestsBusy = false, onResolveThread, onDismissThread,
  onPublishReview, onDiscardDrafts, syncedAt, syncing, onRefresh, headerAction,
}: {
  bundle: LocalPRBundle;
  capabilities: PRCapabilities;
  commentValue: string;
  onCommentChange: (v: string) => void;
  username?: string;
  onAddComment?: () => void;
  onPush?: () => void;
  onAskAgent?: () => void;
  onMerge?: () => void;
  onMergeAnyway?: () => void;
  /** Opens the full-page changed-files + diff review. Omitted when there's
   *  nothing to review yet. */
  onReviewChanges?: () => void;
  /** Manually re-run the local test suite (design doc slice 4). Omitted
   *  when there's no PR to run tests against yet. */
  onRunTests?: () => void;
  runTestsBusy?: boolean;
  onResolveThread?: (commentId: string) => void;
  onDismissThread?: (commentId: string) => void;
  onPublishReview?: () => void;
  onDiscardDrafts?: () => void;
  syncedAt: number | null;
  syncing: boolean;
  onRefresh: () => void;
  /** e.g. the standalone details page's "Review with agent →" affordance. */
  headerAction?: ReactNode;
}) {
  const { pr, commits, timeline, checks, comments } = bundle;
  const local = isLocalStatus(pr.status);

  const openComments = comments.filter(c => c.resolvedAt === null && c.strippedOnPushAt === null).length;
  // The brain's dev-end review outcome (plan-rail-runs.md R21/R23) — derived
  // from its own comments (author='brain'), never a separate fetch: a brain
  // review round always concludes before the PR flips to local-open, so by
  // the time this renders, its story is fully told here.
  const brainComments = comments.filter(c => c.author === 'brain');
  const brainReview = brainComments.length > 0
    ? { total: brainComments.length, unresolved: brainComments.filter(c => c.resolvedAt === null && c.dismissedAt === null).length }
    : undefined;
  const localChecks = checks.filter(c => c.kind === 'local');
  const remoteChecks = checks.filter(c => c.kind === 'remote');
  // The promotion gate (design doc slice 5) only cares about the MOST RECENT
  // local run — an earlier failure that's since been fixed doesn't block.
  const latestLocalCheck = localChecks.reduce<typeof localChecks[number] | undefined>(
    (latest, c) => latest === undefined || c.startedAt > latest.startedAt ? c : latest, undefined);
  const localTestsFailing = latestLocalCheck?.status === 'failed';
  const draftCount = comments.filter(
    c => c.origin === 'local' && c.publishedAt === null && c.dismissedAt === null).length;
  // GitHub's commit-list API has no per-commit stats, so an external PR's
  // synced commits always sum to 0 — use the PR-level total GitHub reports
  // instead. Task-origin commits carry real per-commit stats, so summing
  // them is exact.
  const additions = pr.origin === 'external' ? pr.syncedAdditions ?? 0 : commits.reduce((sum, c) => sum + c.additions, 0);
  const deletions = pr.origin === 'external' ? pr.syncedDeletions ?? 0 : commits.reduce((sum, c) => sum + c.deletions, 0);

  return (
    <div className="pr-view">
      <PRHeader
        pr={pr}
        syncedAt={syncedAt}
        syncing={syncing}
        onRefresh={onRefresh}
        commitCount={commits.length}
        checkCount={checks.length}
        conversationCount={comments.length + 1 /* description */}
        additions={additions}
        deletions={deletions}
        headerAction={headerAction}
      />

      <div className="pr-body-scroll">
        {onReviewChanges !== undefined && (
          <button type="button" className="pr-review-btn" onClick={onReviewChanges}>
            <span className="ic" aria-hidden>◧</span>
            Review changed files &amp; diff
            <span className="arrow" aria-hidden>→</span>
          </button>
        )}

        <PRTimeline
          pr={pr}
          events={timeline}
          comments={comments}
          onReviewChanges={onReviewChanges}
          onResolveThread={capabilities.draftLocalComments ? onResolveThread : undefined}
          onDismissThread={capabilities.draftLocalComments ? onDismissThread : undefined}
        />

        <MergeBox
          pr={pr}
          capabilities={capabilities}
          localChecks={localChecks}
          remoteChecks={remoteChecks}
          openComments={openComments}
          localTestsFailing={localTestsFailing}
          pendingStripCount={bundle.pendingStripCount ?? 0}
          draftCount={draftCount}
          brainReview={brainReview}
          onPush={onPush}
          onAskAgent={onAskAgent}
          onMerge={onMerge}
          onMergeAnyway={onMergeAnyway}
          onPublishReview={onPublishReview}
          onDiscardDrafts={onDiscardDrafts}
        />

        {onRunTests !== undefined && (
          <div className="mb-actions" style={{ paddingLeft: 0 }}>
            <button type="button" className="btn sm" onClick={onRunTests} disabled={runTestsBusy}>
              {runTestsBusy ? 'Running tests…' : 'Run tests'}
            </button>
          </div>
        )}

        {capabilities.draftLocalComments && (
          <PRCommentComposer
            local={local}
            username={username}
            value={commentValue}
            onChange={onCommentChange}
            onSubmit={onAddComment}
          />
        )}
      </div>
    </div>
  );
}
