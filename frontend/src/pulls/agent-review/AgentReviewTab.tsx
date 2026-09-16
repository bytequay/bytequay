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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PanelsTopLeft } from 'lucide-react';
import type { ReviewCardFocus, ReviewRequestTarget } from '../../agents/ReviewRequestCard';
import { useReviewCardFocus } from '../../agents/reviewCardNavigation';
import { ConfirmDialog } from '../../ConfirmDialog';
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
import LocalReviewThreadCard from '../local-review/LocalReviewThreadCard';
import type { LocalReviewThreads } from '../local-review/types';
import ReviewActivity from './ReviewActivity';
import ReviewFindingCard from './ReviewFindingCard';
import ReviewDetails from './ReviewDetails';
import { useFindingActions } from './useFindingActions';
import ReviewSummaryCard from './ReviewSummaryCard';
import type { AgentReviewDto, ReviewFindingDto } from './contracts';
import { latestRun, newCommits, openFindings, provisional, reviewElapsed, reviewRecoveryRequired, reviewRunning, reviewStatus, shortSha, shownRun } from './model';

export type ReviewNavigationTarget = {
  path: string;
  side: 'LEFT' | 'RIGHT';
  startLine: number;
  endLine: number;
  findingId: string;
  /** Bumped per request, so asking for the same range twice still scrolls. */
  token: number;
};

type PendingAction = 'resume' | 'other';

/** A placeholder for the hook's contract while no review exists; its actions are never reachable. */
const EMPTY_REVIEW = {
  id: '',
  repo: '',
  number: 0,
  draft: null,
} as unknown as AgentReviewDto;

