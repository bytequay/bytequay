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

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { privateThreadSource } from '../pulls/PrivateThread';
import { pullReviewThreads, type CommitRange, type DiffAgentComment } from '../pulls/PullChanges';
import { PullDetailHeader } from '../pulls/PullsScreen';
import ReviewCommentIndex from '../pulls/ReviewCommentIndex';
import AgentReviewTab, { type ReviewNavigationTarget } from '../pulls/agent-review/AgentReviewTab';
import { FindingsHead, shownRound } from '../pulls/agent-review/SummaryTimeline';
import type { TaskReviewOptions } from '../pulls/agent-review/TaskReviewSummary';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import { mergeReviewFindings } from '../pulls/agent-review/model';
import CodeTestsTab from '../pulls/code-tests/CodeTestsTab';
import WorktreeChanges from '../pulls/code-tests/WorktreeChanges';
import type { CodeHostRef } from '../pulls/code-tests/contracts';
import LocalReviewThreadCard from '../pulls/local-review/LocalReviewThreadCard';
import type { LocalReviewThreadDto, LocalReviewThreads } from '../pulls/local-review/types';
import { useLocalReviewThreads } from '../pulls/local-review/useLocalReviewThreads';
import { canSubmitReview } from '../pulls/model';
import type { AgentChildSummaryDto, AgentTaskDto, InlineReviewCommentDto, PullRequestDto } from '../types';
import { workspaceReviewTaskRoute } from '../workspaces/model';
import { useOutputWheel, type ApprovalDecider } from './AgentConversation';
import ConversationUtilityTab from './ConversationUtilityTab';
import type { ReviewCardFocus } from './InstructionCard';
import SubagentsPanel from './SubagentsPanel';
import { NoPullRequestView, TaskChangesView, TaskPullRequestView, type TaskPullRequestChanges, type TaskPullRequestDetail } from './TaskPullRequestPanel';
import { findConversationReview } from './agentConversations';
import { DiscussionsPanel } from './discussions/DiscussionsPanel';
import type { TaskDiscussions } from './discussions/useTaskDiscussions';
import { useReviewCardFocus } from './reviewCardNavigation';
import type { FixableComment } from './taskFix';
import { agentDisplayName, type AgentNode, type TranscriptTurn } from './transcript';

export type PanelTab = 'pr' | 'files' | 'changes' | 'review' | 'code' | 'agents' | 'discussions';
/** Panel tabs that exist only once the conversation opens them, and close on their own ×. */
export type UtilityTab = Extract<PanelTab, 'agents' | 'discussions'>;

/** The task's private review comments as the Summary's Findings, one round per pull request head they were written against. */
function LocalFindings({ local }: { local: LocalReviewThreads }): React.JSX.Element {
  const [round, setRound] = useState('last');
  const rounds = [...new Set(local.threads.map(thread => thread.headSha).filter((sha): sha is string => sha !== null))].map(headSha => ({ id: headSha, headSha }));
  const shown = shownRound(rounds, round);
  const inRound = (thread: LocalReviewThreadDto) => shown === null || thread.headSha === shown.id;
  const open = local.threads.filter(thread => !thread.resolved).length;
  return <section className="agr-local-comments" aria-label="Findings">
    <FindingsHead title="Findings" rounds={rounds} round={round} onRound={setRound} overall={`${open} open · ${local.threads.length - open} resolved`}
      count={item => local.threads.filter(thread => item === null || thread.headSha === item.id).length} />
    {shown !== null && !local.threads.some(inRound) && <p className="agr-card__empty">No findings in this round.</p>}
    {local.threads.map(thread => <div key={thread.id} hidden={!inRound(thread)}><LocalReviewThreadCard thread={thread} {...local} /></div>)}
  </section>;
}

/**
 * The design keeps the panel tabs pinned while only the selected panel body scrolls.
 *
 * The diff brings its own toolbar and scroller, so it mounts flush and fills the frame instead
 * of sitting inside a second scroller with its own padding.
 */
