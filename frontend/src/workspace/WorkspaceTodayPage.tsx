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
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { ThreadDto, ThreadTurnDto, WorkspaceCardDto } from '../types';
import {
  workspaceApi,
  type WorkspaceOnboardingDto,
} from './workspaceApi';

type Props = {
  workspace: WorkspaceCardDto;
  threads: ThreadDto[];
  onOpenThread?: (threadId: string) => void;
  onNewThread: () => void;
  onOpenInsights: () => void;
  onOpenMemory: () => void;
};

/**
 * Frame 2b's workspace hub. It is a projection over existing trunk and
 * scheduler data; no second "today" model is persisted.
 */
export default function WorkspaceTodayPage({
  workspace, threads, onOpenThread, onNewThread, onOpenInsights, onOpenMemory,
}: Props) {
  const [turns, setTurns] = useState<ThreadTurnDto[]>([]);
  const [onboarding, setOnboarding] = useState<WorkspaceOnboardingDto | null>(null);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.listActiveTaskTurns()
      .then(rows => {
        if (!cancelled) setTurns(rows);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [threads]);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const next = await workspaceApi.onboarding(workspace.id);
        if (!cancelled) setOnboarding(next);
      }
      catch {
        // A restored legacy workspace may not have its onboarding row yet.
      }
    };
    void load();
    const timer = window.setInterval(() => { void load(); }, 2_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [workspace.id]);

  const publicThreads = useMemo(
    () => threads.filter(thread => thread.flow !== 'review'),
    [threads],
  );
  const threadIds = useMemo(() => new Set(publicThreads.map(thread => thread.id)), [publicThreads]);
  const liveTurns = turns.filter(turn => threadIds.has(turn.threadId));
  const needsYou = publicThreads.filter(thread =>
    thread.status === 'AWAITING_REVIEW' || thread.status === 'NEEDS_ATTENTION');
  const running = publicThreads.filter(thread =>
    thread.status === 'RUNNING' || liveTurns.some(turn => turn.threadId === thread.id));
  const landed = publicThreads.filter(thread =>
    (thread.status === 'COMPLETED' || thread.status === 'ARCHIVED') && isToday(thread.updatedAt));
  const visualFrame = document.documentElement.dataset.workspaceVisualFrame;
  const firstSyncRunning = onboarding !== null && onboarding.syncState !== 'ready';

  return (
    <section className="wu-page wu-today">
      <header className="wu-page-header">
        <span className="wu-today__title">Today</span>
        {firstSyncRunning
          ? <span className="wu-today__syncing"><i />first sync running</span>
          : <span className="wu-today__date">{visualFrame === '2b' ? 'Thursday, Jul 16' : formatToday()}</span>}
        <span className="wu-today__header-spacer" />
        <button type="button" className="wu-primary-button" onClick={onNewThread}>
          <PlusIcon />
          New trunk
        </button>
      </header>

      <div className="wu-today__scroll">
        <div className="wu-today__body">
          {onboarding !== null
            && !onboardingComplete(onboarding)
            && (
              <WorkspaceOnboarding
                workspaceName={workspace.name}
                state={onboarding}
                threadCount={publicThreads.length}
                onNewThread={onNewThread}
                onOpenMemory={onOpenMemory}
                onLearningAction={async action => {
                  if (action === 'pause') await workspaceApi.pauseLearning(workspace.id);
                  else if (action === 'resume') await workspaceApi.resumeLearning(workspace.id);
                  else await workspaceApi.retryLearning(workspace.id);
                  setOnboarding(await workspaceApi.onboarding(workspace.id));
                }}
              />
            )}
          {!firstSyncRunning && <TodaySection label="Needs you" tone="attention">
            {needsYou.length === 0 ? (
              <QuietEmpty>Nothing needs your attention.</QuietEmpty>
            ) : needsYou.map(thread => (
              <TodayAction
                key={thread.id}
                icon={<AttentionIcon kind={attentionKind(thread)} />}
                title={thread.title}
                meta={`${attentionLabel(thread)} · ${
                  thread.model || thread.provider} · ${relativeTime(thread.updatedAt)}`}
                action={attentionAction(thread)}
                primary={thread.status === 'AWAITING_REVIEW'}
                onOpen={() => onOpenThread?.(thread.id)}
              />
            ))}
          </TodaySection>}

          {!firstSyncRunning && <TodaySection label="Running" tone="running">
            {running.length === 0 ? (
              <QuietEmpty>No sessions are running.</QuietEmpty>
            ) : running.map(thread => {
              const turn = liveTurns.find(row => row.threadId === thread.id);
              return (
                <TodayAction
                  key={thread.id}
                  icon={<span className="wu-running-dot" />}
                  title={turn?.input ? `${thread.title} — ${shortInput(turn.input)}` : thread.title}
                  meta={`${thread.model || thread.provider} · ${formatSpend(thread.costUsdMilli)} · ${
                    elapsed(thread.updatedAt)} elapsed`}
                  action="Watch"
                  onOpen={() => onOpenThread?.(thread.id)}
                  monospaceMeta
                />
              );
            })}
          </TodaySection>}

          {!firstSyncRunning && <TodaySection label="Landed today">
            {landed.length === 0 ? (
              <QuietEmpty>No work has landed today.</QuietEmpty>
            ) : (
              <div className="wu-landed-list">
                {landed.map(thread => (
                  <div
                    key={thread.id}
                    className="wu-landed-row"
                    role="button"
                    tabIndex={0}
                    onClick={() => onOpenThread?.(thread.id)}
                    onKeyDown={event => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onOpenThread?.(thread.id);
                      }
                    }}
                  >
                    <MergedIcon />
                    <span>{thread.title} — merged</span>
                    <span className="wu-landed-row__time">{compactRelativeTime(thread.updatedAt)}</span>
                  </div>
                ))}
              </div>
            )}
          </TodaySection>}

          {!firstSyncRunning && <div className="wu-today__stats">
            <span>
              Today: <b>{formatSpend(workspace.spendTodayMilliUsd)}</b> spend ·{' '}
              <b>{landed.length}</b> tasks shipped · <b>{workspace.activeThreadCount}</b>{' '}
              {visualFrame === '2b' ? 'threads' : 'trunks'} active
            </span>
            <span className="wu-today__stats-spacer" />
            <a
              role="button"
              tabIndex={0}
              onClick={onOpenInsights}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onOpenInsights();
                }
              }}
            >
              Insights →
            </a>
          </div>}
        </div>
      </div>
    </section>
  );
}

