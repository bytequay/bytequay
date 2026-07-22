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
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import type { UserProfileDto } from '../../types';
import type { LocalPRBundle } from '../../types/localPr';
import { isLocalStatus } from '../../types/localPr';
import { getCached, setCached } from '../../dataCache';
import type { PRCapabilities } from '../prCapabilities';
import { useGitHubActivityFeed } from '../useGitHubActivityFeed';
import type { GitHubThreadActions } from './GitHubTimelineRow';
import { PRHeader, type PRHeaderTab } from './PRHeader';
import { PRTimeline } from './PRTimeline';
import { CommitsList } from './CommitsList';
import { CheckRows } from './CheckRows';
import { MergeBox } from './MergeBox';
import { PRCommentComposer } from './PRCommentComposer';
import type { AgentReviewData } from '../../review/agentReviewTypes';
import type { LocalReviewGate } from './localReviewGate';

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
  onRunTests, runTestsBusy = false, onResolveThread, onDismissThread, onReplyThread, onReplyLineThread, onOpenStage,
  onPublishReview, onDiscardDrafts, syncedAt, syncing, onRefresh, headerAction, openSubTabRequest,
  changesContent, reviewData, onOpenReviewRound, onAnswerFinding, onReviewRoundAction,
  onSetFindingResolved, onToggleFindingPromotion, localReviewGate,
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
  onReplyThread?: (rootCommentId: string, body: string) => void | Promise<void>;
  onReplyLineThread?: (
    rootCommentId: string, filePath: string, side: 'LEFT' | 'RIGHT', lineNumber: number,
    startLine: number | undefined, startSide: 'LEFT' | 'RIGHT' | undefined, body: string,
  ) => void | Promise<void>;
  /** Jumps to a stage's detail view — the timeline's "View the plan" link
   *  card on a `plan-finalized` row is the only thing that uses it today. */
  onOpenStage?: (stageId: string) => void;
  onPublishReview?: () => void;
  onDiscardDrafts?: () => void;
  syncedAt: number | null;
  syncing: boolean;
  onRefresh: () => void;
  /** e.g. the standalone details page's "Review with agent →" affordance. */
  headerAction?: ReactNode;
  /** Force-switches the header's own sub-tab (e.g. the live-plan rail's CI
   *  validation node opens Checks in place; the ready-for-review callout
   *  opens Changes) — a fresh `token` re-fires even for a repeat click on
   *  the sub-tab that's already active. */
  openSubTabRequest?: { subTab: PRHeaderTab; token: number };
  /** Embedded changed-files + diff UI. When omitted, Changes keeps opening the
   *  host's full-page review surface. */
  changesContent?: ReactNode;
  reviewData?: AgentReviewData;
  onOpenReviewRound?: (roundId: string) => void;
  onAnswerFinding?: (findingId: string, text: string) => void | Promise<unknown>;
  onReviewRoundAction?: (roundId: string) => void;
  onSetFindingResolved?: (findingId: string, resolved: boolean) => void | Promise<unknown>;
  onToggleFindingPromotion?: (findingId: string) => void | Promise<unknown>;
  /** Authoritative task/validation/Brain state for task-origin promotion. */
  localReviewGate?: LocalReviewGate;
}) {
  const { pr, commits, timeline, checks, comments } = bundle;
  const local = isLocalStatus(pr.status);

  const openComments = comments.filter(c => c.parentCommentId === null
    && c.resolvedAt === null && c.strippedOnPushAt === null).length;
  const localChecks = checks.filter(c => c.kind === 'local');
  const remoteChecks = checks.filter(c => c.kind === 'remote');
  // The promotion gate (design doc slice 5) only cares about the MOST RECENT
  // local run — an earlier failure that's since been fixed doesn't block.
  const latestLocalCheck = localChecks.reduce<typeof localChecks[number] | undefined>(
    (latest, c) => latest === undefined || c.startedAt > latest.startedAt ? c : latest, undefined);
  const localTestsFailing = latestLocalCheck?.status === 'failed';
  const draftCount = comments.filter(
    c => c.parentCommentId === null && c.origin === 'local' && c.publishedAt === null
      && c.resolvedAt === null && c.dismissedAt === null).length;
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
  const { activity, reviewThreads, detail: remoteDetail, refresh: refreshActivityFeed } = useGitHubActivityFeed(pr.repo, pr.remotePrNumber);
  const [currentUser, setCurrentUser] = useState<UserProfileDto | null>(
    () => getCached<UserProfileDto>('home:profile') ?? null,
  );
  useEffect(() => {
    if (currentUser !== null || typeof window === 'undefined'
      || typeof window.bridge?.getUserProfile !== 'function') return;
    let cancelled = false;
    window.bridge.getUserProfile()
      .then(profile => {
        if (cancelled) return;
        setCached('home:profile', profile);
        setCurrentUser(profile);
      })
      .catch(() => { /* actor text still falls back to the stored PR author */ });
    return () => { cancelled = true; };
  }, [currentUser]);
  const currentUserLogin = currentUser?.login ?? null;
  const threadActions: GitHubThreadActions | undefined = pr.repo === null ? undefined : {
    repo: pr.repo,
    prAuthor: pr.author,
    prHtmlUrl: pr.remotePrUrl ?? '',
    currentUserLogin,
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
  const openChanges = useCallback(() => {
    onReviewChanges?.();
    if (changesContent !== undefined) setActiveTab('changes');
  }, [changesContent, onReviewChanges]);
  const handleTabChange = useCallback((tab: PRHeaderTab) => {
    if (tab === 'changes') openChanges();
    else setActiveTab(tab);
  }, [openChanges]);
  const handledSubTabToken = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (openSubTabRequest === undefined) return;
    if (handledSubTabToken.current === openSubTabRequest.token) return;
    handledSubTabToken.current = openSubTabRequest.token;
    handleTabChange(openSubTabRequest.subTab);
    // A fresh token re-fires it; ordinary parent polling must not.
  }, [handleTabChange, openSubTabRequest]);

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
        changesInline={changesContent !== undefined}
        activeTab={activeTab}
        onTabChange={handleTabChange}
      />

      <div className={activeTab === 'changes' ? 'pr-body-scroll pr-body-scroll--changes' : 'pr-body-scroll'}>
        {activeTab === 'conversation' && (
          <PRTimeline
            pr={pr}
            events={timeline}
            comments={comments}
            commits={commits}
            onReviewChanges={onReviewChanges === undefined ? undefined : openChanges}
            onResolveThread={capabilities.draftLocalComments ? onResolveThread : undefined}
            onDismissThread={capabilities.draftLocalComments ? onDismissThread : undefined}
            onReplyThread={capabilities.draftLocalComments ? onReplyThread : undefined}
            onReplyLineThread={capabilities.draftLocalComments ? onReplyLineThread : undefined}
            onReplyFindingThread={onReplyThread}
            onReplyFindingLineThread={onReplyLineThread}
            onOpenStage={onOpenStage}
            activity={activity}
            reviewThreads={reviewThreads}
            remoteDetail={remoteDetail}
            threadActions={threadActions}
            currentUserLogin={currentUserLogin}
            reviewData={reviewData}
            onOpenReviewRound={onOpenReviewRound}
            onAnswerFinding={onAnswerFinding}
            onReviewRoundAction={onReviewRoundAction}
            onSetFindingResolved={onSetFindingResolved}
            onToggleFindingPromotion={onToggleFindingPromotion}
            canPromoteFindings={capabilities.publishReview}
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

        {activeTab === 'changes' && changesContent !== undefined && (
          <div className="pr-changes-tab">{changesContent}</div>
        )}

        {activeTab === 'conversation' && (
          <MergeBox
            pr={pr}
            capabilities={capabilities}
            localChecks={localChecks}
            remoteChecks={remoteChecks}
            remoteDetail={remoteDetail}
            openComments={openComments}
            localTestsFailing={localTestsFailing}
            pendingStripCount={bundle.pendingStripCount ?? 0}
            draftCount={draftCount}
            localReviewGate={localReviewGate}
            onPush={onPush}
            onAskAgent={onAskAgent}
            onMerge={onMerge}
            onDequeue={onDequeue}
            onDeleteBranch={onDeleteBranch}
            onPublishReview={onPublishReview}
            onDiscardDrafts={onDiscardDrafts}
            onRunTests={onRunTests}
            runTestsBusy={runTestsBusy}
            hidePublishGate={headerAction !== undefined}
          />
        )}

        {activeTab === 'conversation'
          && (capabilities.draftLocalComments || capabilities.postRemoteComment) && (
          <PRCommentComposer
            local={local}
            username={username ?? currentUserLogin ?? undefined}
            value={commentValue}
            onChange={onCommentChange}
            onSubmit={onAddComment}
          />
        )}
      </div>
    </div>
  );
}
