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
  BookOpen,
  Check,
  ChevronsDownUp,
  Circle,
  CircleAlert,
  CircleCheck,
  CircleX,
  CornerDownRight,
  FilePenLine,
  FolderOpen,
  Globe,
  Image,
  ListChecks,
  ListPlus,
  LoaderCircle,
  MessageSquare,
  PanelRight,
  Search,
  ShieldCheck,
  ShieldX,
  SquareTerminal,
  Trash2,
  TriangleAlert,
  Wrench,
  X,
  type LucideIcon,
} from 'lucide-react';
import {
  displayModelName,
  laneLabel,
  providerAvailable,
  providerRemedy,
  remedyCommand,
} from '../settings/model';
import { useResizablePane } from '../useResizablePane';
import { AgentMark } from './AgentMark';
import { ModelPicker } from './ModelPicker';
import { renderMarkdown } from './markdown';
import { agentProviderUnavailableFrom } from './contracts';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { Chevron } from '../Chevron';
import { relativeTime } from '../relativeTime';
import '../css/agents.css';
import {
  acceptsImages,
  agentTaskReducer,
  approvalDecisions,
  canComplete,
  canQueue,
  canSend,
  canSteer,
  confirmCompletion,
  createDraftSubmissionTracker,
  initialAgentTaskState,
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
  activityElapsedMs,
  activityExpanded,
  activityPhrase,
  activityRunning,
  activitySummary,
  currentPlan,
  formatDuration,
  planChip,
  planSummary,
  projectAgents,
  projectTranscript,
  settleFinalTurn,
  turnElapsedMs,
  turnLabel,
  type ActivityBlock,
  type ActivityMember,
  type AgentNode,
  type ApprovalBlock,
  type CommandAction,
  type CommandMember,
  type PlanStep,
  type TranscriptTurn,
  type TurnBlock,
} from './transcript';
import type {
  AgentApprovalDecision,
  AgentCapabilitiesDto,
  AgentChildSummaryDto,
  AgentProviderUnavailableDto,
  AgentProviderStatus,
  AgentQueuedInputDto,
  AgentTaskDto,
  AgentTurnDto,
  ModelOptionDto,
  PullRequestDto,
} from '../types';

export type PanelTab = 'agents' | 'prs';

/** What the screen knows about one attachment's image: still loading, an object URL, or gone. */
export type AttachmentPreview = { status: 'loading' } | { status: 'ready'; url: string } | { status: 'unavailable' };

function sizeText(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** A stored image as a tile: the picture when its bytes arrived, otherwise its name and state. */
function AttachmentTile({ attachment, preview, compact = false }: {
  attachment: { id: string; name: string; mediaType: string };
  preview: AttachmentPreview | undefined;
  compact?: boolean;
}): React.JSX.Element {
  const state = preview?.status ?? 'loading';
  if (state === 'ready' && preview?.status === 'ready') {
    return <img className={compact ? 'agt-thumb' : 'agt-image'} src={preview.url} alt={attachment.name} title={attachment.name} />;
  }
  return (
    <span className={`agt-file${compact ? ' is-compact' : ''} is-${state}`} title={attachment.mediaType}>
      {attachment.name}{state === 'unavailable' ? ' · unavailable' : ''}
    </span>
  );
}

export interface AgentTaskScreenProps {
  workspaceId: string;
  taskId: string;
  /** Electron's bridge unless a test supplies its own. */
  bridge?: AgentBridgePort;
  onBack: () => void;
  /** Opens the existing pull-request page for one found on the task's branch. */
  onOpenPullRequest?: (number: number) => void;
  /** Opens the provider settings used by authentication recovery. */
  onOpenSettings?: () => void;
}

function stateClass(state: string): string {
  return state.toLowerCase();
}

/** The catalog's display name for a model id; the raw slug is never shown as a label. */
function modelName(models: ModelOptionDto[], id: string | null): string {
  if (id === null || id === '') return 'No model';
  return displayModelName(models.find((model) => model.id === id)?.name ?? id);
}

function serviceTierName(models: ModelOptionDto[], model: string | null, tier: string | null | undefined): string {
  if (tier == null) return '';
  return models.find((option) => option.id === model)?.serviceTiers?.find((option) => option.id === tier)?.name ?? 'Custom speed';
}

/** Backend policy names are enum values, not prose, and not provider wire strings. */
function permissionLabel(policy: string): string {
  return policy === 'AUTO' ? 'Auto' : policy;
}

/** "Aug 16", or "Aug 23, 2026" with the year, as the design dates a finished agent. */
function shortDate(iso: string, withYear = false): string {
  return new Date(iso).toLocaleDateString([], withYear ? { month: 'short', day: 'numeric', year: 'numeric' } : { month: 'short', day: 'numeric' });
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

function Spinner({ warn = false }: { warn?: boolean } = {}): React.JSX.Element {
  return <LoaderCircle className={`agt-spin${warn ? ' is-warn' : ''}`} aria-hidden="true" />;
}

function Glyph({ icon: Icon, className = '' }: { icon: LucideIcon; className?: string }): React.JSX.Element {
  return <span className={`agt-icon${className === '' ? '' : ` ${className}`}`} aria-hidden="true"><Icon /></span>;
}

function memberState(member: ActivityMember): 'running' | 'ok' | 'failed' {
  if (member.kind === 'file') return 'ok';
  if (member.state === 'STARTED' || member.state === 'PROGRESS') return 'running';
  if (member.state === 'FAILED') return 'failed';
  return member.kind === 'command' && member.exitCode !== null && member.exitCode !== 0 ? 'failed' : 'ok';
}

const ACTION_VERBS = { READ: 'Read', LIST_FILES: 'Listed', SEARCH: 'Searched', OTHER: '' } as const;

const ACTION_ICONS: Record<CommandAction['kind'], LucideIcon> = {
  READ: BookOpen,
  LIST_FILES: FolderOpen,
  SEARCH: Search,
  OTHER: SquareTerminal,
};

const TOOL_ICONS: Record<string, LucideIcon> = {
  'Web search': Globe,
  'Image view': Image,
  'Context compaction': ChevronsDownUp,
};

/** Activity type is the primary icon; a live/failing state temporarily overrides it. */
function ActivityIcon({ member, action }: { member: ActivityMember; action?: CommandAction['kind'] }): React.JSX.Element {
  const state = memberState(member);
  if (state === 'running') return <span className="agt-icon is-running"><Spinner /></span>;
  if (state === 'failed') return <Glyph icon={CircleX} className="is-failed" />;
  if (member.kind === 'command') return <Glyph icon={ACTION_ICONS[action ?? 'OTHER']} />;
  if (member.kind === 'file') return <Glyph icon={FilePenLine} />;
  return <Glyph icon={TOOL_ICONS[member.name] ?? Wrench} />;
}

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
          <ActivityIcon member={member} action={action.kind} />
          <span className="agt-muted">{ACTION_VERBS[action.kind]}</span>
          <span className="agt-mono">
            {action.kind === 'SEARCH'
              ? `${action.query ?? member.command}${action.path === null ? '' : ` in ${action.path}`}`
              : action.path ?? '.'}
          </span>
          {index === 0 && !verbatim && <Duration ms={member.durationMs} />}
        </div>
      ))}
      {verbatim && (member.output === null || member.output === ''
        ? <div className="agt-act__row is-command"><CommandCells member={member} /></div>
        : (
          // The row is the disclosure: keyboard-operable for free, and a running command's output
          // stays open because that is what the user is watching.
          <details className="agt-out" open={live && running ? true : undefined}>
            <summary className="agt-act__row is-command">
              <CommandCells member={member} chevron />
            </summary>
            <pre className="agt-pre">
              {member.output}
              {member.truncated && <span className="agt-muted"> … truncated</span>}
              {running && <span className="agt-cursor" aria-hidden="true" />}
            </pre>
          </details>
        ))}
      {!verbatim && member.output !== null && member.output !== '' && (
        <details className="agt-out" open={live && running ? true : undefined}>
          <summary><Chevron />Output{member.truncated ? ' (truncated)' : ''}</summary>
          <pre className="agt-pre">{member.output}{running && <span className="agt-cursor" aria-hidden="true" />}</pre>
        </details>
      )}
    </>
  );
}