function WorkspaceOnboarding({
  workspaceName,
  state,
  threadCount,
  onNewThread,
  onOpenMemory,
  onLearningAction,
}: {
  workspaceName: string;
  state: WorkspaceOnboardingDto;
  threadCount: number;
  onNewThread: () => void;
  onOpenMemory: () => void;
  onLearningAction: (action: 'pause' | 'resume' | 'retry') => Promise<void>;
}) {
  const [learningBusy, setLearningBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const hasLearning = state.learningState !== null;
  const milestones = Number(state.cloneComplete)
    + Number(state.syncState === 'ready')
    + 1
    + Number(state.firstTrunkComplete || threadCount > 0)
    + Number(state.learningState !== null && learningDone(state.learningState));
  const milestoneTotal = hasLearning ? 5 : 4;
  const progress = state.syncTotal <= 0
    ? 0
    : Math.min(100, Math.round(state.syncCurrent / state.syncTotal * 100));

  const visualFrame = document.documentElement.dataset.workspaceVisualFrame;
  const cloneReadyDetail = visualFrame === '6a'
    ? 'forked to chenjian2664/trino-python-client · managed clone ready'
    : 'managed clone ready';

  return (
    <>
      <section className="wu-onboarding-card">
      <header>
        <h2>Set up {workspaceName}</h2>
        <span>{milestones} of {milestoneTotal} done</span>
      </header>
      <div className={`wu-onboarding-step ${state.cloneComplete ? 'done' : 'active'}`}>
        <MilestoneIcon done={state.cloneComplete} active={!state.cloneComplete} />
        <div>
          <strong className={state.cloneComplete ? 'struck' : ''}>Clone the repo</strong>
          <small>{state.cloneComplete
            ? cloneReadyDetail
            : 'Creating a verified ByteQuay-managed clone'}</small>
        </div>
      </div>
      <div className={`wu-onboarding-step wu-onboarding-step--sync ${
        state.syncState === 'ready' ? 'done' : 'active'}`}>
        <MilestoneIcon done={state.syncState === 'ready'} active={state.syncState !== 'ready'} />
        <div>
          <strong>Sync pull requests &amp; issues</strong>
          <span className="wu-onboarding-progress-row">
            <span className="wu-onboarding-progress"><i style={{ width: `${progress}%` }} /></span>
            <code>{state.syncState === 'ready'
              ? 'first sync complete'
              : visualFrame === '6a'
                ? '14 / 38 PRs · issues queued'
                : `${state.syncCurrent} / ${state.syncTotal || 3} steps`}</code>
          </span>
        </div>
      </div>
      <div className="wu-onboarding-step skipped">
        <MilestoneIcon skipped />
        <div>
          <strong className="struck">Seed memory</strong>
          <small>Workspace memory is still in progress — skipped for now</small>
        </div>
        <span className="wu-onboarding-step__status">Skipped</span>
      </div>
      <div className={`wu-onboarding-step ${
        state.firstTrunkComplete || threadCount > 0 ? 'done' : ''}`}>
        <MilestoneIcon done={state.firstTrunkComplete || threadCount > 0} />
        <div>
          <strong>Start your first trunk</strong>
          <small>Plan a change, generate a backlog, or just ask around the codebase</small>
        </div>
        {!state.firstTrunkComplete && threadCount === 0 && (
          <button type="button" className="primary" onClick={onNewThread}>New trunk</button>
        )}
      </div>
      {state.learningState !== null && (
        <div className={`wu-onboarding-step ${
          learningDone(state.learningState) ? 'done'
            : learningLive(state.learningState) ? 'active' : ''}`}>
          <MilestoneIcon
            done={learningDone(state.learningState)}
            active={learningLive(state.learningState)}
          />
          <div>
            <strong>Learn this project</strong>
            <small>
              {learningLabel(state.learningState)}
              {' — '}
              {state.learningCataloged} cataloged · {state.learningAnalyzed} analyzed
              {' · '}{state.learningLessons} lessons
            </small>
            {state.learningPendingLessons > 0 && (
              <button type="button" className="wu-onboarding-link" onClick={onOpenMemory}>
                {state.learningPendingLessons} proposal
                {state.learningPendingLessons === 1 ? '' : 's'} need review
              </button>
            )}
            {state.learningLastError !== undefined && state.learningLastError !== null
              && state.learningLastError !== '' && (
              <small className="wu-onboarding-error">{state.learningLastError}</small>
            )}
          </div>
          {learningAction(state.learningState) !== null && (
            <button
              type="button"
              disabled={learningBusy}
              onClick={() => {
                setLearningBusy(true);
                setError(null);
                void onLearningAction(learningAction(state.learningState) as
                    'pause' | 'resume' | 'retry')
                  .catch(cause => setError(
                    cause instanceof Error ? cause.message : 'Learning action failed'))
                  .finally(() => setLearningBusy(false));
              }}
            >
              {learningActionLabel(state.learningState)}
            </button>
          )}
        </div>
      )}
      {error !== null && <p className="wu-onboarding-error">{error}</p>}
      </section>
      {state.syncState !== 'ready' && (
        <div className="wu-onboarding-incoming">
          <div><span>Syncing in</span><i /></div>
          <div className="wu-onboarding-incoming__row">
            <PullRequestIcon />
            <p>{visualFrame === '6a'
              ? "2 PRs already request your review — they'll surface here when sync lands"
              : 'Pull requests and issues already needing you will surface here when sync lands'}</p>
            <time>~1 min</time>
          </div>
        </div>
      )}
    </>
  );
}

function PullRequestIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden>
      <circle cx="18" cy="18" r="2.6" />
      <circle cx="6" cy="6" r="2.6" />
      <path d="M13 6h3a2 2 0 0 1 2 2v7M6 9v12" />
    </svg>
  );
}

