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
  projectAgentRows,
  retryableMessage,
  runtimeStatus,
  startAgentTaskSession,
  unloadedTurnIds,
  type AgentBridgePort,
  type AgentRow,
  type AgentRuntimeStatus,
  type AgentTaskState,
} from './model';
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

/**
 * The settings a turn actually ran with, as recorded when it was accepted.
 *
 * Read from the durable turn, never from the defaults at render time: a default changed after the
 * message was sent must not rewrite what that message ran with.
 */
function sentWith(models: ModelOptionDto[], turn: AgentTurnDto | undefined): string | null {
  if (turn === undefined || turn.settings === null) return null;
  const settings = turn.settings;
  return `Sent with ${modelName(models, settings.model)}`
    + (settings.effort === null ? '' : ` · ${settings.effort}`)
    + ` · ${permissionLabel(settings.permissionPolicy)}`;
}

/** Renders one transcript row; every event kind gets a row so nothing is silently dropped. */
export function AgentRowView({ row, models = [], turns = {}, onDecide }: {
  row: AgentRow;
  models?: ModelOptionDto[];
  turns?: Record<string, AgentTurnDto>;
  onDecide?: (approvalId: string, decision: AgentApprovalDecision) => void;
}): React.JSX.Element {
  switch (row.kind) {
    case 'user': {
      const settings = row.turnId === null ? null : sentWith(models, turns[row.turnId]);
      return (
        <div className="agt-item is-user">
          <div className="agt-item__kind">You · {row.delivery}</div>
          <div>{row.text}</div>
          {settings !== null && <div className="agt-row-meta">{settings}</div>}
        </div>
      );
    }
    case 'assistant':
      return (
        <div className="agt-item is-assistant">
          <div className="agt-item__kind">{row.messageKind}{row.final ? '' : ' · streaming'}</div>
          <div>{row.text}</div>
        </div>
      );
    case 'plan':
      return (
        <div className="agt-item">
          <div className="agt-item__kind">Plan</div>
          <ul>
            {row.items.map((item, index) => (
              <li key={index}>{item.state} — {item.text}</li>
            ))}
          </ul>
        </div>
      );
    case 'command':
      return (
        <div className="agt-item">
          <div className="agt-item__kind">Command · {row.state}{row.exitCode === null ? '' : ` · exit ${row.exitCode}`}</div>
          <pre className="agt-pre">{row.argv.join(' ')}</pre>
          {/* Output folds behind a native disclosure, so it is keyboard-operable for free. */}
          {row.output !== null && (
            <details>
              <summary>Output{row.truncated ? ' (truncated)' : ''}</summary>
              <pre className="agt-pre">{row.output}</pre>
            </details>
          )}
        </div>
      );
    case 'file':
      return (
        <div className="agt-item">
          <div className="agt-item__kind">File · {row.change} · {row.state}</div>
          <div>{row.path}</div>
          {row.diff !== null && (
            <details>
              <summary>Diff</summary>
              <pre className="agt-pre">{row.diff}</pre>
            </details>
          )}
        </div>
      );
    case 'tool':
      return (
        <div className="agt-item">
          <div className="agt-item__kind">Tool · {row.state}</div>
          <div>{row.name} — {row.summary}</div>
        </div>
      );
    case 'approval': {
      const decisions = approvalDecisions(row);
      return (
        <div className="agt-item">
          <div className="agt-item__kind">
            Approval · {row.approvalKind} · {row.agent}
            {row.outcome === null ? '' : ` · ${row.outcome}`}
          </div>
          <div>{row.title}</div>
          <div className="agt-row-meta">{row.detail}</div>
          {/* What is actually being asked for; a decision without it is made blind. */}
          {row.scope.length > 0 && (
            <ul className="agt-scope">
              {row.scope.map((entry) => <li key={entry}>{entry}</li>)}
            </ul>
          )}
          {decisions.length > 0 && (
            <div className="agt-decisions">
              {decisions.map((choice) => (
                <button
                  key={choice.offered}
                  type="button"
                  className="agt-decision"
                  aria-label={`${choice.label}: ${row.title}`}
                  onClick={() => onDecide?.(row.approvalId, choice.decision)}
                >
                  {choice.label}
                </button>
              ))}
            </div>
          )}
          {row.outcome === null && decisions.length === 0 && (
            // Failing closed: an approval nobody can answer says so rather than offering a guess.
            <div className="agt-row-meta">No decision this build can offer.</div>
          )}
        </div>
      );
    }
    case 'usage':
      return (
        <div className="agt-item">
          <div className="agt-item__kind">Usage · {row.semantics}</div>
          <div className="agt-row-meta">
            in {row.inputTokens} · cached {row.cachedInputTokens} · out {row.outputTokens}
            {row.cost === null ? '' : ` · ${row.cost}`}
          </div>
        </div>
      );
    case 'lifecycle':
      return (
        <div className="agt-item">
          <div className="agt-item__kind">{row.phase}</div>
          <div className="agt-row-meta">{row.message}</div>
        </div>
      );
    case 'error':
      return (
        <div className="agt-item is-error" role="alert">
          <div className="agt-item__kind">Error · {row.code}{row.retryable ? ' · retryable' : ''}</div>
          <div>{row.message}</div>
        </div>
      );
    default:
      return <div className="agt-item" />;
  }
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
  loading: 'Loading',
  idle: 'Idle',
  running: 'Running',
  closing: 'Closing',
  recovery: 'Recovery required',
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

/**
 * The whole screen as a function of state, so every visual state can be rendered on its own.
 *
 * The effectful wrapper below owns the stream; this owns nothing, which is what makes a
 * reconnecting, recovering or closed screen testable without driving a real subscription.
 */
export function AgentTaskBody({
  state, models, onBack, onSend, onCancel, onComplete, onDecide, onCompose, onSelectModel,
  onStopServer, onMarkFailed, onRetry, composerRef,
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
}): React.JSX.Element {
  const rows = projectAgentRows(state.events);
  const status = runtimeStatus(state);
  const terminal = status === 'completed' || status === 'failed';
  const retry = retryableMessage(state);
  const statusText = state.turn === null
    ? STATUS_LABELS[status]
    : `${STATUS_LABELS[status]} · turn ${state.turn.state.toLowerCase()}`;
  return (
    <div className="agt-screen">
      <div className="agt-screen-head">
        <button type="button" className="agt-back" onClick={onBack} aria-label="Back to tasks">‹ Tasks</button>
        <strong>{state.task?.title ?? 'Agent task'}</strong>
        <span className={`agt-state is-${status}`}>{STATUS_LABELS[status]}</span>
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

      <div className="agt-transcript">
        {rows.length === 0
          ? <div className="agt-empty">No activity yet.</div>
          : rows.map((row) => (
            <AgentRowView key={row.id} row={row} models={models} turns={state.turns} onDecide={onDecide} />
          ))}
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
    />
  );
}
