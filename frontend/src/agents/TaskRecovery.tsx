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

import { relativeTime } from '@bytequay/ui/relativeTime';
import { TriangleAlert } from 'lucide-react';
import React from 'react';
import { isDeepSeekKeyLane, laneLabel, providerAvailable, providerRemedy, remedyCommand } from '../settings/model';
import type { AgentProviderStatus, AgentTaskDto, AgentTurnDto } from '../types';
import { renderMarkdown } from './markdown';
import type { RetryableInput } from './model';
import { agentDisplayName, type AgentNode } from './transcript';

/**
 * Replaces the mutation controls while the checkout is still reserved.
 *
 * A retained claim means shutdown was not verified, not that a process is known to be running.
 * Only an explicit Stop retries verification; the renderer never releases the checkout itself.
 */
export function RecoveryCard({ task, turn, working, onStopServer, onMarkFailed }: {
  task: AgentTaskDto;
  turn: AgentTurnDto | null;
  working: AgentNode[];
  onStopServer: () => void;
  onMarkFailed: () => void;
}): React.JSX.Element {
  const oldProcess = task.mutationClaim?.kind === 'RECOVERY';
  const automationUnfinished = task.developmentContinuationUnfinished === true;
  return (
    <section className="agt-recovery" aria-labelledby="agt-recovery-title">
      <div className="agt-recovery__head">
        <TriangleAlert aria-hidden="true" />
        <span id="agt-recovery-title">Could not verify shutdown</span>
        <span className="agt-spacer" />
        <button
          type="button"
          className="agt-action is-danger"
          onClick={onStopServer}
          title={automationUnfinished ? 'Stops this task and its linked CI agents, after confirmation' : oldProcess
            ? 'Stops and verifies this task’s provider runtime from before the restart, after confirmation'
            : 'Stops only this task’s provider runtime, after confirmation'}
        >
          {automationUnfinished ? 'Stop automation…' : <>Stop this task&apos;s agent runtime…</>}
        </button>
      </div>
      <div className="agt-recovery__body">
        {automationUnfinished ? 'Automation stopped, but ByteQuay could not verify shutdown of every linked agent.' : oldProcess
          ? 'ByteQuay could not verify shutdown of this task’s agent from before the restart.'
          : 'The last turn ended, but ByteQuay could not verify that its agent and tools stopped.'}
        {' Your conversation and files are kept. New messages stay blocked until shutdown is verified. '
          + 'Use Stop to check again; if verification remains blocked, follow the reported diagnostic.'}
        {turn?.terminalReason && <div className="agt-row-meta">{turn.terminalReason}</div>}
      </div>
      {working.length > 0 && (
        <div className="agt-recovery__row">
          <TriangleAlert aria-hidden="true" />
          <span className="agt-recovery__key">Last reported activity:</span>
          <span className="agt-ellipsis">{working.map((agent) => agentDisplayName(agent.label)).join(', ')}</span>
        </div>
      )}
      <div className="agt-recovery__actions">
        {!automationUnfinished && <button
          type="button"
          className="agt-action"
          onClick={onMarkFailed}
          title="Fails the task and keeps its checkout for inspection, after confirmation"
        >
          Mark task failed
        </button>}
        <span className="agt-spacer" />
        <span className="agt-recovery__note">{automationUnfinished ? 'Stop verifies that this task and its linked CI agents have stopped' : <>Only this task&apos;s provider runtime is stopped</>}</span>
      </div>
    </section>
  );
}

