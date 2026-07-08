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
import { useEffect, useState, type ReactNode } from 'react';
import type { UserProfileDto } from '../../types';
import type { LocalPRBundle } from '../../types/localPr';
import { isLocalStatus } from '../../types/localPr';
import { getCached } from '../../dataCache';
import type { PRCapabilities } from '../prCapabilities';
import { useGitHubActivityFeed } from '../useGitHubActivityFeed';
import type { GitHubThreadActions } from './GitHubTimelineRow';
import { PRHeader, type PRHeaderTab } from './PRHeader';
import { PRTimeline } from './PRTimeline';
import { CommitsList } from './CommitsList';
import { CheckRows } from './CheckRows';
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
  onAddComment, onPush, onAskAgent, onMerge, onDequeue, onDeleteBranch, onReviewChanges,
  onRunTests, runTestsBusy = false, onResolveThread, onDismissThread,
  onPublishReview, onDiscardDrafts, syncedAt, syncing, onRefresh, headerAction, openSubTabRequest,
}: {
  bundle: LocalPRBundle;
  capabilities: PRCapabilities;
  commentValue: string;
  onCommentChange: (v: string) => void;
  username?: string;
  onAddComment?: () => void;
  onPush?: () => void;
  onAskAgent?: () => void;
  /** Confirms the merge (or, on a queue-enabled repo, the enqueue) with the
   *  chosen method — the merge box's own inline confirm step calls this. */
  onMerge?: (method: string) => void;
  /** Removes the PR from its repo's merge queue. */
  onDequeue?: () => void;
  /** Deletes the merged PR's head branch on GitHub. */
  onDeleteBranch?: () => void;
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
  /** Force-switches the header's own sub-tab (e.g. the live-plan rail's CI
   *  validation node opens Checks in place) — a fresh `token` re-fires even
   *  for a repeat click on the sub-tab that's already active. */
  openSubTabRequest?: { subTab: PRHeaderTab; token: number };
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

  // GitHub's own conversation feed (labels, review-requests, force-pushes,
  // inline diff threads, …) — only fetched once the PR has a remote
  // identity; see PRTimeline's `githubFeedActive` for how it takes over
  // from the local sync tables at that point.
  const { activity, reviewThreads, refresh: refreshActivityFeed } = useGitHubActivityFeed(pr.repo, pr.remotePrNumber);
  const threadActions: GitHubThreadActions | undefined = pr.repo === null ? undefined : {
    repo: pr.repo,
    prAuthor: pr.author,
    prHtmlUrl: pr.remotePrUrl ?? '',
    currentUserLogin: getCached<UserProfileDto>('home:profile')?.login ?? null,
    onReply: async (rootGithubId, body) => {
      if (pr.repo === null || pr.remotePrNumber === null) return;
      await window.bridge.replyToReviewThread(pr.repo, pr.remotePrNumber, rootGithubId, body);
    },
    onReact: async (commentGithubId, content) => {
      if (pr.repo === null) return;
      await window.bridge.addReviewCommentReaction(pr.repo, commentGithubId, content);
    },
  };

  const [activeTab, setActiveTab] = useState<PRHeaderTab>('conversation');

  useEffect(() => {
    if (openSubTabRequest === undefined) return;
    setActiveTab(openSubTabRequest.subTab);
  }, [openSubTabRequest]);

  // The header's "Sync" button previously only re-fetched the local PR
  // bundle — the GitHub-native conversation feed (comments/review threads)
  // fetches once on mount and otherwise never refreshes, so the tab could
  // go stale for the entire time the page stayed open. Force-bypass the
  // ETag-probe cache here (force=true) since an explicit user click is
  // exactly the "I want the real current state" signal that should skip it.
  const handleRefresh = () => {
    onRefresh();
    refreshActivityFeed(true);
  };

  const githubFeedActive = pr.remotePrNumber !== null;
  // Once the GitHub feed is active it's the source of truth for the
  // Conversation tab's count too — github.com counts top-level comments
  // plus reviews that carry a written summary (a bare approve/request-
  // changes with no comment isn't its own conversation entry).
  const conversationCount = githubFeedActive
    ? activity.filter(a => a.eventType === 'commented'
        || (a.eventType === 'reviewed' && a.body !== null && a.body.trim().length > 0)).length + 1
    : comments.length + 1;

  return (
    <div className="pr-view">
      <PRHeader
        pr={pr}
        syncedAt={syncedAt}
        syncing={syncing}
        onRefresh={handleRefresh}
        commitCount={commits.length}
        checkCount={checks.length}
        conversationCount={conversationCount}
        additions={additions}
        deletions={deletions}
        headerAction={headerAction}
        onReviewChanges={onReviewChanges}
        activeTab={activeTab}
        onTabChange={setActiveTab}
      />

      <div className="pr-body-scroll">
        {activeTab === 'conversation' && (
          <PRTimeline
            pr={pr}
            events={timeline}
            comments={comments}
            onReviewChanges={onReviewChanges}
            onResolveThread={capabilities.draftLocalComments ? onResolveThread : undefined}
            onDismissThread={capabilities.draftLocalComments ? onDismissThread : undefined}
            activity={activity}
            reviewThreads={reviewThreads}
            threadActions={threadActions}
          />
        )}

        {activeTab === 'commits' && <CommitsList commits={commits} author={pr.author} />}

        {activeTab === 'checks' && (
          <div className="pr-merge-box">
            <div className="check-list">
              {localChecks.length > 0 && <div className="check-group">LOCAL</div>}
              <CheckRows checks={localChecks} />
              {remoteChecks.length > 0 && <div className="check-group">REMOTE</div>}
              <CheckRows checks={remoteChecks} />
            </div>
          </div>
        )}

        {activeTab === 'conversation' && (
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
            onDequeue={onDequeue}
            onDeleteBranch={onDeleteBranch}
            onPublishReview={onPublishReview}
            onDiscardDrafts={onDiscardDrafts}
            onRunTests={onRunTests}
            runTestsBusy={runTestsBusy}
          />
        )}

        {activeTab === 'conversation' && capabilities.draftLocalComments && (
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
