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

import React, { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { ModelChip } from '../settings/SettingsShell';
import { INTERACTIVE_LANE_ID } from '../settings/model';
import { relativeTime } from '../relativeTime';
import '../css/agents.css';
import {
  agentIdempotencyKey,
  agentTaskReducer,
  approvalDecisions,
  canSend,
  initialAgentTaskState,
  retryableMessage,
  runtimeStatus,
  startAgentTaskSession,
  unloadedTurnIds,
  type AgentBridgePort,
  type AgentRuntimeStatus,
  type AgentTaskState,
} from './model';
import {
  activityElapsedMs,
  activityExpanded,
  activityPhrase,
  activityRunning,
  activitySummary,
  currentPlan,
  formatDuration,
  planChip,
  planSummary,
  projectTranscript,
  turnElapsedMs,
  turnLabel,
  usageText,
  type ActivityBlock,
  type ActivityMember,
  type ApprovalBlock,
  type CommandMember,
  type PlanStep,
  type TranscriptTurn,
  type TurnBlock,
} from './transcript';
import type { AgentApprovalDecision, AgentTaskDto, AgentTurnDto, ModelOptionDto } from '../types';

export interface AgentTaskScreenProps {
  workspaceId: string;
  taskId: string;
  /** Electron's bridge unless a test supplies its own. */
  bridge?: AgentBridgePort;
  onBack: () => void;
  /** Fixed at creation; the screen reads its catalog, it does not offer a choice. */
  laneId?: string;
}

function stateClass(state: string): string {
  return state.toLowerCase();
}

/** The catalog's display name for a model id; the raw slug is never shown as a label. */
function modelName(models: ModelOptionDto[], id: string | null): string {
  if (id === null || id === '') return 'No model';
  return models.find((model) => model.id === id)?.name ?? id;
}

/** Backend policy names are enum values, not prose, and not provider wire strings. */
function permissionLabel(policy: string): string {
  return policy === 'AUTO' ? 'Auto' : policy;
}

function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

/** "Today 4:31 PM", or the date when the message is older than today. */
function stamp(iso: string, now: number): string {
  const at = new Date(iso);
  const today = new Date(now);
  const sameDay = at.getFullYear() === today.getFullYear()
    && at.getMonth() === today.getMonth() && at.getDate() === today.getDate();
  const day = sameDay ? 'Today' : at.toLocaleDateString([], { month: 'short', day: 'numeric' });
  return `${day} ${clock(iso)}`;
}

function Spinner(): React.JSX.Element {
  return <span className="agt-spin" aria-hidden="true" />;
}

function Chevron({ open }: { open: boolean }): React.JSX.Element {
  return <span className={`agt-chevron${open ? ' is-open' : ''}`} aria-hidden="true">›</span>;
}

function StateIcon({ state }: { state: 'running' | 'ok' | 'failed' }): React.JSX.Element {
  if (state === 'running') return <span className="agt-icon"><Spinner /></span>;
  return <span className={`agt-icon ${state === 'ok' ? 'is-ok' : 'is-failed'}`} aria-hidden="true">{state === 'ok' ? '✓' : '✕'}</span>;
}

function memberState(member: ActivityMember): 'running' | 'ok' | 'failed' {
  if (member.kind === 'file') return 'ok';
  if (member.state === 'STARTED' || member.state === 'PROGRESS') return 'running';
  if (member.state === 'FAILED') return 'failed';
  return member.kind === 'command' && member.exitCode !== null && member.exitCode !== 0 ? 'failed' : 'ok';
}

const ACTION_VERBS = { READ: 'Read', LIST_FILES: 'Listed', SEARCH: 'Searched', OTHER: '' } as const;

function Duration({ ms }: { ms: number | null }): React.JSX.Element | null {
  return ms === null ? null : <span className="agt-mono agt-muted agt-right">{formatDuration(ms)}</span>;
}

/** One command, as the rows the provider's parsing of it justifies: reads and searches by name, the rest verbatim. */
function CommandRows({ member, live }: { member: CommandMember; live: boolean }): React.JSX.Element {
  const state = memberState(member);
  const parsed = member.actions.filter((action) => action.kind !== 'OTHER');
  const verbatim = parsed.length === 0 || member.actions.some((action) => action.kind === 'OTHER');
  const running = state === 'running';
  return (
    <>
      {parsed.map((action, index) => (
        <div className="agt-act__row" key={`${member.id}:${index}`}>
          <StateIcon state={state} />
          <span className="agt-muted">{ACTION_VERBS[action.kind]}</span>
          <span className="agt-mono">
            {action.kind === 'SEARCH'
              ? `${action.query ?? member.command}${action.path === null ? '' : ` in ${action.path}`}`
              : action.path ?? '.'}
          </span>
          <span className="agt-spacer" />
          {index === 0 && !verbatim && <Duration ms={member.durationMs} />}
        </div>
      ))}
      {verbatim && (
        <div className="agt-act__row is-command">
          <StateIcon state={state} />
          <span className="agt-mono">$ {member.command}</span>
          {member.exitCode !== null && (
            <span className={`agt-exit ${member.exitCode === 0 ? 'is-ok' : 'is-failed'}`}>exit {member.exitCode}</span>
          )}
          {member.cwd !== null && member.cwd !== '.' && <span className="agt-mono agt-muted">in {member.cwd}</span>}
          <span className="agt-spacer" />
          <Duration ms={member.durationMs} />
        </div>
      )}
      {member.output !== null && member.output !== '' && (
        // Output folds behind a native disclosure, so it is keyboard-operable for free; a running
        // command's output stays open because that is what the user is watching.
        <details className="agt-out" open={live && running ? true : undefined}>
          <summary>Output{member.truncated ? ' (truncated)' : ''}</summary>
          <pre className="agt-pre">{member.output}{running && <span className="agt-cursor" aria-hidden="true" />}</pre>
        </details>
      )}
    </>
  );
}

const CHANGE_VERBS: Record<string, string> = { ADDED: 'Added', MODIFIED: 'Edited', DELETED: 'Deleted', RENAMED: 'Renamed' };

function MemberRows({ members, live }: { members: ActivityMember[]; live: boolean }): React.JSX.Element {
  return (
    <>
      {members.map((member) => {
        if (member.kind === 'command') return <CommandRows key={member.id} member={member} live={live} />;
        if (member.kind === 'file') {
          return (
            <React.Fragment key={member.id}>
              <div className="agt-act__row">
                <StateIcon state="ok" />
                <span className="agt-muted">{CHANGE_VERBS[member.change] ?? member.change}</span>
                <span className="agt-mono">{member.path}</span>
                {member.diff !== null && (
                  <>
                    <span className="agt-mono agt-added">+{member.added}</span>
                    <span className="agt-mono agt-removed">−{member.removed}</span>
                  </>
                )}
              </div>
              {member.diff !== null && (
                <details className="agt-out">
                  <summary>Diff</summary>
                  <pre className="agt-pre">{member.diff}</pre>
                </details>
              )}
            </React.Fragment>
          );
        }
        return (
          <div className="agt-act__row" key={member.id}>
            <StateIcon state={memberState(member)} />
            <span className="agt-muted">{member.name}</span>
            {member.summary !== '' && member.summary !== member.name && <span className="agt-mono">{member.summary}</span>}
            <span className="agt-spacer" />
            <Duration ms={member.durationMs} />
          </div>
        );
      })}
    </>
  );
}

/**
 * Contiguous activity as one block: a running group shows its members under a "Working" header,
 * a finished one folds to a sentence derived from those members unless the user opened it.
 */
function ActivityView({ block, now, expanded, onToggle }: {
  block: ActivityBlock;
  now: number;
  expanded: boolean;
  onToggle: () => void;
}): React.JSX.Element {
  const elapsed = formatDuration(activityElapsedMs(block, now));
  if (activityRunning(block)) {
    return (
      <div className="agt-act is-running">
        <div className="agt-act__head">
          <Spinner />
          <span className="agt-act__title">Working</span>
          <span className="agt-muted">— {activityPhrase(block.members)}</span>
          <span className="agt-spacer" />
          <span className="agt-mono agt-muted">{elapsed}</span>
        </div>
        <MemberRows members={block.members} live />
      </div>
    );
  }
  return (
    <div className="agt-act">
      <button type="button" className="agt-act__fold" aria-expanded={expanded} onClick={onToggle}>
        <Chevron open={expanded} />
        <span>{activitySummary(block.members)}</span>
        <span className="agt-spacer" />
        <span className="agt-mono agt-muted">{elapsed}</span>
      </button>
      {expanded && <div className="agt-act__body"><MemberRows members={block.members} live={false} /></div>}
    </div>
  );
}

function PlanSteps({ steps, compact = false }: { steps: PlanStep[]; compact?: boolean }): React.JSX.Element {
  return (
    <div className={compact ? 'agt-plan__steps is-compact' : 'agt-plan__steps'}>
      {steps.map((step, index) => (
        <div className={`agt-plan__step is-${step.state.toLowerCase().replace('_', '-')}`} key={index}>
          {step.state === 'IN_PROGRESS'
            ? <span className="agt-icon"><Spinner /></span>
            : <span className={`agt-icon ${step.state === 'COMPLETED' ? 'is-ok' : 'is-pending'}`} aria-hidden="true">{step.state === 'COMPLETED' ? '✓' : '○'}</span>}
          <span className="agt-plan__text">{step.text}</span>
        </div>
      ))}
    </div>
  );
}

const OUTCOME_LABELS: Record<string, string> = {
  ALLOWED: 'Approved once',
  DENIED: 'Denied',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
};

function ApprovalView({ block, onDecide }: {
  block: ApprovalBlock;
  onDecide?: (approvalId: string, decision: AgentApprovalDecision) => void;
}): React.JSX.Element {
  const decisions = approvalDecisions(block);
  if (block.outcome !== null) {
    return (
      <div className="agt-appr-done" role="group" aria-label={`Approval: ${block.title}`}>
        <span className={`agt-icon ${block.outcome === 'ALLOWED' ? 'is-ok' : 'is-failed'}`} aria-hidden="true">{block.outcome === 'ALLOWED' ? '✓' : '✕'}</span>
        <span>{OUTCOME_LABELS[block.outcome] ?? block.outcome} —</span>
        <span className="agt-mono">{block.detail}</span>
        <span>· {clock(block.at)}</span>
      </div>
    );
  }
  return (
    <div className="agt-appr" role="group" aria-label={`Approval: ${block.title}`}>
      <div className="agt-appr__head">
        <span className="agt-chip is-warn">Approval needed</span>
        <span className="agt-appr__title">{block.title}</span>
        <span className="agt-spacer" />
        <span className="agt-mono agt-muted">{block.agent}</span>
      </div>
      {block.detail !== '' && <pre className="agt-code">{block.detail}</pre>}
      {block.scope.length > 0 && (
        <ul className="agt-scope" aria-label="Requested permissions">
          {block.scope.map((entry) => <li key={entry}>{entry}</li>)}
        </ul>
      )}
      {decisions.length > 0 && (
        <div className="agt-decisions">
          {decisions.map((choice) => (
            <button
              key={choice.offered}
              type="button"
              className={`agt-decision${choice.decision === 'ALLOW' ? ' is-allow' : ' is-deny'}`}
              aria-label={`${choice.label}: ${block.title}`}
              onClick={() => onDecide?.(block.approvalId, choice.decision)}
            >
              {choice.label}
            </button>
          ))}
        </div>
      )}
      {!block.actionable && block.outcome === null && <div className="agt-muted">Waiting on the provider</div>}
    </div>
  );
}

function BlockView({ block, now, activityChoice, onToggleActivity, onDecide }: {
  block: TurnBlock;
  now: number;
  activityChoice: Record<string, boolean>;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide?: (approvalId: string, decision: AgentApprovalDecision) => void;
}): React.JSX.Element {
  switch (block.kind) {
    case 'text':
      if (block.role === 'reasoning') {
        return <div className="agt-reasoning">{block.text}{!block.complete && <span className="agt-cursor" aria-hidden="true" />}</div>;
      }
      if (block.role === 'planText') {
        return (
          <div className="agt-plan">
            <div className="agt-plan__head"><span className="agt-label">Plan</span></div>
            <div className="agt-text agt-pre-wrap">{block.text}{!block.complete && <span className="agt-cursor" aria-hidden="true" />}</div>
          </div>
        );
      }
      return <div className="agt-text">{block.text}{!block.complete && <span className="agt-cursor" aria-hidden="true" />}</div>;
    case 'plan':
      return (
        <div className="agt-plan">
          <div className="agt-plan__head">
            <span className="agt-label">Plan</span>
            <span className="agt-chip">{planChip(block.steps)}</span>
          </div>
          <PlanSteps steps={block.steps} />
        </div>
      );
    case 'activity': {
      const expanded = activityExpanded(block, activityChoice[block.id]);
      return <ActivityView block={block} now={now} expanded={expanded} onToggle={() => onToggleActivity(block.id, !expanded)} />;
    }
    case 'approval':
      return <ApprovalView block={block} onDecide={onDecide} />;
    case 'error':
      return <div className="agt-error" role="alert">{block.message}{block.retryable ? ' — retrying' : ''}</div>;
    case 'note':
      return <div className="agt-note">{block.text}</div>;
    default:
      return <></>;
  }
}

/** The line under an answer: what the turn ran with and what it used, from the durable turn and its usage event. */
function TurnFooter({ turn, durable, models }: {
  turn: TranscriptTurn;
  durable: AgentTurnDto | undefined;
  models: ModelOptionDto[];
}): React.JSX.Element {
  const parts = [`Turn ${turn.ordinal}`];
  const elapsed = turnElapsedMs(turn, 0);
  if (elapsed !== null) parts.push(formatDuration(elapsed));
  if (durable?.settings) {
    parts.push(`${modelName(models, durable.settings.model)}${durable.settings.effort === null ? '' : ` ${durable.settings.effort}`}`);
    parts.push(permissionLabel(durable.settings.permissionPolicy));
  }
  if (turn.endedAt !== null) parts.push(clock(turn.endedAt));
  return (
    <div className="agt-usage">
      <span className={`agt-icon ${turn.outcome === 'interrupted' ? 'is-failed' : 'is-ok'}`} aria-hidden="true">{turn.outcome === 'interrupted' ? '✕' : '✓'}</span>
      <span>{parts.join(' · ')}</span>
      {turn.usage !== null && <span className="agt-usage__tokens" title="Token usage">{usageText(turn.usage)}</span>}
    </div>
  );
}

function TurnView({ turn, first, open, now, models, durableTurns, activityChoice, onToggle, onToggleActivity, onDecide }: {
  turn: TranscriptTurn;
  first: boolean;
  open: boolean;
  now: number;
  models: ModelOptionDto[];
  durableTurns: Record<string, AgentTurnDto>;
  activityChoice: Record<string, boolean>;
  onToggle: () => void;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide?: (approvalId: string, decision: AgentApprovalDecision) => void;
}): React.JSX.Element {
  const waiting = turn.blocks.some((block) => block.kind === 'approval' && block.actionable && block.outcome === null);
  const live = turn.startedAt !== null && turn.endedAt === null && !waiting;
  const hasLog = turn.startedAt !== null || turn.blocks.length > 0;
  return (
    <section className="agt-turn" aria-label={`Turn ${turn.ordinal}`}>
      {turn.user !== null && (
        <>
          {first && <div className="agt-stamp">{stamp(turn.user.at, now)}</div>}
          <div className="agt-user">
            <div className="agt-user__bubble">
              {turn.user.attachments.length > 0 && (
                <div className="agt-user__files">
                  {turn.user.attachments.map((attachment) => (
                    <span className="agt-file" key={attachment.id} title={attachment.mediaType}>{attachment.name}</span>
                  ))}
                </div>
              )}
              <div>{turn.user.text}</div>
              {turn.user.delivery !== 'TURN' && <div className="agt-muted agt-small">{turn.user.delivery === 'STEER' ? 'Steered into the running turn' : 'Queued'}</div>}
            </div>
          </div>
        </>
      )}
      {hasLog && (
        <button type="button" className="agt-turn__head" aria-expanded={open} onClick={onToggle} title="Show or hide this turn's log">
          {live && <Spinner />}
          <span className="agt-turn__label">{turnLabel(turn, now, waiting)}</span>
          <Chevron open={open} />
        </button>
      )}
      {open && turn.blocks.map((block) => (
        <BlockView
          key={block.id}
          block={block}
          now={now}
          activityChoice={activityChoice}
          onToggleActivity={onToggleActivity}
          onDecide={onDecide}
        />
      ))}
      {turn.answer !== null && (
        <div className="agt-answer">{turn.answer.text}{!turn.answer.complete && <span className="agt-cursor" aria-hidden="true" />}</div>
      )}
      {turn.endedAt !== null && <TurnFooter turn={turn} durable={turn.turnId === null ? undefined : durableTurns[turn.turnId]} models={models} />}
    </section>
  );
}

const CONNECTION_TEXT: Record<string, string> = {
  connecting: 'Connecting — loading task history…',
  open: 'Live',
  failed: 'Disconnected',
};

/**
 * What the connection strip says.
 *
 * Reconnecting names the sequence it is resuming from, because "catching up" without a resume
 * point asks the user to take the claim that nothing was lost on faith.
 */
function connectionText(state: AgentTaskState): string {
  if (state.connection === 'reconnecting') {
    return `Reconnecting — catching up from event ${state.lastSequence}. Nothing is lost.`;
  }
  const text = CONNECTION_TEXT[state.connection] ?? state.connection;
  return state.connectionDetail === null ? text : `${text} — ${state.connectionDetail}`;
}

const STATUS_LABELS: Record<AgentRuntimeStatus, string> = {
  loading: 'Connecting',
  idle: 'Ready',
  running: 'Running',
  closing: 'Closing',
  recovery: 'Recovering',
  completed: 'Completed',
  failed: 'Failed',
};

/** Why Complete is unavailable, told on the button rather than left to be guessed. */
function completeTitle(status: AgentRuntimeStatus): string {
  switch (status) {
    case 'idle':
      return 'Closes this task, removes its clean checkout and keeps the result branch';
    case 'recovery':
      return 'Unlocks after recovery';
    case 'closing':
      return 'Closing…';
    default:
      return 'Available when the task is idle';
  }
}

/** The header's second line: where the task is, in the fewest words that are still facts. */
function headerMeta(turns: TranscriptTurn[], status: AgentRuntimeStatus, waiting: boolean, now: number, state: AgentTaskState, models: ModelOptionDto[]): string {
  const last = turns[turns.length - 1];
  if (status === 'running' && last !== undefined) {
    if (waiting) return `Turn ${last.ordinal} · waiting on you`;
    const elapsed = turnElapsedMs(last, now);
    return elapsed === null ? `Turn ${last.ordinal}` : `Turn ${last.ordinal} · ${formatDuration(elapsed)}`;
  }
  if (last !== undefined) {
    const ended = [...turns].reverse().find((turn) => turn.endedAt !== null);
    return `${turns.length} ${turns.length === 1 ? 'turn' : 'turns'}${ended === undefined ? '' : ` · ${clock(ended.endedAt as string)}`}`;
  }
  if (state.effective !== null) {
    return `${modelName(models, state.effective.model)}${state.effective.effort === null ? '' : ` ${state.effective.effort}`} · ${permissionLabel(state.effective.permissionPolicy)}`;
  }
  return '';
}

/**
 * Replaces the mutation controls while the checkout is still reserved.
 *
 * Nothing here edits or cleans that checkout. A claim from a turn means this task's own Codex
 * server could not be shown to have stopped writing, and stopping it is the way out. A recovery
 * claim means the process predates this ByteQuay, so nothing here can stop it: the checkout stays
 * reserved until that process is gone and a restart verifies it.
 */
function RecoveryCard({ task, turn, onStopServer, onMarkFailed }: {
  task: AgentTaskDto;
  turn: AgentTurnDto | null;
  onStopServer: () => void;
  onMarkFailed: () => void;
}): React.JSX.Element {
  const oldProcess = task.mutationClaim?.kind === 'RECOVERY';
  return (
    <section className="agt-recovery" aria-labelledby="agt-recovery-title">
      <div className="agt-recovery__head">
        <span id="agt-recovery-title">Recovering agent execution</span>
        <span className="agt-spacer" />
        <button
          type="button"
          className="agt-action is-danger"
          onClick={onStopServer}
          disabled={oldProcess}
          title={oldProcess
            ? 'That process predates this ByteQuay run; stop it yourself, then restart ByteQuay'
            : 'Kills only this task’s Codex server, after confirmation'}
        >
          Stop this task&apos;s Codex server…
        </button>
      </div>
      <div className="agt-recovery__body">
        {oldProcess
          ? 'ByteQuay restarted while this task was working, and a process from before the restart '
            + 'could not be verified stopped. The transcript is safe; the checkout stays reserved until '
            + 'that process is gone and ByteQuay has restarted.'
          : 'The last turn ended, but its Codex process could not be shown to have stopped writing '
            + 'here. The transcript is safe; new messages wait until nothing from that run can still '
            + 'change files.'}
        {turn?.terminalReason && <div className="agt-row-meta">{turn.terminalReason}</div>}
      </div>
      <div className="agt-recovery__actions">
        <button
          type="button"
          className="agt-action"
          onClick={onMarkFailed}
          title="Fails the task and keeps its checkout for inspection, after confirmation"
        >
          Mark task failed
        </button>
        <button type="button" className="agt-action" disabled title="Unlocks after recovery — never re-sends by itself">
          Retry last message
        </button>
        <span className="agt-spacer" />
        <span className="agt-recovery__note">Only this task&apos;s Codex server is stopped</span>
      </div>
    </section>
  );
}

/** The task's result once it is closed: what was kept, and whether the checkout is gone. */
function TerminalSummary({ task }: { task: AgentTaskDto }): React.JSX.Element {
  const completed = task.state === 'COMPLETED';
  return (
    <section className="agt-terminal" aria-label={completed ? 'Task result' : 'Task failure'}>
      <div className="agt-terminal__kind">
        {completed ? 'Completed' : 'Failed'}{task.terminalAt === null ? '' : ` · ${relativeTime(task.terminalAt)}`}
      </div>
      {task.terminalReason !== null && <div>{task.terminalReason}</div>}
      {task.summary?.finalResponse && <div>{task.summary.finalResponse}</div>}
      <dl>
        {task.summary?.resultBranch && <><dt>Result branch</dt><dd>{task.summary.resultBranch}</dd></>}
        {task.summary?.resultHead && <><dt>HEAD</dt><dd>{task.summary.resultHead}</dd></>}
        <dt>Checkout</dt>
        <dd>
          {task.checkoutRemovedAt === null
            ? 'kept for inspection'
            : `removed ${relativeTime(task.checkoutRemovedAt)}; the branch remains`}
        </dd>
      </dl>
    </section>
  );
}

/** The plan the running turn is following, kept in view above the composer while it runs. */
function PlanCard({ steps, open, onToggle }: { steps: PlanStep[]; open: boolean; onToggle: () => void }): React.JSX.Element {
  return (
    <div className="agt-todos">
      <button type="button" className="agt-todos__head" aria-expanded={open} aria-label="Show or hide the plan" onClick={onToggle}>
        <span className="agt-todos__title">Plan</span>
        <span className="agt-muted agt-ellipsis">{planSummary(steps)}</span>
        <span className="agt-spacer" />
        <Chevron open={open} />
      </button>
      {open && <PlanSteps steps={steps} compact />}
    </div>
  );
}

/**
 * The whole screen as a function of state, so every visual state can be rendered on its own.
 *
 * The effectful wrapper below owns the stream and the clock; this owns nothing, which is what
 * makes a reconnecting, recovering or closed screen testable without driving a real subscription.
 */
export function AgentTaskBody({
  state, models, onBack, onSend, onCancel, onComplete, onDecide, onCompose, onSelectModel,
  onStopServer, onMarkFailed, onRetry, composerRef,
  now = Date.now(),
  activityChoice = {},
  turnChoice = {},
  planOpen = true,
  onToggleActivity = () => {},
  onToggleTurn = () => {},
  onTogglePlan = () => {},
}: {
  state: AgentTaskState;
  models: ModelOptionDto[];
  onBack: () => void;
  onSend: () => void;
  onCancel: () => void;
  onComplete: () => void;
  onDecide: (approvalId: string, decision: AgentApprovalDecision) => void;
  onCompose: (text: string) => void;
  onSelectModel: (model: string, effort: string | null) => void;
  onStopServer: () => void;
  onMarkFailed: () => void;
  onRetry: () => void;
  composerRef?: React.Ref<HTMLTextAreaElement>;
  /** The clock reading the elapsed times are drawn against; ticks once a second while a turn runs. */
  now?: number;
  /** Whether the user opened or folded an activity group, by block id; live updates never change it. */
  activityChoice?: Record<string, boolean>;
  turnChoice?: Record<string, boolean>;
  planOpen?: boolean;
  onToggleActivity?: (id: string, open: boolean) => void;
  onToggleTurn?: (id: string, open: boolean) => void;
  onTogglePlan?: () => void;
}): React.JSX.Element {
  const turns = projectTranscript(state.events);
  const status = runtimeStatus(state);
  const terminal = status === 'completed' || status === 'failed';
  const retry = retryableMessage(state);
  const waiting = turns.some((turn) => turn.blocks.some((block) => block.kind === 'approval' && block.actionable && block.outcome === null));
  const statusLabel = status === 'running' && waiting ? 'Needs approval' : STATUS_LABELS[status];
  const statusText = state.turn === null
    ? STATUS_LABELS[status]
    : `${STATUS_LABELS[status]} · turn ${state.turn.state.toLowerCase()}`;
  const plan = status === 'running' ? currentPlan(turns) : null;
  return (
    <div className="agt-screen">
      <div className="agt-screen-head">
        <button type="button" className="agt-back" onClick={onBack} aria-label="Back to tasks">‹ Tasks</button>
        <strong className="agt-title">{state.task?.title ?? 'Agent task'}</strong>
        <span className={`agt-state is-${status}${waiting && status === 'running' ? ' is-waiting' : ''}`}>
          {status === 'running' && !waiting && <Spinner />}
          {statusLabel}
        </span>
        <span className="agt-mono agt-muted agt-meta">{headerMeta(turns, status, waiting, now, state, models)}</span>
        <span className="agt-spacer" />
        <span
          className={`agt-conn-dot is-${stateClass(state.connection)}`}
          role="status"
          title={connectionText(state)}
          aria-label={connectionText(state)}
        />
        {!terminal && (
          <button
            type="button"
            className="agt-action"
            title={completeTitle(status)}
            onClick={onComplete}
            disabled={status !== 'idle'}
          >
            Complete
          </button>
        )}
      </div>

      {/* A healthy connection is just the header dot; the strip is for states needing attention. */}
      {(state.connection !== 'open' || state.gap) && (
        <div className={`agt-conn is-${stateClass(state.connection)}`} role="status">
          {connectionText(state)}
          {state.gap ? ' — waiting for a missing event' : ''}
        </div>
      )}
      {state.notice !== null && <div className="agt-notice" role="alert">{state.notice}</div>}

      <div className="agt-transcript" role="log" aria-label="Task transcript">
        {turns.length === 0
          ? <div className="agt-empty">No activity yet.</div>
          : turns.map((turn, index) => {
            const id = turn.turnId ?? `turn-${turn.ordinal}`;
            const last = index === turns.length - 1;
            const open = turnChoice[id] ?? (last || turn.endedAt === null);
            return (
              <TurnView
                key={id}
                turn={turn}
                first={index === 0}
                open={open}
                now={now}
                models={models}
                durableTurns={state.turns}
                activityChoice={activityChoice}
                onToggle={() => onToggleTurn(id, !open)}
                onToggleActivity={onToggleActivity}
                onDecide={onDecide}
              />
            );
          })}
      </div>

      {/* Bounded: the status and turn state change a handful of times, streamed text never lands here. */}
      <div className="agt-status" role="status" aria-live="polite">{statusText}</div>

      {terminal && state.task !== null && <TerminalSummary task={state.task} />}

      {status === 'recovery' && state.task !== null && (
        <RecoveryCard task={state.task} turn={state.turn} onStopServer={onStopServer} onMarkFailed={onMarkFailed} />
      )}

      {!terminal && state.effective !== null && (
        <div className="agt-next">
          {/* Settings are snapshotted when a turn is accepted, so a change lands on the next one. */}
          Next turn: {modelName(models, state.effective.model)}
          {state.effective.effort === null ? '' : ` · ${state.effective.effort}`}
          {' · '}{permissionLabel(state.effective.permissionPolicy)}
        </div>
      )}

      {!terminal && plan !== null && <PlanCard steps={plan.steps} open={planOpen} onToggle={onTogglePlan} />}

      {!terminal && (
        <div className="agt-composer">
          <textarea
            ref={composerRef}
            value={state.composer}
            aria-label="Message the agent"
            placeholder={state.connection === 'connecting' ? 'Connecting…' : status === 'closing' ? 'Closing…' : 'Message the agent'}
            disabled={status === 'closing' || status === 'recovery'}
            onChange={(event) => onCompose(event.target.value)}
          />
          <span className="agt-lane" title="Permission policy for the next turn" aria-label="Permission policy">
            {permissionLabel(state.task?.defaults.permissionPolicy ?? 'AUTO')}
          </span>
          <ModelChip
            ariaLabel="Model and effort for the next message"
            label={modelName(models, state.task?.defaults.model ?? null)}
            detail={state.task?.defaults.effort ?? undefined}
            disabled={models.length === 0 || terminal}
            models={models}
            selectedModel={state.task?.defaults.model ?? ''}
            selectedEffort={state.task?.defaults.effort ?? undefined}
            onSelect={onSelectModel}
          />
          {retry !== null && (
            <button
              type="button"
              className="agt-action"
              onClick={onRetry}
              title="Sends the last message again as a new turn, after confirmation"
            >
              Retry last message
            </button>
          )}
          <button type="button" className="agt-action" onClick={onSend} disabled={!canSend(state)}>Send</button>
          <button
            type="button"
            className="agt-action"
            onClick={onCancel}
            disabled={status !== 'running'}
            title="Interrupts the running turn and waits for its work to stop"
          >
            Stop this turn
          </button>
          {status === 'running' && (
            <button
              type="button"
              className="agt-action is-danger"
              onClick={onStopServer}
              title="Last resort: kills only this task’s Codex server, after confirmation"
            >
              Stop server…
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function failureText(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

export default function AgentTaskScreen({
  workspaceId,
  taskId,
  bridge: explicitBridge,
  onBack,
  laneId = INTERACTIVE_LANE_ID,
}: AgentTaskScreenProps): React.JSX.Element {
  const [state, dispatch] = useReducer(agentTaskReducer, undefined, initialAgentTaskState);
  const [models, setModels] = useState<ModelOptionDto[]>([]);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  // Fold choices are the user's, kept here so a live update can never override them.
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const [planOpen, setPlanOpen] = useState(true);
  const [now, setNow] = useState(() => Date.now());
  const running = runtimeStatus(state) === 'running';
  // The clock only ticks while a turn runs; a finished turn's elapsed time is fixed by its events.
  useEffect(() => {
    if (!running) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running]);
  // Resolved when used, not when rendered: the product route reaches Electron's bridge, and a
  // static render has no window to reach.
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);

  useEffect(() => {
    let active = true;
    const session = startAgentTaskSession(live(), taskId, workspaceId, dispatch, () => active);
    session.catch((failure: unknown) => {
      if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
    return () => {
      active = false;
      void session.then((stop) => stop(), () => {});
    };
  }, [live, taskId, workspaceId]);

  useEffect(() => {
    let active = true;
    void (async () => {
      const [catalog, effective] = await Promise.all([
        live().getModelSettings(),
        live().getEffectiveTurnSettings(taskId, workspaceId),
      ]);
      if (!active) return;
      setModels(catalog.providers.find((provider) => provider.id === laneId)?.models ?? []);
      dispatch({ type: 'settings/loaded', settings: effective });
    })().catch(() => { /* the composer then shows no catalog, which the list already explains */ });
    return () => { active = false; };
  }, [live, laneId, taskId, workspaceId]);

  // Each message shows the settings its turn ran with, which only the durable turn records.
  const unloaded = unloadedTurnIds(state).join(',');
  useEffect(() => {
    if (unloaded === '') return;
    let active = true;
    void Promise.all(unloaded.split(',').map((turnId) => live().getAgentTurn(turnId)))
      .then((turns) => {
        if (!active) return;
        turns.forEach((turn) => dispatch({ type: 'turn/loaded', turn }));
      })
      .catch((failure: unknown) => {
        if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
      });
    return () => { active = false; };
  }, [live, unloaded]);

  // Every lifecycle event changes durable task or turn facts the events themselves do not carry
  // (the claim, the terminal state), so each one triggers a re-read of both.
  const { lifecycleEpoch, latestTurnId } = state;
  useEffect(() => {
    if (lifecycleEpoch === 0) return;
    let active = true;
    void (async () => {
      const [task, turn] = await Promise.all([
        live().getAgentTask(taskId, workspaceId),
        latestTurnId === null ? Promise.resolve(null) : live().getAgentTurn(latestTurnId),
      ]);
      if (!active) return;
      dispatch({ type: 'task/loaded', task });
      if (turn !== null) dispatch({ type: 'turn/changed', turn });
    })().catch((failure: unknown) => {
      if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
    return () => { active = false; };
  }, [live, taskId, workspaceId, lifecycleEpoch, latestTurnId]);

  /** Runs one bridge command, surfacing its failure in the notice strip instead of losing it. */
  const run = useCallback(async (action: () => Promise<void>) => {
    dispatch({ type: 'notice/changed', notice: null });
    try {
      await action();
    }
    catch (failure) {
      dispatch({ type: 'notice/changed', notice: failureText(failure) });
    }
  }, []);

  const selectModel = useCallback((model: string, effort: string | null) => run(async () => {
    const task = state.task;
    if (task === null) return;
    // The response carries the bumped version; keeping the old one conflicts on the next change.
    dispatch({
      type: 'task/loaded',
      task: await live().updateAgentTaskDefaults(taskId, workspaceId, task.version, {
        model,
        effort,
        permissionPolicy: task.defaults.permissionPolicy,
      }),
    });
    dispatch({
      type: 'settings/loaded',
      settings: await live().getEffectiveTurnSettings(taskId, workspaceId),
    });
  }), [live, run, state.task, taskId, workspaceId]);

  const startTurn = useCallback(async (message: string) => {
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const turn = await live().startAgentTurn({
        taskId,
        message,
        idempotencyKey: agentIdempotencyKey(taskId, state.lastSequence),
      });
      dispatch({ type: 'turn/changed', turn });
    }
    finally {
      dispatch({ type: 'composer/sending', sending: false });
    }
  }, [live, state.lastSequence, taskId]);

  const send = useCallback(() => run(async () => {
    const message = state.composer.trim();
    if (message.length === 0) return;
    await startTurn(message);
    // The transcript still waits for the durable user-message event rather than inventing it.
    dispatch({ type: 'composer/changed', text: '' });
  }), [run, startTurn, state.composer]);

  const retry = useCallback(() => run(async () => {
    const message = retryableMessage(state);
    if (message === null) return;
    // Never automatic: the user confirms the exact text that will run again as a new turn.
    if (!window.confirm(`Send this message again as a new turn?\n\n${message}`)) return;
    await startTurn(message);
  }), [run, startTurn, state]);

  const cancel = useCallback(() => run(async () => {
    if (state.turn === null) return;
    dispatch({ type: 'turn/changed', turn: await live().cancelAgentTurn(state.turn.id) });
  }), [live, run, state.turn]);

  const complete = useCallback(() => run(async () => {
    await live().completeAgentTask(taskId, workspaceId);
    dispatch({ type: 'task/loaded', task: await live().getAgentTask(taskId, workspaceId) });
  }), [live, run, taskId, workspaceId]);

  const stopServer = useCallback(() => run(async () => {
    const impact = await live().getAgentRuntimeStopImpact(taskId, workspaceId);
    const consequence = impact.activeTurnId === null
      ? 'No turn is running; the checkout is released once the process is verified stopped.'
      : 'The running turn is interrupted and its pending approvals denied.';
    if (!window.confirm(`Stop this task's Codex server? Only this task is affected.\n\n${consequence}`)) return;
    dispatch({ type: 'task/loaded', task: await live().stopAgentRuntime(taskId, workspaceId, impact.generation) });
    if (latestTurnId !== null) {
      dispatch({ type: 'turn/changed', turn: await live().getAgentTurn(latestTurnId) });
    }
  }), [live, latestTurnId, run, taskId, workspaceId]);

  const markFailed = useCallback(() => run(async () => {
    if (!window.confirm('Mark this task failed? Its checkout is kept for inspection unless it is clean; '
      + 'the transcript is retained.')) return;
    dispatch({
      type: 'task/loaded',
      task: await live().markAgentTaskFailed(taskId, workspaceId, 'Marked failed by the user during recovery'),
    });
  }), [live, run, taskId, workspaceId]);

  const decide = useCallback((approvalId: string, decision: AgentApprovalDecision) => run(async () => {
    await live().resolveAgentApproval(approvalId, decision);
    // The buttons that had focus disappear with the decision; the composer is the next thing to do.
    composerRef.current?.focus();
  }), [live, run]);

  return (
    <AgentTaskBody
      state={state}
      models={models}
      composerRef={composerRef}
      onBack={onBack}
      onSend={() => { void send(); }}
      onCancel={() => { void cancel(); }}
      onComplete={() => { void complete(); }}
      onDecide={(approvalId, decision) => { void decide(approvalId, decision); }}
      onCompose={(text) => dispatch({ type: 'composer/changed', text })}
      onSelectModel={(model, effort) => { void selectModel(model, effort); }}
      onStopServer={() => { void stopServer(); }}
      onMarkFailed={() => { void markFailed(); }}
      onRetry={() => { void retry(); }}
      now={now}
      activityChoice={activityChoice}
      turnChoice={turnChoice}
      planOpen={planOpen}
      onToggleActivity={(id, open) => setActivityChoice((choices) => ({ ...choices, [id]: open }))}
      onToggleTurn={(id, open) => setTurnChoice((choices) => ({ ...choices, [id]: open }))}
      onTogglePlan={() => setPlanOpen((open) => !open)}
    />
  );
}
