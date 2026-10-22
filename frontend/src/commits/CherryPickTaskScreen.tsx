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
import { Check, CircleAlert, CornerDownRight, Plus, RotateCw, X } from 'lucide-react';
import { useEffect, useMemo, useReducer, useState, type ReactNode } from 'react';
import { Pill } from '@bytequay/ui/cards';
import { shortDate } from '@bytequay/ui/displayText';
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import { AgentConversation, ConversationComposer, Spinner, useConversationHistory, type ConversationHistory } from '../agents/AgentConversation';
import { TaskStats } from '../agents/AgentTaskScreen';
import TaskPaneDivider from '../agents/TaskPaneDivider';
import { executionTitle } from '../agents/taskList';
import { agentTaskReducer, historyBefore, initialAgentTaskState, startAgentConversationSession, unloadedTurnIds, type AgentTaskAction, type AgentTaskState } from '../agents/model';
import { projectTranscript, withApprovalAuthority, type TranscriptTurn } from '../agents/transcript';
import { mergePull } from '../assistant/pullActions';
import { PullRequestChip } from '../pulls/PullRequestChip';
import { usePullRequestDetail } from '../pulls/usePullRequestDetail';
import { remoteErrorMessage } from '../remoteError';
import type { AgentApprovalDecision, AgentMetricsDto, AgentPendingApprovalDto, AgentTurnDto, CherryPickExecution, CiAutofixRunDto, ModelOptionDto, PullRequestDetailDto } from '../types';
import { useNow } from '../useNow';
import { useResizablePane } from '../useResizablePane';
import {
  ciAttemptRows,
  cherryPickStages,
  cherryPickStatus,
  cherryPickWaitsFor,
  commitRows,
  commitSummary,
  driftBadge,
  greenRound,
  shortSha,
  type CommitRow,
  type StageView,
} from './model';
import '../css/cherry-pick-task.css';

const NO_HISTORY: ConversationHistory = { before: null, after: null, loading: false, onLoad: () => {}, onFollow: () => {} };

type Tab = 'stages' | 'commits' | 'ci';

/**
 * A cherry-pick as a task: the agents' conversation on the left, and the stages, commits and CI
 * checks it moves through on the right. The cherry-pick agent and each CI round's agent keep their
 * own transcripts; they read as one conversation, in the order their turns started.
 */
