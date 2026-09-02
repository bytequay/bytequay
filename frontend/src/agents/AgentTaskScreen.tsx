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

import React, { useCallback, useEffect, useReducer } from 'react';
import '../css/agents.css';
import type { AgentBridgePort } from './preview';
import {
  agentIdempotencyKey,
  agentTaskReducer,
  approvalDecisions,
  canSend,
  initialAgentTaskState,
  isIdle,
  projectAgentRows,
  startAgentTaskSession,
  type AgentRow,
  type AgentTaskState,
} from './model';
import type { AgentApprovalDecision } from '../types';

export interface AgentTaskScreenProps {
  workspaceId: string;
  taskId: string;
  bridge: AgentBridgePort;
  onBack: () => void;
}

function stateClass(state: string): string {
  return state.toLowerCase();
}

/** Renders one transcript row; every event kind gets a row so nothing is silently dropped. */
export function AgentRowView({ row, onDecide }: {
  row: AgentRow;
  onDecide?: (approvalId: string, decision: AgentApprovalDecision) => void;
}): React.JSX.Element {
  switch (row.kind) {
    case 'user':
      return (
        <div className="agt-item is-user">
          <div className="agt-item__kind">You · {row.delivery}</div>
          <div>{row.text}</div>
        </div>
      );
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
          {row.output !== null && <pre className="agt-pre">{row.output}{row.truncated ? '\n…truncated' : ''}</pre>}
        </div>
      );
    case 'file':
      return (
        <div className="agt-item">
          <div className="agt-item__kind">File · {row.change} · {row.state}</div>
          <div>{row.path}</div>
          {row.diff !== null && <pre className="agt-pre">{row.diff}</pre>}
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
          <div className="agt-item__kind">Approval · {row.approvalKind}{row.outcome === null ? '' : ` · ${row.outcome}`}</div>
          <div>{row.title}</div>
          <div className="agt-row-meta">{row.detail}</div>
          {decisions.length > 0 && (
            <div className="agt-decisions">
              {decisions.map((choice) => (
                <button
                  key={choice.offered}
                  type="button"
                  className="agt-decision"
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

/**
 * The whole screen as a function of state, so every visual state can be rendered on its own.
 *
 * The effectful wrapper below owns the stream; this owns nothing, which is what makes a
 * reconnecting or errored screen testable without driving a real subscription to produce it.
 */
export function AgentTaskBody({ state, onBack, onSend, onCancel, onComplete, onDecide, onCompose }: {
  state: AgentTaskState;
  onBack: () => void;
  onSend: () => void;
  onCancel: () => void;
  onComplete: () => void;
  onDecide: (approvalId: string, decision: AgentApprovalDecision) => void;
  onCompose: (text: string) => void;
}): React.JSX.Element {
  const rows = projectAgentRows(state.events);
  const idle = isIdle(state);
  return (
    <div className="agt-screen">
      <div className="agt-screen-head">
        <button type="button" className="agt-back" onClick={onBack}>‹ Tasks</button>
        <strong>{state.task?.title ?? 'Agent task'}</strong>
        <span className="agt-fixture-badge">Fixture data</span>
        <span className="agt-spacer" />
        <span
          className={`agt-conn-dot is-${stateClass(state.connection)}`}
          role="status"
          title={connectionText(state)}
          aria-label={connectionText(state)}
        />
        <button
          type="button"
          className="agt-action"
          title={idle ? 'Closes this task and frees the workspace' : 'Available when the task is idle'}
          onClick={onComplete}
          disabled={!idle}
        >
          Complete
        </button>
      </div>

      {/* A healthy connection is just the header dot; the strip is for states needing attention. */}
      {(state.connection !== 'open' || state.gap) && (
        <div className={`agt-conn is-${stateClass(state.connection)}`} role="status">
          {connectionText(state)}
          {state.gap ? ' \u2014 waiting for a missing event' : ''}
        </div>
      )}

      <div className="agt-transcript">
        {rows.length === 0
          ? <div className="agt-empty">No activity yet.</div>
          : rows.map((row) => <AgentRowView key={row.id} row={row} onDecide={onDecide} />)}
      </div>

      <div className="agt-composer">
        <textarea
          value={state.composer}
          aria-label="Message the agent"
          placeholder={state.connection === 'connecting' ? 'Connecting\u2026' : 'Message the agent'}
          onChange={(event) => onCompose(event.target.value)}
        />
        <button type="button" className="agt-action" onClick={onSend} disabled={!canSend(state)}>Send</button>
        {/* Cancel needs a turn to cancel; before one exists the handler falls straight out. */}
        <button
          type="button"
          className="agt-action"
          onClick={onCancel}
          disabled={state.turn === null || idle}
        >
          Cancel
        </button>
      </div>
    </div>
  );
}

export default function AgentTaskScreen({
  workspaceId,
  taskId,
  bridge,
  onBack,
}: AgentTaskScreenProps): React.JSX.Element {
  const [state, dispatch] = useReducer(agentTaskReducer, undefined, initialAgentTaskState);

  useEffect(() => {
    let active = true;
    const session = startAgentTaskSession(bridge, taskId, workspaceId, dispatch, () => active);
    return () => {
      active = false;
      void session.then((stop) => stop());
    };
  }, [bridge, taskId, workspaceId]);

  const send = useCallback(async () => {
    const message = state.composer.trim();
    if (message.length === 0) return;
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const turn = await bridge.startAgentTurn({
        taskId,
        message,
        idempotencyKey: agentIdempotencyKey(taskId, state.lastSequence),
      });
      dispatch({ type: 'turn/changed', turn });
      // The transcript still waits for the durable user-message event rather than inventing it.
      dispatch({ type: 'composer/changed', text: '' });
    }
    finally {
      dispatch({ type: 'composer/sending', sending: false });
    }
  }, [bridge, state.composer, state.lastSequence, taskId]);

  const cancel = useCallback(async () => {
    if (state.turn === null) return;
    dispatch({ type: 'turn/changed', turn: await bridge.cancelAgentTurn(state.turn.id) });
  }, [bridge, state.turn]);

  return (
    <AgentTaskBody
      state={state}
      onBack={onBack}
      onSend={() => { void send(); }}
      onCancel={() => { void cancel(); }}
      onComplete={() => { void bridge.completeAgentTask(taskId, workspaceId); }}
      onDecide={(approvalId, decision) => { void bridge.resolveAgentApproval(approvalId, decision); }}
      onCompose={(text) => dispatch({ type: 'composer/changed', text })}
    />
  );
}
