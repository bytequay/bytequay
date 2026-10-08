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
import { usePreparedAgentInput } from '../../agents/preparedInput';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { CircleAlert, File, GitCommitHorizontal, PanelsTopLeft } from 'lucide-react';
import type { ReviewCardFocus, ReviewRequestTarget } from '../../agents/ReviewRequestCard';
import { useReviewCardFocus } from '../../agents/reviewCardNavigation';
import { ConfirmDialog } from '../../ConfirmDialog';
import AddRepoModal from '../../AddRepoModal';
import { workspaceRoute } from '../../workspaces/model';
import {
  agentTaskReducer,
  historyAfter,
  historyBefore,
  initialAgentTaskState,
  readRecentAgentHistory,
  startAgentConversationSession,
  type AgentTaskAction,
} from '../../agents/model';
import type {
  AgentApprovalDecision,
  AgentPendingApprovalDto,
  AgentRuntimeStopImpactDto,
  Bridge,
  ModelOptionDto,
  PullRequestDetailDto,
  UserProfileDto,
} from '../../types';
import { remoteErrorMessage } from '../PullOverview';
import MarkdownBody from '../MarkdownBody';
import LocalReviewThreadCard from '../local-review/LocalReviewThreadCard';
import type { LocalReviewThreads } from '../local-review/types';
import ReviewActivity, { type ReviewAgentPanel } from './ReviewActivity';
import ReviewFindingCard from './ReviewFindingCard';
import ReviewDetails from './ReviewDetails';
import { recordReviewAttentionAction, useFindingActions } from './useFindingActions';
import ReviewSummaryCard from './ReviewSummaryCard';
import { findingLines } from './contracts';
import type { AgentReviewDto, ReviewFindingDto } from './contracts';
import { explanationRun, findingInResults, isExplanationRun, latestRun, newCommits, openFindings, provisional, reviewElapsed, reviewProgress, reviewRecoveryRequired, reviewRunning, reviewStatus, reviewWorktreeNotice, shortSha } from './model';

export type ReviewNavigationTarget = {
  path: string;
  side: 'LEFT' | 'RIGHT';
  startLine: number;
  endLine: number;
  findingId: string;
  /** Bumped per request, so asking for the same range twice still scrolls. */
  token: number;
};

type PendingAction = 'resume' | 'refresh' | 'other';

/** A placeholder for the hook's contract while no review exists; its actions are never reachable. */
const EMPTY_REVIEW = {
  id: '',
  repo: '',
  number: 0,
  draft: null,
} as unknown as AgentReviewDto;