export default function CherryPickTaskScreen({ executionId, onBack, headerAction, onOpenPullRequest }: {
  executionId: string;
  onBack: () => void;
  headerAction?: ReactNode;
  onOpenPullRequest?: (number: number) => void;
}): React.JSX.Element {
  const [execution, setExecution] = useState<CherryPickExecution | null>(null);
  const [error, setError] = useState<string | null>(null);
  const finished = execution?.status === 'FINISHED';
  useEffect(() => {
    let active = true;
    const read = (): void => {
      void window.bridge.getCherryPickExecution(executionId).then(
        value => { if (active) { setExecution(value); setError(null); } },
        failure => { if (active) setError(remoteErrorMessage(failure)); });
    };
    read();
    if (finished) return () => { active = false; };
    const timer = window.setInterval(read, 3_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [executionId, finished]);

  if (execution === null) {
    return <section className="agt-screen" aria-label="Cherry-pick task">
      <div className="agt-screen-head">
        <button type="button" className="agt-back" onClick={onBack} aria-label="Back to tasks">‹ Tasks</button>
        <span className="agt-spacer" />{headerAction}
      </div>
      <div className="cpt-loading" role={error === null ? 'status' : 'alert'}>{error ?? 'Loading cherry-pick…'}</div>
    </section>;
  }
  return <CherryPickTask execution={execution} onChange={setExecution} error={error} onBack={onBack} headerAction={headerAction}
    onOpenPullRequest={onOpenPullRequest} />;
}

function CherryPickTask({ execution, onChange, error, onBack, headerAction, onOpenPullRequest }: {
  execution: CherryPickExecution;
  onChange: (execution: CherryPickExecution) => void;
  error: string | null;
  onBack: () => void;
  headerAction?: ReactNode;
  onOpenPullRequest?: (number: number) => void;
}): React.JSX.Element {
  const lifecycle = execution.lifecycle;
  const finished = execution.status === 'FINISHED';
  const rounds = useCiRounds(lifecycle.ciRunIds, !finished);
  const taskIds = [execution.agentTaskId, ...rounds.map(round => round.agentTaskId)].filter((id): id is string => typeof id === 'string');
  const conversations = useTaskConversations(taskIds);
  const approvals = usePendingApprovals(conversations);
  const pendingIds = useMemo(() => new Set(approvals.list.map(approval => approval.approvalId)), [approvals.list]);
  const turns = useMemo(() => mergedTurns(Object.values(conversations), pendingIds), [conversations, pendingIds]);
  const events = useMemo(() => Object.values(conversations).flatMap(state => state.events), [conversations]);
  const durableTurns = useMemo<Record<string, AgentTurnDto>>(() => Object.assign({}, ...Object.values(conversations).map(state => state.turns)), [conversations]);
  const running = Object.values(conversations).some(state => state.latestTurnId !== null && !state.latestTurnSettled);
  const now = useNow(running);
  const scroll = useConversationHistory(events, turns.at(-1)?.endedAt, NO_HISTORY);
  const models = useModels(execution.workspaceId);
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('stages');
  const [commitView, setCommitView] = useState<'interleaved' | 'split'>('interleaved');
  const pane = useResizablePane(560, 340, 1200, 'start');
  const pullNumber = execution.delivery?.pullRequestNumber ?? null;
  const pull = usePullRequestDetail(pullNumber === null ? null : { repo: execution.repository, number: pullNumber });

  const act = async (action: () => Promise<CherryPickExecution | void>): Promise<void> => {
    setBusy(true);
    setNotice(null);
    try {
      const next = await action();
      if (next) onChange(next);
    }
    catch (failure) {
      setNotice(remoteErrorMessage(failure));
    }
    finally {
      setBusy(false);
    }
  };
  const send = (): void => {
    const text = draft.trim();
    if (text === '') return;
    scroll.followSend();
    void act(async () => {
      const next = await window.bridge.sendCherryPickMessage(execution.executionId, text);
      setDraft('');
      return next;
    });
  };
  const approveSquash = (): void => { void act(() => window.bridge.approveCherryPickSquash(execution.executionId, execution.currentTargetSha)); };
  const merge = (): void => {
    if (pullNumber === null) return;
    void act(async () => {
      await mergePull(execution.repository, pullNumber, pull.detail?.headSha ?? null);
      await pull.refresh();
    });
  };
  const decide = (approvalId: string, decision: AgentApprovalDecision, answers?: Record<string, string>): void => {
    void window.bridge.resolveAgentApproval(approvalId, decision, undefined, answers)
      .then(() => approvals.refresh(), failure => setNotice(remoteErrorMessage(failure)));
  };

  const status = cherryPickStatus(execution, rounds);
  const waits = cherryPickWaitsFor(execution, rounds);
  const held = execution.status === 'PAUSED' || lifecycle.hold != null;
  const paused = lifecycle.hold?.reason === 'USER';
  const metrics = useMemo(() => turnMetrics(Object.values(durableTurns)), [durableTurns]);
  const latestSettings = Object.values(durableTurns).filter(turn => turn.taskId === execution.agentTaskId).at(-1)?.settings ?? null;
  const cards = milestoneCards(execution, rounds);
  const lastTurnId = turns.at(-1)?.turnId ?? null;
  const cardsByTurn = placeCards(turns, cards.timed);

  return (
    <div className="agt-screen">
      <div className="agt-body">
        <section className="agt-task-column" aria-label="Cherry-pick conversation">
          <div className="agt-screen-head">
            <button type="button" className="agt-back" onClick={onBack} aria-label="Back to tasks">‹ Tasks</button>
            <span className="cpt-title" title={executionTitle(execution)}>{shortTitle(execution)}</span>
            <Pill tone={status.tone} spinning={status.tone === 'blue' || status.tone === 'purple'}>{status.label}</Pill>
            <span className="agt-spacer" />
            {headerAction}
          </div>
          {(error !== null || notice !== null) && <div className="agt-notice" role="alert">{notice ?? error}</div>}
          <AgentConversation label="Cherry-pick conversation" turns={turns} repository={execution.repository} durableTurns={durableTurns}
            models={models} now={now} scroll={scroll} workspaceId={execution.workspaceId}
            connection={conversations[execution.agentTaskId ?? '']?.connection ?? 'open'} running={running} activeTurn={null}
            waiting={approvals.list.length > 0} pendingApprovals={approvals.list}
            activityChoice={activityChoice} turnChoice={turnChoice}
            onToggleTurn={(id, open) => setTurnChoice(choices => ({ ...choices, [id]: open }))}
            onToggleActivity={(id, open) => setActivityChoice(choices => ({ ...choices, [id]: open }))}
            onDecide={decide}
            turnFooter={turnId => (cardsByTurn.get(turnId) ?? []).map(card => <MilestoneCard key={card.key} card={card} />)}
            transcriptFooter={<>
              {(lastTurnId === null ? cards.timed : []).map(card => <MilestoneCard key={card.key} card={card} />)}
              {cards.now.map(card => <MilestoneCard key={card.key} card={card}
                onApprove={card.kind === 'approve' ? approveSquash : undefined}
                onReview={card.kind === 'approve' ? () => { setTab('commits'); setCommitView('split'); } : undefined} busy={busy} />)}
            </>}>
            {lifecycle.notes.length > 0 && <div className="agt-barrier" role="status">
              {lifecycle.notes.length === 1 ? '1 message waits' : `${lifecycle.notes.length} messages wait`} for the agent’s next step
            </div>}
            {(paused || pullNumber !== null) && <div className="agt-auto-row">
              {paused && <span className="cpt-paused">Paused — the agent finishes its current step, then waits. Auto-rebase is held too.</span>}
              {pullNumber !== null && <PullRequestChip pullRequest={{ number: pullNumber, title: pull.detail?.title ?? execution.pullRequestDraft?.title ?? '',
                state: pull.detail?.state ?? 'open', draft: pull.detail?.draft ?? true }}
                pressed={false} onClick={() => onOpenPullRequest?.(pullNumber)} />}
            </div>}
            {!finished && <ConversationComposer value={draft} onChange={setDraft} onSend={send} onStop={() => {}} onSteer={() => {}} onQueue={() => {}}
              running={false} sending={busy} disabled={busy} sendEnabled={!busy && draft.trim() !== ''} steerEnabled={false} queueEnabled={false}
              models={models} model={latestSettings?.model ?? null} effort={latestSettings?.effort ?? null}
              connection="open" placeholder="Message the agent — it reads messages between steps"
              footer={<TaskStats metrics={metrics} turns={turns} now={now} />} />}
          </AgentConversation>
        </section>
        <TaskPaneDivider pane={pane} />
        <section className="agt-panel cpt-panel" style={{ width: pane.width ?? undefined }} aria-label="Cherry-pick progress">
          <PanelHead execution={execution} rounds={rounds} pull={pull.detail} waits={waits} held={held} busy={busy}
            tab={tab} onTab={setTab}
            onApprove={approveSquash} onMerge={merge}
            onPause={() => { void act(() => held ? window.bridge.resumeCherryPick(execution.executionId) : window.bridge.pauseCherryPick(execution.executionId)); }}
            onAbort={() => { void act(() => window.bridge.cancelCherryPickExecution(execution.workspaceId, execution.executionId)); }} />
          <div className="cpt-body">
            {tab === 'stages' && <StagesTab execution={execution} stages={cherryPickStages(execution, rounds)} />}
            {tab === 'commits' && <CommitsTab execution={execution} view={commitView} onView={setCommitView} />}
            {tab === 'ci' && <CiTab rounds={rounds} pull={pull.detail} published={pullNumber !== null} />}
          </div>
        </section>
      </div>
    </div>
  );
}

function PanelHead({ execution, rounds, pull, waits, held, busy, tab, onTab, onApprove, onMerge, onPause, onAbort }: {
  execution: CherryPickExecution;
  rounds: CiAutofixRunDto[];
  pull: PullRequestDetailDto | null;
  waits: ReturnType<typeof cherryPickWaitsFor>;
  held: boolean;
  busy: boolean;
  tab: Tab;
  onTab: (tab: Tab) => void;
  onApprove: () => void;
  onMerge: () => void;
  onPause: () => void;
  onAbort: () => void;
}): React.JSX.Element {
  const [abortOpen, setAbortOpen] = useState(false);
  const popover = usePopoverDismiss(() => setAbortOpen(false), true, abortOpen);
  const lifecycle = execution.lifecycle;
  const finished = execution.status === 'FINISHED';
  const drift = driftBadge(execution);
  const rebasing = lifecycle.rebases.some(rebase => rebase.state === 'RUNNING') && !held;
  const stages = cherryPickStages(execution, rounds);
  const rows = commitRows(execution);
  const checks = pull?.checkRuns ?? [];
  const passed = checks.filter(check => check.category === 'successful').length;
  const mergeReady = waits === 'merge' && pull !== null && !pull.draft && pull.mergeable !== false;
  const pullNumber = execution.delivery?.pullRequestNumber ?? null;
  const hint = finished
    ? execution.finishReason === 'CANCELLED' ? 'Task aborted' : pullNumber !== null ? `Merged · PR #${pullNumber}` : 'Applied'
    : held ? lifecycle.hold?.summary ?? execution.summary ?? 'Paused — needs your input'
      : waits === 'squash' ? `CI green — squash ${plural(lifecycle.commits.filter(commit => commit.fixupOf).length, 'fixup')}?`
        : lifecycle.stage === 'MERGE' ? greenRound(rounds.at(-1)) ? 'CI green on the squashed head' : 'Waiting for CI on the squashed head'
          : 'Agent is working — nothing needed from you';
  const source = execution.sourceRepository ? `${repoName(execution.sourceRepository)} → ` : '';
  const stageTone = finished && execution.finishReason === 'CANCELLED' ? 'is-red' : stages.some(stage => stage.state === 'needs-you') ? 'is-amber'
    : stages.every(stage => stage.state === 'done') ? 'is-green' : 'is-blue';
  const ciTone = checks.length === 0 ? 'is-gray' : checks.some(check => check.category === 'pending') ? 'is-blue'
    : checks.some(check => check.category === 'failing') ? 'is-red' : 'is-green';
  const fixupCount = rows.fixups.length;
  const tabs: Array<{ id: Tab; label: string; count: string; tone: string }> = [
    { id: 'stages', label: 'Stages', count: `${stages.filter(stage => stage.state === 'done').length}/${stages.length}`, tone: stageTone },
    { id: 'commits', label: 'Commits', count: `${rows.sources.filter(row => row.state === 'applied').length} + ${fixupCount}`,
      tone: lifecycle.squash?.resultHead ? 'is-green' : 'is-blue' },
    { id: 'ci', label: 'CI checks', count: checks.length === 0 ? '—' : `${passed}/${checks.length}`, tone: ciTone },
  ];
  return (
    <div className="cpt-head">
      <div className="cpt-head__title">
        <span className="cpt-head__name">Cherry-pick</span>
        <span className="cpt-head__route" title={`${source}${execution.repository} · ${execution.targetBranch} · ${execution.workBranch}`}>
          {source}{repoName(execution.repository)} · {execution.targetBranch} · {execution.workBranch}
        </span>
        {drift !== null && <span className="cpt-drift">{rebasing && <span className="cpt-drift__spin" aria-hidden="true" />}{drift}</span>}
      </div>
      <div className="cpt-actions">
        {waits === 'squash' && <button type="button" className="cpt-primary" disabled={busy} onClick={onApprove}>Approve squash</button>}
        {lifecycle.stage === 'MERGE' && !finished && !held && (
          <button type="button" className="cpt-primary" disabled={busy || !mergeReady} onClick={onMerge}
            title={mergeReady ? undefined : 'Available once CI is green on the squashed head'}>
            {mergeReady ? 'Merge pull request' : 'Merge when green'}
          </button>
        )}
        <span className="cpt-hint" title={hint}>{hint}</span>
        {!finished && <>
          <button type="button" className="cpt-btn" disabled={busy} onClick={onPause}>{held ? 'Resume' : 'Pause'}</button>
          <button type="button" className="cpt-btn is-danger" disabled={busy} aria-expanded={abortOpen} onClick={() => setAbortOpen(open => !open)}>Abort…</button>
        </>}
        {abortOpen && (
          <div className="cpt-abort" ref={popover} role="dialog" aria-label="Abort and discard branch?">
            <span className="cpt-abort__title">Abort and discard branch?</span>
            <span className="cpt-abort__text">
              Deletes <span className="cpt-mono">{execution.workBranch}</span>
              {pullNumber !== null ? ` and closes draft PR #${pullNumber}` : ''}. {execution.targetBranch} is untouched.
            </span>
            <div className="cpt-abort__actions">
              <button type="button" className="cpt-btn" onClick={() => setAbortOpen(false)}>Keep running</button>
              <button type="button" className="cpt-danger" onClick={() => { setAbortOpen(false); onAbort(); }}>Abort task</button>
            </div>
          </div>
        )}
      </div>
      <div className="cpt-tabs" role="tablist" aria-label="Cherry-pick views">
        {tabs.map(entry => (
          <button key={entry.id} type="button" role="tab" aria-selected={tab === entry.id} className={`cpt-tab${tab === entry.id ? ' is-active' : ''}`}
            onClick={() => onTab(entry.id)}>
            <span className={`cpt-dot ${entry.tone}`} aria-hidden="true" />{entry.label}<span className="cpt-tab__count">{entry.count}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

function StagesTab({ execution, stages }: { execution: CherryPickExecution; stages: StageView[] }): React.JSX.Element {
  const rebases = execution.lifecycle.rebases;
  const moved = rebases.filter(rebase => rebase.state !== 'FAILED').reduce((total, rebase) => total + rebase.targetMoved, 0);
  return (
    <div className="cpt-stages">
      <ol className="cpt-rail" aria-label="Stages">
        {stages.map((stage, index) => (
          <li key={stage.stage} className={`cpt-stage is-${stage.state}`}>
            <span className="cpt-stage__marker" aria-hidden="true">
              <span className="cpt-stage__circle">
                {stage.state === 'done' && <Check size={11} strokeWidth={3} />}
                {stage.state === 'needs-you' && '!'}
              </span>
              {index < stages.length - 1 && <span className="cpt-stage__rail" />}
            </span>
            <span className="cpt-stage__text">
              <span className="cpt-stage__name">{stage.name}</span>
              <span className="cpt-stage__detail">{stage.detail}</span>
            </span>
            <span className="cpt-stage__time">{stage.state === 'running' || stage.state === 'needs-you' ? 'now' : stage.at ? shortDate(stage.at) : ''}</span>
          </li>
        ))}
      </ol>
      {execution.lifecycle.delivers && <div className="cpt-section">
        <div className="cpt-section__head">Target drift<span>{moved === 0 ? 'none' : `${execution.targetBranch} +${moved} since start`}</span></div>
        <div className="cpt-list">
          {rebases.map(rebase => (
            <div key={rebase.number} className={`cpt-row${rebase.state === 'RUNNING' ? ' is-live' : ''}`}>
              <span className="cpt-row__icon is-purple" aria-hidden="true">{rebase.state === 'FAILED' ? <X size={12} /> : <RotateCw size={12} />}</span>
              <span className="cpt-row__text">
                <span className="cpt-row__title">Auto-rebase #{rebase.number}{rebase.state === 'RUNNING' ? ' · running' : rebase.state === 'FAILED' ? ' · stopped' : ''}</span>
                <span className="cpt-row__detail">{execution.targetBranch} +{rebase.targetMoved}{rebase.summary ? ` · ${rebase.summary}` : ''}</span>
              </span>
              <span className="cpt-row__time">{rebase.state === 'RUNNING' ? 'now' : shortDate(rebase.endedAt ?? rebase.startedAt)}</span>
            </div>
          ))}
          {rebases.length === 0 && <div className="cpt-empty">Target hasn’t moved. The agent rebases automatically if it does.</div>}
        </div>
      </div>}
    </div>
  );
}

function CommitsTab({ execution, view, onView }: {
  execution: CherryPickExecution;
  view: 'interleaved' | 'split';
  onView: (view: 'interleaved' | 'split') => void;
}): React.JSX.Element {
  const rows = commitRows(execution);
  return (
    <div className="cpt-commits">
      <div className="cpt-commits__bar">
        <span className="cpt-commits__summary">{commitSummary(execution)}</span>
        <div className="cpt-seg" role="group" aria-label="Commit view">
          <button type="button" aria-pressed={view === 'interleaved'} onClick={() => onView('interleaved')}>Interleaved</button>
          <button type="button" aria-pressed={view === 'split'} onClick={() => onView('split')}>Source / agent</button>
        </div>
      </div>
      {view === 'interleaved' ? <div className="cpt-list">{rows.interleaved.map(row => <CommitLine key={row.key} row={row} />)}</div> : <>
        <span className="cpt-label">Source · {rows.sources.length}</span>
        <div className="cpt-list">{rows.sources.map(row => <CommitLine key={row.key} row={row} />)}</div>
        <span className="cpt-label is-agent">Agent fixups · {rows.fixups.length}</span>
        <div className="cpt-list">
          {rows.fixups.map(row => <CommitLine key={row.key} row={row} flat />)}
          {rows.fixups.length === 0 && <div className="cpt-empty">No fixups yet</div>}
        </div>
      </>}
    </div>
  );
}

function CommitLine({ row, flat = false }: { row: CommitRow; flat?: boolean }): React.JSX.Element {
  const icon = row.state === 'applied' ? <Check size={12} /> : row.state === 'resolving' ? <CircleAlert size={12} />
    : row.state === 'fixup' ? <Plus size={12} /> : row.state === 'squashed' ? <CornerDownRight size={12} /> : <span className="cpt-ring" />;
  return (
    <div className={`cpt-row is-${row.state}${row.kind === 'fixup' && !flat ? ' is-nested' : ''}`}>
      <span className="cpt-row__icon" aria-hidden="true">{icon}</span>
      <span className="cpt-row__text">
        <span className="cpt-row__title">{row.title}</span>
        <span className="cpt-row__shas">{row.shas}</span>
      </span>
      <span className="cpt-row__tag">{row.tag}</span>
    </div>
  );
}

const CHECK_STATE: Record<PullRequestDetailDto['checkRuns'][number]['category'], { label: string; tone: string }> = {
  successful: { label: 'passed', tone: 'is-green' },
  failing: { label: 'failed', tone: 'is-red' },
  pending: { label: 'running', tone: 'is-blue' },
  skipped: { label: 'skipped', tone: 'is-gray' },
  cancelled: { label: 'cancelled', tone: 'is-gray' },
  neutral: { label: 'neutral', tone: 'is-gray' },
};

function CiTab({ rounds, pull, published }: { rounds: CiAutofixRunDto[]; pull: PullRequestDetailDto | null; published: boolean }): React.JSX.Element {
  const attempts = ciAttemptRows(rounds);
  const checks = pull?.checkRuns ?? [];
  return (
    <div className="cpt-ci">
      <div className="cpt-section">
        <div className="cpt-section__head">Current run
          <span className="cpt-mono">{pull?.headSha ? `round ${Math.max(1, rounds.length)} · ${shortSha(pull.headSha)}` : ''}</span>
        </div>
        <div className="cpt-list">
          {checks.map((check, index) => {
            const state = CHECK_STATE[check.category];
            return (
              <div key={check.githubId ?? `${check.name}-${index}`} className="cpt-row">
                <span className={`cpt-row__icon ${state.tone}`} aria-hidden="true">
                  {check.category === 'successful' ? <Check size={12} /> : check.category === 'failing' ? <X size={12} /> : <span className="cpt-ring" />}
                </span>
                <span className="cpt-row__text"><span className="cpt-row__title cpt-mono">{check.name ?? 'check'}</span></span>
                <span className={`cpt-row__tag ${state.tone}`}>{state.label}</span>
              </div>
            );
          })}
          {checks.length === 0 && <div className="cpt-empty">{published ? 'No checks reported yet' : 'CI runs after the work branch is pushed'}</div>}
        </div>
      </div>
      <div className="cpt-section">
        <div className="cpt-section__head">Attempts<span>fix until green</span></div>
        <div className="cpt-list">
          {attempts.map(attempt => (
            <div key={attempt.key} className="cpt-row">
              <span className={`cpt-row__icon ${attempt.state === 'passed' ? 'is-green' : attempt.state === 'failed' ? 'is-red' : attempt.state === 'running' ? 'is-blue' : 'is-gray'}`}
                aria-hidden="true">
                {attempt.state === 'passed' ? <Check size={12} /> : attempt.state === 'failed' ? <X size={12} /> : <span className="cpt-ring" />}
              </span>
              <span className="cpt-row__text">
                <span className="cpt-row__title">{attempt.title}</span>
                {attempt.detail !== '' && <span className="cpt-row__detail">{attempt.detail}</span>}
              </span>
              <span className="cpt-row__time">{attempt.at === null ? 'now' : shortDate(attempt.at)}</span>
            </div>
          ))}
          {attempts.length === 0 && <div className="cpt-empty">No CI attempts yet</div>}
        </div>
      </div>
    </div>
  );
}

type MilestoneKind = 'rebase' | 'rebase-run' | 'ci-pass' | 'ci-fail' | 'squashed' | 'approve' | 'stopped' | 'merged' | 'aborted';

type Milestone = { key: string; kind: MilestoneKind; title: string; sub: string; body: string; at: string | null };

const MILESTONE_LABEL: Record<MilestoneKind, string> = {
  rebase: 'Auto-rebase', 'rebase-run': 'Auto-rebase', 'ci-pass': 'CI', 'ci-fail': 'CI', squashed: 'Squash',
  approve: 'Needs you', stopped: 'Needs you', merged: 'Merged', aborted: 'Aborted',
};

/** What the execution did between agent turns: rebases, CI round results, the squash, and what waits on the user now. */
function milestoneCards(execution: CherryPickExecution, rounds: CiAutofixRunDto[]): { timed: Milestone[]; now: Milestone[] } {
  const lifecycle = execution.lifecycle;
  const timed: Milestone[] = [
    ...lifecycle.rebases.map((rebase): Milestone => ({
      key: `rebase-${rebase.number}`,
      kind: rebase.state === 'RUNNING' ? 'rebase-run' : rebase.state === 'FAILED' ? 'ci-fail' : 'rebase',
      title: rebase.state === 'RUNNING' ? `Auto-rebasing #${rebase.number}` : `Auto-rebase #${rebase.number}${rebase.state === 'FAILED' ? ' stopped' : ''}`,
      sub: `${execution.targetBranch} moved +${rebase.targetMoved}`,
      body: rebase.summary ?? 'CI round stopped. Replaying the commits onto the new target head…',
      at: rebase.startedAt,
    })),
    ...rounds.flatMap((round, index): Milestone[] => round.status === 'ACTIVE' ? [] : [{
      key: `round-${round.id}`,
      kind: greenRound(round) ? 'ci-pass' : 'ci-fail',
      title: `CI round ${index + 1} ${greenRound(round) ? 'green' : round.finishReason === 'CANCELLED' ? 'cancelled' : 'stopped'}`,
      sub: `${plural(round.attemptsUsed, 'attempt')} · ${shortSha(round.expectedRemoteHead)}`,
      body: round.summary ?? '',
      at: round.updatedAt,
    }]),
    ...(lifecycle.squash?.resultHead ? [{
      key: 'squashed', kind: 'squashed' as const,
      title: `Squashed ${plural(lifecycle.squash.fixups.length, 'fixup')}`,
      sub: `${plural(lifecycle.squash.fixups.length, 'fixup')} → ${plural(lifecycle.commits.length, 'commit')}`,
      body: `Force-pushed ${shortSha(lifecycle.squash.resultHead)} and started CI on the squashed head.`,
      at: lifecycle.squash.approvedAt,
    }] : []),
  ];
  const now: Milestone[] = [];
  const fixups = lifecycle.commits.filter(commit => commit.fixupOf);
  if (cherryPickWaitsFor(execution, rounds) === 'squash') {
    now.push({
      key: 'approve', kind: 'approve', title: `Squash ${plural(fixups.length, 'fixup')}`,
      sub: `${plural(fixups.length, 'fixup')} → ${plural(lifecycle.commits.length - fixups.length, 'commit')}`,
      body: fixups.map(fixup => {
        const into = lifecycle.commits.find(commit => commit.sourceSha === fixup.fixupOf);
        return `${shortSha(fixup.sha)} → ${into ? shortSha(into.sha) : 'its commit'}`;
      }).join(' · '),
      at: null,
    });
  }
  if (lifecycle.hold && lifecycle.hold.reason !== 'USER' && execution.status !== 'FINISHED') {
    now.push({ key: 'hold', kind: 'stopped', title: 'Paused — needs you', sub: lifecycle.hold.reason.toLowerCase().replaceAll('_', ' '),
      body: `${lifecycle.hold.summary} Send a message or Resume to continue.`, at: lifecycle.hold.at });
  }
  if (execution.status === 'PAUSED') {
    now.push({ key: 'paused', kind: 'stopped', title: 'Paused — needs you', sub: (execution.pauseReason ?? '').toLowerCase().replaceAll('_', ' '),
      body: `${execution.summary ?? 'The worktree is preserved.'} Send a message or Resume to continue.`, at: null });
  }
  if (execution.status === 'FINISHED') {
    now.push(execution.finishReason === 'CANCELLED'
      ? { key: 'end', kind: 'aborted', title: 'Task aborted', sub: execution.workBranch, body: execution.summary ?? '', at: execution.terminalAt }
      : { key: 'end', kind: 'merged', title: `Merged into ${execution.targetBranch}`, sub: execution.delivery?.pullRequestNumber ? `PR #${execution.delivery.pullRequestNumber}` : '',
        body: execution.summary ?? 'Task complete.', at: execution.terminalAt });
  }
  return { timed, now };
}

/** Hangs each timed card under the last turn that started before it, so the feed stays in time order. */
function placeCards(turns: TranscriptTurn[], cards: Milestone[]): Map<string, Milestone[]> {
  const placed = new Map<string, Milestone[]>();
  for (const card of cards) {
    const owner = [...turns].reverse().find(turn => turn.turnId !== null && turnTime(turn) <= (card.at ?? ''))
      ?? turns.find(turn => turn.turnId !== null);
    if (owner?.turnId == null) continue;
    placed.set(owner.turnId, [...(placed.get(owner.turnId) ?? []), card]);
  }
  return placed;
}

function MilestoneCard({ card, onApprove, onReview, busy = false }: {
  card: Milestone;
  onApprove?: () => void;
  onReview?: () => void;
  busy?: boolean;
}): React.JSX.Element {
  const icon = card.kind === 'rebase-run' ? <Spinner /> : card.kind === 'rebase' ? <RotateCw size={11} strokeWidth={2.5} />
    : card.kind === 'ci-fail' || card.kind === 'aborted' ? <X size={11} strokeWidth={3} />
      : card.kind === 'approve' || card.kind === 'stopped' ? '!' : <Check size={11} strokeWidth={3} />;
  return (
    <div className={`cpt-card is-${card.kind}`} role="group" aria-label={card.title}>
      <div className="cpt-card__head">
        <span className="cpt-card__icon" aria-hidden="true">{icon}</span>
        <span className="cpt-card__heading">
          <span className="cpt-card__title">{card.title}</span>
          {card.sub !== '' && <span className="cpt-card__sub">{card.sub}</span>}
        </span>
        <span className="cpt-card__kind">{MILESTONE_LABEL[card.kind]}</span>
      </div>
      {card.body !== '' && <div className="cpt-card__body">{card.body}</div>}
      {(onApprove !== undefined || onReview !== undefined) && (
        <div className="cpt-card__actions">
          {onApprove !== undefined && <button type="button" className="cpt-primary" disabled={busy} onClick={onApprove}>Approve squash</button>}
          {onReview !== undefined && <button type="button" className="cpt-btn" onClick={onReview}>Review fixups</button>}
        </div>
      )}
    </div>
  );
}

function useCiRounds(runIds: string[], live: boolean): CiAutofixRunDto[] {
  const [runs, setRuns] = useState<Record<string, CiAutofixRunDto>>({});
  const key = runIds.join(',');
  useEffect(() => {
    if (key === '') return undefined;
    let active = true;
    const read = (): void => {
      void Promise.all(key.split(',').map(id => window.bridge.getCiAutofixRun(id)))
        .then(values => { if (active) setRuns(Object.fromEntries(values.map(run => [run.id, run]))); }, () => {});
    };
    read();
    if (!live) return () => { active = false; };
    const timer = window.setInterval(read, 5_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [key, live]);
  return useMemo(() => key === '' ? [] : key.split(',').flatMap(id => runs[id] === undefined ? [] : [runs[id]]), [key, runs]);
}

type ConversationAction = { taskId: string; action: AgentTaskAction };

function conversationsReducer(states: Record<string, AgentTaskState>, { taskId, action }: ConversationAction): Record<string, AgentTaskState> {
  return { ...states, [taskId]: agentTaskReducer(states[taskId] ?? initialAgentTaskState(), action) };
}

/** One bounded history and live stream per agent task, with the durable turn each message ran as. */
function useTaskConversations(taskIds: string[]): Record<string, AgentTaskState> {
  const [states, dispatch] = useReducer(conversationsReducer, {});
  const key = taskIds.join(',');
  useEffect(() => {
    if (key === '') return undefined;
    let live = true;
    const sessions = key.split(',').map(taskId => startAgentConversationSession(window.bridge, taskId,
      action => { if (live) dispatch({ taskId, action }); }, () => live)
      .catch((failure: unknown) => {
        if (live) dispatch({ taskId, action: { type: 'notice/changed', notice: remoteErrorMessage(failure) } });
        return () => {};
      }));
    return () => {
      live = false;
      sessions.forEach(session => { void session.then(stop => stop()); });
    };
  }, [key]);
  const unloaded = Object.values(states).flatMap(unloadedTurnIds).join(',');
  useEffect(() => {
    if (unloaded === '') return undefined;
    let live = true;
    void Promise.all(unloaded.split(',').map(turnId => window.bridge.getAgentTurn(turnId))).then(turns => {
      if (live) turns.forEach(turn => dispatch({ taskId: turn.taskId, action: { type: 'turn/loaded', turn } }));
    }, () => {});
    return () => { live = false; };
  }, [unloaded]);
  return states;
}

/** Approvals the live turns are waiting on; a decided one leaves on the next read. */
function usePendingApprovals(states: Record<string, AgentTaskState>): { list: AgentPendingApprovalDto[]; refresh: () => void } {
  const [list, setList] = useState<AgentPendingApprovalDto[]>([]);
  const [revision, setRevision] = useState(0);
  const live = Object.entries(states)
    .filter(([, state]) => state.latestTurnId !== null && !state.latestTurnSettled)
    .map(([taskId, state]) => `${taskId}:${state.latestTurnId}`)
    .join(',');
  useEffect(() => {
    if (live === '') {
      setList([]);
      return undefined;
    }
    let active = true;
    const read = (): void => {
      void Promise.all(live.split(',').map(entry => {
        const [taskId, turnId] = entry.split(':');
        return window.bridge.readAgentPendingApprovals(taskId, turnId);
      })).then(values => { if (active) setList(values.flat()); }, () => {});
    };
    read();
    const timer = window.setInterval(read, 5_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [live, revision]);
  return { list, refresh: () => setRevision(value => value + 1) };
}

function useModels(workspaceId: string): ModelOptionDto[] {
  const [models, setModels] = useState<ModelOptionDto[]>([]);
  useEffect(() => {
    let active = true;
    void window.bridge.getModelSettings(workspaceId).then(settings => {
      if (active) setModels(settings.providers.flatMap(provider => provider.models));
    }, () => {});
    return () => { active = false; };
  }, [workspaceId]);
  return models;
}

function turnTime(turn: TranscriptTurn): string {
  return turn.startedAt ?? turn.users[0]?.at ?? '';
}

function mergedTurns(states: AgentTaskState[], pending: ReadonlySet<string>): TranscriptTurn[] {
  return states
    .flatMap(state => withApprovalAuthority(projectTranscript(state.events, null, state.turns, historyBefore(state) === null, true), pending))
    .sort((left, right) => turnTime(left).localeCompare(turnTime(right)));
}

function turnMetrics(turns: Array<{ metrics: AgentMetricsDto }>): AgentMetricsDto | null {
  if (turns.length === 0) return null;
  return turns.reduce<AgentMetricsDto>((total, turn) => ({
    ...total,
    inputTokens: total.inputTokens + turn.metrics.inputTokens,
    cachedInputTokens: total.cachedInputTokens + turn.metrics.cachedInputTokens,
    outputTokens: total.outputTokens + turn.metrics.outputTokens,
  }), { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: '' });
}

function plural(amount: number, word: string): string {
  return `${amount} ${amount === 1 ? word : `${word}s`}`;
}

function repoName(repository: string): string {
  return repository.split('/').at(-1) ?? repository;
}

function shortTitle(execution: CherryPickExecution): string {
  const count = execution.items.filter(item => item.result !== 'USER_SKIPPED' && item.result !== 'ALREADY_ON_TARGET').length;
  return `${plural(count, 'commit')} → ${execution.targetBranch}`;
}