/** A typed readiness block, retaining the auth-only explicit retry when its input is safe. */
export function ProviderRecoveryCard({
  provider,
  laneId,
  status,
  keyRemoved,
  statusFresh,
  failedTurn,
  checking,
  input,
  sending,
  onRefresh,
  onRetry,
  onOpenSettings,
  onSignIn,
}: {
  provider: string;
  laneId: string;
  status: AgentProviderStatus | null;
  keyRemoved: boolean;
  /** True only after readiness was probed after this exact failed turn became durable. */
  statusFresh: boolean;
  failedTurn: boolean;
  checking: boolean;
  input: RetryableInput | null;
  sending: boolean;
  onRefresh: () => void;
  onRetry: () => void;
  onOpenSettings?: () => void;
  /** Absent where no terminal can be opened, such as a server-rendered fixture. */
  onSignIn?: () => void;
}): React.JSX.Element {
  if (keyRemoved) {
    return <section className="agt-recovery is-auth" aria-labelledby="agt-auth-recovery-title">
      <div className="agt-recovery__head"><TriangleAlert aria-hidden="true" /><span id="agt-auth-recovery-title">DeepSeek key removed</span></div>
      <div className="agt-recovery__body">This task&apos;s saved key was removed. Its transcript and checkout remain available. Add a key in Models settings to start a new task.</div>
      <div className="agt-recovery__actions">
        <button type="button" className="agt-action" disabled={checking || sending} onClick={onRefresh}>{checking ? 'Checking…' : 'Check again'}</button>
        {onOpenSettings !== undefined && <button type="button" className="agt-action" onClick={onOpenSettings}>Open Models settings</button>}
      </div>
    </section>;
  }
  const ready = statusFresh && status !== null && providerAvailable(status.readiness);
  const statusLaneId = status?.laneId ?? laneId;
  const keyLane = isDeepSeekKeyLane(laneId) || isDeepSeekKeyLane(statusLaneId);
  const remedyLaneId = isDeepSeekKeyLane(laneId) ? laneId : statusLaneId;
  const providerName = provider || (statusLaneId === '' ? 'Provider' : laneLabel(statusLaneId));
  // A READY row from before the failed turn is stale until the post-failure probe proves it again.
  const readiness = failedTurn && (status === null || providerAvailable(status.readiness))
    ? 'AUTHENTICATION_REQUIRED'
    : status?.readiness ?? 'AUTHENTICATION_REQUIRED';
  const remedy = providerRemedy(readiness, remedyLaneId);
  const command = status === null ? remedy.command : remedyCommand(status, remedy);
  const title = readiness === 'AUTHENTICATION_REQUIRED'
    ? keyLane ? 'DeepSeek key needs attention' : `${providerName} sign-in required`
    : readiness === 'CLI_MISSING'
      ? keyLane ? 'Codex CLI not installed' : `${providerName} CLI not installed`
      : readiness === 'UNSUPPORTED'
        ? `${providerName} is unsupported`
        : `${providerName} is unavailable`;
  const retryDisabled = !ready || input === null || checking || sending;
  const retryTitle = input === null
    ? 'Retry is unavailable until the failed turn is quiescent and its delivery is known'
    : ready ? 'Confirms and resends the last message as a new turn' : 'Check provider readiness after following the remedy';
  return (
    <section className="agt-recovery is-auth" aria-labelledby="agt-auth-recovery-title">
      <div className="agt-recovery__head">
        <TriangleAlert aria-hidden="true" />
        <span id="agt-auth-recovery-title">{title}</span>
      </div>
      <div className="agt-recovery__body">
        {input === null
          ? failedTurn
            ? 'The failed turn has no safely retryable input. Its delivery may be uncertain, so ByteQuay will not offer to resend it.'
            : 'This task cannot start another turn until the provider is ready. No new turn or delivery was created.'
          : keyLane
            ? 'The last turn stopped because this DeepSeek key needs attention. The task and checkout were preserved.'
            : 'The last turn stopped because this provider needs authentication. The task and checkout were preserved.'}
        {!ready && (
          <div className="agt-row-meta">
            <strong>{remedy.title}.</strong>{' '}
            {onSignIn !== undefined && command !== null && readiness === 'AUTHENTICATION_REQUIRED'
              ? "Signing in here signs ByteQuay's own provider home in, not your personal CLI login."
              : remedy.detail}
          </div>
        )}
        {readiness === 'UNSUPPORTED' && status?.version && <div className="agt-row-meta">Detected CLI version: {status.version}</div>}
        {status?.diagnostic && <div className="agt-row-meta">{status.diagnostic}</div>}
        {command && !ready && <div className="agt-mono agt-auth-command">{command}</div>}
        {ready && <div className="agt-auth-ready">{keyLane ? 'The key is ready' : 'Login status is ready'}. Retry remains a separate confirmed action.</div>}
        {input !== null && (
          <div className="agt-auth-input" aria-label="Message that will be retried">
            <strong>Message to resend</strong>
            <div className="agt-auth-input__text">
              {input.text.length > 500 ? `${input.text.slice(0, 500)}…` : input.text}
            </div>
            {input.attachments.length > 0 && (
              <div className="agt-auth-input__attachments">
                <strong>{input.attachments.length} {input.attachments.length === 1 ? 'attachment' : 'attachments'}:</strong>{' '}
                {input.attachments.map((attachment) => `${attachment.name} (${attachment.mediaType})`).join(', ')}
              </div>
            )}
          </div>
        )}
      </div>
      <div className="agt-recovery__actions">
        {onSignIn !== undefined && command !== null && readiness === 'AUTHENTICATION_REQUIRED' && (
          <button type="button" className="agt-action is-primary" disabled={checking || sending} onClick={onSignIn}>Sign in</button>
        )}
        <button type="button" className="agt-action" disabled={checking || sending} onClick={onRefresh}>
          {checking ? 'Checking…' : keyLane ? 'Check key again' : failedTurn || readiness === 'AUTHENTICATION_REQUIRED' ? 'Refresh login status' : 'Check again'}
        </button>
        {!ready && onOpenSettings !== undefined && (
          <button type="button" className="agt-action" onClick={onOpenSettings}>Open {keyLane ? 'Models' : 'workspace'} settings</button>
        )}
        {input !== null && (
          <button type="button" className="agt-action" disabled={retryDisabled} title={retryTitle} onClick={onRetry}>
            {sending ? 'Retrying…' : 'Retry last message…'}
          </button>
        )}
        <span className="agt-spacer" />
        <span className="agt-recovery__note">Nothing is resent automatically</span>
      </div>
    </section>
  );
}

