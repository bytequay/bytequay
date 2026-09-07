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
import {
  Archive,
  ArchiveRestore,
  Bot,
  CircleCheck,
  CircleX,
  FileDiff,
  GitPullRequest,
  ListChecks,
  Maximize2,
  PanelRight,
  TriangleAlert,
} from 'lucide-react';
import {
  laneLabel,
  providerAvailable,
  providerRemedy,
  remedyCommand,
} from '../settings/model';
import { useResizablePane } from '../useResizablePane';
import { AgentMark } from './AgentMark';
import { taskArchived } from './AgentTasksView';
import { renderMarkdown } from './markdown';
import { agentProviderUnavailableFrom } from './contracts';
import { pasteImage } from './clipboard';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { Chevron } from '../Chevron';
import { relativeTime } from '../relativeTime';
import '../css/agents.css';
import { AgentConversation, BlockView, clock, codeGraphText, ConversationComposer, ConversationQueue, Glyph, modelName, PlanSteps, serviceTierName, shortDate, Spinner, stateClass, useConversationHistory, useOutputWheel, type AttachmentPreview } from './AgentConversation';
import {
  acceptsImages,
  agentTaskReducer,
  canComplete,
  canQueue,
  canSend,
  canSteer,
  confirmCompletion,
  createDraftSubmissionTracker,
  initialAgentTaskState,
  readRecentAgentHistory,
  historyBefore,
  historyAfter,
  runtimeStatus,
  retryableInput,
  startAgentTaskSession,
  unloadedTurnIds,
  type AgentBridgePort,
  type DraftSubmissionTracker,
  type AgentRuntimeStatus,
  type AgentTaskState,
  type RetryableInput,
} from './model';
import {
  activeAgents,
  currentPlan,
  formatDuration,
  planSummary,
  projectAgents,
  projectTranscript,
  settleFinalTurn,
  turnElapsedMs,
  withApprovalAuthority,
  type AgentNode,
  type PlanStep,
  type TranscriptTurn,
} from './transcript';
import type {
  AgentApprovalDecision,
  AgentPendingApprovalDto,
  AgentCapabilitiesDto,
  AgentChildSummaryDto,
  AgentProviderStatus,
  AgentProviderUnavailableDto,
  AgentQueuedInputDto,
  AgentTaskDto,
  AgentTurnDto,
  ModelOptionDto,
  PullRequestDto,
  WorkspaceDto,
} from '../types';

import {
  NO_PULL_REQUEST_CHANGES,
  NO_PULL_REQUEST_DETAIL,
  NoPullRequestView,
  samePullRequest,
  TaskChangesView,
  TaskPullRequestView,
  taskPullRequest,
  useTaskPullRequestChanges,
  useTaskPullRequestDetail,
  type TaskPullRequestChanges,
  type TaskPullRequestDetail,
} from './TaskPullRequestPanel';

export { TurnView,type AttachmentPreview } from './AgentConversation';

export type PanelTab = 'pr' | 'changes' | 'agents';

