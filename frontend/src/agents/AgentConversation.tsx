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

import { BookOpen,Check,ChevronsDownUp,Circle,CircleAlert,CircleCheck,CircleX,CornerDownRight,FilePenLine,FolderOpen,Globe,Image,ListChecks,ListPlus,LoaderCircle,MessageSquare,Search,ShieldCheck,ShieldX,SquareTerminal,Trash2,TriangleAlert,Wrench,X,type LucideIcon } from 'lucide-react';
import React,{ useEffect,useLayoutEffect,useRef,useState } from 'react';
import { Chevron } from '../Chevron';
import '../css/agents.css';
import { displayModelName } from '../settings/model';
import type { AgentApprovalDecision,AgentAttachmentDto,AgentPendingApprovalDto,AgentQueuedInputDto,AgentStreamConnectionState,AgentTurnDto,ModelOptionDto,ToolCountersDto } from '../types';
import { AgentMark } from './AgentMark';
import type { AgentEventEnvelope } from './events';
import { captureHistoryAnchors,restoreHistoryAnchor,type HistoryScrollAnchor } from './historyScroll';
import { ImageAttachment } from './ImageAttachment';
import { renderMarkdown } from './markdown';
import { approvalDecisions } from './model';
import { ModelPicker } from './ModelPicker';
import { activityExpanded,activityPhrase,activityRunning,activitySummary,formatDuration,planChip,turnElapsedMs,turnLabel,type ActivityBlock,type ActivityMember,type ApprovalBlock,type CommandAction,type CommandMember,type PlanStep,type TranscriptTurn,type TurnBlock } from './transcript';

/** What the screen knows about one attachment's image: still loading, an object URL, or gone. */
export type AttachmentPreview = { status: 'notLoaded' } | { status: 'loading' } | { status: 'ready'; url: string; loadOriginal?: () => Promise<Blob> } | { status: 'unavailable' };

/** A stored image as a tile: the picture when its bytes arrived, otherwise its name and state. */
function AttachmentTile({ attachment, preview, compact = false }: {
  attachment: { id: string; name: string; mediaType: string };
  preview: AttachmentPreview | undefined;
  compact?: boolean;
}): React.JSX.Element {
  const state = preview?.status ?? 'notLoaded';
  if (state === 'ready' && preview?.status === 'ready') {
    return <ImageAttachment url={preview.url} loadOriginal={preview.loadOriginal} name={attachment.name} compact={compact} />;
  }
  if (compact) {
    return <span className={`agt-image-placeholder is-${state}`} role="img" aria-label={`${attachment.name}: ${state}`}>
      <Image aria-hidden="true" />
      <span>{state === 'unavailable' ? 'Unavailable' : state === 'notLoaded' ? 'Preview not loaded' : 'Loading…'}</span>
    </span>;
  }
  return (
    <span className={`agt-file is-${state}`} title={attachment.mediaType}>
      {attachment.name}{state === 'unavailable' ? ' · unavailable' : state === 'notLoaded' ? ' · preview not loaded' : ''}
    </span>
  );
}

export function stateClass(state: string): string {
  return state.toLowerCase();
}

/** The catalog's display name for a model id; the raw slug is never shown as a label. */
export function modelName(models: ModelOptionDto[], id: string | null): string {
  if (id === null || id === '') return 'No model';
  return displayModelName(models.find((model) => model.id === id)?.name ?? id);
}

export function serviceTierName(models: ModelOptionDto[], model: string | null, tier: string | null | undefined): string {
  if (tier == null) return '';
  return models.find((option) => option.id === model)?.serviceTiers?.find((option) => option.id === tier)?.name ?? 'Custom speed';
}

/** Backend policy names are enum values, not prose, and not provider wire strings. */
function permissionLabel(policy: string): string {
  return policy === 'AUTO' ? 'Auto' : policy;
}