function PanelFrame({ label, tabs, width, flush = false, overview = false, kept = null, keptShown = false, children }: {
  label: string;
  tabs: React.JSX.Element;
  width: number;
  flush?: boolean;
  overview?: boolean;
  /**
   * A body kept mounted once visited (Tears of time, so its open files and drafts survive tab switches). It sits in the same
   * frame after the one header, so switching tabs never remounts the header or the frame the resize divider measures.
   */
  kept?: React.ReactNode;
  keptShown?: boolean;
  children?: React.ReactNode;
}): React.JSX.Element {
  const scrollRef = useRef<HTMLDivElement>(null);
  useOutputWheel(scrollRef);
  return (
    <aside className="agt-panel" aria-label={label} style={{ width }}>
      {tabs}
      {kept !== null && <div className="agt-panel__flush" hidden={!keptShown}>{kept}</div>}
      {keptShown ? null : flush
        ? <div className="agt-panel__flush">{children}</div>
        : <div className={overview ? 'pull-detail__body' : 'agt-panel__scroll'} ref={scrollRef}>{children}</div>}
    </aside>
  );
}

/**
 * The side panel: the subagents this task delegated to, with one agent's own transcript when it
 * is opened, and the pull requests on the task's branch.
 *
 * Opening an agent filters the task's existing events by its thread; it is not another route and
 * not a second subscription. Once retention has removed the transcript the agent is still listed
 * from the task's kept summary, and opening it says its activity expired rather than inventing it.
 */