export default function AgentReviewTab({
  detail,
  workspaceId,
  viewer,
  review,
  onReviewChange,
  onNavigateToChanges,
  focusFindingId, focusReviewCard, onOpenReview,
  mode = 'cards',
  ownStream = true,
  onOpenWorkspace,
  localReview,
}: {
  detail: PullRequestDetailDto;
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
  mode?: 'cards' | 'conversation';
  /**
   * False for cards beside this review's conversation. Main keeps one stream per renderer and review,
   * so a second subscription would silently stop the conversation's; its reloads keep the shared review current.
   */
  ownStream?: boolean;
  onOpenWorkspace?: () => void;
  localReview?: LocalReviewThreads;
}) {
  const [starting, setStarting] = useState(false);
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const busy = pendingAction !== null;
  const resuming = pendingAction === 'resume';
  const [error, setError] = useState<string | null>(null);
  const [recoveryConfirmation, setRecoveryConfirmation] = useState<{ reviewId: string; impact: AgentRuntimeStopImpactDto } | null>(null);
  const recoveryPending = useRef(false);
  const recoveryRequest = useRef(0);
  const [conversation, setConversation] = useState(initialAgentTaskState);
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
  const [models, setModels] = useState<ModelOptionDto[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const navigationToken = useRef(0);
  const cardsRef = useRef<HTMLDivElement>(null);
  const [reportRunId, setReportRunId] = useState<string | null>(null);
  useEffect(() => {
    if (mode === 'cards' && focusReviewCard?.target.kind === 'card') setReportRunId(focusReviewCard.target.runId);
  }, [focusReviewCard, mode]);
  useReviewCardFocus(cardsRef, focusReviewCard, mode === 'cards' && review !== null);
  const running = review !== null && reviewRunning(review);
  const recovery = review !== null && reviewRecoveryRequired(review);
  // The wait is a durable task fact; the fetched list only fills in the card, and a slow read
  // must not make a blocked run look like it is still working.
  const waiting = (review?.pendingApprovals ?? 0) > 0 || approvals.length > 0;

  const status: ReturnType<typeof reviewStatus> | null = review === null ? null
    : resuming && !running
      ? { status: 'running', label: 'Reviewing', tone: 'running', detail: 'starting the review', chip: null, busy: true }
      : reviewStatus(review);
  const currentRun = review === null ? null : shownRun(review);
  const run = review?.runs.find(item => item.id === reportRunId) ?? currentRun;
  const findings = review === null ? [] : openFindings(review);
  const withdrawn = review === null ? [] : review.findings.filter(finding => finding.state === 'WITHDRAWN');

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

  const refresh = useCallback(async (reviewId: string, turnId: string | null = null) => {
    const revision = ++projectionRevision.current;
    const [updated, turn] = await Promise.all([
      window.bridge.getAgentReview(reviewId),
      turnId === null || mode !== 'conversation' ? Promise.resolve(null) : window.bridge.getAgentTurn(turnId),
    ]);
    if (currentReviewId.current !== reviewId || projectionRevision.current !== revision) return null;
    if (turn !== null) dispatchConversation({ type: 'turn/changed', turn });
    onReviewChange(updated);
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
      setError(remoteErrorMessage(failure));
      if (propagate) throw failure;
    }
    finally {
      projectionRevision.current++;
      setPendingAction(null);
    }
  }, []);

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
    followingHistory.current = true;
    const reload = async (turnId: string | null) => {
      try {
        const updated = await refresh(reviewId, turnId);
        // The review observer settles after the task event. One follow-up covers a read that reached it first.
        if (live && updated !== null && updated.activeTurn === null && updated.task.mutationClaim === null && latestRun(updated)?.state === 'RUNNING') {
          if (settlingRefresh !== undefined) clearTimeout(settlingRefresh);
          settlingRefresh = setTimeout(() => { if (live) void refresh(reviewId, turnId).catch(() => {}); }, 200);
        }
      }
      catch (failure) {
        if (live) setError(remoteErrorMessage(failure));
      }
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
      if (action.type === 'live/changed') void reload(action.turnId);
      if (action.type === 'events/observed') void reload(action.events.at(-1)?.turnId ?? null);
      if (action.type === 'connection/changed' && action.state === 'open') void reload(null);
    }, () => live, () => followingHistory.current, reviewOpen);
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
  }, [conversationBridge, dispatchConversation, reviewId, taskId]);

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
    scrollToFinding(focusFindingId);
  }, [focusFindingId]);

  const start = () => {
    if (workspaceId === undefined) return;
    setStarting(true);
    setError(null);
    void window.bridge.startAgentReview(workspaceId, detail.repo, detail.number)
      .then(onReviewChange)
      .catch(failure => setError(remoteErrorMessage(failure)))
      .finally(() => setStarting(false));
  };

  const navigate = useCallback((finding: ReviewFindingDto) => {
    navigationToken.current += 1;
    onNavigateToChanges({
      path: finding.path,
      side: finding.side,
      startLine: finding.startLine,
      endLine: finding.endLine,
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
    return (
      <div className="agr-empty">
        <div className="agr-empty__title">Agent review needs a watched repository</div>
        <p>
          Watch {detail.repo} in Workspaces to start a private review. ByteQuay uses that workspace&apos;s managed
          worktree, and the review is available here or from the workspace.
        </p>
      </div>
    );
  }

  if (review === null) {
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

  const message = (delivery: 'TURN' | 'STEER' | 'QUEUE') => run_(async () => {
    const text = composer.trim();
    if (text === '') return;
    const key = `review-${delivery.toLowerCase()}:${review.id}:${text.length}:${Date.now()}`;
    if (delivery !== 'TURN') {
      onReviewChange(await window.bridge.messageAgentReview(review.id, text, delivery, key));
      setComposer('');
      return;
    }
    // A new turn's message moves into the conversation at once; its durable event replaces it there.
    dispatchConversation({ type: 'message/sent', text });
    setComposer('');
    try {
      onReviewChange(await window.bridge.messageAgentReview(review.id, text, delivery, key));
    }
    catch (failure) {
      dispatchConversation({ type: 'message/cleared' });
      setComposer(composer);
      throw failure;
    }
  });

  return (
    <div className={`agr-pane${mode === 'conversation' ? ' agr-pane--conversation' : ''}`}>
      <div className="agr-strip">
        <span className={`agr-strip__status is-${status?.tone ?? 'neutral'}`}>
          {status?.busy && <span className="agr-spinner" aria-hidden="true" />}
          <strong>{status?.label}</strong>
          <span className="agr-strip__detail">· {status?.detail}</span>
        </span>
        <span className="agr-spacer" />
        {mode === 'cards' && onOpenWorkspace !== undefined && (
          <button type="button" className="agr-action" aria-label="View in workspace" title="View in workspace" onClick={onOpenWorkspace}>
            <PanelsTopLeft size={16} aria-hidden="true" />
          </button>
        )}
        {!resuming && reviewElapsed(review, now) !== null && (
          <span className="agr-strip__elapsed">{reviewElapsed(review, now)}</span>
        )}
        {running && (
          <button type="button" className="agr-action is-danger" disabled={busy}
            onClick={() => { void run_(async () => onReviewChange(await window.bridge.stopAgentReview(review.id))); }}>Stop</button>
        )}
        {!running && review.state === 'OPEN' && status?.status === 'stopped' && (
          <button type="button" className="agr-action" disabled={busy || recovery}
            onClick={() => { void run_(async () => onReviewChange(await window.bridge.resumeAgentReview(review.id)), 'resume'); }}>
            {resuming ? 'Starting…' : 'Resume'}
          </button>
        )}
        {!running && review.state === 'OPEN' && (
          <button type="button" className="agr-action" disabled={busy || recovery}
            onClick={() => { void run_(async () => onReviewChange(await window.bridge.updateAgentReview(review.id))); }}>Update review</button>
        )}
        {!running && review.state === 'OPEN' && (
          <button type="button" className="agr-action is-quiet" disabled={busy || recovery}
            title="Completes the task and cleans the worktree; the summary, findings and discussion stay"
            onClick={() => { void run_(async () => onReviewChange(await window.bridge.endAgentReview(review.id))); }}>End review</button>
        )}
      </div>

      {!running && review.state === 'OPEN' && <div className="agr-banner is-neutral">
        Local experiments are restored when possible. If they conflict, all local experiments are discarded.
      </div>}
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
          {run !== null && run.id !== currentRun?.id && <div className="agr-banner is-neutral">
            <span>Earlier review · head {shortSha(run.headSha)}</span>
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
                {provisional(review) ? 'Any saved work below is provisional.' : 'The previous completed review is shown below.'}
              </div>
              {onOpenWorkspace !== undefined && <button type="button" className="agr-action is-quiet" onClick={onOpenWorkspace}>See what happened</button>}
            </div>
          )}
          {newCommits(review) && (
            <div className="agr-banner is-warn">
              <span className="agr-banner__chip">New commits</span>
              <div className="agr-banner__text">
                Commits were pushed since this review ({shortSha(run?.headSha ?? '')} → {shortSha(review.observedHeadSha ?? '')}).
                Findings below refer to the reviewed version.
              </div>
              <button type="button" className="agr-action is-primary" disabled={busy || recovery}
                onClick={() => { void run_(async () => onReviewChange(await window.bridge.updateAgentReview(review.id))); }}>Update review</button>
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

          {run !== null && <ReviewDetails run={run} repository={review.repo} />}
          <ReviewSummaryCard
            review={review}
            run={run}
            findings={review.findings}
            viewerLogin={viewer?.login ?? ''}
            viewerAvatarUrl={viewer?.avatarUrl ?? null}
            busy={busy || recovery}
            onAsk={(text, revision, cardId) => run_(async () => {
              onReviewChange(await window.bridge.askAgentReview(review.id, null, revision, text, cardId));
            }, 'other', true)}
            onSelectFinding={finding => {
              scrollToFinding(finding.id);
            }} />

          <div className="agr-findings__head">
            <h2>Findings</h2>
            <span className="agr-findings__count">
              {findings.length === 0
                ? provisional(review) ? 'none saved' : 'none'
                : `${findings.length} from this run · ${findings.filter(finding => finding.state === 'OPEN').length} open`}
            </span>
          </div>

          {findings.length === 0 && status?.status === 'nofindings' && (
            <div className="agr-none">
              <div className="agr-none__title">No inline findings</div>
              <p>
                The agent recorded complete coverage of the review scope. Complete means the scope was
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
            <ReviewFindingCard
              key={finding.id}
              review={review}
              finding={finding}
              viewerLogin={viewer?.login ?? ''}
              viewerAvatarUrl={viewer?.avatarUrl ?? null}
              projection="review"
              actions={actions}
              busy={busy || recovery || findingActions.busy}
              defaultOpen={finding.state === 'OPEN'} />
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
          onStop={() => { void run_(async () => onReviewChange(await window.bridge.stopAgentReview(review.id))); }}
          onDecide={(approvalId: string, decision: AgentApprovalDecision) => {
            void run_(async () => {
              await window.bridge.resolveAgentReviewApproval(review.id, approvalId, decision);
              await refresh(review.id);
            });
          }}
          onRemoveQueued={queuedInputId => {
            void run_(async () => onReviewChange(await window.bridge.removeAgentReviewQueued(review.id, queuedInputId)));
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
  const element = document.getElementById(`agent-review-finding-${findingId}`);
  const card = element?.querySelector('details');
  if (card !== null && card !== undefined) card.open = true;
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