/** "Aug 16", or "Aug 23, 2026" with the year, as the design dates a finished agent. */
export function shortDate(iso: string, withYear = false): string {
  return new Date(iso).toLocaleDateString([], withYear ? { month: 'short', day: 'numeric', year: 'numeric' } : { month: 'short', day: 'numeric' });
}

export function clock(iso: string): string {
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

export function Spinner({ warn = false }: { warn?: boolean } = {}): React.JSX.Element {
  return <LoaderCircle className={`agt-spin${warn ? ' is-warn' : ''}`} aria-hidden="true" />;
}

export function Glyph({ icon: Icon, className = '' }: { icon: LucideIcon; className?: string }): React.JSX.Element {
  return <span className={`agt-icon${className === '' ? '' : ` ${className}`}`} aria-hidden="true"><Icon /></span>;
}

function memberState(member: ActivityMember): 'running' | 'ok' | 'failed' | 'unknown' {
  if (member.kind === 'file') return 'ok';
  if (member.state === 'UNKNOWN') return 'unknown';
  if (member.state === 'STARTED' || member.state === 'PROGRESS') return 'running';
  if (member.state === 'FAILED') return 'failed';
  return member.kind === 'command' && member.exitCode !== null && member.exitCode !== 0 ? 'failed' : 'ok';
}

function currentActivityLabel(turn: TranscriptTurn | undefined): string {
  const running = turn?.blocks.flatMap((block) => block.kind === 'activity' ? block.members : [])
    .filter((member) => memberState(member) === 'running') ?? [];
  const member = running.at(-1);
  if (member?.kind === 'command') {
    const action = member.actions.find((action) => action.kind !== 'OTHER');
    if (action?.kind === 'READ') return `Reading ${action.name ?? action.path ?? 'file'}`;
    if (action?.kind === 'SEARCH') return `Searching ${action.query ?? action.name ?? 'files'}`;
    if (action?.kind === 'LIST_FILES') return `Listing ${action.name ?? action.path ?? 'files'}`;
    return member.command === '' ? 'Running command' : `Running ${member.command}`;
  }
  if (member?.kind === 'tool') return member.name;
  if (turn?.answer && !turn.answer.complete) return 'Writing response';
  return 'Thinking';
}

function LiveActivity({ label, animated = true }: { label: string; animated?: boolean }): React.JSX.Element {
  return <div className="agt-live" role="status" aria-live="polite">
    <span className={`agt-live__label${animated ? ' is-active' : ''}`} title={label}>{label}</span>
  </div>;
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
  'Automatic approval': ShieldCheck,
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

export function useOutputWheel(containerRef: React.RefObject<HTMLElement | null>, scrollIntent?: React.RefObject<number>): void {
  useEffect(() => {
    const container = containerRef.current;
    if (container === null) return;
    // Wheel gestures navigate messages; an output scrollbar remains an explicit choice.
    const scrollOutput = (event: WheelEvent): void => {
      if (!(event.target instanceof Element) || event.target.closest('.agt-pre') === null || event.deltaY === 0) return;
      event.preventDefault();
      if (scrollIntent !== undefined) scrollIntent.current = Math.sign(event.deltaY);
      const scale = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? container.clientHeight : 1;
      container.scrollTop += event.deltaY * scale;
    };
    container.addEventListener('wheel', scrollOutput, { passive: false });
    return () => container.removeEventListener('wheel', scrollOutput);
  }, [containerRef, scrollIntent]);
}

/** A command has one result, even when the provider describes several actions within it. */
function CommandRows({ member, live }: { member: CommandMember; live: boolean }): React.JSX.Element {
  const running = memberState(member) === 'running';
  if (member.command === '' && !member.output) {
    return <div className="agt-act__row"><CommandCells member={member} /></div>;
  }
  return (
    <details className="agt-out" open={live && running ? true : undefined}>
      <summary className="agt-act__row is-command">
        <CommandCells member={member} chevron />
      </summary>
      <pre className="agt-pre">
        <span className="agt-out__label">Shell{member.cwd !== null && member.cwd !== '.' ? ` · ${member.cwd}` : ''}</span>
        {member.command !== '' && `$ ${member.command}\n\n`}
        {member.output}
        {member.truncated && <span className="agt-muted"> … truncated</span>}
        {running && <span className="agt-cursor" aria-hidden="true" />}
      </pre>
    </details>
  );
}

/** Summary titles use provider facts; the exact shell command stays in the disclosure. */
function CommandCells({ member, chevron = false }: {
  member: CommandMember;
  chevron?: boolean;
}): React.JSX.Element {
  const parsed = member.actions.filter((action) => action.kind !== 'OTHER');
  return (
    <>
      {parsed.map((action, index) => (
        <React.Fragment key={index}>
          <ActivityIcon member={member} action={action.kind} />
          <span className="agt-act__label">
            {ACTION_VERBS[action.kind]}{' '}
            {action.kind === 'SEARCH'
              ? `${action.query ?? action.name ?? 'files'}${action.path === null ? '' : ` in ${action.path}`}`
              : action.name ?? action.path ?? (action.kind === 'READ' ? 'file' : 'files')}
          </span>
        </React.Fragment>
      ))}
      {(parsed.length === 0 || member.actions.some((action) => action.kind === 'OTHER')) && (
        <><ActivityIcon member={member} /><span className="agt-act__label">Ran command</span></>
      )}
      {chevron && <Chevron size={11} />}
      {member.exitCode !== null && (
        <span className={`agt-exit ${member.exitCode === 0 ? 'is-ok' : 'is-failed'}`}>exit {member.exitCode}</span>
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
function ActivityView({ block, expanded, onToggle }: {
  block: ActivityBlock;
  expanded: boolean;
  onToggle: () => void;
}): React.JSX.Element {
  if (activityRunning(block)) {
    return (
      <div className="agt-act is-running">
        <div className="agt-act__head">
          <Spinner />
          <span className="agt-act__title">Working</span>
          <span className="agt-muted">— {activityPhrase(block.members.filter((member) => memberState(member) === 'running'))}</span>
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
      </button>
      {expanded && <div className="agt-act__body"><MemberRows members={block.members} live={false} /></div>}
    </div>
  );
}

export function PlanSteps({ steps, compact = false }: { steps: PlanStep[]; compact?: boolean }): React.JSX.Element {
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

function pendingApprovalBlock(approval: AgentPendingApprovalDto): ApprovalBlock {
  return {
    kind: 'approval', id: approval.approvalId, approvalId: approval.approvalId,
    title: approval.title, detail: approval.detail, approvalKind: approval.approvalKind,
    scope: approval.scope, agent: approval.source.logicalAgentPath ?? 'agent',
    decisions: approval.availableDecisions, actionable: approval.actionable,
    outcome: null, forTask: false, at: approval.occurredAt,
  };
}

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

export function BlockView({ block, activityChoice, agentLabels, onToggleActivity, onDecide, onOpenAgent }: {
  block: TurnBlock;
  activityChoice: Record<string, boolean>;
  agentLabels: Record<string, string>;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide?: (approvalId: string, decision: AgentApprovalDecision) => void;
  onOpenAgent?: (threadId: string) => void;
}): React.JSX.Element {
  switch (block.kind) {
    case 'text':
      if (block.role === 'reasoning') {
        return <div className="agt-reasoning">{renderMarkdown(block.text)}{!block.complete && !block.historical && <span className="agt-cursor" aria-hidden="true" />}</div>;
      }
      if (block.role === 'planText') {
        return (
          <div className="agt-plan">
            <div className="agt-plan__head"><Glyph icon={ListChecks} /><span className="agt-label">Plan</span></div>
            <div className="agt-text">{renderMarkdown(block.text)}{!block.complete && !block.historical && <span className="agt-cursor" aria-hidden="true" />}</div>
          </div>
        );
      }
      return <div className="agt-text">{renderMarkdown(block.text)}{!block.complete && !block.historical && <span className="agt-cursor" aria-hidden="true" />}</div>;
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
      return <ActivityView block={block} expanded={expanded} onToggle={() => onToggleActivity(block.id, !expanded)} />;
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

/** The graph part of a stats line: calls with their seconds, or why there are none. */
export function codeGraphText(counters: ToolCountersDto, on: boolean): string {
  if (counters.codegraphCalls > 0) {
    const failed = counters.codegraphFailures > 0 ? `, ${counters.codegraphFailures} failed` : '';
    return `CodeGraph ${counters.codegraphCalls} ${counters.codegraphCalls === 1 ? 'call' : 'calls'} · ${(counters.codegraphMillis / 1000).toFixed(1)}s${failed}`;
  }
  return on ? 'CodeGraph unused' : 'CodeGraph off';
}

/** The line under an answer: what the turn ran with and what it used, from the durable turn and its usage event. */
function TurnFooter({ turn, durable, models }: {
  turn: TranscriptTurn;
  durable: AgentTurnDto | undefined;
  models: ModelOptionDto[];
}): React.JSX.Element {
  const parts = [turn.ordinal > 0 ? `Turn ${turn.ordinal}` : 'Turn'];
  const elapsed = turnElapsedMs(turn, 0);
  if (elapsed !== null) parts.push(formatDuration(elapsed));
  if (durable?.settings) {
    parts.push(`${modelName(models, durable.settings.model)}${durable.settings.effort === null ? '' : ` ${durable.settings.effort}`}`);
    const tier = serviceTierName(models, durable.settings.model, durable.settings.serviceTier);
    if (tier !== '') parts.push(tier);
    parts.push(permissionLabel(durable.settings.permissionPolicy));
  }
  const counters = durable?.metrics.counters;
  if (counters) {
    parts.push(`Tools ${counters.toolCalls} · Explored ${counters.exploreActions}${counters.codegraphCalls > 0 ? ` · ${codeGraphText(counters, true)}` : ''}`);
  }
  if (turn.endedAt !== null) parts.push(clock(turn.endedAt));
  return (
    <div className="agt-usage">
      <Glyph icon={turn.outcome === 'interrupted' ? CircleX : CircleCheck} className={`${turn.outcome === 'interrupted' ? 'is-failed' : 'is-ok'} is-outcome`} />
      <span>{parts.join(' · ')}</span>
    </div>
  );
}

export function TurnView({ turn, first, open, now, models, durableTurns, activityChoice, previews, agentLabels, onToggle, onToggleActivity, onDecide, onOpenAgent, generatedRequest = false }: {
  generatedRequest?: boolean;
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
    <section className="agt-turn" data-history-anchor={turn.turnId ?? `turn-${turn.ordinal}`} aria-label={turn.ordinal > 0 ? `Turn ${turn.ordinal}` : 'Turn'}>
      {turn.users.map((user, index) => (
        <React.Fragment key={user.id}>
          {first && index === 0 && <div className="agt-stamp">{stamp(user.at, now)}</div>}
          <div className="agt-user" data-history-anchor={user.id}>
            <div className="agt-user__bubble">
              {generatedRequest && index === 0 && user.delivery === 'TURN' && (
                <div className="agt-user__attribution">ByteQuay · Review request</div>
              )}
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
        <div key={block.id} data-history-anchor={'anchorId' in block ? block.anchorId : block.id}><BlockView
          block={block}
          activityChoice={activityChoice}
          agentLabels={agentLabels}
          onToggleActivity={onToggleActivity}
          onDecide={onDecide}
          onOpenAgent={onOpenAgent}
        /></div>
      ))}
      {turn.answer !== null && (
        <div className="agt-answer" data-history-anchor={turn.answer.anchorId ?? turn.answer.id}>{renderMarkdown(turn.answer.text)}{!turn.answer.complete && !turn.answer.historical && <span className="agt-cursor" aria-hidden="true" />}</div>
      )}
      {turn.endedAt !== null && <TurnFooter turn={turn} durable={turn.turnId === null ? undefined : durableTurns[turn.turnId]} models={models} />}
    </section>
  );
}

export type ConversationQueuedInput = {
  id: string;
  preview: string;
  state: AgentQueuedInputDto['state'] | null;
  pauseReason?: string | null;
  attachmentCount?: number;
  modelLabel?: string;
};

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
export function ConversationQueue({ queue, running, idle, resumeBlocked, onRemove, onResume }: {
  queue: ConversationQueuedInput[];
  running: boolean;
  idle: boolean;
  resumeBlocked: boolean;
  onRemove: (queuedInputId: string) => void;
  onResume?: () => void;
}): React.JSX.Element | null {
  if (queue.length === 0) return null;
  const head = queue[0];
  return (
    <div className={`agt-queue${running ? ' is-attached' : ''}`} aria-label="Queued messages">
      {queue.map((input) => {
        const state = input.state ?? 'UNKNOWN';
        return (
          <div key={input.id} className={`agt-queue__row is-${state.toLowerCase()}`}>
            {state === 'QUEUED' || state === 'STARTING'
              ? <MessageSquare aria-hidden="true" className="agt-queue__icon" />
              : <CornerDownRight aria-hidden="true" className="agt-queue__icon" />}
            {(state in QUEUE_LABELS || state === 'UNKNOWN') && (
              <span className="agt-queue__pill" title={input.pauseReason ?? undefined}>{QUEUE_LABELS[state] ?? 'Waiting'}</span>
            )}
            {state === 'STARTING' && <Spinner />}
            <span className="agt-queue__text" title={input.preview}>
              {input.preview}
              {state === 'UNCERTAIN' && <span className="agt-muted"> — never re-sends by itself</span>}
            </span>
            {(input.attachmentCount ?? 0) > 0 && (
              <span className="agt-mono agt-queue__meta">{(input.attachmentCount ?? 0)} {(input.attachmentCount ?? 0) === 1 ? 'image' : 'images'}</span>
            )}
            <span className="agt-mono agt-queue__meta">
              {input.modelLabel}
            </span>
            {state === 'PAUSED' && input.id === head.id && idle && onResume !== undefined && (
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
            {(state === 'QUEUED' || state === 'PAUSED' || state === 'UNKNOWN') && (
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

export type ConversationHistory = {
  before: string | null;
  after: string | null;
  loading: boolean;
  onLoad: (direction: 'older' | 'newer' | 'latest') => void;
  onFollow: (following: boolean) => void;
};

/** Shared user-intent scrolling; streamed output and folding must not move someone reading history. */
export function useConversationHistory(events: AgentEventEnvelope[], endedAt: string | null | undefined, history: ConversationHistory) {
  const transcriptRef = useRef<HTMLDivElement>(null);
  const follow = useRef(true);
  const [following, setFollowing] = useState(true);
  const scrollIntent = useRef(0);
  const previousScrollTop = useRef(0);
  useOutputWheel(transcriptRef, scrollIntent);
  const pendingHistory = useRef<{ events: AgentEventEnvelope[]; anchors: HistoryScrollAnchor[]; direction: 'older' | 'newer' } | null>(null);
  const followingChange = useRef<(next: boolean) => void>(() => {});
  const { before, after, loading, onLoad, onFollow } = history;
  const changeFollowing = (next: boolean): void => {
    follow.current = next;
    setFollowing(next);
    onFollow(next);
  };
  followingChange.current = changeFollowing;
  useEffect(() => {
    // Reopening a conversation must resume its parent session as well as its local scroll state.
    followingChange.current(true);
  }, []);
  const loadHistory = (direction: 'older' | 'newer' | 'latest'): void => {
    if (loading || (direction === 'older' && before === null) || (direction === 'newer' && after === null)) return;
    scrollIntent.current = 0;
    const container = transcriptRef.current;
    changeFollowing(direction === 'latest');
    pendingHistory.current = container !== null && direction !== 'latest'
      ? { events, anchors: captureHistoryAnchors(container), direction } : null;
    onLoad(direction);
  };
  useLayoutEffect(() => {
    const container = transcriptRef.current;
    if (container === null) return;
    const pending = pendingHistory.current;
    if (pending !== null && pending.events !== events) {
      restoreHistoryAnchor(container, pending.anchors);
      pendingHistory.current = null;
      if (pending.direction === 'newer' && after === null && container.scrollHeight - container.scrollTop - container.clientHeight <= 1) followingChange.current(true);
    }
    else if (follow.current) container.scrollTop = container.scrollHeight;
    else if (pending === null && after === null && container.scrollHeight <= container.clientHeight) followingChange.current(true);
  }, [events, after, endedAt]);
  useEffect(() => {
    // An immediate failure can batch loading=true/false into one render.
    if (!loading) pendingHistory.current = null;
  });
  useLayoutEffect(() => {
    const container = transcriptRef.current;
    if (following && container !== null) container.scrollTop = container.scrollHeight;
  }, [following]);
  const scrollHistory = (container: HTMLDivElement, direction: number): void => {
    if (direction === 0) return;
    const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight <= 1;
    if (direction < 0) {
      if (container.scrollTop <= 0 && scrollIntent.current === -1) scrollIntent.current = 0;
      if (!atBottom || container.scrollHeight > container.clientHeight || before !== null) changeFollowing(false);
      if (container.scrollTop < 400 && before !== null) loadHistory('older');
    }
    else if (atBottom) {
      if (scrollIntent.current === 1) scrollIntent.current = 0;
      if (after !== null) loadHistory('newer');
      else if (pendingHistory.current === null) changeFollowing(true);
    }
    else changeFollowing(false);
  };
  const transcriptProps = {
    ref: transcriptRef,
    onWheel: (event: React.WheelEvent<HTMLDivElement>) => {
      scrollIntent.current = Math.sign(event.deltaY);
      scrollHistory(event.currentTarget, scrollIntent.current);
    },
    onPointerDown: () => { scrollIntent.current = 2; },
    onPointerUp: () => { scrollIntent.current = 0; },
    onKeyDown: (event: React.KeyboardEvent<HTMLDivElement>) => {
      if (event.target !== event.currentTarget) return;
      const direction = ['ArrowUp', 'PageUp', 'Home'].includes(event.key) ? -1
        : ['ArrowDown', 'PageDown', 'End', ' '].includes(event.key) ? 1 : 0;
      scrollIntent.current = direction;
      scrollHistory(event.currentTarget, direction);
    },
    onScroll: (event: React.UIEvent<HTMLDivElement>) => {
      const { scrollTop } = event.currentTarget;
      const intent = scrollIntent.current === 2 ? Math.sign(scrollTop - previousScrollTop.current) : scrollIntent.current;
      previousScrollTop.current = scrollTop;
      if (scrollIntent.current !== 2) scrollIntent.current = 0;
      scrollHistory(event.currentTarget, intent);
      if (pendingHistory.current !== null) pendingHistory.current.anchors = captureHistoryAnchors(event.currentTarget);
    },
  };
  return { following, changeFollowing, loadHistory, transcriptProps, before, after, loading };
}

export function AgentConversation({
  turns, durableTurns, models, now, scroll, connection, running, activeTurn, activeTurnId = activeTurn?.id, waiting, gap = false,
  activityChoice, turnChoice, onToggleTurn, onToggleActivity, onDecide,
  previews = {}, agentLabels = {}, pendingApprovals = [], generatedTurnIds,
  onOpenAgent, children, transcriptFooter, label = 'Task transcript',
}: {
  turns: TranscriptTurn[];
  durableTurns: Record<string, AgentTurnDto>;
  models: ModelOptionDto[];
  now: number;
  scroll: ReturnType<typeof useConversationHistory>;
  connection: AgentStreamConnectionState;
  running: boolean;
  activeTurn: { id: string; state: string; cancellationRequestedAt: string | null } | null;
  activeTurnId?: string | null;
  waiting: boolean;
  gap?: boolean;
  activityChoice: Record<string, boolean>;
  turnChoice: Record<string, boolean>;
  onToggleTurn: (id: string, open: boolean) => void;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide: (approvalId: string, decision: AgentApprovalDecision) => void;
  previews?: Record<string, AttachmentPreview>;
  agentLabels?: Record<string, string>;
  pendingApprovals?: AgentPendingApprovalDto[];
  generatedTurnIds?: ReadonlySet<string>;
  onOpenAgent?: (threadId: string) => void;
  children: React.ReactNode;
  transcriptFooter?: React.ReactNode;
  label?: string;
}): React.JSX.Element {
  const liveTurn = turns.find(turn => turn.turnId === activeTurnId);
  const liveLabel = connection !== 'open' || gap ? 'Reconnecting…'
    : waiting ? 'Waiting for approval'
      : activeTurn?.cancellationRequestedAt != null ? 'Stopping…'
        : activeTurn?.state === 'ACCEPTED' ? 'Starting…'
          : liveTurn?.endedAt != null ? 'Finishing…' : currentActivityLabel(liveTurn);
  return (
    <div className="agt-conversation">
      {scroll.loading && <div className="agt-history-edge" role="status">Loading activity…</div>}
      <div className="agt-transcript" role="log" aria-label={label} tabIndex={0} {...scroll.transcriptProps}>
        {pendingApprovals.filter(approval => !turns.some(turn => turn.blocks.some(block => block.kind === 'approval' && block.approvalId === approval.approvalId))).map(approval => (
          <ApprovalView key={approval.approvalId} block={pendingApprovalBlock(approval)} onDecide={onDecide} />
        ))}
        {turns.length === 0 && connection === 'connecting' && (
          <div className="agt-skeleton">
            <div className="agt-skeleton__note">Connecting — loading task history…</div>
            <div className="agt-skeleton__bar is-1" /><div className="agt-skeleton__bar is-2" />
            <div className="agt-skeleton__bar is-3" /><div className="agt-skeleton__block" />
          </div>
        )}
        {turns.length === 0 && connection !== 'connecting'
          ? <div className="agt-empty">No activity yet.</div>
          : turns.map((turn, index) => {
            const id = turn.turnId ?? `turn-${turn.ordinal}`;
            const open = turnChoice[id] ?? (turn.endedAt === null || turn.answer === null);
            return <TurnView key={id} turn={turn} first={index === 0} open={open} now={now}
              models={models} durableTurns={durableTurns} activityChoice={activityChoice}
              previews={previews} agentLabels={agentLabels} onToggle={() => onToggleTurn(id, !open)}
              onToggleActivity={onToggleActivity} onDecide={onDecide} onOpenAgent={onOpenAgent}
              generatedRequest={turn.turnId !== null && generatedTurnIds?.has(turn.turnId)} />;
          })}
        {running && scroll.after === null && scroll.following && <LiveActivity label={liveLabel} animated={!waiting && connection === 'open' && !gap} />}
        {transcriptFooter}
      </div>
      <div className="agt-bottom">{children}</div>
    </div>
  );
}

/** The whole task composer, including its controls, is shared by every conversation. */
export function ConversationComposer({
  value, onChange, onSend, onStop, onSteer, onQueue, running, sending, disabled,
  sendEnabled, steerEnabled, queueEnabled, models, model, effort, serviceTier, provider = '',
  permissionPolicy = 'AUTO', onSelectModel, onAttach, attachEnabled = false,
  attachTitle = 'Image attachments are unavailable', attachments = [], previews = {}, onRemoveAttachment, footer, automation,
  composerRef, onPaste, connection, closing = false,
  steerTitle = 'Sends into the running turn now', queueTitle = 'Runs after this turn finishes',
}: {
  value: string;
  onChange: (text: string) => void;
  onSend: () => void;
  onStop: () => void;
  onSteer: () => void;
  onQueue: () => void;
  running: boolean;
  sending: boolean;
  disabled: boolean;
  sendEnabled: boolean;
  steerEnabled: boolean;
  queueEnabled: boolean;
  models: ModelOptionDto[];
  model: string | null;
  effort?: string | null;
  serviceTier?: string | null;
  provider?: string;
  permissionPolicy?: string;
  onSelectModel?: (model: string, effort: string | null, serviceTier: string | null) => void;
  onAttach?: () => void;
  attachEnabled?: boolean;
  attachTitle?: string;
  attachments?: AgentAttachmentDto[];
  previews?: Record<string, AttachmentPreview>;
  onRemoveAttachment?: (attachmentId: string) => void;
  footer?: React.ReactNode;
  automation?: React.ReactNode;
  composerRef?: React.Ref<HTMLTextAreaElement>;
  onPaste?: React.ClipboardEventHandler<HTMLTextAreaElement>;
  connection: AgentStreamConnectionState;
  closing?: boolean;
  steerTitle?: string;
  queueTitle?: string;
}): React.JSX.Element {
  return (
    <div className="agt-compose-dock is-task">
      <div className="agt-compose" aria-busy={sending}>
        <span className="agt-compose__streamer" aria-hidden="true" />
        {attachments.length > 0 && (
          <div className="agt-drafts" aria-label="Images to send">
            {attachments.map(attachment => (
              <span className="agt-draft" key={attachment.id}>
                <AttachmentTile attachment={attachment} preview={previews[attachment.id]} compact />
                <button type="button" className="agt-draft__remove" aria-label={`Remove image ${attachment.name}`}
                  disabled={sending || onRemoveAttachment === undefined} onClick={() => onRemoveAttachment?.(attachment.id)}>
                  <X aria-hidden="true" />
                </button>
              </span>
            ))}
          </div>
        )}
        <textarea ref={composerRef} className="agt-compose__input" rows={1} value={value}
          aria-label="Message the agent" placeholder={connection === 'connecting' ? 'Connecting…' : closing ? 'Closing…' : 'Message the agent'} disabled={disabled}
          onChange={event => onChange(event.target.value)} onPaste={onPaste}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault();
              if (sendEnabled) onSend();
            }
          }} />
        <div className="agt-compose__row">
          <button type="button" className="agt-attach" aria-label="Attach an image" title={attachTitle}
            disabled={!attachEnabled || onAttach === undefined || closing || sending} onClick={onAttach}>+</button>
          <span className="agt-compose__chip is-fact" title="Routine work proceeds automatically; other actions may need your authorization">
            {permissionLabel(permissionPolicy)}
          </span>
          {automation}
          <span className="agt-spacer" />
          <ModelPicker
            ariaLabel="Model and effort for the next message"
            disabled={models.length === 0 || sending || onSelectModel === undefined}
            models={models} provider={provider} selectedModel={model ?? ''}
            selectedEffort={effort ?? undefined} selectedServiceTier={serviceTier}
            onSelect={onSelectModel ?? (() => {})} />
          {running && value.trim() !== '' && <>
            <button type="button" className="agt-steer" disabled={!steerEnabled} title={steerTitle} onClick={onSteer}>Steer</button>
            <button type="button" className="agt-compose__chip" disabled={!queueEnabled} title={queueTitle} onClick={onQueue}>Run next</button>
          </>}
          {running
            ? <button type="button" className="agt-send is-stop" aria-label="Stop this turn"
                title="Interrupts the running turn and waits for its work to stop" onClick={onStop}>
                <span className="agt-stop-square" aria-hidden="true" />
              </button>
            : <button type="button" className="agt-send" aria-label="Send message" title="Send" onClick={onSend} disabled={!sendEnabled}>↑</button>}
        </div>
      </div>
      {footer}
    </div>
  );
}