/** The task's result once it is closed: what was kept, and whether the checkout is gone. */
export function TerminalSummary({ task, repository, onCleanup }: { task: AgentTaskDto; repository: string | null; onCleanup: () => void }): React.JSX.Element {
  const completed = task.state === 'COMPLETED';
  return (
    <section className="agt-terminal" aria-label={completed ? 'Task result' : 'Task failure'}>
      <div className="agt-terminal__kind">
        {completed ? 'Completed' : 'Failed'}{task.terminalAt === null ? '' : ` · ${relativeTime(task.terminalAt)}`}
      </div>
      {task.terminalReason !== null && <div>{task.terminalReason}</div>}
      {task.summary?.finalResponse && <div className="agt-terminal__response">{renderMarkdown(task.summary.finalResponse, repository)}</div>}
      <dl>
        {task.summary?.resultBranch && <><dt>Result branch</dt><dd>{task.summary.resultBranch}</dd></>}
        {task.summary?.resultHead && <><dt>HEAD</dt><dd>{task.summary.resultHead}</dd></>}
        <dt>Checkout</dt>
        <dd>
          {task.checkoutRemovedAt === null
            ? 'Cleanup required — kept for inspection'
            : `removed ${relativeTime(task.checkoutRemovedAt)}; the branch remains`}
        </dd>
      </dl>
      {task.checkoutRemovedAt === null && (
        <button type="button" className="agt-action" onClick={onCleanup}>Retry checkout cleanup</button>
      )}
    </section>
  );
}