function MilestoneIcon({ done = false, active = false, skipped = false }: {
  done?: boolean;
  active?: boolean;
  skipped?: boolean;
}) {
  if (skipped) {
    return <span className="wu-milestone skipped"><svg viewBox="0 0 24 24"><path d="M7 12h10" /></svg></span>;
  }
  if (done) {
    return <span className="wu-milestone done"><svg viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5" /></svg></span>;
  }
  return <span className={`wu-milestone${active ? ' active' : ''}`} />;
}

function onboardingComplete(state: WorkspaceOnboardingDto): boolean {
  return state.cloneComplete && state.syncState === 'ready'
    && state.firstTrunkComplete
    && (state.learningState === null || learningDone(state.learningState));
}

function learningLive(state: string): boolean {
  return state === 'queued' || state === 'indexing'
    || state === 'cataloging' || state === 'analyzing';
}

function learningDone(state: string): boolean {
  return state === 'useful' || state === 'caught-up';
}

function learningLabel(state: string): string {
  switch (state) {
    case 'queued': return 'Learning queued';
    case 'indexing': return 'Indexing local docs';
    case 'cataloging': return 'Cataloging merged history';
    case 'analyzing': return 'Learning merged history';
    case 'useful': return 'Learned — backfill continues daily';
    case 'caught-up': return 'Merged history learned';
    case 'paused': return 'Learning paused';
    case 'partial': return 'Learning interrupted';
    case 'failed': return 'Learning failed';
    default: return state;
  }
}

function learningAction(state: string): 'pause' | 'resume' | 'retry' | null {
  if (learningLive(state)) return 'pause';
  if (state === 'paused') return 'resume';
  if (state === 'partial' || state === 'failed') return 'retry';
  return null;
}