export default function TaskSidePanel({
  discussions, onJumpToPassage, onHoverDiscussion, discussionTurns, discussionFocus,
  agents, openAgent, expiredChildren, deleteAfter, transcript, now, tab: requestedTab, task = null, pullRequest,
  pullRequestError, pullRequestLoading, pullRequestChecked, onRefreshPullRequests,
  pullRequestDetail, pullRequestChanges, pullRequestCommitRange, pullRequestPendingComments,
  onPullRequestCommitRangeChange, onPullRequestPendingCommentsChange, workspaceId, branch, repository,
  activityChoice, onOpenAgent, onCloseAgent, onToggleActivity, onDecide, onSelectTab, onOpenPullRequest,
  onAskAgent, onAskAgentDisabled, onFixComment, localReview, learning, reviewFocus, onCodeUnsavedChange,
  width, showAgents, utilityTabs, onCloseUtility, logChoice = null, onToggleLog = () => {}, historyUnloaded = false, onLoadEarlier = () => {}, historyLoading = false,
}: {
  agents: AgentNode[];
  historyUnloaded?: boolean;
  historyLoading?: boolean;
  onLoadEarlier?: () => void;
  openAgent: string | null;
  expiredChildren: AgentChildSummaryDto[];
  deleteAfter: string | null;
  transcript: TranscriptTurn[];
  now: number;
  tab: PanelTab;
  /** The task whose worktree Tears of time reads. */
  task?: AgentTaskDto | null;
  /** The pull request on this task's branch, or null while the branch has none. */
  pullRequest: PullRequestDto | null;
  pullRequestError: string | null;
  pullRequestLoading: boolean;
  /** Whether a lookup has already answered, so a later one refreshes under the panel it filled. */
  pullRequestChecked: boolean;
  onRefreshPullRequests?: () => void;
  pullRequestDetail: TaskPullRequestDetail;
  pullRequestChanges: TaskPullRequestChanges;
  pullRequestCommitRange: CommitRange;
  pullRequestPendingComments: InlineReviewCommentDto[];
  onPullRequestCommitRangeChange: (range: CommitRange) => void;
  onPullRequestPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  workspaceId?: string;
  /** The task's result branch and the repository a draft would target, for the empty state. */
  branch: string | null;
  repository: string | null;
  activityChoice: Record<string, boolean>;
  onOpenAgent: (threadId: string) => void;
  onCloseAgent: () => void;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide: ApprovalDecider;
  onSelectTab: (tab: PanelTab) => void;
  onOpenPullRequest?: (number: number) => void;
  onAskAgent?: (comment: DiffAgentComment) => Promise<void>;
  onAskAgentDisabled?: string | null;
  onFixComment?: (comment: FixableComment) => Promise<void>;
  localReview?: LocalReviewThreads;
  discussions?: TaskDiscussions;
  onJumpToPassage?: (target: { turnId: string; discussionId?: string }) => void;
  onHoverDiscussion?: (discussionId: string | null) => void;
  /** The answers the loaded transcript holds, so a thread's card can name the one it hangs under. */
  discussionTurns?: { turnId: string; ordinal: number; synopsis: string }[];
  /** The discussion a transcript footer asked to see, which opens its card. */
  discussionFocus?: { id: string } | null;
  /** Renders the Summary; a development task's reviewer comes with it, so it can sit in the Summary's own strip and timeline. */
  learning?: React.ReactNode | ((onSelectCommit: (sha: string) => void, review?: TaskReviewOptions) => React.ReactNode);
  reviewFocus?: ReviewCardFocus | null;
  /** How many files hold unsaved Tears of time drafts, so the screen can confirm before they are dropped. */
  onCodeUnsavedChange?: (count: number) => void;
  width: number;
  showAgents: boolean;
  /** The Subagents and Discussions tabs the conversation opened, in the order it opened them. */
  utilityTabs: UtilityTab[];
  onCloseUtility: (tab: UtilityTab) => void;
  /** The agent whose log the user opened, or "!thread" for one they folded; live agents open by default. */
  logChoice?: string | null;
  onToggleLog?: (choice: string) => void;
}): React.JSX.Element {
  const codeAvailable = workspaceId !== undefined && task !== null && task.checkoutRemovedAt === null;
  const tab = requestedTab === 'changes' && pullRequest !== null ? 'files' : requestedTab;
  // Mounted on first visit and then kept, like the PR page's Tears of time tab.
  const [codeVisited, setCodeVisited] = useState(false);
  if (tab === 'code' && !codeVisited) setCodeVisited(true);
  const [changesVisited, setChangesVisited] = useState(false);
  if (tab === 'changes' && !changesVisited) setChangesVisited(true);
  const [summaryVisited, setSummaryVisited] = useState(false);
  if (tab === 'review' && !summaryVisited) setSummaryVisited(true);
  const reviewContainer = useRef<HTMLDivElement>(null);
  useReviewCardFocus(reviewContainer, reviewFocus, tab === 'review');
  const [review, setReview] = useState<AgentReviewDto | null>(null);
  const updateReview = useCallback((value: AgentReviewDto) => setReview(current => mergeReviewFindings(current, value)), []);
  const [reviewLoading, setReviewLoading] = useState(true);
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [reviewReload, setReviewReload] = useState(0);
  const [reviewNavigation, setReviewNavigation] = useState<ReviewNavigationTarget | null>(null);
  const [focusFindingId, setFocusFindingId] = useState<string | null>(null);
  const [changesToolbar, setChangesToolbar] = useState<HTMLElement | null>(null);
  const [commitSelection, setCommitSelection] = useState<{ source: 'task' | 'review'; commitSha: string; headSha?: string; baseSha?: string; token: number } | null>(null);
  const selectCommit = (source: 'task' | 'review', commitSha: string, snapshot?: { headSha: string; baseSha: string }): void => {
    setReviewNavigation(null);
    if (pullRequest !== null) {
      setCommitSelection(null);
      pullRequestChanges.selectCommit(commitSha);
      onSelectTab('files');
      return;
    }
    setCommitSelection(current => ({ source, commitSha, ...snapshot, token: (current?.token ?? 0) + 1 }));
    onSelectTab('changes');
  };
  const navigateToChanges = (target: ReviewNavigationTarget): void => {
    setCommitSelection(null);
    setReviewNavigation(target);
    setFocusFindingId(null);
    if (pullRequest !== null) onPullRequestCommitRangeChange(null);
    onSelectTab(pullRequest === null ? 'changes' : 'files');
  };
  const showReviewedChanges = review !== null && (!codeAvailable || reviewNavigation !== null || commitSelection?.source === 'review');
  const changesHost: CodeHostRef | null = showReviewedChanges ? { kind: 'review', reviewId: review.id }
    : codeAvailable ? { kind: 'task', taskId: task.id, workspaceId } : null;
  const prRepo = pullRequest?.repo;
  const prNumber = pullRequest?.number;
  const prLocalReview = useLocalReviewThreads(prRepo === undefined || prNumber === undefined ? null : {
    repo: prRepo, number: prNumber, workspaceId,
  });
  // The task hook accepts new comments immediately; the PR hook adds discussions from other tasks.
  const localSources = new Map<string, { thread: LocalReviewThreadDto; actions: LocalReviewThreads }>();
  for (const source of [localReview, prLocalReview]) {
    if (source === undefined) continue;
    for (const thread of source.threads) {
      if (thread.repo.toLowerCase() === prRepo?.toLowerCase() && thread.number === prNumber
        && (localSources.get(thread.id)?.thread.version ?? -1) < thread.version) {
        localSources.set(thread.id, { thread, actions: source });
      }
    }
  }
  const localThreads: LocalReviewThreads = {
    threads: [...localSources.values()].map(value => value.thread).sort((left, right) => left.createdAt.localeCompare(right.createdAt)),
    viewer: localReview?.viewer ?? prLocalReview.viewer,
    // Replies go to this task's agent, so they wait for what the task waits for, such as a running follow-up.
    disabledReason: localReview?.disabledReason,
    learningDisabledReason: localReview?.learningDisabledReason,
    onReply: (id, body) => (localSources.get(id)?.actions ?? prLocalReview).onReply(id, body),
    onResolve: (id, resolved) => (localSources.get(id)?.actions ?? prLocalReview).onResolve(id, resolved),
    onRetry: (id, messageId) => (localSources.get(id)?.actions ?? prLocalReview).onRetry(id, messageId),
  };
  const reviewIdentity = `${workspaceId ?? ''}:${task?.id ?? `${prRepo ?? ''}#${prNumber ?? ''}`}`;
  const loadedReviewIdentity = useRef(reviewIdentity);
  useEffect(() => {
    if (loadedReviewIdentity.current !== reviewIdentity) setReview(null);
    loadedReviewIdentity.current = reviewIdentity;
    setReviewError(null);
    setReviewLoading(true);
    setReviewNavigation(null);
    setFocusFindingId(null);
    if (workspaceId === undefined || (!task?.interactive && (prRepo === undefined || prNumber === undefined))) {
      setReviewLoading(false);
      return;
    }
    let active = true;
    void findConversationReview(window.bridge, workspaceId, task?.interactive ? task.id : undefined,
      prRepo === undefined || prNumber === undefined ? undefined : { repo: prRepo, number: prNumber })
      .then(value => { if (active) setReview(value); })
      .catch(failure => { if (active) setReviewError(failure instanceof Error ? failure.message : 'Unable to load review'); })
      .finally(() => { if (active) setReviewLoading(false); });
    return () => { active = false; };
  }, [workspaceId, prRepo, prNumber, reviewReload, task?.id, task?.interactive, reviewIdentity]);
  // This task's agent replies on findings from its own turns, which the review's events never report. Re-reading the review whenever the
  // active turn changes shows a finished turn's replies, also when a queued turn starts right behind it.
  const shownReview = useRef(review);
  shownReview.current = review;
  const activeTurn = task?.mutationClaim?.kind === 'TURN' ? task.mutationClaim.ownerId : null;
  useEffect(() => {
    const shown = shownReview.current;
    if (shown === null) return;
    void Promise.resolve().then(() => window.bridge.getAgentReview(shown.id))
      .then(value => setReview(current => (current === shown ? value : current)), () => {});
  }, [activeTurn]);
  const privateThreads = discussions === undefined || pullRequest === null || task?.interactive !== true ? undefined
    : { ...privateThreadSource(discussions, pullRequest, undefined, workspaceId), fix: onFixComment, fixDisabledReason: onAskAgentDisabled };
  const agentCount = agents.length + expiredChildren.length;
  const shownUtilities = utilityTabs.filter(utility => (utility === 'agents' ? showAgents : discussions !== undefined));
  const agentTab = shownUtilities.map(utility => <ConversationUtilityTab key={utility} tab={utility}
    count={utility === 'agents' ? agentCount : discussions?.discussions.length ?? 0} active={tab === utility}
    onSelect={() => onSelectTab(utility)} onClose={() => onCloseUtility(utility)} />);
  const tabs = <PullDetailHeader
      pr={pullRequest}
      detail={pullRequestDetail.detail}
      tab={tab === 'pr' ? 'overview' : tab === 'agents' || tab === 'discussions' ? null : tab}
      codeAvailable={codeAvailable}
      onChangesToolbar={setChangesToolbar}
      review={review}
      onReviewChange={updateReview}
      agentTab={agentTab}
      onNavigate={route => { window.location.hash = route; }}
      agent="developer"
      developerTask={task === null || workspaceId === undefined ? null : { workspaceId, taskId: task.id }}
      onTab={tab => onSelectTab(tab === 'overview' ? 'pr' : tab)}
      onRefresh={pullRequestDetail.refresh}
      refreshing={pullRequestDetail.loading}
      onReviewSubmitted={pullRequestDetail.replace}
      onDetailUpdate={pullRequestDetail.update}
      pendingComments={pullRequestPendingComments}
      onPendingCommentsChange={onPullRequestPendingCommentsChange}
      onZoom={onOpenPullRequest === undefined || pullRequest === null
        ? undefined
        : () => onOpenPullRequest(pullRequest.number)}
    />;
  const pullRequestNotice = pullRequestError !== null
    ? <div className="agt-note" role="status">
        Could not refresh this task&apos;s pull request. {pullRequestError}{' '}
        {onRefreshPullRequests !== undefined && <button
          type="button" className="agt-action" disabled={pullRequestLoading} onClick={onRefreshPullRequests}
        >{pullRequestLoading ? 'Retrying…' : 'Retry'}</button>}
      </div>
    : pullRequest === null && pullRequestLoading && !pullRequestChecked
      ? <div className="agt-muted agt-panel__empty" role="status">Checking for a pull request…</div>
      : null;
  const detail = pullRequestDetail.detail;
  const codeBody = !codeAvailable && tab === 'code' ? <div className="agt-panel__empty">The task&apos;s worktree is unavailable.</div>
    : codeAvailable && (codeVisited || tab === 'code') ? (
    <div className="pull-detail__code" hidden={tab !== 'code'}>
      <CodeTestsTab
        detail={detail ?? undefined}
        workspaceId={workspaceId}
        review={null}
        task={task}
        onReviewChange={() => {}}
        active={tab === 'code'}
        ownReviewStream={false}
        onUnsavedChange={onCodeUnsavedChange}
        comments={detail === null || pullRequest === null ? undefined : {
          repo: pullRequest.repo,
          number: pullRequest.number,
          workspaceId,
          canReview: canSubmitReview(pullRequest, detail),
          threads: pullReviewThreads(pullRequest, detail, workspaceId, pullRequestDetail.refresh, pullRequestDetail.update),
          viewer: null,
          pending: pullRequestPendingComments,
          onPendingChange: onPullRequestPendingCommentsChange,
          onDetailChange: pullRequestDetail.replace,
          localReview: localThreads,
          onAskAgent: onAskAgentDisabled == null ? onAskAgent : undefined,
        }} />
    </div>
  ) : null;
  const taskReview: TaskReviewOptions | undefined = task !== null && task.interactive && workspaceId !== undefined ? {
    workspaceId, active: tab === 'review', onReviewChange: setReview, onSelectCommit: (sha, snapshot) => selectCommit('review', sha, snapshot),
    focusFindingId, focusReviewCard: reviewFocus, onNavigateToChanges: navigateToChanges,
  } : undefined;
  const summaryBody = summaryVisited ? <ReviewCommentIndex className="agr-development-learning" ref={reviewContainer} hidden={tab !== 'review'}>
    {(typeof learning === 'function' ? learning(sha => selectCommit('task', sha), taskReview) : learning) ?? <p className="agr-card__empty">After a turn changes code or commits, the agent will refine the summary here automatically.</p>}
    {localThreads.threads.length > 0 && <LocalFindings local={localThreads} />}
    {taskReview !== undefined ? null
      : pullRequest !== null && reviewLoading ? <div className="agt-panel__empty" role="status">Loading review cards…</div>
      : reviewError !== null ? <div className="agt-panel__empty" role="alert">{reviewError}
        <button type="button" className="agt-action" onClick={() => setReviewReload(value => value + 1)}>Retry</button>
      </div>
      : review !== null && <AgentReviewTab detail={detail ?? undefined} workspaceId={workspaceId} viewer={null}
        review={review} onReviewChange={updateReview} onSelectCommit={(sha, snapshot) => selectCommit('review', sha, snapshot)} focusFindingId={focusFindingId} ownStream={tab === 'review'}
        onOpenWorkspace={() => { window.location.hash = workspaceReviewTaskRoute(review.workspaceId, review.id); }}
        onNavigateToChanges={navigateToChanges} />}
  </ReviewCommentIndex> : null;
  const changesBody = pullRequest === null && changesVisited ? <div className="pull-detail__changes" hidden={tab !== 'changes'}>
    {changesHost !== null ? <WorktreeChanges key={changesHost.kind === 'review' ? changesHost.reviewId : changesHost.taskId} selection={commitSelection}
        reviewNavigation={reviewNavigation} review={review} onReviewChange={updateReview}
        onOpenInReview={findingId => { setFocusFindingId(findingId); onSelectTab('review'); }}
        host={changesHost} active={tab === 'changes'} toolbarTarget={changesToolbar} />
    : <div className="agt-panel__empty">The task&apos;s worktree is unavailable.</div>}
  </div> : null;
  const code = { kept: <>{codeBody}{summaryBody}{changesBody}</>, keptShown: tab === 'code' || tab === 'review' || tab === 'changes' };
  if (tab === 'discussions' && discussions !== undefined) {
    return <PanelFrame label="Discussions" tabs={tabs} width={width} {...code}>
      <DiscussionsPanel discussions={discussions} repository={repository} turns={discussionTurns} focus={discussionFocus}
        onJump={onJumpToPassage} onHover={onHoverDiscussion} />
    </PanelFrame>;
  }
  if (tab === 'code' || tab === 'review' || tab === 'changes') return <PanelFrame
    label={tab === 'code' ? 'Tears of time' : tab === 'review' ? 'Summary' : 'Changes'} tabs={tabs} width={width} flush {...code} />;
  if (tab === 'pr') {
    return (
      <PanelFrame label={pullRequest === null ? 'Pull request' : `Pull request #${pullRequest.number}`} tabs={tabs} width={width} overview={pullRequest !== null} {...code}>
        {pullRequestNotice}
        {pullRequest === null
          ? pullRequestNotice === null && <NoPullRequestView branch={branch} repository={repository} onCheck={onRefreshPullRequests} checking={pullRequestLoading} />
          : (
            <TaskPullRequestView localReview={localThreads} privateThreads={privateThreads}
              key={`${pullRequest.repo.toLowerCase()}#${pullRequest.number}`}
              pr={pullRequest}
              workspaceId={workspaceId}
              detail={pullRequestDetail.detail}
              loading={pullRequestDetail.loading}
              error={pullRequestDetail.error}
              onRefresh={pullRequestDetail.refresh}
              onDetailUpdate={pullRequestDetail.update}
              onDetailChange={pullRequestDetail.replace}
            />
          )}
      </PanelFrame>
    );
  }
  if (tab === 'files') {
    return (
      <PanelFrame label="Changes" tabs={tabs} width={width} flush={pullRequest !== null} {...code}>
        {pullRequestNotice}
        {(pullRequest !== null || pullRequestNotice === null) && <TaskChangesView
          pr={pullRequest}
          detail={pullRequestDetail.detail}
          changes={pullRequestChanges}
          toolbarTarget={changesToolbar}
          workspaceId={workspaceId}
          commitRange={pullRequestCommitRange}
          pendingComments={pullRequestPendingComments}
          localReview={localThreads}
          agentReview={review === null ? undefined : { review, viewer: null, navigation: reviewNavigation,
            onReviewChange: setReview, onOpenInReview: findingId => { setFocusFindingId(findingId); onSelectTab('review'); } }}
          onAskAgent={onAskAgent}
          onAskAgentDisabled={onAskAgentDisabled}
          onCommitRangeChange={onPullRequestCommitRangeChange}
          onPendingCommentsChange={onPullRequestPendingCommentsChange}
          onRefresh={pullRequestDetail.refresh}
          onDetailUpdate={pullRequestDetail.update}
          onDetailChange={pullRequestDetail.replace}
        />}
      </PanelFrame>
    );
  }
  const agentLabel = agents.find(agent => agent.threadId === openAgent)?.label
    ?? expiredChildren.find(agent => agent.threadId === openAgent)?.label ?? 'Agent';
  return <PanelFrame label={openAgent === null ? 'Subagents' : `Agent: ${agentDisplayName(agentLabel)}`} tabs={tabs} width={width} {...code}>
    <SubagentsPanel agents={agents} openAgent={openAgent} expiredChildren={expiredChildren} deleteAfter={deleteAfter}
      transcript={transcript} now={now} repository={repository} activityChoice={activityChoice}
      onOpenAgent={onOpenAgent} onCloseAgent={onCloseAgent} onToggleActivity={onToggleActivity} onDecide={onDecide}
      logChoice={logChoice} onToggleLog={onToggleLog} historyUnloaded={historyUnloaded}
      onLoadEarlier={onLoadEarlier} historyLoading={historyLoading} />
  </PanelFrame>;
}