/** What the screen knows about one attachment's image: still loading, an object URL, or gone. */
export interface AgentTaskScreenProps {
  workspaceId: string;
  taskId: string;
  /** Electron's bridge unless a test supplies its own. */
  bridge?: AgentBridgePort;
  onBack: () => void;
  /** Opens the existing pull-request page for one found on the task's branch. */
  onOpenPullRequest?: (number: number) => void;
  /** The workspace the task runs in; names the repository and base a draft pull request targets. */
  workspace?: WorkspaceDto;
  /** Opens the provider settings used by authentication recovery. */
  onOpenSettings?: () => void;
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
/**
 * The task's name, and the control that changes it: click to edit, Enter or blur to keep, Escape
 * to drop. While the background naming request runs, the title is a compact first-message
 * fallback and reads as provisional.
 */
function TaskTitle({ title, provisional, onRename }: {
  title: string;
  provisional: boolean;
  onRename?: (title: string) => void;
}): React.JSX.Element {
  const [draft, setDraft] = useState<string | null>(null);
  // Enter commits and then the input unmounts, which blurs it; the flag keeps that from committing twice.
  const committed = useRef(false);
  const className = `agt-title${provisional ? ' is-provisional' : ''}`;
  // Text, not a dead control, while there is nothing to rename yet.
  if (onRename === undefined) {
    return <strong className={className}>{title}</strong>;
  }
  if (draft === null) {
    return (
      <button
        type="button"
        className={className}
        title={provisional
          ? 'Using your first message while a short name is generated. Click to rename.'
          : 'Click to rename'}
        onClick={() => { committed.current = false; setDraft(title); }}
      >
        {title}
      </button>
    );
  }
  const commit = (): void => {
    if (committed.current) return;
    committed.current = true;
    const next = draft.trim();
    setDraft(null);
    if (next !== '' && next !== title) onRename(next);
  };
  return (
    <input
      className="agt-title-edit"
      type="text"
      aria-label="Task name"
      value={draft}
      autoFocus
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') commit();
        if (event.key === 'Escape') { committed.current = true; setDraft(null); }
      }}
    />
  );
}

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
function completeTitle(status: AgentRuntimeStatus, state: AgentTaskState): string {
  switch (status) {
    case 'idle':
      return canComplete(state)
        ? 'Mark it complete'
        : 'Run or remove every waiting queued message first';
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
 * Nothing here edits or cleans that checkout. A claim from a turn means this task's own provider
 * runtime could not be shown to have stopped writing, and stopping it is the way out. A recovery
 * claim means the process predates this ByteQuay run, but the same explicit stop action can retry
 * shutdown and verification without adding renderer-owned recovery state.
 */
function RecoveryCard({ task, turn, working, onStopServer, onMarkFailed }: {
  task: AgentTaskDto;
  turn: AgentTurnDto | null;
  working: AgentNode[];
  onStopServer: () => void;
  onMarkFailed: () => void;
}): React.JSX.Element {
  const oldProcess = task.mutationClaim?.kind === 'RECOVERY';
  return (
    <section className="agt-recovery" aria-labelledby="agt-recovery-title">
      <div className="agt-recovery__head">
        <Spinner warn />
        <span id="agt-recovery-title">Recovering agent execution</span>
        <span className="agt-spacer" />
        <button
          type="button"
          className="agt-action is-danger"
          onClick={onStopServer}
          title={oldProcess
            ? 'Stops and verifies this task’s provider runtime from before the restart, after confirmation'
            : 'Stops only this task’s provider runtime, after confirmation'}
        >
          Stop this task&apos;s agent runtime…
        </button>
      </div>
      <div className="agt-recovery__body">
        {oldProcess
          ? 'ByteQuay restarted while this task was working, and a process from before the restart '
            + 'could not be verified stopped. The transcript is safe; stop this task’s agent runtime '
            + 'to retry shutdown and verification. The checkout stays reserved until that succeeds.'
          : 'The last turn ended, but its provider process could not be shown to have stopped writing '
            + 'here. The transcript is safe; new messages wait until nothing from that run can still '
            + 'change files.'}
        {turn?.terminalReason && <div className="agt-row-meta">{turn.terminalReason}</div>}
      </div>
      {working.length > 0 && (
        <div className="agt-recovery__row">
          <Spinner warn />
          <span className="agt-recovery__key">Still working:</span>
          <span className="agt-ellipsis">{working.map((agent) => agent.label).join(', ')}</span>
        </div>
      )}
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
        <span className="agt-recovery__note">Only this task&apos;s provider runtime is stopped</span>
      </div>
    </section>
  );
}

/** A typed readiness block, retaining the auth-only explicit retry when its input is safe. */
function ProviderRecoveryCard({
  provider,
  status,
  statusFresh,
  failedTurn,
  checking,
  input,
  sending,
  onRefresh,
  onRetry,
  onOpenSettings,
}: {
  provider: string;
  status: AgentProviderStatus | null;
  /** True only after readiness was probed after this exact failed turn became durable. */
  statusFresh: boolean;
  failedTurn: boolean;
  checking: boolean;
  input: RetryableInput | null;
  sending: boolean;
  onRefresh: () => void;
  onRetry: () => void;
  onOpenSettings?: () => void;
}): React.JSX.Element {
  const ready = statusFresh && status !== null && providerAvailable(status.readiness);
  const providerName = provider || (status === null ? 'Provider' : laneLabel(status.laneId));
  // A READY row from before the failed turn is stale until the post-failure probe proves it again.
  const readiness = failedTurn && (status === null || providerAvailable(status.readiness))
    ? 'AUTHENTICATION_REQUIRED'
    : status?.readiness ?? 'AUTHENTICATION_REQUIRED';
  const remedy = providerRemedy(readiness, status?.laneId ?? '');
  const command = status === null ? remedy.command : remedyCommand(status, remedy);
  const title = readiness === 'AUTHENTICATION_REQUIRED'
    ? `${providerName} sign-in required`
    : readiness === 'CLI_MISSING'
      ? `${providerName} CLI not installed`
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
          : 'The last turn stopped because this provider needs authentication. The task and checkout were preserved.'}
        {!ready && <div className="agt-row-meta"><strong>{remedy.title}.</strong> {remedy.detail}</div>}
        {readiness === 'UNSUPPORTED' && status?.version && <div className="agt-row-meta">Detected CLI version: {status.version}</div>}
        {status?.diagnostic && <div className="agt-row-meta">{status.diagnostic}</div>}
        {command && !ready && <div className="agt-mono agt-auth-command">{command}</div>}
        {ready && <div className="agt-auth-ready">Login status is ready. Retry remains a separate confirmed action.</div>}
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
        <button type="button" className="agt-action" disabled={checking || sending} onClick={onRefresh}>
          {checking ? 'Checking…' : failedTurn || readiness === 'AUTHENTICATION_REQUIRED' ? 'Refresh login status' : 'Check again'}
        </button>
        {!ready && onOpenSettings !== undefined && (
          <button type="button" className="agt-action" onClick={onOpenSettings}>Open workspace settings</button>
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
function TerminalSummary({ task, onCleanup }: { task: AgentTaskDto; onCleanup: () => void }): React.JSX.Element {
  const completed = task.state === 'COMPLETED';
  return (
    <section className="agt-terminal" aria-label={completed ? 'Task result' : 'Task failure'}>
      <div className="agt-terminal__kind">
        {completed ? 'Completed' : 'Failed'}{task.terminalAt === null ? '' : ` · ${relativeTime(task.terminalAt)}`}
      </div>
      {task.terminalReason !== null && <div>{task.terminalReason}</div>}
      {task.summary?.finalResponse && <div className="agt-terminal__response">{renderMarkdown(task.summary.finalResponse)}</div>}
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

/** The plan the running turn is following, kept in view above the composer while it runs. */
function PlanCard({ steps, open, onToggle }: { steps: PlanStep[]; open: boolean; onToggle: () => void }): React.JSX.Element {
  return (
    <div className="agt-todos">
      <button type="button" className="agt-todos__head" aria-expanded={open} aria-label="Show or hide the plan" onClick={onToggle}>
        <ListChecks className="agt-todos__icon" aria-hidden="true" />
        <span className="agt-todos__title">Plan</span>
        <span className="agt-todos__summary agt-ellipsis">{planSummary(steps)}</span>
        <span className="agt-spacer" />
        <Chevron open={open} />
      </button>
      {open && <PlanSteps steps={steps} compact />}
    </div>
  );
}

/** How long an agent worked, live while it runs and fixed once it ends. */
function agentElapsed(agent: AgentNode, now: number): string | null {
  if (agent.startedAt === null || agent.status === 'unknown') return null;
  const end = agent.endedAt === null ? now : Date.parse(agent.endedAt);
  return formatDuration(Math.max(0, end - Date.parse(agent.startedAt)));
}

function AgentRow({ agent, now, onOpen }: { agent: AgentNode; now: number; onOpen: () => void }): React.JSX.Element {
  return (
    <button type="button" className="agt-panel__row" onClick={onOpen} title="Open this agent">
      <AgentMark name={agent.label} />
      <span className="agt-panel__name">
        <span className="agt-panel__label">{agent.label}</span>
        {agent.purpose !== null && <span className="agt-panel__purpose">{agent.purpose}</span>}
      </span>
      {agent.status === 'running' ? <Spinner /> : <span className="agt-spacer" />}
      {agent.status === 'unknown' && <span className="agt-chip">historical activity</span>}
      {agent.status === 'failed' && <span className="agt-chip is-warn">failed</span>}
      <span className="agt-mono agt-muted">{agentElapsed(agent, now) ?? ''}</span>
      <Chevron />
    </button>
  );
}

/**
 * The subagents this task delegated to, and one agent's own transcript when it is opened.
 *
 * Opening an agent filters the task's existing events by its thread; it is not another route and
 * not a second subscription. Once retention has removed the transcript the agent is still listed
 * from the task's kept summary, and opening it says its activity expired rather than inventing it.
 */
/**
 * The fixed request behind the panel's Create draft PR button. It goes through the ordinary turn
 * path, so the agent commits, pushes and opens the draft with its own tools under its usual
 * approvals; ByteQuay itself writes nothing to GitHub here. A linked workspace's pull request
 * targets the upstream it monitors, with the head named through the fork the branch is pushed to.
 */
export function draftPullRequestPrompt(workspace: WorkspaceDto): string {
  const linked = workspace.upstreamOwner !== null;
  const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const base = (linked ? workspace.upstreamDefaultBranch : null) ?? workspace.defaultBranch;
  const head = linked ? `${workspace.selectedOwner}:<branch>` : '<branch>';
  return [
    'Open a draft pull request for this task.',
    '',
    '1. Commit any uncommitted changes on the current branch in focused commits with clear messages that follow the repository\'s conventions. Do not amend or rewrite commits that already exist.',
    '2. Read the repository\'s pull request template if it has one (.github/PULL_REQUEST_TEMPLATE.md, .github/pull_request_template.md, PULL_REQUEST_TEMPLATE.md, docs/PULL_REQUEST_TEMPLATE.md, or a file under .github/PULL_REQUEST_TEMPLATE/). Write the title and description for what actually changed, filling in every section of the template; without one, describe what changed, why, and how it was verified.',
    '3. Push the current branch: git push -u origin HEAD',
    '4. Create the pull request as a draft with the GitHub CLI, where <branch> is the current branch name (git branch --show-current):',
    `   gh pr create --draft --repo ${repository} --base ${base} --head ${head} --title "<title>" --body-file <a temporary file outside the checkout>`,
    '5. Reply with the pull request URL. If the push or the GitHub CLI fails, report exactly what failed; do not publish another way.',
  ].join('\n');
}

/**
 * The panel's one top row: the three tabs, then Open full width and Hide panel.
 *
 * Nothing else lives here. The pull request's state, its branches and its checks read in the
 * body under the title, where the full-width page already puts them, and scroll with it.
 */
function PanelTabs({ tab, showAgents, pullRequest, agentCount, onSelect, onOpenFullWidth, onHide }: {
  tab: PanelTab;
  showAgents: boolean;
  pullRequest: PullRequestDto | null;
  agentCount: number;
  onSelect: (tab: PanelTab) => void;
  /** Absent when the panel has no pull request to open, or no page to open it on. */
  onOpenFullWidth?: () => void;
  onHide: () => void;
}): React.JSX.Element {
  return (
    <div className="agt-panel__tabs">
      {/* Labels never wrap: below the panel's minimum width the tabs scroll and the icons stay. */}
      <div className="agt-panel__tablist" role="tablist" aria-label="Task side panel views">
        <button type="button" role="tab" className={`agt-panel__tab${tab === 'pr' ? ' is-current' : ''}`} aria-selected={tab === 'pr'} onClick={() => onSelect('pr')}>
          <GitPullRequest className="agt-panel__tab-mark" aria-hidden="true" />
          {pullRequest === null ? 'Pull request' : `PR #${pullRequest.number}`}
        </button>
        <button type="button" role="tab" className={`agt-panel__tab${tab === 'changes' ? ' is-current' : ''}`} aria-selected={tab === 'changes'} onClick={() => onSelect('changes')}>
          <FileDiff className="agt-panel__tab-mark" aria-hidden="true" />
          Changes
          {pullRequest !== null && (
            <span className="agt-panel__tab-count">
              <span className="is-added">+{pullRequest.additions}</span>
              <span className="is-removed">−{pullRequest.deletions}</span>
            </span>
          )}
        </button>
        {showAgents && (
          <button type="button" role="tab" className={`agt-panel__tab${tab === 'agents' ? ' is-current' : ''}`} aria-selected={tab === 'agents'} onClick={() => onSelect('agents')}>
            <Bot className="agt-panel__tab-mark" aria-hidden="true" />
            Subagents
            {agentCount > 0 && <span className="agt-chip">{agentCount}</span>}
          </button>
        )}
      </div>
      <span className="agt-spacer" />
      {onOpenFullWidth !== undefined && (
        <button type="button" className="agt-panel__icon" aria-label="Open this pull request full width" title="Open full width" onClick={onOpenFullWidth}>
          <Maximize2 aria-hidden="true" />
        </button>
      )}
      <button type="button" className="agt-panel__hide" aria-label="Hide the side panel" onClick={onHide}>»</button>
    </div>
  );
}

/**
 * The design keeps the panel tabs pinned while only the selected panel body scrolls.
 *
 * The diff brings its own toolbar and scroller, so it mounts flush and fills the frame instead
 * of sitting inside a second scroller with its own padding.
 */
function PanelFrame({ label, tabs, width, flush = false, children }: {
  label: string;
  tabs: React.JSX.Element;
  width: number;
  flush?: boolean;
  children: React.ReactNode;
}): React.JSX.Element {
  const scrollRef = useRef<HTMLDivElement>(null);
  useOutputWheel(scrollRef);
  return (
    <aside className="agt-panel" aria-label={label} style={{ width }}>
      {tabs}
      {flush
        ? <div className="agt-panel__flush">{children}</div>
        : <div className="agt-panel__scroll" ref={scrollRef}>{children}</div>}
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
function SidePanel({
  agents, openAgent, expiredChildren, deleteAfter, transcript, now, tab, pullRequest,
  pullRequestDetail, pullRequestChanges, workspaceId, branch, repository,
  activityChoice, onOpenAgent, onCloseAgent, onToggleActivity, onDecide, onSelectTab, onHide, onOpenPullRequest,
  onCreatePullRequest, createPullRequestBlocked = null,
  width, showAgents, logChoice = null, onToggleLog = () => {}, historyUnloaded = false, onLoadEarlier = () => {}, historyLoading = false,
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
  /** The pull request on this task's branch, or null while the branch has none. */
  pullRequest: PullRequestDto | null;
  pullRequestDetail: TaskPullRequestDetail;
  pullRequestChanges: TaskPullRequestChanges;
  workspaceId?: string;
  /** The task's result branch and the repository a draft would target, for the empty state. */
  branch: string | null;
  repository: string | null;
  activityChoice: Record<string, boolean>;
  onOpenAgent: (threadId: string) => void;
  onCloseAgent: () => void;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide: (approvalId: string, decision: AgentApprovalDecision) => void;
  onSelectTab: (tab: PanelTab) => void;
  onHide: () => void;
  onOpenPullRequest?: (number: number) => void;
  onCreatePullRequest?: () => void;
  createPullRequestBlocked?: string | null;
  width: number;
  showAgents: boolean;
  /** The agent whose log the user opened, or "!thread" for one they folded; live agents open by default. */
  logChoice?: string | null;
  onToggleLog?: (choice: string) => void;
}): React.JSX.Element {
  const active = activeAgents(agents);
  const done = agents.filter((agent) => agent.status !== 'running');
  const labels = Object.fromEntries(agents.map((agent) => [agent.threadId, agent.label]));
  const tabs = (
    <PanelTabs
      tab={tab}
      showAgents={showAgents}
      pullRequest={pullRequest}
      agentCount={agents.length + expiredChildren.length}
      onSelect={onSelectTab}
      onOpenFullWidth={pullRequest === null || onOpenPullRequest === undefined
        ? undefined
        : () => onOpenPullRequest(pullRequest.number)}
      onHide={onHide}
    />
  );
  if (tab === 'pr') {
    return (
      <PanelFrame label={pullRequest === null ? 'Pull request' : `Pull request #${pullRequest.number}`} tabs={tabs} width={width}>
        {pullRequest === null
          ? <NoPullRequestView branch={branch} repository={repository} onCreate={onCreatePullRequest} createBlocked={createPullRequestBlocked} />
          : (
            <TaskPullRequestView
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
  if (tab === 'changes') {
    return (
      <PanelFrame label="Changes" tabs={tabs} width={width} flush={pullRequest !== null}>
        <TaskChangesView pr={pullRequest} changes={pullRequestChanges} />
      </PanelFrame>
    );
  }
  if (openAgent !== null) {
    const agent = agents.find((node) => node.threadId === openAgent);
    const expired = expiredChildren.find((child) => child.threadId === openAgent);
    const blocks = transcript.flatMap((turn) => turn.blocks);
    const label = agent?.label ?? expired?.label ?? 'Agent';
    const result = agent?.result ?? expired?.result ?? null;
    const purpose = agent?.purpose ?? expired?.purpose ?? null;
    const parent = agent?.parentThreadId ?? expired?.parentThreadId ?? null;
    const parentLabel = parent === null ? 'root agent' : labels[parent] ?? 'root agent';
    const endedAt = agent?.endedAt ?? expired?.endedAt ?? null;
    const startedAt = agent?.startedAt ?? expired?.startedAt ?? null;
    const worked = startedAt === null || agent?.status === 'unknown' ? null : formatDuration(Math.max(0, (endedAt === null ? now : Date.parse(endedAt)) - Date.parse(startedAt)));
    const running = agent?.status === 'running';
    // A live agent's log is what the user came to watch; a finished one folds to its result.
    const logOpen = logChoice === openAgent ? true : logChoice === `!${openAgent}` ? false : running;
    return (
      <PanelFrame label={`Agent: ${label}`} tabs={tabs} width={width}>
        <div className="agt-panel__head">
          <button type="button" className="agt-back" onClick={onCloseAgent} aria-label="Back to all subagents">‹</button>
          <AgentMark name={label} muted={agent === undefined} />
          <strong className={`agt-panel__label${agent === undefined ? ' is-muted' : ''}`}>{label}</strong>
          {running
            ? <Spinner />
            : (
              <span className={`agt-panel__outcome${agent?.status === 'failed' ? ' is-failed' : agent === undefined ? ' is-expired' : ' is-done'}`}>
                {agent?.status === 'failed' ? <CircleX aria-hidden="true" /> : <CircleCheck aria-hidden="true" />}
                <span>{agent?.status === 'unknown' ? 'Historical activity' : agent?.status === 'failed' ? 'Failed' : agent === undefined ? `Done${endedAt === null ? '' : ` · ${shortDate(endedAt)}`}` : 'Done'}</span>
              </span>
            )}
        </div>
        {worked !== null && blocks.length > 0 && (
          <button
            type="button"
            className="agt-panel__worked"
            aria-expanded={logOpen}
            title="Show or hide this agent's log"
            onClick={() => onToggleLog(logOpen ? `!${openAgent}` : openAgent)}
          >
            {running ? 'Working for ' : 'Worked for '}{worked}{endedAt === null ? '' : ` · finished ${clock(endedAt)}`}
            <Chevron open={logOpen} size={11} />
          </button>
        )}
        {blocks.length === 0 && (
          <div className="agt-panel__expired">
            <div className="agt-panel__expired-title">{historyUnloaded ? 'Activity is not loaded' : 'Activity expired'}</div>
            {historyUnloaded ? <button type="button" className="agt-action" disabled={historyLoading} onClick={onLoadEarlier}>Load earlier activity</button> : <div className="agt-panel__expired-body">
              {deleteAfter === null
                ? 'Detailed steps were removed at the end of the retention window. The result is kept.'
                : `Detailed steps were removed on ${shortDate(deleteAfter, true)} at the end of the retention window. The result is kept.`}
            </div>}
          </div>
        )}
        {logOpen && blocks.length > 0 && (
          <>
            {purpose !== null && (
              <div className="agt-panel__prompt"><span className="agt-panel__from">From {parentLabel} —</span> {purpose}</div>
            )}
            {agent?.backfilling === true && (
              <div className="agt-note">Recovering this agent&apos;s activity from its history…</div>
            )}
            <div className="agt-panel__log">
              {blocks.map((block) => (
                <BlockView
                  key={block.id}
                  block={block}
                  activityChoice={activityChoice}
                  agentLabels={labels}
                  onToggleActivity={onToggleActivity}
                  onDecide={onDecide}
                />
              ))}
            </div>
          </>
        )}
        {result !== null && (
          <>
            <div className="agt-label agt-panel__section">Result{blocks.length === 0 ? ' — kept' : ''}</div>
            <div className="agt-panel__result">{renderMarkdown(result)}</div>
          </>
        )}
        {!running && worked !== null && (
          <div className="agt-panel__finished">
            <Glyph icon={CircleCheck} className="is-ok" />
            {blocks.length === 0 && endedAt !== null ? `Finished ${shortDate(endedAt)} · ${worked}` : worked}
          </div>
        )}
      </PanelFrame>
    );
  }
  return (
    <PanelFrame label="Subagents" tabs={tabs} width={width}>
      {historyUnloaded && <div className="agt-note">Showing subagents in loaded activity. Earlier or newer pages may contain others.</div>}
      <div className="agt-label agt-panel__section">{historyUnloaded ? 'Active in this window' : 'Active'} · {active.length}</div>
      {active.length === 0
        ? <div className="agt-muted agt-panel__empty">{historyUnloaded ? 'No active subagents in loaded activity' : 'No active subagents'}</div>
        : active.map((agent) => (
          <AgentRow key={agent.threadId} agent={agent} now={now} onOpen={() => onOpenAgent(agent.threadId)} />
        ))}
      {(done.length > 0 || expiredChildren.length > 0) && (
        <div className="agt-label agt-panel__section">{done.some((agent) => agent.status === 'unknown') ? 'Earlier activity' : 'Done'} · {done.length + expiredChildren.length}</div>
      )}
      {done.map((agent) => (
        <AgentRow key={agent.threadId} agent={agent} now={now} onOpen={() => onOpenAgent(agent.threadId)} />
      ))}
      {expiredChildren.map((child) => (
        <button
          type="button"
          className="agt-panel__row"
          key={child.threadId}
          onClick={() => onOpenAgent(child.threadId)}
          title="Open this agent"
        >
          <AgentMark name={child.label} muted />
          <span className="agt-panel__name"><span className="agt-panel__label is-muted">{child.label}</span></span>
          <span className="agt-chip">{historyUnloaded ? 'not loaded' : 'expired'}</span>
          <span className="agt-spacer" />
          {child.endedAt !== null && <span className="agt-mono">{shortDate(child.endedAt)}</span>}
          <Chevron />
        </button>
      ))}
    </PanelFrame>
  );
}

/**
 * The whole screen as a function of state, so every visual state can be rendered on its own.
 *
 * The effectful wrapper below owns the stream and the clock; this owns nothing, which is what
 * makes a reconnecting, recovering or closed screen testable without driving a real subscription.
 */
export function AgentTaskBody({
  state, models, onBack, onSend: send, onCancel, onComplete, onDecide, onCompose, onSelectModel,
  onStopServer, onMarkFailed, onRename, onArchive, composerRef,
  provider = '',
  providerStatus = null,
  providerStatusFresh = false,
  providerChecking = false,
  onRefreshProvider = () => {},
  onRetryAuthentication = () => {},
  onOpenSettings,
  now = Date.now(),
  activityChoice = {},
  turnChoice = {},
  planOpen = true,
  previews = {},
  panelOpen = false,
  openAgent = null,
  panelTab = 'pr',
  pullRequests = [],
  pullRequestDetail = NO_PULL_REQUEST_DETAIL,
  pullRequestChanges = NO_PULL_REQUEST_CHANGES,
  repository = null,
  logChoice = null,
  onToggleLog = () => {},
  onToggleActivity = () => {},
  onToggleTurn = () => {},
  onTogglePlan = () => {},
  onAttach = () => {},
  onPasteImages,
  attaching = false,
  onRemoveAttachment = () => {},
  onTogglePanel = () => {},
  onOpenAgent = () => {},
  onCloseAgent = () => {},
  onSelectPanelTab = () => {},
  onOpenPullRequest,
  onCreatePullRequest,
  onSteer: steer = () => {},
  onQueue = () => {},
  onRemoveQueued = () => {},
  onResumeQueue = () => {},
  onCleanup = () => {},
  historyLoading = false,
  onLoadHistory = () => {},
  onFollowHistory = () => {},
  pendingApprovals = null,
}: {
  state: AgentTaskState;
  models: ModelOptionDto[];
  historyLoading?: boolean;
  onLoadHistory?: (direction: 'older' | 'newer' | 'latest') => void;
  onFollowHistory?: (following: boolean) => void;
  pendingApprovals?: AgentPendingApprovalDto[] | null;
  /** The lane's label, as the heading of its model list. */
  provider?: string;
  /** Fresh status for the immutable task lane, used only by typed authentication recovery. */
  providerStatus?: AgentProviderStatus | null;
  /** A readiness probe completed after the typed failed turn currently on screen. */
  providerStatusFresh?: boolean;
  providerChecking?: boolean;
  onRefreshProvider?: () => void;
  onRetryAuthentication?: () => void;
  onOpenSettings?: () => void;
  onBack: () => void;
  onSend: () => void;
  onCancel: () => void;
  onComplete: () => void;
  onDecide: (approvalId: string, decision: AgentApprovalDecision) => void;
  onCompose: (text: string) => void;
  onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => void;
  onStopServer: () => void;
  onMarkFailed: () => void;
  /** A person's name for the task; absent while the task is still loading or in a static render. */
  onRename?: (title: string) => void;
  /** Shelves the task or brings it back; absent while the task is still loading or in a static render. */
  onArchive?: (archived: boolean) => void;
  composerRef?: React.Ref<HTMLTextAreaElement>;
  /** The clock reading the elapsed times are drawn against; ticks once a second while a turn runs. */
  now?: number;
  /** Whether the user opened or folded an activity group, by block id; live updates never change it. */
  activityChoice?: Record<string, boolean>;
  turnChoice?: Record<string, boolean>;
  planOpen?: boolean;
  /** Object URLs for stored images, by attachment id; owned and revoked by the wrapper. */
  previews?: Record<string, AttachmentPreview>;
  panelOpen?: boolean;
  /** Which subagent the panel is showing, by thread id; null lists them all. */
  openAgent?: string | null;
  onToggleActivity?: (id: string, open: boolean) => void;
  onToggleTurn?: (id: string, open: boolean) => void;
  onTogglePlan?: () => void;
  onAttach?: () => void;
  onPasteImages?: () => void;
  attaching?: boolean;
  onRemoveAttachment?: (attachmentId: string) => void;
  onTogglePanel?: () => void;
  onOpenAgent?: (threadId: string) => void;
  onCloseAgent?: () => void;
  /** Which side-panel tab is showing, and the pull requests found on the task's branch. */
  panelTab?: PanelTab;
  pullRequests?: PullRequestDto[];
  /** What the wrapper has read for the panel's pull request; this body reads nothing itself. */
  pullRequestDetail?: TaskPullRequestDetail;
  pullRequestChanges?: TaskPullRequestChanges;
  /** The repository a pull request from this task targets, named in the no-pull-request state. */
  repository?: string | null;
  onSelectPanelTab?: (tab: PanelTab) => void;
  onOpenPullRequest?: (number: number) => void;
  /** Asks the agent to open a draft pull request; absent when no workspace names its base. */
  onCreatePullRequest?: () => void;
  logChoice?: string | null;
  onToggleLog?: (choice: string) => void;
  /** Steer sends the draft into the running turn; Run next queues it behind that turn. */
  onSteer?: () => void;
  onQueue?: () => void;
  onRemoveQueued?: (queuedInputId: string) => void;
  onResumeQueue?: () => void;
  /** Retries safe checkout removal for a terminal task whose first attempt failed. */
  onCleanup?: () => void;
}): React.JSX.Element {
  const sidePanel = useResizablePane(300, 240, 1600, 'start');
  const before = historyBefore(state);
  const after = historyAfter(state);
  const scroll = useConversationHistory(state.events, state.turn?.endedAt, {
    before, after, loading: historyLoading, onLoad: onLoadHistory, onFollow: onFollowHistory,
  });
  const { following, changeFollowing, loadHistory } = scroll;
  const pendingIds = new Set(pendingApprovals?.map((approval) => approval.approvalId) ?? []);
  const project = (threadId: string | null): TranscriptTurn[] => {
    const atLatest = after === null && (following || (state.events.at(-1)?.sequence ?? 0) >= state.lastSequence);
    const projected = projectTranscript(state.events, threadId, state.turns, before === null, atLatest);
    return state.historyPages.length > 0 ? withApprovalAuthority(projected, pendingIds) : projected;
  };
  const projectedTurns = project(null);
  const terminalTask = state.task !== null && state.task.state !== 'OPEN';
  const turns = projectedTurns.map((turn, index) => (
    terminalTask && index === projectedTurns.length - 1
      ? settleFinalTurn(turn, state.task?.terminalAt ?? null, state.task?.state === 'COMPLETED')
      : turn
  ));
  const onSend = (): void => { changeFollowing(true); send(); };
  const onSteer = (): void => { changeFollowing(true); steer(); };
  const supportsNestedAgents = state.capabilities?.nestedAgentActivity === true;
  const agents = supportsNestedAgents ? projectAgents(state.events, state.historyPages.length === 0 ? undefined : state.turn?.state === 'RUNNING' ? state.turn.id : null, after === null) : [];
  const agentLabels = Object.fromEntries(agents.map((agent) => [agent.threadId, agent.label]));
  const working = activeAgents(agents);
  // A child the summary names but the events no longer hold: its steps were cleaned up.
  const expiredChildren = (supportsNestedAgents ? state.task?.summary?.children ?? [] : [])
    .filter((child) => !agents.some((agent) => agent.threadId === child.threadId));
  const hasAgents = agents.length > 0 || expiredChildren.length > 0;
  const visiblePanelTab: PanelTab = supportsNestedAgents || panelTab !== 'agents' ? panelTab : 'pr';
  const taskPr = taskPullRequest(pullRequests);
  const resultBranch = state.task?.summary?.resultBranch ?? null;
  /**
   * A link to this task's own pull request reads in the panel instead of leaving for GitHub.
   * The task route does not change, and every other link keeps its ordinary behaviour.
   */
  const selectPullRequestTab = (event: React.MouseEvent<HTMLDivElement>): void => {
    if (taskPr === null || event.defaultPrevented || event.metaKey || event.ctrlKey) return;
    const link = (event.target as Element | null)?.closest?.('a');
    if (link === null || link === undefined || !samePullRequest(link.getAttribute('href'), taskPr)) return;
    event.preventDefault();
    onSelectPanelTab('pr');
    if (!panelOpen) onTogglePanel();
  };
  const status = runtimeStatus(state);
  const terminal = status === 'completed' || status === 'failed';
  const waiting = state.historyPages.length > 0 ? (pendingApprovals?.length ?? 0) > 0
    : turns.some((turn) => turn.blocks.some((block) => block.kind === 'approval' && block.actionable && block.outcome === null));
  const statusLabel = status === 'running' && waiting ? 'Needs approval' : STATUS_LABELS[status];
  const statusText = state.turn === null
    ? STATUS_LABELS[status]
    : `${STATUS_LABELS[status]} · turn ${state.turn.state.toLowerCase()}`;
  const plan = status === 'running' && after === null && state.capabilities?.structuredPlans === true
    ? currentPlan(state.historyPages.length === 0 ? turns : turns.filter((turn) => turn.turnId === state.turn?.id)) : null;
  const nextImagesAccepted = acceptsImages(models, state.task?.defaults.model, state.capabilities);
  const activeImagesAccepted = acceptsImages(models, state.turn?.settings?.model, state.capabilities);
  const imagesAccepted = status === 'running'
    ? nextImagesAccepted || (state.capabilities?.steering === true && activeImagesAccepted)
    : nextImagesAccepted;
  const attachTitle = status === 'running'
    ? (imagesAccepted ? 'The current or next-turn model accepts images' : 'Neither the current nor next-turn model accepts images')
    : (imagesAccepted
      ? `${modelName(models, state.task?.defaults.model ?? null)} accepts images`
      : `${modelName(models, state.task?.defaults.model ?? null)} does not accept images`);
  const hasDraftImages = state.attachments.length > 0;
  const steerImageBlocked = hasDraftImages && !activeImagesAccepted;
  const queueImageBlocked = hasDraftImages && !nextImagesAccepted;
  const authenticationFailure = state.turn?.failureKind === 'AUTHENTICATION_REQUIRED';
  const providerBlocked = providerStatus !== null && !providerAvailable(providerStatus.readiness);
  const providerRecoveryRequired = status === 'idle' && (authenticationFailure || providerBlocked);
  const providerStartBlocked = providerBlocked || (authenticationFailure && !providerStatusFresh);
  const authenticationRetry = authenticationFailure ? retryableInput(state) : null;
  return (
    <div className="agt-screen">
      <div className="agt-screen-head">
        <button type="button" className="agt-back" onClick={onBack} aria-label="Back to tasks">‹ Tasks</button>
        <TaskTitle
          title={state.task?.title ?? 'Agent task'}
          provisional={state.task?.titleSource === 'PROVISIONAL'}
          onRename={state.task === null ? undefined : onRename}
        />
        <span className={`agt-state is-${status}${waiting && status === 'running' ? ' is-waiting' : ''}`}>
          {status === 'running' && !waiting && <Spinner />}
          {(status === 'recovery' || status === 'loading' || (status === 'running' && waiting)) && <span className="agt-state__dot" aria-hidden="true" />}
          {statusLabel}
        </span>
        {state.task !== null && onArchive !== undefined && (
          <button
            type="button"
            className="agt-headbtn is-shelf"
            title={taskArchived(state.task) ? 'Restore' : 'Archive'}
            aria-label={taskArchived(state.task) ? 'Restore' : 'Archive'}
            disabled={state.sending}
            onClick={() => onArchive(!taskArchived(state.task ?? { archivedAt: null }))}
          >
            {taskArchived(state.task) ? <ArchiveRestore aria-hidden="true" /> : <Archive aria-hidden="true" />}
          </button>
        )}
        {!terminal && (
          <button
            type="button"
            className="agt-headbtn"
            aria-label="Mark it complete"
            title={completeTitle(status, state)}
            onClick={onComplete}
            disabled={state.sending || !canComplete(state)}
          >
            <CircleCheck aria-hidden="true" />
          </button>
        )}
        <span className="agt-spacer" />
        <span
          className={`agt-conn-dot is-${stateClass(state.connection)}`}
          title={connectionText(state)}
          aria-label={connectionText(state)}
        />
        {state.task !== null && status !== 'loading' && (
          <button
            type="button"
            className="agt-headbtn"
            aria-label="Show or hide the side panel"
            aria-pressed={panelOpen}
            title={supportsNestedAgents
              ? (hasAgents
                ? `PR, changes and subagents (${agents.length + expiredChildren.length})`
                : 'PR, changes and subagents')
              : 'PR and changes'}
            onClick={onTogglePanel}
          >
            <PanelRight aria-hidden="true" />
          </button>
        )}
      </div>

      {/* A healthy connection is just the header dot; the strip is for states needing attention. */}
      {((state.connection !== 'open' && state.connection !== 'connecting') || state.gap) && (
        <div className={`agt-conn is-${stateClass(state.connection)}`}>
          {state.connection === 'reconnecting' && <Spinner warn />}
          {connectionText(state)}
          {state.gap ? ' — waiting for a missing event' : ''}
        </div>
      )}
      {state.notice !== null && <div className="agt-notice" role="alert">{state.notice}</div>}

      <div className="agt-body" onClick={selectPullRequestTab}>
      <AgentConversation
        turns={turns} durableTurns={state.turns} models={models} now={now} scroll={scroll}
        connection={state.connection} running={status === 'running'} activeTurn={state.turn} activeTurnId={state.turn?.id ?? state.latestTurnId}
        waiting={waiting} gap={state.gap} activityChoice={activityChoice} turnChoice={turnChoice}
        previews={previews} agentLabels={agentLabels} pendingApprovals={pendingApprovals ?? []}
        onToggleTurn={onToggleTurn} onToggleActivity={onToggleActivity} onDecide={onDecide} onOpenAgent={onOpenAgent}
        transcriptFooter={working.length > 0 && (
          <div className="agt-barrier">
            {working.length === 1 ? '1 subagent is still working' : `${working.length} subagents are still working`}
            {' — Send, queued messages, and Complete unlock when every agent and command finishes.'}
          </div>
        )}>
      {/* Bounded: the status and turn state change a handful of times, streamed text never lands here. */}
      <div className="agt-status" role="status" aria-live="polite">{statusText}</div>

      {terminal && state.task !== null && <TerminalSummary task={state.task} onCleanup={onCleanup} />}

      {!terminal && plan !== null && <PlanCard steps={plan.steps} open={planOpen} onToggle={onTogglePlan} />}

      {!terminal && (
        <ConversationQueue
          queue={state.queue.map(input => ({
            ...input,
            attachmentCount: input.settings.attachments.length,
            modelLabel: `${modelName(models, input.settings.model)}${input.settings.effort === null ? '' : ` ${input.settings.effort}`}${input.settings.serviceTier == null ? '' : ` · ${serviceTierName(models, input.settings.model, input.settings.serviceTier)}`}`,
          }))}
          running={status === 'running'}
          idle={status === 'idle'}
          resumeBlocked={providerStartBlocked}
          onRemove={onRemoveQueued}
          onResume={onResumeQueue}
        />
      )}

      {status === 'recovery' && state.task !== null && (
        <RecoveryCard task={state.task} turn={state.turn} working={working} onStopServer={onStopServer} onMarkFailed={onMarkFailed} />
      )}

      {providerRecoveryRequired && (
        <ProviderRecoveryCard
          provider={provider}
          status={providerStatus}
          statusFresh={providerStatusFresh}
          failedTurn={authenticationFailure}
          checking={providerChecking}
          input={authenticationRetry}
          sending={state.sending}
          onRefresh={onRefreshProvider}
          onRetry={onRetryAuthentication}
          onOpenSettings={onOpenSettings}
        />
      )}

      {/* The design's composer: the message above one row of controls, and one round button. */}
      {!terminal && status !== 'recovery' && !providerRecoveryRequired && (
        <ConversationComposer
          value={state.composer} onChange={onCompose} onSend={onSend} onStop={onCancel} onSteer={onSteer} onQueue={onQueue}
          running={status === 'running'} sending={state.sending}
          disabled={status === 'closing' || state.connection === 'connecting' || (state.sending && !attaching)}
          sendEnabled={canSend(state)} steerEnabled={canSteer(state) && !steerImageBlocked} queueEnabled={canQueue(state) && !queueImageBlocked}
          connection={state.connection} closing={status === 'closing'}
          composerRef={composerRef}
          onPaste={event => pasteImage(event, imagesAccepted && (status === 'idle' || status === 'running') && !state.sending, onPasteImages)}
          steerTitle={steerImageBlocked ? 'The running turn’s model does not accept images'
            : state.capabilities?.steering === true ? 'Sends into the running turn now' : 'This provider cannot take input into a running turn'}
          queueTitle={queueImageBlocked ? 'The next-turn model does not accept images' : 'Runs after this turn finishes'}
          attachments={state.attachments} previews={previews} onRemoveAttachment={onRemoveAttachment}
          onAttach={onAttach} attachEnabled={imagesAccepted} attachTitle={attachTitle}
          permissionPolicy={state.task?.defaults.permissionPolicy ?? 'AUTO'}
          models={models} provider={provider} model={state.task?.defaults.model ?? null}
          effort={state.task?.defaults.effort} serviceTier={state.task?.defaults.serviceTier} onSelectModel={onSelectModel}
          footer={<TaskStats task={state.task} turns={turns} now={now} />} />
      )}
      </AgentConversation>
      {panelOpen && state.task !== null && (
        <>
          <div
            className={`agt-panel-resize${sidePanel.resizing ? ' is-resizing' : ''}`}
            role="separator"
            aria-orientation="vertical"
            aria-label="Resize side panel"
            title="Drag to resize the panel"
            onMouseDown={sidePanel.startResize}
          />
          <SidePanel
            agents={agents}
            openAgent={supportsNestedAgents ? openAgent : null}
            expiredChildren={expiredChildren}
            deleteAfter={state.task.deleteAfter}
            transcript={!supportsNestedAgents || openAgent === null ? [] : project(openAgent)}
            historyUnloaded={before !== null || after !== null}
            onLoadEarlier={() => loadHistory('older')}
            historyLoading={historyLoading}
            now={now}
            tab={visiblePanelTab}
            pullRequest={taskPr}
            pullRequestDetail={pullRequestDetail}
            pullRequestChanges={pullRequestChanges}
            workspaceId={state.task.workspaceId}
            branch={resultBranch}
            repository={repository}
            activityChoice={activityChoice}
            onOpenAgent={onOpenAgent}
            onCloseAgent={onCloseAgent}
            onToggleActivity={onToggleActivity}
            onDecide={onDecide}
            onSelectTab={onSelectPanelTab}
            onHide={onTogglePanel}
            onOpenPullRequest={onOpenPullRequest}
            onCreatePullRequest={onCreatePullRequest}
            createPullRequestBlocked={status !== 'idle' || state.sending || state.connection === 'failed'
              ? 'Available once the agent is idle'
              : null}
            width={sidePanel.width}
            showAgents={supportsNestedAgents}
            logChoice={logChoice}
            onToggleLog={onToggleLog}
          />
        </>
      )}
      </div>
    </div>
  );
}

function tokens(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M tok`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(count >= 10_000 ? 0 : 1)}k tok`;
  return `${count} tok`;
}

/** The line under the composer: what the task has done so far and what it has used, from the task's totals. */
function TaskStats({ task, turns, now }: { task: AgentTaskDto | null; turns: TranscriptTurn[]; now: number }): React.JSX.Element | null {
  if (task === null || turns.length === 0) return null;
  const steps = turns.flatMap((turn) => turn.blocks).reduce((total, block) => total + (block.kind === 'activity' ? block.members.length : 0), 0);
  const worked = turns.reduce((total, turn) => total + (turnElapsedMs(turn, now) ?? 0), 0);
  const { inputTokens, cachedInputTokens, outputTokens, cost, costProvenance } = task.metrics;
  const groups = [
    `${turns.length} ${turns.length === 1 ? 'turn' : 'turns'} · ${steps} ${steps === 1 ? 'step' : 'steps'}`,
    `Worked ${formatDuration(worked)}`,
  ];
  if (inputTokens > 0) groups.push(`Cache hit ${Math.round((cachedInputTokens / inputTokens) * 100)}%`);
  const costText = cost === null
    ? ' · Cost unavailable'
    : costProvenance === 'CLI_ESTIMATE' ? ` · Approx. $${cost.toFixed(2)}` : ` · $${cost.toFixed(2)}`;
  groups.push(`Input ${tokens(inputTokens)} · Cached ${tokens(cachedInputTokens)} · Output ${tokens(outputTokens)}${costText}`);
  const counters = task.metrics.counters;
  if (counters) {
    groups.push(`Tools ${counters.toolCalls} · Explored ${counters.exploreActions} · ${codeGraphText(counters, task.codegraphArm === 'ON')}`);
  }
  return (
    <div className="agt-compose__stats" aria-label="Task statistics">
      {groups.map((group, index) => (
        <React.Fragment key={group}>
          {index > 0 && <span className="agt-compose__sep" aria-hidden="true">|</span>}
          <span>{group}</span>
        </React.Fragment>
      ))}
    </div>
  );
}

function failureText(failure: unknown): string {
  const unavailable = agentProviderUnavailableFrom(failure);
  if (unavailable !== null) {
    const command = unavailable.signInCommand ? ` Run: ${unavailable.signInCommand}` : '';
    return `${laneLabel(unavailable.laneId)} is not ready — ${unavailable.diagnostic}${command}`;
  }
  return failure instanceof Error ? failure.message : String(failure);
}

/** Replaces a possibly stale READY row with the typed refusal returned by the turn preflight. */
export function providerStatusFromUnavailable(
  unavailable: AgentProviderUnavailableDto,
  current: AgentProviderStatus | null,
  fallbackCapabilities: AgentCapabilitiesDto,
): AgentProviderStatus {
  const sameLane = current?.laneId === unavailable.laneId;
  return {
    laneId: unavailable.laneId,
    readiness: unavailable.readiness,
    version: sameLane ? current.version : null,
    diagnostic: unavailable.diagnostic,
    capabilities: sameLane ? current.capabilities : fallbackCapabilities,
    signInCommand: unavailable.signInCommand,
  };
}

export default function AgentTaskScreen({
  workspaceId,
  taskId,
  bridge: explicitBridge,
  onBack,
  onOpenPullRequest,
  workspace,
  onOpenSettings,
}: AgentTaskScreenProps): React.JSX.Element {
  const [state, dispatch] = useReducer(agentTaskReducer, undefined, initialAgentTaskState);
  const [models, setModels] = useState<ModelOptionDto[]>([]);
  const [provider, setProvider] = useState('');
  const [providerStatus, setProviderStatus] = useState<AgentProviderStatus | null>(null);
  const [providerStatusTurnId, setProviderStatusTurnId] = useState<string | null>(null);
  const [providerChecking, setProviderChecking] = useState(false);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const [attaching, setAttaching] = useState(false);
  const attachmentPendingRef = useRef(false);
  // Fold choices are the user's, kept here so a live update can never override them.
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const [planOpen, setPlanOpen] = useState(true);
  const [panelOpen, setPanelOpen] = useState(false);
  const [openAgent, setOpenAgent] = useState<string | null>(null);
  const [panelTab, setPanelTab] = useState<PanelTab>('pr');
  const [logChoice, setLogChoice] = useState<string | null>(null);
  const [pullRequests, setPullRequests] = useState<PullRequestDto[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const [historyLoading, setHistoryLoading] = useState(false);
  const historyLoadingRef = useRef(false);
  const followingHistory = useRef(true);
  const [pendingApprovals, setPendingApprovals] = useState<AgentPendingApprovalDto[] | null>(null);
  const stateRef = useRef(state);
  stateRef.current = state;
  // Object URLs are browser resources: created when an image's bytes arrive, revoked when the
  // screen goes away or a draft is dropped, never left for the garbage collector to find.
  const [previews, setPreviews] = useState<Record<string, AttachmentPreview>>({});
  const previewsRef = useRef<Record<string, AttachmentPreview>>({});
  const ownedPreviewUrls = useRef(new Set<string>());
  const previewWork = useRef<Promise<void>>(Promise.resolve());
  previewsRef.current = previews;
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
  const mountedRef = useRef(true);
  const projectionRevisionRef = useRef(0);
  const submissionRef = useRef<DraftSubmissionTracker | null>(null);
  if (submissionRef.current === null) submissionRef.current = createDraftSubmissionTracker();
  const submissions = submissionRef.current;

  useEffect(() => {
    mountedRef.current = true;
    const projectionRevision = projectionRevisionRef;
    return () => {
      mountedRef.current = false;
      projectionRevision.current++;
    };
  }, []);

  /** Reads one coherent server projection; a later request or mutation invalidates its result. */
  const refreshProjection = useCallback(async (turnId: string | null) => {
    const projectionRevision = ++projectionRevisionRef.current;
    const [task, turn, queue] = await Promise.all([
      live().getAgentTask(taskId, workspaceId),
      turnId === null ? Promise.resolve(null) : live().getAgentTurn(turnId),
      live().listAgentQueue(taskId, workspaceId),
    ]);
    if (!mountedRef.current || projectionRevision !== projectionRevisionRef.current) return;
    dispatch({ type: 'task/loaded', task });
    if (turn !== null) dispatch({ type: 'turn/changed', turn });
    dispatch({ type: 'queue/loaded', queue });
  }, [live, taskId, workspaceId]);

  /** Makes an accepted mutation authoritative immediately, even if an older read is still open. */
  const applyQueuedInput = useCallback((input: AgentQueuedInputDto) => {
    projectionRevisionRef.current++;
    dispatch({ type: 'queue/upserted', input });
  }, []);

  const removeQueuedInput = useCallback((queuedInputId: string) => {
    projectionRevisionRef.current++;
    dispatch({ type: 'queue/removed', queuedInputId });
  }, []);

  /** Task versions reject stale reads without discarding an unrelated queue refresh. */
  const applyTask = useCallback((task: AgentTaskDto) => {
    dispatch({ type: 'task/loaded', task });
  }, []);

  const applyTurn = useCallback((turn: AgentTurnDto) => {
    projectionRevisionRef.current++;
    dispatch({ type: 'turn/changed', turn });
  }, []);

  const taskLaneId = state.task?.laneId ?? null;
  useEffect(() => {
    let active = true;
    const session = startAgentTaskSession(live(), taskId, workspaceId, dispatch, () => active, () => followingHistory.current);
    session.catch((failure: unknown) => {
      if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
    return () => {
      active = false;
      void session.then((stop) => stop(), () => {});
    };
  }, [live, taskId, workspaceId]);

  const loadHistory = useCallback(async (direction: 'older' | 'newer' | 'latest') => {
    if (historyLoadingRef.current) return;
    const current = stateRef.current;
    const before = direction === 'older' ? historyBefore(current) : null;
    const after = direction === 'newer' ? historyAfter(current) : null;
    if ((direction === 'older' && before === null) || (direction === 'newer' && after === null)) return;
    historyLoadingRef.current = true;
    setHistoryLoading(true);
    followingHistory.current = direction === 'latest';
    try {
      const history = direction === 'latest'
        ? await readRecentAgentHistory(live(), taskId)
        : { page: await live().readAgentHistory(taskId, before ?? undefined, after ?? undefined) };
      if (mountedRef.current) dispatch({ type: 'history/loaded', ...history, direction });
    }
    catch (failure) {
      if (mountedRef.current) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    }
    finally {
      historyLoadingRef.current = false;
      if (mountedRef.current) setHistoryLoading(false);
    }
  }, [live, taskId]);

  const authenticationTurnId = state.turn?.failureKind === 'AUTHENTICATION_REQUIRED'
    ? state.turn.id
    : null;
  useEffect(() => {
    setProviderStatusTurnId(null);
  }, [authenticationTurnId, taskLaneId]);

  useEffect(() => {
    let active = true;
    void (async () => {
      const [catalog, effective] = await Promise.all([
        live().getModelSettings(),
        live().getEffectiveTurnSettings(taskId, workspaceId),
      ]);
      if (!active) return;
      const lane = catalog.providers.find((candidate) => candidate.id === taskLaneId);
      setModels(lane?.models ?? []);
      setProvider(lane?.label ?? '');
      if (lane !== undefined) {
        dispatch({ type: 'capabilities/loaded', capabilities: lane.capabilities });
        setProviderStatus({
          laneId: lane.id,
          readiness: lane.readiness,
          version: lane.version,
          diagnostic: lane.diagnostic,
          capabilities: lane.capabilities,
          signInCommand: lane.signInCommand,
        });
      }
      dispatch({ type: 'settings/loaded', settings: effective });
    })().catch(() => { /* the composer then shows no catalog, which the list already explains */ });
    return () => { active = false; };
  }, [live, taskId, taskLaneId, workspaceId]);

  useEffect(() => {
    void refreshProjection(null).catch((failure: unknown) => {
      if (mountedRef.current) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
  }, [refreshProjection]);

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
  const { lifecycleEpoch } = state;
  const latestTurnId = state.task?.mutationClaim?.kind === 'TURN' ? state.task.mutationClaim.ownerId : state.latestTurnId;
  useEffect(() => {
    if (latestTurnId === null && lifecycleEpoch === 0) return;
    void refreshProjection(latestTurnId).catch((failure: unknown) => {
      if (mountedRef.current) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
  }, [refreshProjection, lifecycleEpoch, latestTurnId]);

  useEffect(() => {
    let active = true;
    setPendingApprovals(null);
    if (latestTurnId === null) return;
    void live().readAgentPendingApprovals(taskId, latestTurnId).then((approvals) => {
      if (active) setPendingApprovals(approvals);
    }).catch((failure: unknown) => {
      if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
    return () => { active = false; };
  }, [live, taskId, latestTurnId, lifecycleEpoch]);

  // Any ordinary turn can publish a PR, including a turn started outside the PR panel.
  const taskLoaded = state.task !== null;
  const pullRequestTurnId = state.turn?.id ?? null;
  const pullRequestTurnEndedAt = state.turn?.endedAt ?? null;
  const pullRequestTurnKnown = latestTurnId === null || latestTurnId === pullRequestTurnId;
  const pullRequestPanelVisible = panelOpen && panelTab !== 'agents';
  useEffect(() => {
    if (!taskLoaded || state.connection !== 'open' || !pullRequestTurnKnown || running) return;
    let active = true;
    const lookup = async (): Promise<void> => {
      const cached = await live().listAgentTaskPullRequests(taskId, workspaceId);
      if (!active) return;
      setPullRequests(cached);
      const found = await live().listAgentTaskPullRequests(taskId, workspaceId, true);
      if (active) setPullRequests(found);
    };
    void lookup()
      .catch((failure: unknown) => {
        if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
      });
    return () => { active = false; };
  }, [live, taskLoaded, state.connection, pullRequestTurnKnown, pullRequestTurnId, pullRequestTurnEndedAt, pullRequestPanelVisible, taskId, workspaceId, running]);

  // The panel reads the pull request itself, so the detail and its diff are read here rather
  // than by the body, which stays a pure function of the state a test can hand it.
  const taskPr = taskPullRequest(pullRequests);
  const pullRequestDetail = useTaskPullRequestDetail(taskPr, panelOpen && panelTab === 'pr');
  const pullRequestChanges = useTaskPullRequestChanges(taskPr, panelOpen && panelTab === 'changes');

  /** Runs one bridge command, surfacing its failure in the notice strip instead of losing it. */
  const run = useCallback(async (action: () => Promise<void>) => {
    dispatch({ type: 'notice/changed', notice: null });
    try {
      await action();
    }
    catch (failure) {
      // A version conflict means another window changed durable task facts. Re-read them while
      // keeping the draft and its retry identity intact, so the next deliberate action can use
      // the newly observed version instead of conflicting forever.
      try {
        await refreshProjection(stateRef.current.latestTurnId);
      }
      catch {
        // The command's original failure is the useful notice; the live stream can reconnect the
        // projection later if this best-effort read also failed.
      }
      dispatch({ type: 'notice/changed', notice: failureText(failure) });
    }
  }, [refreshProjection]);

  const refreshProviderStatus = useCallback(async (): Promise<AgentProviderStatus | null> => {
    const laneId = stateRef.current.task?.laneId;
    if (laneId === undefined) return null;
    const observedTurn = stateRef.current.turn;
    const observedAuthenticationTurnId = observedTurn?.failureKind === 'AUTHENTICATION_REQUIRED'
      ? observedTurn.id
      : null;
    const statuses = await live().refreshAgentProviderReadiness();
    const status = statuses.find((candidate) => candidate.laneId === laneId);
    if (status === undefined) throw new Error(`${laneLabel(laneId)} readiness was not returned`);
    setProviderStatus(status);
    const currentTurn = stateRef.current.turn;
    setProviderStatusTurnId(currentTurn?.failureKind === 'AUTHENTICATION_REQUIRED'
      && currentTurn.id === observedAuthenticationTurnId
      ? currentTurn.id
      : null);
    dispatch({ type: 'capabilities/loaded', capabilities: status.capabilities });
    return status;
  }, [live]);

  const recordProviderUnavailable = useCallback((failure: unknown): boolean => {
    const unavailable = agentProviderUnavailableFrom(failure);
    if (unavailable === null) return false;
    setProviderStatus((current) => providerStatusFromUnavailable(
      unavailable,
      current,
      stateRef.current.capabilities ?? NO_AGENT_CAPABILITIES,
    ));
    setProviderStatusTurnId(null);
    return true;
  }, []);

  const selectModel = useCallback((model: string, effort: string | null, serviceTier: string | null) => run(async () => {
    const task = state.task;
    if (task === null) return;
    // The response carries the bumped version; keeping the old one conflicts on the next change.
    const updated = await live().updateAgentTaskDefaults(taskId, workspaceId, task.version, {
      model,
      effort,
      serviceTier,
      permissionPolicy: task.defaults.permissionPolicy,
    });
    const draft = stateRef.current;
    submissions.draftChanged(taskId, draft.composer, draft.attachments.map((attachment) => attachment.id), updated.defaults);
    applyTask(updated);
    dispatch({
      type: 'settings/loaded',
      settings: await live().getEffectiveTurnSettings(taskId, workspaceId),
    });
  }), [applyTask, live, run, state.task, submissions, taskId, workspaceId]);

  const rename = useCallback((title: string) => run(async () => {
    const task = state.task;
    if (task === null) return;
    applyTask(await live().renameAgentTask(taskId, workspaceId, task.version, title));
  }), [applyTask, live, run, state.task, taskId, workspaceId]);

  const archive = useCallback((archived: boolean) => run(async () => {
    const task = state.task;
    if (task === null) return;
    applyTask(await live().archiveAgentTask(taskId, workspaceId, task.version, archived));
  }), [applyTask, live, run, state.task, taskId, workspaceId]);

  const startTurn = useCallback(async (message: string, attachments: string[], idempotencyKey: string, expectedTaskVersion: number) => {
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const turn = await live().startAgentTurn({
        taskId,
        message,
        expectedTaskVersion,
        idempotencyKey,
        attachments,
      });
      submissions.settled(idempotencyKey);
      applyTurn(turn);
      try {
        // Starting also bumps the task version. Keep the composer busy until that authoritative
        // version is adopted, otherwise an immediate Run next would submit the pre-start version.
        await refreshProjection(turn.id);
      }
      catch {
        // The accepted turn is still authoritative; lifecycle replay can repair this projection.
      }
    }
    catch (failure) {
      recordProviderUnavailable(failure);
      throw failure;
    }
    finally {
      dispatch({ type: 'composer/sending', sending: false });
    }
  }, [applyTurn, live, recordProviderUnavailable, refreshProjection, submissions, taskId]);

  const send = useCallback(() => run(async () => {
    if (attachmentPendingRef.current) return;
    const message = state.composer.trim();
    const task = state.task;
    if (message.length === 0 || task === null) return;
    const attachmentIds = state.attachments.map((attachment) => attachment.id);
    const idempotencyKey = submissions.keyFor('send', taskId, taskId, message, attachmentIds, task.defaults);
    await startTurn(message, attachmentIds, idempotencyKey, task.version);
    // The transcript still waits for the durable user-message event rather than inventing it.
    dispatch({ type: 'composer/changed', text: '' });
    dispatch({ type: 'attachments/cleared' });
  }), [run, startTurn, state.attachments, state.composer, state.task, submissions, taskId]);

  /** One click, one ordinary turn: the agent does the committing, pushing and opening itself. */
  const createPullRequest = useCallback(() => run(async () => {
    const current = stateRef.current;
    const task = current.task;
    if (task === null || workspace === undefined || runtimeStatus(current) !== 'idle' || current.sending) return;
    const message = draftPullRequestPrompt(workspace);
    const idempotencyKey = submissions.keyFor('send', taskId, taskId, message, [], task.defaults);
    await startTurn(message, [], idempotencyKey, task.version);
  }), [run, startTurn, submissions, taskId, workspace]);

  const steer = useCallback(() => run(async () => {
    if (attachmentPendingRef.current) return;
    const message = state.composer.trim();
    const turn = state.turn;
    if (message.length === 0 || turn === null) return;
    const attachmentIds = state.attachments.map((attachment) => attachment.id);
    const idempotencyKey = submissions.keyFor('steer', turn.id, taskId, message, attachmentIds, null);
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const delivery = await live().steerAgentTurn({
        turnId: turn.id,
        message,
        idempotencyKey,
        attachments: attachmentIds,
      });
      submissions.settled(idempotencyKey);
      dispatch({ type: 'composer/changed', text: '' });
      dispatch({ type: 'attachments/cleared' });
      // The message is already in the transcript; what the notice adds is that the agent did
      // not get it, or that nobody can say. Neither is resent from here.
      if (delivery.state === 'UNKNOWN') {
        dispatch({ type: 'notice/changed', notice: `The steer may not have reached the agent: ${delivery.failure ?? 'no answer'}. It is not sent again by itself.` });
      }
      else if (delivery.state !== 'DELIVERED') {
        dispatch({ type: 'notice/changed', notice: `The steer was not delivered: ${delivery.failure ?? delivery.state}` });
      }
    }
    finally {
      dispatch({ type: 'composer/sending', sending: false });
    }
  }), [live, run, state.attachments, state.composer, state.turn, submissions, taskId]);

  const queueNext = useCallback(() => run(async () => {
    if (attachmentPendingRef.current) return;
    const message = state.composer.trim();
    const task = state.task;
    if (message.length === 0 || task === null) return;
    const attachmentIds = state.attachments.map((attachment) => attachment.id);
    const idempotencyKey = submissions.keyFor('queue', taskId, taskId, message, attachmentIds, task.defaults);
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const queued = await live().queueAgentInput({
        taskId,
        workspaceId,
        message,
        expectedVersion: task.version,
        idempotencyKey,
        attachments: attachmentIds,
      });
      submissions.settled(idempotencyKey);
      applyQueuedInput(queued);
      dispatch({ type: 'composer/changed', text: '' });
      dispatch({ type: 'attachments/cleared' });
      // The write moved the task version; one ordered refresh adopts it and reconciles auto-start.
      await refreshProjection(state.latestTurnId);
    }
    finally {
      dispatch({ type: 'composer/sending', sending: false });
    }
  }), [applyQueuedInput, live, refreshProjection, run, state.attachments, state.composer, state.latestTurnId, state.task, submissions, taskId, workspaceId]);

  const removeQueued = useCallback((queuedInputId: string) => run(async () => {
    await live().removeAgentQueuedInput(taskId, workspaceId, queuedInputId);
    removeQueuedInput(queuedInputId);
    await refreshProjection(state.latestTurnId);
  }), [live, refreshProjection, removeQueuedInput, run, state.latestTurnId, taskId, workspaceId]);

  const resumeQueue = useCallback(() => run(async () => {
    try {
      const turn = await live().resumeAgentQueue(taskId, workspaceId);
      applyTurn(turn);
      await refreshProjection(turn.id);
    }
    catch (failure) {
      recordProviderUnavailable(failure);
      throw failure;
    }
  }), [applyTurn, live, recordProviderUnavailable, refreshProjection, run, taskId, workspaceId]);

  const attach = useCallback((source: 'picker' | 'clipboard' = 'picker') => run(async () => {
    const current = stateRef.current;
    const status = runtimeStatus(current);
    const imagesAccepted = acceptsImages(models, current.task?.defaults.model, current.capabilities)
      || (status === 'running' && current.capabilities?.steering === true && acceptsImages(models, current.turn?.settings?.model, current.capabilities));
    if (attachmentPendingRef.current || current.sending || !imagesAccepted || (status !== 'idle' && status !== 'running')) return;
    attachmentPendingRef.current = true;
    setAttaching(true);
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const stored = await (source === 'clipboard' ? live().pasteAgentAttachments(taskId, workspaceId) : live().pickAgentAttachments(taskId, workspaceId));
      if (!mountedRef.current || stateRef.current.task?.id !== taskId || stored.length === 0) return;
      const draft = stateRef.current;
      submissions.draftChanged(
        taskId,
        draft.composer,
        [...draft.attachments, ...stored].map((attachment) => attachment.id),
        draft.task?.defaults ?? null,
      );
      dispatch({ type: 'attachments/added', attachments: stored });
    }
    finally {
      attachmentPendingRef.current = false;
      if (mountedRef.current) {
        setAttaching(false);
        dispatch({ type: 'composer/sending', sending: false });
      }
    }
  }), [live, models, run, submissions, taskId, workspaceId]);

  const removeAttachment = useCallback((attachmentId: string) => run(async () => {
    const draft = stateRef.current;
    submissions.draftChanged(
      taskId,
      draft.composer,
      draft.attachments.filter((attachment) => attachment.id !== attachmentId).map((attachment) => attachment.id),
      draft.task?.defaults ?? null,
    );
    dispatch({ type: 'attachments/removed', attachmentId });
    const preview = previewsRef.current[attachmentId];
    if (preview?.status === 'ready') {
      URL.revokeObjectURL(preview.url);
      ownedPreviewUrls.current.delete(preview.url);
    }
    setPreviews((current) => {
      const next = { ...current };
      delete next[attachmentId];
      return next;
    });
    await live().removeAgentAttachment(taskId, workspaceId, attachmentId);
  }), [live, run, submissions, taskId, workspaceId]);

  // Keep only previews for the visible history window and drafts, with a fixed image count cap.
  const retainedImageIds = [...new Set([
    ...state.events.flatMap((event) => event.type === 'user_message' ? event.payload.attachments.map((attachment) => attachment.id) : []),
    ...state.attachments.map((attachment) => attachment.id),
  ])].slice(-16).join(',');
  useEffect(() => {
    let active = true;
    const ids = retainedImageIds === '' ? [] : retainedImageIds.split(',');
    const retained = new Set(ids);
    const current = previewsRef.current;
    for (const [id, preview] of Object.entries(current)) {
      if (!retained.has(id) && preview.status === 'ready') {
        URL.revokeObjectURL(preview.url);
        ownedPreviewUrls.current.delete(preview.url);
      }
    }
    const needed = ids.filter((id) => current[id] === undefined || current[id].status === 'loading');
    setPreviews(Object.fromEntries(ids.map((id) => [id, current[id] ?? { status: 'loading' as const }])));
    for (const id of needed) {
      // Decode one source image at a time; only 512px thumbnails remain in the renderer.
      previewWork.current = previewWork.current.then(async () => {
        if (!active) return;
        const image = await live().readAgentAttachment(taskId, workspaceId, id);
        if (!active) return;
        const bitmap = await createImageBitmap(new Blob([image.bytes as BlobPart], { type: image.mediaType }));
        try {
          if (!active) return;
          const scale = Math.min(1, 512 / Math.max(bitmap.width, bitmap.height));
          const canvas = new OffscreenCanvas(Math.max(1, Math.round(bitmap.width * scale)), Math.max(1, Math.round(bitmap.height * scale)));
          const context = canvas.getContext('2d');
          if (context === null) throw new Error('Image preview is unavailable');
          context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
          const thumbnail = await canvas.convertToBlob({ type: 'image/png' });
          if (!active) return;
          const url = URL.createObjectURL(thumbnail);
          // Own the URL before React commits state, so an intervening unmount still releases it.
          ownedPreviewUrls.current.add(url);
          const loadOriginal = async (): Promise<Blob> => {
            const original = await live().readAgentAttachment(taskId, workspaceId, id);
            return new Blob([original.bytes as BlobPart], { type: original.mediaType });
          };
          setPreviews((loaded) => ({ ...loaded, [id]: { status: 'ready', url, loadOriginal } }));
        }
        finally { bitmap.close(); }
      }).catch(() => {
        if (active) setPreviews((loaded) => ({ ...loaded, [id]: { status: 'unavailable' } }));
      });
    }
    return () => { active = false; };
  }, [live, taskId, workspaceId, retainedImageIds]);

  useEffect(() => {
    const turns = projectTranscript(state.events);
    const turnIds = new Set(turns.map((turn) => turn.turnId ?? `turn-${turn.ordinal}`));
    const blockIds = new Set(state.events.map((event) => event.eventId));
    setTurnChoice((choices) => Object.fromEntries(Object.entries(choices).filter(([id]) => turnIds.has(id))));
    setActivityChoice((choices) => Object.fromEntries(Object.entries(choices).filter(([id]) => blockIds.has(id))));
  }, [state.events]);

  useEffect(() => {
    const urls = ownedPreviewUrls.current;
    return () => {
      for (const url of urls) URL.revokeObjectURL(url);
      urls.clear();
    };
  }, []);

  const cancel = useCallback(() => run(async () => {
    if (state.turn === null) return;
    const turn = await live().cancelAgentTurn(state.turn.id);
    applyTurn(turn);
    try {
      await refreshProjection(turn.id);
    }
    catch {
      // Terminal lifecycle replay can repair a projection read that failed here.
    }
  }), [applyTurn, live, refreshProjection, run, state.turn]);

  const complete = useCallback(() => run(async () => {
    if (attachmentPendingRef.current || !canComplete(state) || !confirmCompletion(state.queue, (message) => window.confirm(message))) return;
    await live().completeAgentTask(taskId, workspaceId);
    await refreshProjection(state.latestTurnId);
  }), [live, refreshProjection, run, state, taskId, workspaceId]);

  const cleanupWorktree = useCallback(() => run(async () => {
    applyTask(await live().cleanupAgentTaskWorktree(taskId, workspaceId));
  }), [applyTask, live, run, taskId, workspaceId]);

  const stopServer = useCallback(() => run(async () => {
    const impact = await live().getAgentRuntimeStopImpact(taskId, workspaceId);
    const consequence = impact.activeTurnId === null
      ? 'No turn is running; the checkout is released once the process is verified stopped.'
      : 'The running turn is interrupted and its pending approvals denied.';
    if (!window.confirm(`Stop this task's agent runtime? Only this task is affected.\n\n${consequence}`)) return;
    applyTask(await live().stopAgentRuntime(taskId, workspaceId, impact.generation));
    if (latestTurnId !== null) {
      applyTurn(await live().getAgentTurn(latestTurnId));
    }
    await refreshProjection(latestTurnId);
  }), [applyTask, applyTurn, live, latestTurnId, refreshProjection, run, taskId, workspaceId]);

  const markFailed = useCallback(() => run(async () => {
    if (!window.confirm('Mark this task failed? Its checkout is kept for inspection unless it is clean; '
      + 'the transcript is retained.')) return;
    applyTask(await live().markAgentTaskFailed(taskId, workspaceId, 'Marked failed by the user during recovery'));
    await refreshProjection(stateRef.current.latestTurnId);
  }), [applyTask, live, refreshProjection, run, taskId, workspaceId]);

  const refreshProvider = useCallback(() => run(async () => {
    setProviderChecking(true);
    try {
      await refreshProviderStatus();
    }
    finally {
      setProviderChecking(false);
    }
  }), [refreshProviderStatus, run]);

  const retryAuthentication = useCallback(() => run(async () => {
    const current = stateRef.current;
    const task = current.task;
    const input = retryableInput(current);
    if (task === null
      || current.turn?.failureKind !== 'AUTHENTICATION_REQUIRED'
      || input === null
      || providerStatus === null
      || providerStatusTurnId !== current.turn.id
      || !providerAvailable(providerStatus.readiness)) return;
    if (!window.confirm(`Retry the last message? This creates a new turn and may run commands again.\n\n${input.text}`)) return;
    const attachmentIds = input.attachments.map((attachment) => attachment.id);
    // The failed turn's key is never reused. The tracker retains this new key only if this reply is
    // ambiguous, so a second confirmed click cannot create a duplicate retry turn.
    const idempotencyKey = submissions.keyFor('retry', task.id, taskId, input.text, attachmentIds, task.defaults);
    await startTurn(input.text, attachmentIds, idempotencyKey, task.version);
  }), [providerStatus, providerStatusTurnId, run, startTurn, submissions, taskId]);

  const decide = useCallback((approvalId: string, decision: AgentApprovalDecision) => run(async () => {
    await live().resolveAgentApproval(approvalId, decision);
    // The buttons that had focus disappear with the decision; the composer is the next thing to do.
    composerRef.current?.focus();
  }), [live, run]);

  return (
    <AgentTaskBody
      state={state}
      historyLoading={historyLoading}
      onLoadHistory={(direction) => { void loadHistory(direction); }}
      onFollowHistory={(following) => {
        const resumed = following && !followingHistory.current;
        followingHistory.current = following;
        if (resumed && stateRef.current.lastSequence > (stateRef.current.events.at(-1)?.sequence ?? 0)) void loadHistory('latest');
      }}
      pendingApprovals={pendingApprovals}
      models={models}
      provider={provider}
      providerStatus={providerStatus}
      providerStatusFresh={authenticationTurnId !== null && providerStatusTurnId === authenticationTurnId}
      providerChecking={providerChecking}
      composerRef={composerRef}
      onBack={onBack}
      onSend={() => { void send(); }}
      onCancel={() => { void cancel(); }}
      onComplete={() => { void complete(); }}
      onCleanup={() => { void cleanupWorktree(); }}
      onDecide={(approvalId, decision) => { void decide(approvalId, decision); }}
      onCompose={(text) => {
        const draft = stateRef.current;
        submissions.draftChanged(taskId, text, draft.attachments.map((attachment) => attachment.id), draft.task?.defaults ?? null);
        dispatch({ type: 'composer/changed', text });
      }}
      onSelectModel={(model, effort, serviceTier) => { void selectModel(model, effort, serviceTier); }}
      onStopServer={() => { void stopServer(); }}
      onMarkFailed={() => { void markFailed(); }}
      onRefreshProvider={() => { void refreshProvider(); }}
      onRetryAuthentication={() => { void retryAuthentication(); }}
      onOpenSettings={onOpenSettings}
      onRename={(title) => { void rename(title); }}
      onArchive={(archived) => { void archive(archived); }}
      now={now}
      activityChoice={activityChoice}
      turnChoice={turnChoice}
      planOpen={planOpen}
      previews={previews}
      onToggleActivity={(id, open) => setActivityChoice((choices) => ({ ...choices, [id]: open }))}
      onToggleTurn={(id, open) => setTurnChoice((choices) => ({ ...choices, [id]: open }))}
      onTogglePlan={() => setPlanOpen((open) => !open)}
      onAttach={() => { void attach(); }}
      onPasteImages={() => { void attach('clipboard'); }}
      attaching={attaching}
      onRemoveAttachment={(attachmentId) => { void removeAttachment(attachmentId); }}
      panelOpen={panelOpen}
      openAgent={openAgent}
      panelTab={panelTab}
      pullRequests={pullRequests}
      pullRequestDetail={pullRequestDetail}
      pullRequestChanges={pullRequestChanges}
      repository={workspace === undefined ? null : `${workspace.monitoredOwner}/${workspace.monitoredRepo}`}
      onTogglePanel={() => setPanelOpen((open) => !open)}
      onOpenAgent={(threadId) => { setPanelOpen(true); setPanelTab('agents'); setOpenAgent(threadId); }}
      onCloseAgent={() => setOpenAgent(null)}
      onSelectPanelTab={(tab) => { setPanelTab(tab); setOpenAgent(null); }}
      logChoice={logChoice}
      onToggleLog={setLogChoice}
      onOpenPullRequest={onOpenPullRequest}
      onCreatePullRequest={workspace === undefined ? undefined : () => { void createPullRequest(); }}
      onSteer={() => { void steer(); }}
      onQueue={() => { void queueNext(); }}
      onRemoveQueued={(queuedInputId) => { void removeQueued(queuedInputId); }}
      onResumeQueue={() => { void resumeQueue(); }}
    />
  );
}