/** The cells of a command's row; the chevron sits after the command when the row folds its output. */
function CommandCells({ member, chevron = false }: {
  member: CommandMember;
  chevron?: boolean;
}): React.JSX.Element {
  return (
    <>
      <ActivityIcon member={member} />
      <span className="agt-mono">$ {member.command}</span>
      {chevron && <Chevron size={11} />}
      {member.exitCode !== null && (
        <span className={`agt-exit ${member.exitCode === 0 ? 'is-ok' : 'is-failed'}`}>exit {member.exitCode}</span>
      )}
      {member.cwd !== null && member.cwd !== '.' && <span className="agt-mono agt-muted">in {member.cwd}</span>}
      <Duration ms={member.durationMs} />
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
                <ActivityIcon member={member} />
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
                  <summary><Chevron />Diff</summary>
                  <pre className="agt-pre">{member.diff}</pre>
                </details>
              )}
            </React.Fragment>
          );
        }
        return (
          <div className="agt-act__row" key={member.id}>
            <ActivityIcon member={member} />
            <span className="agt-muted">{member.name}</span>
            {member.summary !== '' && member.summary !== member.name && <span className="agt-mono">{member.summary}</span>}
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
            ? <span className="agt-icon is-running"><Spinner /></span>
            : <Glyph icon={step.state === 'COMPLETED' ? Check : Circle} className={step.state === 'COMPLETED' ? 'is-ok' : 'is-pending'} />}
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
    const allowed = block.outcome === 'ALLOWED';
    return (
      <div className="agt-appr-done" role="group" aria-label={`Approval: ${block.title}`}>
        <Glyph icon={allowed ? ShieldCheck : ShieldX} className={`${allowed ? 'is-ok' : 'is-failed'} is-outcome`} />
        <span>{block.outcome === 'ALLOWED' && block.forTask ? 'Approved for this task' : OUTCOME_LABELS[block.outcome] ?? block.outcome} —</span>
        <span className="agt-mono">{block.detail}</span>
        <span>· {clock(block.at)}</span>
      </div>
    );
  }
  return (
    <div className="agt-appr" role="group" aria-label={`Approval: ${block.title}`}>
      <div className="agt-appr__head">
        <Glyph icon={ShieldCheck} className="is-warn" />
        <span className="agt-appr__chip">Approval needed</span>
        <span className="agt-appr__title">{block.title}</span>
        <span className="agt-spacer" />
        <span className="agt-mono agt-right">{block.agent}</span>
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
              className={`agt-decision${choice.decision === 'ALLOW' ? ' is-allow' : choice.decision === 'DENY' ? ' is-deny' : ''}`}
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

function BlockView({ block, now, activityChoice, agentLabels, onToggleActivity, onDecide, onOpenAgent }: {
  block: TurnBlock;
  now: number;
  activityChoice: Record<string, boolean>;
  agentLabels: Record<string, string>;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide?: (approvalId: string, decision: AgentApprovalDecision) => void;
  onOpenAgent?: (threadId: string) => void;
}): React.JSX.Element {
  switch (block.kind) {
    case 'text':
      if (block.role === 'reasoning') {
        return <div className="agt-reasoning">{renderMarkdown(block.text)}{!block.complete && <span className="agt-cursor" aria-hidden="true" />}</div>;
      }
      if (block.role === 'planText') {
        return (
          <div className="agt-plan">
            <div className="agt-plan__head"><Glyph icon={ListChecks} /><span className="agt-label">Plan</span></div>
            <div className="agt-text">{renderMarkdown(block.text)}{!block.complete && <span className="agt-cursor" aria-hidden="true" />}</div>
          </div>
        );
      }
      return <div className="agt-text">{renderMarkdown(block.text)}{!block.complete && <span className="agt-cursor" aria-hidden="true" />}</div>;
    case 'plan':
      return (
        <div className="agt-plan">
          <div className="agt-plan__head">
            <Glyph icon={ListChecks} />
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
    case 'agents':
      return (
        <div className="agt-agents">
          {block.threadIds.map((threadId) => (
            <button
              type="button"
              className="agt-agent-chip"
              key={threadId}
              onClick={() => onOpenAgent?.(threadId)}
              title="Open this agent"
            >
              <AgentMark name={agentLabels[threadId] ?? 'Agent'} />
              {agentLabels[threadId] ?? 'Agent'}
            </button>
          ))}
          <span className="agt-agents__phase">{block.phase === 'started' ? 'started working' : 'finished'}</span>
        </div>
      );
    case 'error':
      return <div className="agt-error" role="alert"><Glyph icon={CircleAlert} className="is-failed" /><span>{block.message}{block.retryable ? ' — retrying' : ''}</span></div>;
    case 'note':
      return <div className="agt-note"><Glyph icon={TriangleAlert} className="is-warn" /><span>{block.text}</span></div>;
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
    const tier = serviceTierName(models, durable.settings.model, durable.settings.serviceTier);
    if (tier !== '') parts.push(tier);
    parts.push(permissionLabel(durable.settings.permissionPolicy));
  }
  if (turn.endedAt !== null) parts.push(clock(turn.endedAt));
  return (
    <div className="agt-usage">
      <Glyph icon={turn.outcome === 'interrupted' ? CircleX : CircleCheck} className={`${turn.outcome === 'interrupted' ? 'is-failed' : 'is-ok'} is-outcome`} />
      <span>{parts.join(' · ')}</span>
    </div>
  );
}

export function TurnView({ turn, first, open, now, models, durableTurns, activityChoice, previews, agentLabels, onToggle, onToggleActivity, onDecide, onOpenAgent }: {
  turn: TranscriptTurn;
  first: boolean;
  open: boolean;
  now: number;
  models: ModelOptionDto[];
  durableTurns: Record<string, AgentTurnDto>;
  activityChoice: Record<string, boolean>;
  previews: Record<string, AttachmentPreview>;
  agentLabels: Record<string, string>;
  onToggle: () => void;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide?: (approvalId: string, decision: AgentApprovalDecision) => void;
  onOpenAgent?: (threadId: string) => void;
}): React.JSX.Element {
  const waiting = turn.blocks.some((block) => block.kind === 'approval' && block.actionable && block.outcome === null);
  const live = turn.startedAt !== null && turn.endedAt === null && !waiting;
  const hasLog = turn.startedAt !== null || turn.blocks.length > 0;
  return (
    <section className="agt-turn" aria-label={`Turn ${turn.ordinal}`}>
      {turn.users.map((user, index) => (
        <React.Fragment key={user.id}>
          {first && index === 0 && <div className="agt-stamp">{stamp(user.at, now)}</div>}
          <div className="agt-user">
            <div className="agt-user__bubble">
              {user.attachments.length > 0 && (
                <div className="agt-user__files">
                  {user.attachments.map((attachment) => (
                    <AttachmentTile key={attachment.id} attachment={attachment} preview={previews[attachment.id]} />
                  ))}
                </div>
              )}
              <div>{user.text}</div>
              {user.delivery !== 'TURN' && (
                <div className="agt-muted agt-small agt-user__delivery">
                  <Glyph icon={user.delivery === 'STEER' ? CornerDownRight : ListPlus} />
                  <span>{user.delivery === 'STEER' ? 'Steered into the running turn' : 'Queued'}</span>
                </div>
              )}
            </div>
          </div>
        </React.Fragment>
      ))}
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
          agentLabels={agentLabels}
          onToggleActivity={onToggleActivity}
          onDecide={onDecide}
          onOpenAgent={onOpenAgent}
        />
      ))}
      {turn.answer !== null && (
        <div className="agt-answer">{renderMarkdown(turn.answer.text)}{!turn.answer.complete && <span className="agt-cursor" aria-hidden="true" />}</div>
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
        ? 'Closes this task, removes its clean checkout and keeps the result branch'
        : 'Run or remove every waiting queued message first';
    case 'recovery':
      return 'Unlocks after recovery';
    case 'closing':
      return 'Closing…';
    default:
      return 'Available when the task is idle';
  }
}

/** The header's second line: where the task is, in the fewest words that are still facts. */
function headerMeta(
  turns: TranscriptTurn[],
  status: AgentRuntimeStatus,
  waiting: boolean,
  now: number,
  state: AgentTaskState,
  models: ModelOptionDto[],
  provider: string,
): string {
  const identity = state.task === null
    ? ''
    : `${provider || laneLabel(state.task.laneId)} · ${modelName(models, state.task.defaults.model)} · `;
  const last = turns[turns.length - 1];
  if (status === 'recovery') {
    return `${identity}${last === undefined ? '' : `Turn ${last.ordinal} interrupted · `}workspace reserved`;
  }
  if (status === 'closing') return `${identity}Closing…`;
  if (status === 'running' && last !== undefined) {
    const queued = state.queue.filter((input) => input.state === 'QUEUED').length;
    const queueText = queued === 0 ? '' : ` · ${queued} queued`;
    if (waiting) return `${identity}Turn ${last.ordinal} · waiting on you${queueText}`;
    const elapsed = turnElapsedMs(last, now);
    return `${identity}Turn ${last.ordinal}${elapsed === null ? '' : ` · ${formatDuration(elapsed)}`}${queueText}`;
  }
  if (last !== undefined) {
    const ended = [...turns].reverse().find((turn) => turn.endedAt !== null);
    return `${identity}${turns.length} ${turns.length === 1 ? 'turn' : 'turns'}${ended === undefined ? '' : ` · ${clock(ended.endedAt as string)}`}`;
  }
  if (state.effective !== null) {
    return `${identity}${state.effective.effort === null ? '' : `${state.effective.effort} · `}${permissionLabel(state.effective.permissionPolicy)}`;
  }
  return identity.endsWith(' · ') ? identity.slice(0, -3) : identity;
}

const QUEUE_LABELS: Record<string, string> = {
  STARTING: 'Starting',
  PAUSED: 'Paused',
  UNCERTAIN: 'Delivery uncertain',
};

/**
 * What waits behind the current turn, as the design draws it: one row per message, tucked under
 * the composer while a turn runs, and as bordered rows once the queue has stopped. A paused row
 * offers to resume the queue; an uncertain one, which may or may not have reached the agent, is
 * only ever discarded by hand.
 */
function QueueStrip({ queue, running, idle, resumeBlocked, models, onRemove, onResume }: {
  queue: AgentQueuedInputDto[];
  running: boolean;
  idle: boolean;
  resumeBlocked: boolean;
  models: ModelOptionDto[];
  onRemove: (queuedInputId: string) => void;
  onResume: () => void;
}): React.JSX.Element | null {
  if (queue.length === 0) return null;
  const head = queue[0];
  return (
    <div className={`agt-queue${running ? ' is-attached' : ''}`} aria-label="Queued messages">
      {queue.map((input) => {
        const state = input.state;
        return (
          <div key={input.id} className={`agt-queue__row is-${state.toLowerCase()}`}>
            {state === 'QUEUED' || state === 'STARTING'
              ? <MessageSquare aria-hidden="true" className="agt-queue__icon" />
              : <CornerDownRight aria-hidden="true" className="agt-queue__icon" />}
            {state in QUEUE_LABELS && (
              <span className="agt-queue__pill" title={input.pauseReason ?? undefined}>{QUEUE_LABELS[state]}</span>
            )}
            {state === 'STARTING' && <Spinner />}
            <span className="agt-queue__text" title={input.preview}>
              {input.preview}
              {state === 'UNCERTAIN' && <span className="agt-muted"> — never re-sends by itself</span>}
            </span>
            {input.settings.attachments.length > 0 && (
              <span className="agt-mono agt-queue__meta">{input.settings.attachments.length} {input.settings.attachments.length === 1 ? 'image' : 'images'}</span>
            )}
            <span className="agt-mono agt-queue__meta">
              {modelName(models, input.settings.model)}{input.settings.effort === null ? '' : ` ${input.settings.effort}`}
              {input.settings.serviceTier != null && ` · ${serviceTierName(models, input.settings.model, input.settings.serviceTier)}`}
            </span>
            {state === 'PAUSED' && input.id === head.id && idle && (
              <button
                type="button"
                className="agt-queue__link"
                disabled={resumeBlocked}
                title={resumeBlocked ? 'Check provider readiness before resuming the queue' : undefined}
                onClick={onResume}
              >
                Resume queue
              </button>
            )}
            {state === 'UNCERTAIN' && (
              <button type="button" className="agt-queue__link is-danger" onClick={() => onRemove(input.id)}>Discard</button>
            )}
            {(state === 'QUEUED' || state === 'PAUSED') && (
              <button type="button" className="agt-queue__remove" aria-label="Remove from queue" title="Remove" onClick={() => onRemove(input.id)}>
                <Trash2 aria-hidden="true" />
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
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
  if (agent.startedAt === null) return null;
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
/** GitHub's state words, plus draft, as the pill a pull-request row wears. */
function pullRequestPill(pr: PullRequestDto): { label: string; className: string } {
  if (pr.state === 'merged') return { label: 'Merged', className: 'is-merged' };
  if (pr.state === 'closed') return { label: 'Closed', className: 'is-failed' };
  if (pr.draft) return { label: 'Draft', className: '' };
  return { label: 'Open', className: 'is-completed' };
}

/** The checks on an open pull request; absent until CI has reported anything. */
function checksPill(pr: PullRequestDto): { label: string; className: string; spinning: boolean } | null {
  switch (pr.ciStatus) {
    case 'PASSING': return { label: 'Checks passing', className: 'is-completed', spinning: false };
    case 'FAILING': return { label: 'Checks failing', className: 'is-failed', spinning: false };
    case 'PENDING': return { label: 'Checks', className: 'is-waiting', spinning: true };
    default: return null;
  }
}

function PanelTabs({ tab, showAgents, onSelect, onHide }: {
  tab: PanelTab;
  showAgents: boolean;
  onSelect: (tab: PanelTab) => void;
  onHide: () => void;
}): React.JSX.Element {
  return (
    <div className="agt-panel__tabs" role="tablist">
      {showAgents && (
        <button type="button" role="tab" className={`agt-panel__tab${tab === 'agents' ? ' is-current' : ''}`} aria-selected={tab === 'agents'} onClick={() => onSelect('agents')}>
          <span className="agt-panel__tab-mark" aria-hidden="true">❋</span>Subagents
        </button>
      )}
      <button type="button" role="tab" className={`agt-panel__tab${tab === 'prs' ? ' is-current' : ''}`} aria-selected={tab === 'prs'} onClick={() => onSelect('prs')}>
        <span className="agt-panel__tab-mark" aria-hidden="true">⑃</span>Pull requests
      </button>
      <span className="agt-spacer" />
      <button type="button" className="agt-panel__hide" aria-label="Hide the side panel" onClick={onHide}>»</button>
    </div>
  );
}

/** The design keeps the panel tabs pinned while only the selected panel body scrolls. */
function PanelFrame({ label, tabs, width, children }: {
  label: string;
  tabs: React.JSX.Element;
  width: number;
  children: React.ReactNode;
}): React.JSX.Element {
  return (
    <aside className="agt-panel" aria-label={label} style={{ width }}>
      {tabs}
      <div className="agt-panel__scroll">{children}</div>
    </aside>
  );
}

/**
 * The pull requests on this task's branch. The branch is derived from the task, so this lists
 * whatever the user pushed and opened on it; selecting one goes to the existing pull-request page.
 */
function PullRequestRows({ pullRequests, onOpen }: {
  pullRequests: PullRequestDto[];
  onOpen?: (number: number) => void;
}): React.JSX.Element {
  if (pullRequests.length === 0) {
    return <div className="agt-muted agt-panel__empty">No pull requests from this task yet</div>;
  }
  return (
    <>
      {pullRequests.map((pr) => {
        const pill = pullRequestPill(pr);
        const checks = checksPill(pr);
        return (
          <button type="button" className="agt-panel__row" key={pr.id} onClick={() => onOpen?.(pr.number)} title="Open this pull request">
            <span className="agt-pr-mark" aria-hidden="true">⑃</span>
            <span className="agt-panel__name">
              <span className="agt-panel__label agt-ellipsis">#{pr.number} {pr.title}</span>
              <span className="agt-panel__purpose">on this task&apos;s branch</span>
            </span>
            <span className={`agt-state ${pill.className}`}>{pill.label}</span>
            {checks !== null && (
              <span className={`agt-state ${checks.className}`}>
                {checks.spinning && <Spinner />}
                {checks.label}
              </span>
            )}
          </button>
        );
      })}
    </>
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
  agents, openAgent, expiredChildren, deleteAfter, transcript, now, tab, pullRequests,
  activityChoice, onOpenAgent, onCloseAgent, onToggleActivity, onDecide, onSelectTab, onHide, onOpenPullRequest,
  width, showAgents, logChoice = null, onToggleLog = () => {},
}: {
  agents: AgentNode[];
  openAgent: string | null;
  expiredChildren: AgentChildSummaryDto[];
  deleteAfter: string | null;
  transcript: TranscriptTurn[];
  now: number;
  tab: PanelTab;
  pullRequests: PullRequestDto[];
  activityChoice: Record<string, boolean>;
  onOpenAgent: (threadId: string) => void;
  onCloseAgent: () => void;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide: (approvalId: string, decision: AgentApprovalDecision) => void;
  onSelectTab: (tab: PanelTab) => void;
  onHide: () => void;
  onOpenPullRequest?: (number: number) => void;
  width: number;
  showAgents: boolean;
  /** The agent whose log the user opened, or "!thread" for one they folded; live agents open by default. */
  logChoice?: string | null;
  onToggleLog?: (choice: string) => void;
}): React.JSX.Element {
  const active = activeAgents(agents);
  const done = agents.filter((agent) => agent.status !== 'running');
  const labels = Object.fromEntries(agents.map((agent) => [agent.threadId, agent.label]));
  const tabs = <PanelTabs tab={tab} showAgents={showAgents} onSelect={onSelectTab} onHide={onHide} />;
  if (tab === 'prs') {
    return (
      <PanelFrame label="Pull requests" tabs={tabs} width={width}>
        <PullRequestRows pullRequests={pullRequests} onOpen={onOpenPullRequest} />
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
    const worked = startedAt === null ? null : formatDuration(Math.max(0, (endedAt === null ? now : Date.parse(endedAt)) - Date.parse(startedAt)));
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
                <span>{agent?.status === 'failed' ? 'Failed' : agent === undefined ? `Done${endedAt === null ? '' : ` · ${shortDate(endedAt)}`}` : 'Done'}</span>
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
            <div className="agt-panel__expired-title">Activity expired</div>
            <div className="agt-panel__expired-body">
              {deleteAfter === null
                ? 'Detailed steps were removed at the end of the retention window. The result is kept.'
                : `Detailed steps were removed on ${shortDate(deleteAfter, true)} at the end of the retention window. The result is kept.`}
            </div>
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
                  now={now}
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
      <div className="agt-label agt-panel__section">Active · {active.length}</div>
      {active.length === 0
        ? <div className="agt-muted agt-panel__empty">No active subagents</div>
        : active.map((agent) => (
          <AgentRow key={agent.threadId} agent={agent} now={now} onOpen={() => onOpenAgent(agent.threadId)} />
        ))}
      {(done.length > 0 || expiredChildren.length > 0) && (
        <div className="agt-label agt-panel__section">Done · {done.length + expiredChildren.length}</div>
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
          <span className="agt-chip">expired</span>
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
  state, models, onBack, onSend, onCancel, onComplete, onDecide, onCompose, onSelectModel,
  onStopServer, onMarkFailed, onRename, composerRef,
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
  panelTab = 'agents',
  pullRequests = [],
  logChoice = null,
  onToggleLog = () => {},
  onToggleActivity = () => {},
  onToggleTurn = () => {},
  onTogglePlan = () => {},
  onAttach = () => {},
  onRemoveAttachment = () => {},
  onTogglePanel = () => {},
  onOpenAgent = () => {},
  onCloseAgent = () => {},
  onSelectPanelTab = () => {},
  onOpenPullRequest,
  onSteer = () => {},
  onQueue = () => {},
  onRemoveQueued = () => {},
  onResumeQueue = () => {},
  onCleanup = () => {},
}: {
  state: AgentTaskState;
  models: ModelOptionDto[];
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
  onRemoveAttachment?: (attachmentId: string) => void;
  onTogglePanel?: () => void;
  onOpenAgent?: (threadId: string) => void;
  onCloseAgent?: () => void;
  /** Which side-panel tab is showing, and the pull requests found on the task's branch. */
  panelTab?: PanelTab;
  pullRequests?: PullRequestDto[];
  onSelectPanelTab?: (tab: PanelTab) => void;
  onOpenPullRequest?: (number: number) => void;
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
  const transcriptRef = useRef<HTMLDivElement>(null);
  const sidePanel = useResizablePane(300, 240, 1600, 'start');
  // Stuck to the bottom like a terminal until the user scrolls up, and stuck again once they return;
  // a task with no events yet is a fresh one, so it starts stuck.
  const follow = useRef(true);
  useEffect(() => {
    if (state.events.length === 0) follow.current = true;
    const transcript = transcriptRef.current;
    if (transcript !== null && follow.current) transcript.scrollTop = transcript.scrollHeight;
  });
  const projectedTurns = projectTranscript(state.events);
  const terminalTask = state.task !== null && state.task.state !== 'OPEN';
  const turns = projectedTurns.map((turn, index) => (
    terminalTask && index === projectedTurns.length - 1
      ? settleFinalTurn(turn, state.task?.terminalAt ?? null, state.task?.state === 'COMPLETED')
      : turn
  ));
  const supportsNestedAgents = state.capabilities?.nestedAgentActivity === true;
  const agents = supportsNestedAgents ? projectAgents(state.events) : [];
  const agentLabels = Object.fromEntries(agents.map((agent) => [agent.threadId, agent.label]));
  const working = activeAgents(agents);
  // A child the summary names but the events no longer hold: its steps were cleaned up.
  const expiredChildren = (supportsNestedAgents ? state.task?.summary?.children ?? [] : [])
    .filter((child) => !agents.some((agent) => agent.threadId === child.threadId));
  const hasAgents = agents.length > 0 || expiredChildren.length > 0;
  const visiblePanelTab: PanelTab = supportsNestedAgents ? panelTab : 'prs';
  const status = runtimeStatus(state);
  const terminal = status === 'completed' || status === 'failed';
  const waiting = turns.some((turn) => turn.blocks.some((block) => block.kind === 'approval' && block.actionable && block.outcome === null));
  const statusLabel = status === 'running' && waiting ? 'Needs approval' : STATUS_LABELS[status];
  const statusText = state.turn === null
    ? STATUS_LABELS[status]
    : `${STATUS_LABELS[status]} · turn ${state.turn.state.toLowerCase()}`;
  const plan = status === 'running' && state.capabilities?.structuredPlans === true ? currentPlan(turns) : null;
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
        <span className="agt-mono agt-meta">{headerMeta(turns, status, waiting, now, state, models, provider)}</span>
        <span className="agt-spacer" />
        <span
          className={`agt-conn-dot is-${stateClass(state.connection)}`}
          title={connectionText(state)}
          aria-label={connectionText(state)}
        />
        {!terminal && (
          <button
            type="button"
            className="agt-headbtn is-labelled"
            title={completeTitle(status, state)}
            onClick={onComplete}
            disabled={!canComplete(state)}
          >
            <CircleCheck aria-hidden="true" />
            Complete
          </button>
        )}
        {state.task !== null && status !== 'loading' && (
          <button
            type="button"
            className="agt-headbtn"
            aria-label="Show or hide the side panel"
            aria-pressed={panelOpen}
            title={supportsNestedAgents
              ? (hasAgents
                ? `Subagents (${agents.length + expiredChildren.length}) and pull requests`
                : 'Subagents and pull requests')
              : 'Pull requests'}
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

      <div className="agt-body">
      <div
        className="agt-transcript"
        role="log"
        aria-label="Task transcript"
        ref={transcriptRef}
        onScroll={(event) => {
          const { scrollHeight, scrollTop, clientHeight } = event.currentTarget;
          follow.current = scrollHeight - scrollTop - clientHeight <= 32;
        }}
      >
        {turns.length === 0 && state.connection === 'connecting' && (
          <div className="agt-skeleton">
            <div className="agt-skeleton__note">Connecting — loading task history…</div>
            <div className="agt-skeleton__bar is-1" /><div className="agt-skeleton__bar is-2" />
            <div className="agt-skeleton__bar is-3" /><div className="agt-skeleton__block" />
          </div>
        )}
        {turns.length === 0 && state.connection !== 'connecting'
          ? <div className="agt-empty">No activity yet.</div>
          : turns.map((turn, index) => {
            const id = turn.turnId ?? `turn-${turn.ordinal}`;
            // A turn shows its log while it runs and folds it when it ends; the user's choice outlasts both.
            const open = turnChoice[id] ?? turn.endedAt === null;
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
                previews={previews}
                agentLabels={agentLabels}
                onToggle={() => onToggleTurn(id, !open)}
                onToggleActivity={onToggleActivity}
                onDecide={onDecide}
                onOpenAgent={onOpenAgent}
              />
            );
          })}
        {working.length > 0 && (
          <div className="agt-barrier">
            {working.length === 1 ? '1 subagent is still working' : `${working.length} subagents are still working`}
            {' — Send, queued messages, and Complete unlock when every agent and command finishes.'}
          </div>
        )}
      </div>
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
            transcript={!supportsNestedAgents || openAgent === null ? [] : projectTranscript(state.events, openAgent)}
            now={now}
            tab={visiblePanelTab}
            pullRequests={pullRequests}
            activityChoice={activityChoice}
            onOpenAgent={onOpenAgent}
            onCloseAgent={onCloseAgent}
            onToggleActivity={onToggleActivity}
            onDecide={onDecide}
            onSelectTab={onSelectPanelTab}
            onHide={onTogglePanel}
            onOpenPullRequest={onOpenPullRequest}
            width={sidePanel.width}
            showAgents={supportsNestedAgents}
            logChoice={logChoice}
            onToggleLog={onToggleLog}
          />
        </>
      )}
      </div>

      <div className="agt-bottom">
      {/* Bounded: the status and turn state change a handful of times, streamed text never lands here. */}
      <div className="agt-status" role="status" aria-live="polite">{statusText}</div>

      {terminal && state.task !== null && <TerminalSummary task={state.task} onCleanup={onCleanup} />}

      {!terminal && plan !== null && <PlanCard steps={plan.steps} open={planOpen} onToggle={onTogglePlan} />}

      {!terminal && (
        <QueueStrip
          queue={state.queue}
          running={status === 'running'}
          idle={status === 'idle'}
          resumeBlocked={providerStartBlocked}
          models={models}
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
        <div className="agt-compose-dock is-task">
          {state.attachments.length > 0 && (
            <div className="agt-drafts" aria-label="Images to send">
              {state.attachments.map((attachment) => (
                <span className="agt-draft" key={attachment.id}>
                  <AttachmentTile attachment={attachment} preview={previews[attachment.id]} compact />
                  <span className="agt-mono agt-draft__name">{attachment.name} · {sizeText(attachment.byteSize)}</span>
                  <button
                    type="button"
                    className="agt-draft__remove"
                    aria-label={`Remove image ${attachment.name}`}
                    disabled={state.sending}
                    onClick={() => onRemoveAttachment(attachment.id)}
                  >
                    <X aria-hidden="true" />
                  </button>
                </span>
              ))}
            </div>
          )}
          <div className="agt-compose" aria-busy={state.sending}>
            <span className="agt-compose__streamer" aria-hidden="true" />
            <textarea
              ref={composerRef}
              className="agt-compose__input"
              rows={1}
              value={state.composer}
              aria-label="Message the agent"
              placeholder={state.connection === 'connecting' ? 'Connecting…' : status === 'closing' ? 'Closing…' : 'Message the agent'}
              disabled={status === 'closing' || state.connection === 'connecting' || state.sending}
              onChange={(event) => onCompose(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault();
                  if (canSend(state)) onSend();
                }
              }}
            />
            <div className="agt-compose__row">
              <button
                type="button"
                className="agt-attach"
                aria-label="Attach an image"
                title={attachTitle}
                disabled={!imagesAccepted || status === 'closing' || state.sending}
                onClick={onAttach}
              >
                +
              </button>
              <span className="agt-compose__chip is-fact" title="Auto asks before anything risky">
                {permissionLabel(state.task?.defaults.permissionPolicy ?? 'AUTO')}
              </span>
              <span className="agt-spacer" />
              <ModelPicker
                ariaLabel="Model and effort for the next message"
                disabled={models.length === 0 || state.sending}
                models={models}
                provider={provider}
                selectedModel={state.task?.defaults.model ?? ''}
                selectedEffort={state.task?.defaults.effort ?? undefined}
                selectedServiceTier={state.task?.defaults.serviceTier}
                onSelect={onSelectModel}
              />
              {status === 'running' && state.composer.trim() !== '' && (
                <>
                  <button
                    type="button"
                    className="agt-steer"
                    disabled={!canSteer(state) || steerImageBlocked}
                    title={steerImageBlocked
                      ? 'The running turn’s model does not accept images'
                      : state.capabilities?.steering === true ? 'Sends into the running turn now' : 'This provider cannot take input into a running turn'}
                    onClick={onSteer}
                  >
                    Steer
                  </button>
                  <button
                    type="button"
                    className="agt-compose__chip"
                    disabled={!canQueue(state) || queueImageBlocked}
                    title={queueImageBlocked ? 'The next-turn model does not accept images' : 'Runs after this turn finishes'}
                    onClick={onQueue}
                  >
                    Run next
                  </button>
                </>
              )}
              {status === 'running'
                ? (
                  <button
                    type="button"
                    className="agt-send is-stop"
                    aria-label="Stop this turn"
                    title="Interrupts the running turn and waits for its work to stop"
                    onClick={onCancel}
                  >
                    <span className="agt-stop-square" aria-hidden="true" />
                  </button>
                )
                : (
                  <button
                    type="button"
                    className="agt-send"
                    aria-label="Send message"
                    title="Send"
                    onClick={onSend}
                    disabled={!canSend(state)}
                  >
                    ↑
                  </button>
                )}
            </div>
          </div>
          <TaskStats task={state.task} turns={turns} now={now} />
        </div>
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
  onOpenSettings,
}: AgentTaskScreenProps): React.JSX.Element {
  const [state, dispatch] = useReducer(agentTaskReducer, undefined, initialAgentTaskState);
  const [models, setModels] = useState<ModelOptionDto[]>([]);
  const [provider, setProvider] = useState('');
  const [providerStatus, setProviderStatus] = useState<AgentProviderStatus | null>(null);
  const [providerStatusTurnId, setProviderStatusTurnId] = useState<string | null>(null);
  const [providerChecking, setProviderChecking] = useState(false);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  // Fold choices are the user's, kept here so a live update can never override them.
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const [planOpen, setPlanOpen] = useState(true);
  const [panelOpen, setPanelOpen] = useState(false);
  const [openAgent, setOpenAgent] = useState<string | null>(null);
  const [panelTab, setPanelTab] = useState<PanelTab>('agents');
  const [logChoice, setLogChoice] = useState<string | null>(null);
  const [pullRequests, setPullRequests] = useState<PullRequestDto[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const stateRef = useRef(state);
  stateRef.current = state;
  // Object URLs are browser resources: created when an image's bytes arrive, revoked when the
  // screen goes away or a draft is dropped, never left for the garbage collector to find.
  const [previews, setPreviews] = useState<Record<string, AttachmentPreview>>({});
  const previewsRef = useRef<Record<string, AttachmentPreview>>({});
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
    const session = startAgentTaskSession(live(), taskId, workspaceId, dispatch, () => active);
    session.catch((failure: unknown) => {
      if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
    return () => {
      active = false;
      void session.then((stop) => stop(), () => {});
    };
  }, [live, taskId, workspaceId]);

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
  const { lifecycleEpoch, latestTurnId } = state;
  useEffect(() => {
    if (lifecycleEpoch === 0) return;
    void refreshProjection(latestTurnId).catch((failure: unknown) => {
      if (mountedRef.current) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
  }, [refreshProjection, lifecycleEpoch, latestTurnId]);

  // The branch is derived from the task, so this is only a lookup; it is repeated while the tab is
  // showing whenever something about the task changed.
  const effectivePanelTab: PanelTab = state.capabilities?.nestedAgentActivity === true ? panelTab : 'prs';
  useEffect(() => {
    if (!panelOpen || effectivePanelTab !== 'prs') return;
    let active = true;
    void live().listAgentTaskPullRequests(taskId, workspaceId)
      .then((found) => { if (active) setPullRequests(found); })
      .catch((failure: unknown) => {
        if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
      });
    return () => { active = false; };
  }, [live, panelOpen, effectivePanelTab, taskId, workspaceId, lifecycleEpoch]);

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

  const steer = useCallback(() => run(async () => {
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

  const attach = useCallback(() => run(async () => {
    const stored = await live().pickAgentAttachments(taskId, workspaceId);
    if (stored.length === 0) return;
    const draft = stateRef.current;
    submissions.draftChanged(
      taskId,
      draft.composer,
      [...draft.attachments, ...stored].map((attachment) => attachment.id),
      draft.task?.defaults ?? null,
    );
    dispatch({ type: 'attachments/added', attachments: stored });
  }), [live, run, submissions, taskId, workspaceId]);

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
    if (preview?.status === 'ready') URL.revokeObjectURL(preview.url);
    setPreviews((current) => {
      const next = { ...current };
      delete next[attachmentId];
      return next;
    });
    await live().removeAgentAttachment(taskId, workspaceId, attachmentId);
  }), [live, run, submissions, taskId, workspaceId]);

  // Every stored image the screen shows, drafts and sent messages alike, is read once by reference.
  const wanted = [
    ...state.attachments.map((attachment) => attachment.id),
    ...state.events.flatMap((event) => (event.type === 'user_message' ? event.payload.attachments.map((attachment) => attachment.id) : [])),
  ].filter((id, index, ids) => ids.indexOf(id) === index && previews[id] === undefined).join(',');
  useEffect(() => {
    if (wanted === '') return;
    let active = true;
    const ids = wanted.split(',');
    setPreviews((current) => ({ ...current, ...Object.fromEntries(ids.map((id) => [id, { status: 'loading' as const }])) }));
    for (const id of ids) {
      void live().readAgentAttachment(taskId, workspaceId, id).then(
        (image) => {
          const url = URL.createObjectURL(new Blob([image.bytes as BlobPart], { type: image.mediaType }));
          if (!active) {
            URL.revokeObjectURL(url);
            return;
          }
          setPreviews((current) => ({ ...current, [id]: { status: 'ready', url } }));
        },
        () => {
          if (active) setPreviews((current) => ({ ...current, [id]: { status: 'unavailable' } }));
        },
      );
    }
    return () => { active = false; };
  }, [live, taskId, workspaceId, wanted]);

  useEffect(() => () => {
    for (const preview of Object.values(previewsRef.current)) {
      if (preview.status === 'ready') URL.revokeObjectURL(preview.url);
    }
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
    if (!canComplete(state) || !confirmCompletion(state.queue, (message) => window.confirm(message))) return;
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
      now={now}
      activityChoice={activityChoice}
      turnChoice={turnChoice}
      planOpen={planOpen}
      previews={previews}
      onToggleActivity={(id, open) => setActivityChoice((choices) => ({ ...choices, [id]: open }))}
      onToggleTurn={(id, open) => setTurnChoice((choices) => ({ ...choices, [id]: open }))}
      onTogglePlan={() => setPlanOpen((open) => !open)}
      onAttach={() => { void attach(); }}
      onRemoveAttachment={(attachmentId) => { void removeAttachment(attachmentId); }}
      panelOpen={panelOpen}
      openAgent={openAgent}
      panelTab={panelTab}
      pullRequests={pullRequests}
      onTogglePanel={() => setPanelOpen((open) => !open)}
      onOpenAgent={(threadId) => { setPanelOpen(true); setPanelTab('agents'); setOpenAgent(threadId); }}
      onCloseAgent={() => setOpenAgent(null)}
      onSelectPanelTab={(tab) => { setPanelTab(tab); setOpenAgent(null); }}
      logChoice={logChoice}
      onToggleLog={setLogChoice}
      onOpenPullRequest={onOpenPullRequest}
      onSteer={() => { void steer(); }}
      onQueue={() => { void queueNext(); }}
      onRemoveQueued={(queuedInputId) => { void removeQueued(queuedInputId); }}
      onResumeQueue={() => { void resumeQueue(); }}
    />
  );
}