function learningActionLabel(state: string): string {
  const action = learningAction(state);
  return action === 'pause' ? 'Pause' : action === 'resume' ? 'Resume' : 'Retry';
}

function TodaySection({ label, tone, children }: {
  label: string;
  tone?: 'attention' | 'running';
  children: ReactNode;
}) {
  return (
    <div className={`wu-today-section${tone === undefined ? '' : ` ${tone}`}`}>
      <div className="wu-section-label"><span>{label}</span><span className="wu-section-rule" /></div>
      <div className="wu-today-section__rows">{children}</div>
    </div>
  );
}

function TodayAction({
  icon, title, meta, action, primary = false, monospaceMeta = false, onOpen,
}: {
  icon: ReactNode;
  title: string;
  meta: string;
  action: string;
  primary?: boolean;
  monospaceMeta?: boolean;
  onOpen: () => void;
}) {
  return (
    <div
      className="wu-today-card"
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen();
        }
      }}
    >
      <span className="wu-today-card__icon" aria-hidden>{icon}</span>
      <div className="wu-today-card__copy">
        <span className="wu-today-card__title">{title}</span>
        <span className={`wu-today-card__meta${monospaceMeta ? ' mono' : ''}`}>{meta}</span>
      </div>
      <button
        type="button"
        className={`wu-inline-action${primary ? ' primary' : ''}`}
        onClick={event => {
          event.stopPropagation();
          onOpen();
        }}
      >
        {action}
      </button>
    </div>
  );
}

function attentionKind(thread: ThreadDto): 'review' | 'question' | 'plan' {
  if (thread.status === 'AWAITING_REVIEW') return 'review';
  if (thread.activitySummary?.toLowerCase().includes('approval')) return 'plan';
  return 'question';
}

function attentionLabel(thread: ThreadDto): string {
  const kind = attentionKind(thread);
  if (kind === 'review') return 'Review requested';
  if (kind === 'plan') return 'Awaiting approval';
  return 'Agent question';
}

function attentionAction(thread: ThreadDto): string {
  const kind = attentionKind(thread);
  if (kind === 'review') return 'Review';
  if (kind === 'plan') return 'Open plan';
  return 'Answer';
}

function QuietEmpty({ children }: { children: ReactNode }) {
  return <div className="wu-quiet-empty">{children}</div>;
}

function PlusIcon() {
  return <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="2.2" strokeLinecap="round" aria-hidden><path d="M12 5v14M5 12h14" /></svg>;
}

function AttentionIcon({ kind }: { kind: 'review' | 'question' | 'plan' }) {
  if (kind === 'review') {
    return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="18" cy="18" r="2.6" />
      <circle cx="6" cy="6" r="2.6" /><path d="M13 6h3a2 2 0 0 1 2 2v7M6 9v12" /></svg>;
  }
  if (kind === 'plan') {
    return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <rect x="5" y="3.5" width="14" height="17" rx="2" />
      <path d="M9 8h6M9 12h6M9 16h3.5" />
    </svg>;
  }
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      <path d="M12 8v3M12 13.5h.01" /></svg>;
}

function MergedIcon() {
  return <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    <circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" />
    <path d="M6 21V9a9 9 0 0 0 9 9" />
  </svg>;
}

function isToday(iso: string): boolean {
  const date = new Date(iso);
  const today = new Date();
  return date.getFullYear() === today.getFullYear()
    && date.getMonth() === today.getMonth()
    && date.getDate() === today.getDate();
}

function formatToday(): string {
  return new Intl.DateTimeFormat(undefined, {
    weekday: 'long', month: 'short', day: 'numeric',
  }).format(new Date());
}

function relativeTime(iso: string): string {
  const milliseconds = Date.now() - Date.parse(iso);
  if (!Number.isFinite(milliseconds) || milliseconds < 60_000) return 'now';
  if (milliseconds < 3_600_000) return `${Math.floor(milliseconds / 60_000)}m ago`;
  if (milliseconds < 86_400_000) return `${Math.floor(milliseconds / 3_600_000)}h ago`;
  return `${Math.floor(milliseconds / 86_400_000)}d ago`;
}

function compactRelativeTime(iso: string): string {
  return relativeTime(iso).replace(' ago', '');
}

function elapsed(iso: string): string {
  return relativeTime(iso).replace(' ago', '');
}

function shortInput(input: string): string {
  const oneLine = input.replace(/\s+/g, ' ').trim();
  return oneLine.length > 48 ? `${oneLine.slice(0, 47)}…` : oneLine;
}

function formatSpend(milliUsd: number): string {
  return `$${(milliUsd / 1000).toFixed(2)}`;
}