export default function AgentReviewTab({
  detail = null,
  workspaceId,
  viewer,
  review,
  onReviewChange,
  onNavigateToChanges,
  focusFindingId, focusReviewCard, onOpenReview,
  mode = 'cards',
  timeline = false,
  ownStream = true,
  onOpenWorkspace,
  onRepositoryWatched,
  localReview,
  onSelectCommit,
  toolbarTarget,
  agentPanel,
}: {
  detail?: PullRequestDetailDto | null;
  /** The workspace whose managed clone the review runs in; without one there is nothing to check out. */
  workspaceId: string | undefined;
  viewer: UserProfileDto | null;
  review: AgentReviewDto | null;
  onReviewChange: (review: AgentReviewDto) => void;
  onNavigateToChanges: (target: ReviewNavigationTarget) => void;
  /** A finding the Changes tab asked to show; the card is opened and scrolled to. */
  focusFindingId: string | null;
  focusReviewCard?: ReviewCardFocus | null;
  onOpenReview?: (target: ReviewRequestTarget) => void;
  toolbarTarget?: HTMLElement | null;
  agentPanel?: ReviewAgentPanel;
  mode?: 'cards' | 'conversation';
  /**
   * Cards on a development task's Summary timeline: the page's own strip and review event carry the status and actions,
   * and findings follow directly. The reviewer's own summary and details stay in its workspace, because the developer's
   * summary already explains the change.
   */
  timeline?: boolean;
  /**
   * False for cards beside this review's conversation. Main keeps one stream per renderer and review,
   * so a second subscription would silently stop the conversation's; its reloads keep the shared review current.
   */
  ownStream?: boolean;
  onOpenWorkspace?: () => void;
  onRepositoryWatched?: () => void;
  localReview?: LocalReviewThreads;
  onSelectCommit?: (sha: string, snapshot?: { headSha: string; baseSha: string }) => void;
}) {
  const [starting, setStarting] = useState(false);
  const [watchRepositoryId, setWatchRepositoryId] = useState<number | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const busy = pendingAction !== null;
  const resuming = pendingAction === 'resume';
  const [error, setError] = useState<string | null>(null);
  const [recoveryConfirmation, setRecoveryConfirmation] = useState<{ reviewId: string; impact: AgentRuntimeStopImpactDto } | null>(null);
  const recoveryPending = useRef(false);
  const recoveryRequest = useRef(0);
  const [conversation, setConversation] = useState(initialAgentTaskState);
  const [reviewSequence, setReviewSequence] = useState(0);
  const conversationRef = useRef(conversation);
  conversationRef.current = conversation;
  const followingHistory = useRef(true);
  const historyRequestRef = useRef<symbol | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const currentReviewId = useRef(review?.id ?? null);
  currentReviewId.current = review?.id ?? null;
  const currentTaskId = useRef(review?.task.id ?? null);
  currentTaskId.current = review?.task.id ?? null;
  const projectionRevision = useRef(0);
  const { events, turns } = conversation;
  const [approvals, setApprovals] = useState<AgentPendingApprovalDto[]>([]);
  const [composer, setComposer] = useState('');
  usePreparedAgentInput(mode === 'conversation' ? review?.task.id ?? null : null, text => setComposer(current => [current, text].filter(Boolean).join('\n\n')));
  const [models, setModels] = useState<ModelOptionDto[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const navigationToken = useRef(0);
  const cardsRef = useRef<HTMLDivElement>(null);
  const [findingFilter, setFindingFilter] = useState<'All' | 'Open' | 'Resolved'>('All');
  const [reportRunId, setReportRunId] = useState<string | null>(null);
  useEffect(() => {
    if (mode === 'cards' && !timeline && focusReviewCard?.target.kind === 'card') setReportRunId(focusReviewCard.target.runId);
    if (focusReviewCard?.target.kind === 'finding') setFindingFilter('All');
  }, [focusReviewCard, mode, timeline]);
  useReviewCardFocus(cardsRef, focusReviewCard, mode === 'cards' && review !== null
    && (focusReviewCard?.target.kind !== 'finding' || findingFilter === 'All'));
  const running = review !== null && reviewRunning(review);
  const recovery = review !== null && reviewRecoveryRequired(review);
  // A Code & Tests run holds the working copy; agent work and head moves wait for it.
  const localRun = review?.state === 'OPEN' && review.task.mutationClaim === 'LOCAL_RUN';
  const localRunReason = localRun ? 'A run is using this working copy' : undefined;
  // The wait is a durable task fact; the fetched list only fills in the card, and a slow read
  // must not make a blocked run look like it is still working.
  const waiting = (review?.pendingApprovals ?? 0) > 0 || approvals.length > 0;

  const status: ReturnType<typeof reviewStatus> | null = review === null ? null
    : resuming && !running
      ? { status: 'running', label: 'Reviewing', tone: 'running', detail: 'starting the review', chip: null, busy: true }
      : reviewStatus(review);
  const progress = review === null ? null : reviewProgress(review);
  const compactProgress = review === null ? null : reviewProgress(review, true);
  const currentRun = review === null ? null : latestRun(review);
  const worktreeNotice = review === null ? null : reviewWorktreeNotice(review);
  const run = review?.runs.find(item => item.id === reportRunId) ?? currentRun;
  const currentExplanation = review === null ? null : explanationRun(review);
  const summaryRun = reportRunId !== null && run?.summary != null && (!isExplanationRun(run) || run.state === 'ACCEPTED') ? run : currentExplanation ?? currentRun;
  const findings = review === null ? [] : openFindings(review);
  const withdrawn = review === null ? [] : review.findings.filter(finding => finding.state === 'WITHDRAWN' && findingInResults(review, finding));

  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running]);

  // The catalog names the model each turn ran with; the transcript shows a name, never a raw slug.
  const laneId = review?.task.laneId ?? null;
  useEffect(() => {
    if (laneId === null || mode !== 'conversation') return;
    let live = true;
    void window.bridge.getModelSettings()
      .then(catalog => {
        if (live) setModels(catalog.providers.find(provider => provider.id === laneId)?.models ?? []);
      })
      .catch(() => {});
    return () => { live = false; };
  }, [laneId, mode]);

  const dispatchConversation = useCallback((action: AgentTaskAction) => {
    setConversation(current => agentTaskReducer(current, action));
  }, []);

  const refresh = useCallback(async (reviewId: string, turnId: string | null = null, reloadHistory = false, sequence = 0) => {
    const taskId = currentTaskId.current;
    const revision = ++projectionRevision.current;
    const history = !reloadHistory || mode !== 'conversation' || taskId === null || historyAfter(conversationRef.current) !== null
      ? null : await readRecentAgentHistory({ readAgentHistory: (id, before, after) => window.bridge.readAgentReviewHistory(reviewId, id, before, after) }, taskId);
    const observedSequence = Math.max(sequence, history?.page.latestSequence ?? conversationRef.current.lastSequence);
    // Read the review after history so every exposed input has its saved presentation available.
    const current = () => currentReviewId.current === reviewId && projectionRevision.current === revision;
    const [updated, turn] = await Promise.all([
      window.bridge.getAgentReview(reviewId).then(updated => {
        if (!current()) return null;
        if (history !== null && historyAfter(conversationRef.current) === null) dispatchConversation({ type: 'history/loaded', ...history, direction: 'latest' });
        // Turn details can wait for provider startup; the request card must not.
        onReviewChange(updated);
        setReviewSequence(previous => Math.max(previous, observedSequence));
        return updated;
      }),
      turnId === null || mode !== 'conversation' ? Promise.resolve(null) : window.bridge.getAgentTurn(turnId),
    ]);
    if (!current()) return null;
    if (turn !== null) dispatchConversation({ type: 'turn/changed', turn });
    return updated;
  }, [dispatchConversation, mode, onReviewChange]);

  const run_ = useCallback(async (action: () => Promise<void>, pending: PendingAction = 'other', propagate = false) => {
    projectionRevision.current++;
    setPendingAction(pending);
    setError(null);
    try {
      await action();
    }
    catch (failure) {
      // A turn can finish before its stream update reaches this view. Keep the draft and original error,
      // but adopt the durable state so the next deliberate action does not repeat the same stale command.
      const reviewId = currentReviewId.current;
      if (reviewId !== null) await refresh(reviewId, conversationRef.current.latestTurnId, true).catch(() => {});
      if (pending !== 'refresh') setError(remoteErrorMessage(failure));
      if (propagate) throw failure;
    }
    finally {
      projectionRevision.current++;
      setPendingAction(null);
    }
  }, [refresh]);

  const reviewId = review?.id ?? null;
  const taskId = review?.task.id ?? null;
  const reviewOpen = review?.state === 'OPEN';
  useEffect(() => {
    const requests = recoveryRequest;
    setRecoveryConfirmation(null);
    return () => { requests.current++; };
  }, [reviewId]);

  const requestRuntimeRecovery = (): void => {
    if (reviewId === null || taskId === null || recoveryPending.current) return;
    recoveryPending.current = true;
    const request = recoveryRequest.current;
    void run_(async () => {
      const impact = await window.bridge.getAgentReviewRuntimeStopImpact(reviewId);
      if (recoveryRequest.current === request && currentReviewId.current === reviewId && currentTaskId.current === taskId) {
        if (impact.taskId !== taskId) throw new Error('The recovery response belongs to a different task. Refresh this review and try again.');
        setRecoveryConfirmation({ reviewId, impact });
      }
    }).finally(() => { recoveryPending.current = false; });
  };

  const confirmRuntimeRecovery = (): void => {
    if (recoveryConfirmation === null || recoveryConfirmation.reviewId !== reviewId || recoveryPending.current) return;
    recoveryPending.current = true;
    const confirmed = recoveryConfirmation;
    const request = recoveryRequest.current;
    void run_(async () => {
      const updated = await window.bridge.stopAgentReviewRuntime(confirmed.reviewId, confirmed.impact.generation);
      recordReviewAttentionAction(updated, 'update');
      if (recoveryRequest.current === request && currentReviewId.current === confirmed.reviewId) onReviewChange(updated);
    }).finally(() => {
      recoveryPending.current = false;
      setRecoveryConfirmation(null);
    });
  };

  const conversationBridge = useMemo<Pick<Bridge, 'readAgentHistory' | 'subscribeAgentTask'> | null>(() => reviewId === null ? null : ({
    readAgentHistory: (requestedTaskId, before, after) => window.bridge.readAgentReviewHistory(reviewId, requestedTaskId, before, after),
    subscribeAgentTask: (_requestedTaskId, after, listener, statusOnly) => window.bridge.subscribeAgentReview(reviewId, after, listener, statusOnly),
  }), [reviewId]);

  // Review ownership stays at the bridge; history, follow mode and replay use the task conversation session.
  useEffect(() => {
    const historyRequests = historyRequestRef;
    historyRequests.current = null;
    setHistoryLoading(false);
    if (reviewId === null || taskId === null || conversationBridge === null) return;
    let live = true;
    const revisions = projectionRevision;
    let settlingRefresh: ReturnType<typeof setTimeout> | undefined;
    setConversation(initialAgentTaskState());
    setReviewSequence(0);
    followingHistory.current = true;
    let reloading = false;
    let nextRefresh: { turnId: string | null; sequence: number } | null = null;
    const reload = async (turnId: string | null, sequence = 0) => {
      nextRefresh = { turnId, sequence };
      if (reloading) return;
      reloading = true;
      while (live && nextRefresh !== null) {
        const next = nextRefresh;
        nextRefresh = null;
        try {
          const updated = await refresh(reviewId, next.turnId, false, next.sequence);
          // The review observer settles after the task event. One follow-up covers a read that reached it first.
          if (live && updated !== null && updated.activeTurn === null && updated.task.mutationClaim === null && updated.runs.at(-1)?.state === 'RUNNING') {
            if (settlingRefresh !== undefined) clearTimeout(settlingRefresh);
            settlingRefresh = setTimeout(() => { if (live) void refresh(reviewId, next.turnId).catch(() => {}); }, 200);
          }
        }
        catch (failure) {
          if (live) setError(remoteErrorMessage(failure));
        }
      }
      reloading = false;
    };
    const session = mode === 'cards'
      ? reviewOpen && ownStream ? window.bridge.subscribeAgentReview(reviewId, 0, message => {
        if (!live) return;
        if (message.kind === 'changed') void reload(message.turnId);
        else if (message.kind === 'events') void reload(message.events.at(-1)?.turnId ?? null);
        else if (message.state === 'open') void reload(null);
      }, true) : Promise.resolve(() => {})
      : startAgentConversationSession(conversationBridge, taskId, action => {
      if (!live) return;
      dispatchConversation(action);
      // The shared session coalesces stream notifications into history reads.
      if (action.type === 'history/loaded') void reload(action.page.events.at(-1)?.turnId ?? null, action.page.latestSequence);
      else if (historyAfter(conversationRef.current) !== null) {
        if (action.type === 'live/changed') void reload(action.turnId, action.sequence);
        if (action.type === 'events/observed') void reload(action.events.at(-1)?.turnId ?? null, action.events.at(-1)?.sequence ?? 0);
      }
    }, () => live, () => historyAfter(conversationRef.current) === null, reviewOpen);
    session.catch(failure => { if (live) setError(remoteErrorMessage(failure)); });
    return () => {
      live = false;
      revisions.current++;
      historyRequests.current = null;
      if (settlingRefresh !== undefined) clearTimeout(settlingRefresh);
      void session.then(stop => stop(), () => {});
    };
  }, [conversationBridge, dispatchConversation, mode, ownStream, refresh, reviewId, reviewOpen, taskId]);

  const loadHistory = useCallback(async (direction: 'older' | 'newer' | 'latest') => {
    if (conversationBridge === null || taskId === null || historyRequestRef.current !== null) return;
    const before = direction === 'older' ? historyBefore(conversationRef.current) : null;
    const after = direction === 'newer' ? historyAfter(conversationRef.current) : null;
    if ((direction === 'older' && before === null) || (direction === 'newer' && after === null)) return;
    const request = Symbol();
    historyRequestRef.current = request;
    const current = () => historyRequestRef.current === request && currentReviewId.current === reviewId && currentTaskId.current === taskId;
    setHistoryLoading(true);
    followingHistory.current = direction === 'latest';
    try {
      const history = direction === 'latest'
        ? await readRecentAgentHistory(conversationBridge, taskId)
        : { page: await conversationBridge.readAgentHistory(taskId, before ?? undefined, after ?? undefined) };
      if (current() && reviewId !== null) await refresh(reviewId, history.page.events.at(-1)?.turnId ?? null, false, history.page.latestSequence);
      if (current()) dispatchConversation({ type: 'history/loaded', ...history, direction });
    }
    catch (failure) {
      if (current()) setError(remoteErrorMessage(failure));
    }
    finally {
      if (current()) {
        historyRequestRef.current = null;
        setHistoryLoading(false);
      }
    }
  }, [conversationBridge, dispatchConversation, refresh, reviewId, taskId]);

  // Turn settings are what each message ran with, and only the durable turn records them.
  const turnIds = useMemo(
    () => [...new Set(events.map(event => event.turnId).filter((id): id is string => id !== null))],
    [events]);
  const unloaded = turnIds.filter(id => turns[id] === undefined).join(',');
  useEffect(() => {
    if (unloaded === '') return;
    let live = true;
    void Promise.all(unloaded.split(',').map(id => window.bridge.getAgentTurn(id)))
      .then(loaded => {
        if (!live) return;
        loaded.forEach(turn => dispatchConversation({ type: 'turn/loaded', turn }));
      })
      .catch(() => {});
    return () => { live = false; };
  }, [dispatchConversation, unloaded]);

  const activeTurnId = review?.activeTurn?.id ?? null;
  // A missed stream update must not leave a finished review offering Steer and Run next forever.
  useEffect(() => {
    if (reviewId === null || !reviewOpen || (!running && !recovery) || busy || (mode === 'cards' && !ownStream)) return;
    let live = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try { await refresh(reviewId, activeTurnId, true); }
      catch { /* Stream and action errors already surface failures; the next status check can retry. */ }
      finally { if (live) timer = setTimeout(() => { void poll(); }, 5000); }
    };
    timer = setTimeout(() => { void poll(); }, 5000);
    return () => { live = false; clearTimeout(timer); };
  }, [activeTurnId, busy, mode, ownStream, recovery, refresh, reviewId, reviewOpen, running]);

  useEffect(() => {
    if (reviewId === null || taskId === null || activeTurnId === null || mode !== 'conversation') {
      setApprovals([]);
      return;
    }
    let live = true;
    void window.bridge.readAgentReviewApprovals(reviewId, taskId, activeTurnId)
      .then(pending => { if (live) setApprovals(pending); })
      .catch(() => { if (live) setApprovals([]); });
    return () => { live = false; };
  }, [reviewId, taskId, activeTurnId, conversation.lastSequence, mode]);

  useEffect(() => {
    if (focusFindingId === null) return;
    setFindingFilter('All');
    const frame = requestAnimationFrame(() => scrollToFinding(focusFindingId));
    return () => cancelAnimationFrame(frame);
  }, [focusFindingId]);

  const start = () => {
    if (workspaceId === undefined || detail === null) return;
    setStarting(true);
    setError(null);
    void window.bridge.startAgentReview(workspaceId, detail.repo, detail.number)
      .then(updated => { onReviewChange(updated); recordReviewAttentionAction(updated, 'prompt'); })
      .catch(failure => setError(remoteErrorMessage(failure)))
      .finally(() => setStarting(false));
  };

  const navigate = useCallback((finding: ReviewFindingDto) => {
    // A finding about the whole pull request has no lines to show in Changes.
    const lines = findingLines(finding);
    if (lines === null) return;
    navigationToken.current += 1;
    onNavigateToChanges({
      path: lines.path,
      side: lines.side,
      startLine: lines.startLine,
      endLine: lines.endLine,
      findingId: finding.id,
      token: navigationToken.current,
    });
  }, [onNavigateToChanges]);

  const findingActions = useFindingActions({
    review: review ?? EMPTY_REVIEW,
    onReviewChange,
    onNavigate: navigate,
  });
  const actions = findingActions.actions;

  if (workspaceId === undefined) {
    const repository = detail?.repo ?? review?.repo;
    return (
      <>
        <div className="agr-empty">
          <div className="agr-empty__title">Agent review needs a watched repository</div>
          <p>
            Watch {repository ?? 'this repository'} to start a private review. ByteQuay uses that workspace&apos;s managed
            worktree, and the review is available here or from the workspace.
          </p>
          {repository && <div className="agr-empty__actions">
            <button type="button" className="agr-action is-primary" disabled={busy} onClick={() => { void run_(async () => {
              const repositories = await window.bridge.searchRepositories(repository, 30);
              const match = repositories.find(candidate => candidate.fullName.toLowerCase() === repository.toLowerCase());
              if (!match) throw new Error(`Could not find ${repository}. Check your GitHub access and try again.`);
              setWatchRepositoryId(match.id);
            }); }}>
              {busy ? 'Loading repository…' : 'Watch repository'}
            </button>
          </div>}
          {error !== null && <div className="agr-error" role="alert">{error}</div>}
        </div>
        {watchRepositoryId !== null && <AddRepoModal
          initialRepositoryId={watchRepositoryId}
          onAdded={() => onRepositoryWatched?.()}
          onOpenWorkspace={id => { window.location.hash = workspaceRoute(id); }}
          onClose={() => setWatchRepositoryId(null)}
        />}
      </>
    );
  }

  if (review === null && detail === null) return null;
  if (review === null && detail !== null) {
    const commits = detail.recentActivity.filter(activity => activity.eventType === 'committed').length;
    return (
      <div className="agr-summary">
      <LocalComments localReview={localReview} />
      <div className="agr-empty">
        <svg width="26" height="26" viewBox="0 0 16 16" fill="none" aria-hidden="true" className="agr-empty__icon">
          <path d="M2.5 3.5h11v7h-6l-3 2.5v-2.5h-2z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
          <path d="M5.5 6.5h5M5.5 8.5h3" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
        </svg>
        <div className="agr-empty__title">No agent review yet</div>
        <p>
          ByteQuay checks out this pull request&apos;s exact head into a managed worktree and starts a private review
          task. Nothing is posted to GitHub — you decide what, if anything, becomes a comment.
        </p>
        <div className="agr-empty__actions">
          <button type="button" className="agr-action is-primary" disabled={starting} onClick={start}>
            {starting ? 'Starting…' : 'Start review'}
          </button>
          <span className="agr-empty__scope">
            {detail.changedFiles} {detail.changedFiles === 1 ? 'file' : 'files'}
            {commits > 0 ? `, ${commits} ${commits === 1 ? 'commit' : 'commits'}` : ''}
          </span>
        </div>
        {error !== null && <div className="agr-error" role="alert">{error}</div>}
        <div className="agr-empty__note">
          Private review is available on your own and closed pull requests too. Publishing comments still follows the
          pull request&apos;s normal review permissions.
        </div>
      </div>
      </div>
    );
  }

  if (review === null) return null;

  const message = (delivery: 'TURN' | 'STEER' | 'QUEUE') => run_(async () => {
    const text = composer.trim();
    if (text === '') return;
    const key = `review-${delivery.toLowerCase()}:${review.id}:${text.length}:${Date.now()}`;
    if (delivery !== 'TURN') {
      // A steer is for the turn on screen; if that turn has ended, the backend refuses rather than steering the next one.
      onReviewChange(await window.bridge.messageAgentReview(review.id, text, delivery, key, delivery === 'STEER' ? review.activeTurn?.id : undefined));
      recordReviewAttentionAction(review, 'prompt');
      setComposer('');
      return;
    }
    // A new turn's message moves into the conversation at once; its durable event replaces it there.
    dispatchConversation({ type: 'message/sent', text });
    setComposer('');
    try {
      onReviewChange(await window.bridge.messageAgentReview(review.id, text, delivery, key));
      recordReviewAttentionAction(review, 'prompt');
    }
    catch (failure) {
      dispatchConversation({ type: 'message/cleared' });
      setComposer(composer);
      throw failure;
    }
  });

  const retained = review.findings.filter(finding => !findingInResults(review, finding));
  const retainedFindings = retained.length === 0 ? undefined : <details>
    <summary>Other recorded findings · {retained.length}</summary>
    <p>These findings are retained outside the selected result. Unfinished findings remain provisional; supported findings from a completed review can be published explicitly.</p>
    {retained.map(finding => <ReviewFindingCard key={finding.id} review={review} finding={finding}
      viewerLogin={viewer?.login ?? ''} viewerAvatarUrl={viewer?.avatarUrl ?? null} projection="review"
      actions={{ ...actions, onNavigate: undefined }} busy={busy || recovery || findingActions.busy} defaultOpen={false} />)}
  </details>;
  const progressIcons = [GitCommitHorizontal, File, CircleAlert];
  const toolbar = mode === 'cards' && !timeline ? <div className="agr-strip" aria-label="Review progress">
        <span className={`agr-strip__status is-${status?.tone ?? 'neutral'}`} title={`${status?.label} · ${status?.detail}`}>
          {status?.busy && <span className="agr-spinner" aria-hidden="true" />}
          <strong title={progress?.phase}>{status?.label === 'Reviewing' && compactProgress !== null ? compactProgress.phase : status?.label}</strong>
        </span>
        {compactProgress !== null && <div className="agr-strip__facts" title={progress?.facts.join(' · ')}>
          {compactProgress.facts.slice(0, 3).map((fact, index) => {
            const Icon = progressIcons[index];
            return <span key={index} title={progress?.facts[index]}><Icon size={12} aria-hidden="true" />{fact}</span>;
          })}
        </div>}
        <span className="agr-spacer" />
        {onOpenWorkspace !== undefined && (
          <button type="button" className="agr-action" aria-label="View in workspace" title="View in workspace" onClick={onOpenWorkspace}>
            <PanelsTopLeft size={16} aria-hidden="true" />
          </button>
        )}
        {!resuming && reviewElapsed(review, now) !== null && (
          <span className="agr-strip__elapsed">{reviewElapsed(review, now)}</span>
        )}
        {running && (
          <button type="button" className="agr-action is-danger" disabled={busy}
            onClick={() => { void run_(async () => { onReviewChange(await window.bridge.stopAgentReview(review.id)); recordReviewAttentionAction(review, 'update'); }); }}>Stop</button>
        )}
        {!running && review.state === 'OPEN' && status?.status === 'stopped' && (
          <button type="button" className="agr-action" disabled={busy || recovery || localRun} title={localRunReason}
            onClick={() => { void run_(async () => { onReviewChange(await window.bridge.resumeAgentReview(review.id)); recordReviewAttentionAction(review, 'prompt'); }, 'resume'); }}>
            {resuming ? 'Starting…' : 'Resume'}
          </button>
        )}
        {!running && review.state === 'OPEN' && (
          <button type="button" className="agr-action" disabled={busy || recovery || localRun} title={localRunReason}
            onClick={() => { void run_(async () => { onReviewChange(await window.bridge.updateAgentReview(review.id)); recordReviewAttentionAction(review, 'prompt'); }); }}>
            {review.runs.length === 0 ? 'Start review' : 'Update review'}</button>
        )}
        {!running && review.state === 'OPEN' && (
          <button type="button" className="agr-action is-quiet" disabled={busy || recovery || localRun}
            title={localRunReason ?? 'Completes the task and cleans the worktree; the summary, findings and discussion stay'}
            onClick={() => { void run_(async () => { onReviewChange(await window.bridge.endAgentReview(review.id)); recordReviewAttentionAction(review, 'update'); }); }}>
            {review.runs.length === 0 ? 'Remove worktree' : 'End review'}</button>
        )}
      </div> : null;
  return (
    <div className={`agr-pane${timeline ? ' is-timeline' : ''}`}>
      {toolbarTarget && toolbar ? createPortal(toolbar, toolbarTarget) : toolbar}

      {mode === 'cards' && progress !== null && (timeline ? running || progress.gaps.length > 0 : progress.gaps.length > 0) && <section className="agr-progress" aria-label="Review progress">
        {timeline && <div className="agr-progress__phase"><strong>Review progress</strong><span>{progress.phase}</span></div>}
        {timeline && <div className="agr-progress__facts">{progress.facts.map(fact => <span key={fact}>{fact}</span>)}</div>}
        {progress.gaps.length > 0 && <div className="agr-progress__gaps">{progress.gaps.map(gap => <span key={gap}>{gap}</span>)}</div>}
      </section>}

      {error !== null && <div className="agr-error" role="alert">{error}</div>}
      {findingActions.error !== null && <div className="agr-error" role="alert">{findingActions.error}</div>}
      {recovery && (
        <div className="agr-banner is-danger" role="status">
          Provider shutdown could not be verified. New turns and worktree changes are blocked until recovery finishes.
          <button type="button" className="agr-action" disabled={busy} onClick={requestRuntimeRecovery}>
            {busy ? 'Checking runtime…' : 'Recover runtime'}
          </button>
        </div>
      )}

      {mode === 'cards' ? (
        <div className="agr-summary" ref={cardsRef}>
          {run !== null && reportRunId !== null && (run.id !== currentRun?.id || summaryRun?.id !== currentExplanation?.id) && <div className="agr-banner is-neutral">
            <span>{run.id === currentRun?.id ? 'Earlier explanation' : isExplanationRun(run) ? 'Saved explanation' : 'Earlier review'} · head {shortSha(run.headSha)}</span>
            <button type="button" className="agr-action" onClick={() => setReportRunId(null)}>Show latest review</button>
          </div>}
          {waiting && (
            <div className="agr-banner is-warn">
              <span className="agr-banner__chip">Approval needed</span>
              <div className="agr-banner__text">
                The agent is waiting for your answer before it can continue. Open its workspace conversation to respond.
              </div>
              {onOpenWorkspace !== undefined && <button type="button" className="agr-action" onClick={onOpenWorkspace}>View in workspace</button>}
            </div>
          )}
          {status?.status === 'failed' && (
            <div className="agr-banner is-danger">
              <span className="agr-banner__chip">Could not finish</span>
              <div className="agr-banner__text">
                {status.detail}{' '}
                {provisional(review) ? 'Saved work from this attempt is provisional.' : 'The previous completed review is shown below.'}
              </div>
              {onOpenWorkspace !== undefined && <button type="button" className="agr-action is-quiet" onClick={onOpenWorkspace}>See what happened</button>}
            </div>
          )}
          {(newCommits(review) || worktreeNotice !== null) && (
            <div className="agr-banner is-warn">
              <span className="agr-banner__chip">{worktreeNotice?.label ?? 'New commits'}</span>
              <div className="agr-banner__text">
                {worktreeNotice?.message ?? <>The source has new commits since this review ({shortSha(currentRun?.headSha ?? '')} → {shortSha(review.observedHeadSha ?? '')}).
                  {' '}Finding comments stay attached to their reviewed commit.</>}
              </div>
              <button type="button" className="agr-action is-primary" disabled={busy || running || recovery || localRun} title={localRunReason}
                onClick={() => { void run_(async () => { onReviewChange(await window.bridge.updateAgentReview(review.id)); recordReviewAttentionAction(review, 'prompt'); }); }}>Update review</button>
            </div>
          )}
          {status?.status === 'stopped' && (
            <div className="agr-banner is-neutral">
              <span className="agr-banner__chip">Stopped</span>
              <div className="agr-banner__text">
                You stopped the review. The discussion stays open; Resume continues from the saved state.
              </div>
            </div>
          )}

          {run !== null && (timeline || run.id !== summaryRun?.id || run.summary === null) && <>
            {(run.coverage?.warnings.length ?? 0) > 0 && <aside className="agr-banner is-warn agr-summary-card__warnings" aria-label="Verification warning">
              <strong className="agr-banner__chip">Verification warning</strong>
              <div className="agr-banner__text"><MarkdownBody body={[...new Set(run.coverage?.warnings)].map(warning => `- ${warning}`).join('\n')} repository={review.repo} /></div>
            </aside>}
            <div data-review-card={timeline ? undefined : `${run.id}:details`}><ReviewDetails run={run} repository={review.repo} retainedFindings={retainedFindings} /></div>
          </>}
          {!timeline && <ReviewSummaryCard
            review={review}
            run={summaryRun}
            onSelectCommit={onSelectCommit}
            findings={findings}
            retainedFindings={run?.id === summaryRun?.id ? retainedFindings : undefined}
            viewerLogin={viewer?.login ?? ''}
            viewerAvatarUrl={viewer?.avatarUrl ?? null}
            busy={busy || recovery}
            onRefresh={summaryRun !== null && summaryRun.id === currentExplanation?.id ? () => run_(async () => {
              onReviewChange(await window.bridge.refreshAgentReviewExplanation(review.id, summaryRun.id, summaryRun.summary?.revision ?? 1));
              setReportRunId(null);
              recordReviewAttentionAction(review, 'prompt');
            }, 'refresh', true) : undefined}
            refreshDisabledReason={review.state === 'CLOSED' ? 'This review has ended' : recovery ? 'Recover the agent runtime first'
              : localRunReason ?? (running ? 'Wait for the current agent work to finish' : undefined)}
            onAsk={(text, revision, cardId) => run_(async () => {
              onReviewChange(await window.bridge.askAgentReview(review.id, null, revision, text, cardId));
              recordReviewAttentionAction(review, 'prompt');
            }, 'other', true)}
            onSelectFinding={finding => {
              setFindingFilter('All');
              requestAnimationFrame(() => scrollToFinding(finding.id));
            }} />}

          {!timeline && <div className="agr-findings__head">
            <h2><CircleAlert size={16} aria-hidden="true" />Findings</h2>
            {(['Critical', 'High', 'Medium', 'Low'] as const).map(severity => {
              const count = findings.filter(finding => finding.severity === severity).length;
              return count === 0 ? null : <span key={severity} className={`agr-severity is-${severity.toLowerCase()}`}>{count} {severity}</span>;
            })}
            <span className="agr-findings__count">
              {findings.length === 0 && provisional(review) ? 'none saved'
                : `${findings.filter(finding => finding.state === 'OPEN').length} open · ${findings.filter(finding => finding.state === 'RESOLVED').length} resolved`}
            </span>
            <div className="agr-findings__filters" aria-label="Filter findings">
              {(['All', 'Open', 'Resolved'] as const).map(filter => <button key={filter} type="button" aria-pressed={findingFilter === filter}
                onClick={() => setFindingFilter(filter)}>{filter}<span>{filter === 'All' ? findings.length
                  : findings.filter(finding => finding.state === filter.toUpperCase()).length}</span></button>)}
            </div>
          </div>}
          {!timeline && findings.length > 0 && findingFilter !== 'All' && !findings.some(finding => finding.state === findingFilter.toUpperCase())
            && <p className="agr-card__empty">No {findingFilter.toLowerCase()} findings.</p>}

          {findings.length === 0 && status?.status === 'nofindings' && (
            <div className="agr-none">
              <div className="agr-none__title">{retained.length > 0 ? 'No selected findings' : 'No inline findings'}</div>
              <p>
                {retained.length > 0 ? 'No findings were selected for the main result. Other recorded findings remain in Review details. ' : ''}The agent recorded complete coverage of the review scope. Complete means the scope was
                reviewed, not that the pull request is guaranteed correct.
              </p>
            </div>
          )}
          {findings.length === 0 && status?.status !== 'nofindings' && provisional(review) && running && (
            <div className="agr-pending">
              <span className="agr-spinner" aria-hidden="true" />
              Findings appear after the agent checks its candidates. The report stays provisional until validation and complete coverage are accepted.
            </div>
          )}

          {findings.map(finding => (
            <div key={finding.id} hidden={!timeline && findingFilter !== 'All' && finding.state !== findingFilter.toUpperCase()}>
            <ReviewFindingCard
              review={review}
              finding={finding}
              viewerLogin={viewer?.login ?? ''}
              viewerAvatarUrl={viewer?.avatarUrl ?? null}
              projection="review"
              actions={actions}
              busy={busy || recovery || findingActions.busy}
              defaultOpen={finding.state === 'OPEN'} />
            </div>
          ))}
          {withdrawn.map(finding => (
            <ReviewFindingCard
              key={finding.id}
              review={review}
              finding={finding}
              viewerLogin={viewer?.login ?? ''}
              viewerAvatarUrl={viewer?.avatarUrl ?? null}
              projection="review"
              actions={actions}
              busy={busy || recovery || findingActions.busy}
              defaultOpen={false} />
          ))}
          <LocalComments localReview={localReview} />
        </div>
      ) : (
        <ReviewActivity
          key={review.id}
          agentPanel={agentPanel}
          review={review}
          onOpenReview={onOpenReview}
          localReview={localReview === undefined ? undefined : { ...localReview, threads: localReview.threads.filter(thread => thread.taskId === review.task.id) }}
          events={events}
          pendingMessage={conversation.pendingMessage}
          turns={turns}
          models={models}
          pendingApprovals={approvals}
          connection={conversation.connection}
          lastSequence={conversation.lastSequence}
          reviewSequence={reviewSequence}
          history={{
            before: historyBefore(conversation),
            after: historyAfter(conversation),
            loading: historyLoading,
            onLoad: (direction: 'older' | 'newer' | 'latest') => { void loadHistory(direction); },
            onFollow: (following: boolean) => {
              const resumed = following && !followingHistory.current;
              followingHistory.current = following;
              if (resumed && conversationRef.current.lastSequence > (conversationRef.current.events.at(-1)?.sequence ?? 0)) void loadHistory('latest');
            },
          }}
          now={now}
          composer={composer}
          busy={busy}
          onCompose={setComposer}
          onSend={() => { void message('TURN'); }}
          onSteer={() => { void message('STEER'); }}
          onQueue={() => { void message('QUEUE'); }}
          onStop={() => { void run_(async () => { onReviewChange(await window.bridge.stopAgentReview(review.id)); recordReviewAttentionAction(review, 'update'); }); }}
          onDecide={(approvalId: string, decision: AgentApprovalDecision, answers?: Record<string, string>) => {
            void run_(async () => {
              await window.bridge.resolveAgentReviewApproval(review.id, approvalId, decision, answers);
              recordReviewAttentionAction(review, 'update');
              await refresh(review.id);
            });
          }}
          onResumeQueue={() => {
            void run_(async () => { onReviewChange(await window.bridge.resumeAgentReviewQueue(review.id)); recordReviewAttentionAction(review, 'prompt'); });
          }}
          onRemoveQueued={queuedInputId => {
            void run_(async () => { onReviewChange(await window.bridge.removeAgentReviewQueued(review.id, queuedInputId)); recordReviewAttentionAction(review, 'update'); });
          }}
          onSteerQueued={queuedInputId => {
            const turnId = review.activeTurn?.id;
            if (turnId === undefined) return;
            void run_(async () => {
              onReviewChange(await window.bridge.steerAgentReviewQueued(review.id, queuedInputId, turnId));
              recordReviewAttentionAction(review, 'prompt');
            });
          }} />
      )}
      {recovery && recoveryConfirmation?.reviewId === review.id && (
        <ConfirmDialog
          title="Recover this review’s runtime?"
          confirmLabel={busy ? 'Recovering…' : 'Recover runtime'}
          busy={busy}
          onConfirm={confirmRuntimeRecovery}
          onCancel={() => setRecoveryConfirmation(null)}>
          <p>Only this review’s agent runtime is affected. Its conversation, findings and worktree are kept.</p>
          <p>{recoveryConfirmation.impact.activeTurnId === null
            ? 'This checks and stops any remaining agent activity from the interrupted turn.'
            : 'The current response will be interrupted and pending approvals declined.'}</p>
          <p>Once cleanup is verified, use Resume to continue the same review. Nothing is resent automatically.</p>
        </ConfirmDialog>
      )}
    </div>
  );
}

function scrollToFinding(findingId: string) {
  // Code & Tests keeps a hidden copy of a finding card earlier in the page, so look only inside this tab.
  const element = document.querySelector(`.agr-summary [data-finding-id="${CSS.escape(findingId)}"]`);
  const card = element?.querySelector('details');
  if (card !== null && card !== undefined) card.open = true;
  for (let ancestor = element?.parentElement; ancestor; ancestor = ancestor.parentElement) {
    if (ancestor instanceof HTMLDetailsElement) ancestor.open = true;
  }
  element?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function LocalComments({ localReview }: { localReview: LocalReviewThreads | undefined }) {
  if (localReview === undefined || localReview.threads.length === 0) return null;
  return <section className="agr-local-comments" aria-label="Local review comments">
    <div className="agr-findings__head">
      <h2>Local review comments</h2>
      <span className="agr-findings__count">{localReview.threads.length} private discussions</span>
    </div>
    {localReview.threads.map(thread => <LocalReviewThreadCard key={thread.id} {...localReview} thread={thread} />)}
  </section>;
}
