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

import type { JiraTaskSource } from '../jira/contracts';
import { BackportRequestHint } from './BackportRequestHint';
import { JiraRequestCard } from '../jira/JiraSourceAttachment';
import { BookMarked,BookOpen,Check,ChevronsDownUp,Circle,CircleAlert,CircleCheck,CircleX,CornerDownRight,FilePenLine,FolderOpen,GitPullRequest,Globe,Image,ListChecks,ListPlus,LoaderCircle,MessageSquare,Search,ShieldCheck,ShieldX,SquareTerminal,Trash2,TriangleAlert,Wrench,X,type LucideIcon } from 'lucide-react';
import hljs from 'highlight.js/lib/common';
import React,{ useCallback,useEffect,useId,useLayoutEffect,useMemo,useRef,useState } from 'react';
import { Chevron } from '../Chevron';
import '../css/agents.css';
import { displayModelName } from '../settings/model';
import LocalReviewThreadCard from '../pulls/local-review/LocalReviewThreadCard';
import type { LocalReviewThreads } from '../pulls/local-review/types';
import { findingFixRequest } from '../pulls/local-review/conversation';
import { linkIssueNumbers } from '../pulls/MarkdownBody';
import { remoteErrorMessage } from '../pulls/PullOverview';
import { MEMORY_CHANGED } from '../memory/model';
import type { AgentIssueSourceDto,AgentApprovalDecision,AgentAttachmentDto,AgentPendingApprovalDto,AgentQueuedInputDto,AgentStreamConnectionState,AgentTurnDto,ModelOptionDto } from '../types';
import { AgentMark } from './AgentMark';
import { developmentTurnRequest,draftPullRequestRequest } from './DevelopmentFacts';
import type { AgentEventEnvelope } from './events';
import { captureHistoryAnchors,restoreHistoryAnchor,type HistoryScrollAnchor } from './historyScroll';
import { ImageAttachment } from './ImageAttachment';
import { renderMarkdown } from './markdown';
import { DiscussableAnswer, type SelectedPassage } from './discussions/DiscussableAnswer';
import type { TaskDiscussionDto } from './discussions/contracts';
import { approvalDecisions, approvalQuestions, questionAnswers, type ApprovalQuestion, type PendingMessage } from './model';
import { ModelPicker } from './ModelPicker';
import { IssueRequestCard } from './IssueAttachment';
import { ReviewRequestCard, type ReviewRequest, type ReviewRequestTarget } from './ReviewRequestCard';
import { agentDisplayName,activitySummary,formatDuration,planChip,rememberedMemory,turnElapsedMs,turnLabel,type ActivityBlock,type ActivityMember,type ApprovalBlock,type CommandAction,type CommandMember,type PlanStep,type ToolMember,type TranscriptTurn,type TurnBlock } from './transcript';

/** Answers accompany a question tool's allow; every other approval sends the decision alone. */
export type ApprovalDecider = (approvalId: string, decision: AgentApprovalDecision, answers?: Record<string, string>) => void;

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
  return policy === 'AUTO' ? 'Approve for me' : policy;
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
    return member.description ?? (member.command === '' ? 'Running command' : `Running ${member.command}`);
  }
  if (member?.kind === 'tool') return member.description ?? toolLabel(member.name);
  if (turn?.answer && !turn.answer.complete) return 'Writing response';
  return 'Thinking';
}

/** Providers namespace their MCP tools as `mcp__<server>__<tool>`; a row shows the tool. */
function toolLabel(name: string): string {
  return name.replace(/^mcp__.+?__/, '');
}

/** A tool payload is usually JSON; folded output reads it as a document rather than one line. */
function toolDetail(text: string): string {
  const trimmed = text.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return text;
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2);
  }
  catch {
    return text;
  }
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
  WebSearch: Globe,
  WebFetch: Globe,
  'Image view': Image,
  'Context compaction': ChevronsDownUp,
  'Automatic approval': ShieldCheck,
};

/** Activity type is the primary icon; a live/failing state temporarily overrides it. */
export function ActivityIcon({ member, action }: { member: ActivityMember; action?: CommandAction['kind'] }): React.JSX.Element {
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
function CommandRows({ member }: { member: CommandMember }): React.JSX.Element {
  const running = memberState(member) === 'running';
  // hljs escapes the source it highlights, so its markup carries no shell text as HTML.
  const shell = useMemo(
    () => member.command === '' ? null : hljs.highlight(member.command, { language: 'bash', ignoreIllegals: true }).value,
    [member.command]);
  if (shell === null && !member.output) {
    return <div className="agt-act__row"><CommandCells member={member} /></div>;
  }
  return (
    <details className="agt-out">
      <summary className="agt-act__row is-command">
        <CommandCells member={member} chevron />
      </summary>
      <pre className="agt-pre is-shell">
        <span className="agt-out__label">Shell{member.cwd !== null && member.cwd !== '.' ? ` · ${member.cwd}` : ''}</span>
        {shell !== null && <span className="agt-out__cmd">$ <code className="hljs" dangerouslySetInnerHTML={{ __html: shell }} /></span>}
        <span className="agt-out__stream">
          {member.output}
          {member.truncated && <span className="agt-muted"> … truncated</span>}
          {running && <span className="agt-cursor" aria-hidden="true" />}
        </span>
      </pre>
    </details>
  );
}

/** One parsed fragment as its row reads it: "Read src/auth.ts", "Searched token in src". */
function actionLabel(action: CommandAction): string {
  const target = action.kind === 'SEARCH'
    ? `${action.query ?? action.name ?? 'files'}${action.path === null ? '' : ` in ${action.path}`}`
    : action.name ?? action.path ?? (action.kind === 'READ' ? 'file' : 'files');
  return `${ACTION_VERBS[action.kind]} ${target}`;
}

/** What a command's row calls itself: the provider's own label, else what it parsed the shell as. */
function commandLabel(member: CommandMember): string {
  const parsed = member.actions.find((action) => action.kind !== 'OTHER');
  return member.description ?? (parsed === undefined ? 'Ran command' : actionLabel(parsed));
}

/** Summary titles use provider facts; the exact shell command stays in the disclosure. */
function CommandCells({ member, chevron = false }: {
  member: CommandMember;
  chevron?: boolean;
}): React.JSX.Element {
  const parsed = member.description === null ? member.actions.filter((action) => action.kind !== 'OTHER') : [];
  return (
    <>
      {parsed.map((action, index) => (
        <React.Fragment key={index}>
          <ActivityIcon member={member} action={action.kind} />
          <span className="agt-act__label">{actionLabel(action)}</span>
        </React.Fragment>
      ))}
      {(parsed.length === 0 || member.actions.some((action) => action.kind === 'OTHER')) && (
        <><ActivityIcon member={member} /><span className="agt-act__label">{member.description ?? 'Ran command'}</span></>
      )}
      {chevron && <Chevron size={11} />}
      {member.exitCode !== null && (
        <span className={`agt-exit ${member.exitCode === 0 ? 'is-ok' : 'is-failed'}`}>exit {member.exitCode}</span>
      )}
    </>
  );
}

const CHANGE_VERBS: Record<string, string> = { ADDED: 'Added', MODIFIED: 'Edited', DELETED: 'Deleted', RENAMED: 'Renamed' };

/** Undo for what an agent remembered, bound to the transcript's workspace; null where the transcript has none. */
const MemoryUndo = React.createContext<((memoryId: string) => Promise<void>) | null>(null);

/** What an agent remembered, with Undo: a memory it saved goes, and one it updated gets its previous version back. */
function RememberedRow({ memory }: { memory: { updated: boolean; id: string; text: string } }): React.JSX.Element {
  const undo = React.useContext(MemoryUndo);
  const [state, setState] = useState<'idle' | 'busy' | 'undone'>('idle');
  const [error, setError] = useState<string | null>(null);
  const revert = (): void => {
    if (undo === null) return;
    setState('busy');
    setError(null);
    undo(memory.id).then(() => setState('undone'), (cause: unknown) => { setState('idle'); setError(remoteErrorMessage(cause)); });
  };
  return (
    <div className="agt-act__row agt-remembered">
      <BookMarked size={13} aria-hidden="true" className="agt-remembered__icon" />
      <span className="agt-remembered__text" title={memory.id}>{memory.updated ? 'Updated' : 'Remembered'}: {memory.text}</span>
      {undo !== null && (state === 'undone' ? <span className="agt-muted">Undone</span>
        : <button type="button" className="agt-link" disabled={state === 'busy'} onClick={revert}>Undo</button>)}
      {error !== null && <span className="agt-remembered__error" role="alert">{error}</span>}
    </div>
  );
}

/** A one-line result stays on the row; anything longer folds like a command's output. */
function ToolRows({ member }: { member: ToolMember }): React.JSX.Element {
  const remembered = rememberedMemory(member);
  if (remembered !== null) return <RememberedRow memory={remembered} />;
  // A ByteQuay tool says what the call read or saved; the tool's name stays on hover.
  const label = member.description ?? toolLabel(member.name);
  const title = member.description === null ? undefined : toolLabel(member.name);
  const summary = member.summary === member.name || member.summary === toolLabel(member.name) ? '' : member.summary;
  if (summary.length <= 80 && !summary.includes('\n')) {
    return (
      <div className="agt-act__row">
        <ActivityIcon member={member} />
        <span className="agt-muted" title={title}>{label}</span>
        {summary !== '' && <span className="agt-mono">{summary}</span>}
      </div>
    );
  }
  return (
    <details className="agt-out">
      <summary className="agt-act__row">
        <ActivityIcon member={member} />
        <span className="agt-muted" title={title}>{label}</span>
        <Chevron size={11} />
      </summary>
      <pre className="agt-pre">{toolDetail(summary)}</pre>
    </details>
  );
}

function MemberRows({ members }: { members: ActivityMember[] }): React.JSX.Element {
  return (
    <>
      {members.map((member) => {
        if (member.kind === 'command') return <CommandRows key={member.id} member={member} />;
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
        return <ToolRows key={member.id} member={member} />;
      })}
    </>
  );
}

/**
 * Contiguous activity as one folded line: what is running right now while something is, and the
 * sentence its members add up to once nothing is. Opening it lists those members, each still
 * folded over its own output, so a log is only ever read on purpose.
 */
function ActivityView({ block, expanded, onToggle }: {
  block: ActivityBlock;
  expanded: boolean;
  onToggle: () => void;
}): React.JSX.Element {
  const current = block.members.filter((member) => memberState(member) === 'running').at(-1);
  // What the agent remembered stays in sight while the group is folded, so Undo is one click away.
  const remembered = block.members.flatMap((member) => {
    const memory = member.kind === 'tool' ? rememberedMemory(member) : null;
    return memory === null ? [] : [{ key: member.id, memory }];
  });
  return (
    <div className={`agt-act${current === undefined ? '' : ' is-running'}`}>
      <button type="button" className="agt-act__fold" aria-expanded={expanded} onClick={onToggle}>
        <Chevron open={expanded} />
        <span>{current === undefined ? activitySummary(block.members) : memberLabel(current)}</span>
      </button>
      {expanded && <div className="agt-act__body"><MemberRows members={block.members} /></div>}
      {!expanded && remembered.length > 0 && <div className="agt-act__body">{remembered.map(item => <RememberedRow key={item.key} memory={item.memory} />)}</div>}
    </div>
  );
}

/** The one line a running member is named by, in the group's fold. */
export function memberLabel(member: ActivityMember): string {
  if (member.kind === 'command') return commandLabel(member);
  if (member.kind === 'tool') return member.description ?? toolLabel(member.name);
  return `${CHANGE_VERBS[member.change] ?? member.change} ${member.path}`;
}

export function PlanSteps({ steps, compact = false, repository }: { steps: PlanStep[]; compact?: boolean; repository?: string | null }): React.JSX.Element {
  return (
    <div className={compact ? 'agt-plan__steps is-compact' : 'agt-plan__steps'}>
      {steps.map((step, index) => (
        <div className={`agt-plan__step is-${step.state.toLowerCase().replace('_', '-')}`} key={index}>
          {step.state === 'IN_PROGRESS'
            ? <span className="agt-icon is-running"><Spinner /></span>
            : <Glyph icon={step.state === 'COMPLETED' ? Check : Circle} className={step.state === 'COMPLETED' ? 'is-ok' : 'is-pending'} />}
          <span className="agt-plan__text">{linkIssueNumbers(step.text, repository)}</span>
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

export function PendingApprovalCard({ approval, onDecide }: {
  approval: AgentPendingApprovalDto;
  onDecide: ApprovalDecider;
}): React.JSX.Element {
  return <ApprovalView block={pendingApprovalBlock(approval)} onDecide={onDecide} />;
}

/** A question Claude asked: its own options, answered in place, instead of an allow over raw tool input. */
function QuestionView({ block, questions, allow, deny, onDecide }: {
  block: ApprovalBlock;
  questions: ApprovalQuestion[];
  allow: AgentApprovalDecision;
  deny: AgentApprovalDecision;
  onDecide?: ApprovalDecider;
}): React.JSX.Element {
  const [chosen, setChosen] = useState<Record<string, string[]>>({});
  const answers = questionAnswers(questions, chosen);
  const pick = (question: ApprovalQuestion, label: string): void => setChosen((current) => {
    const picked = current[question.question] ?? [];
    if (!question.multiSelect) return { ...current, [question.question]: [label] };
    return { ...current, [question.question]: picked.includes(label) ? picked.filter((one) => one !== label) : [...picked, label] };
  });
  return (
    <div className="agt-appr agt-ask" role="group" aria-label={`Question: ${questions[0].question}`}>
      <div className="agt-appr__head">
        <Glyph icon={MessageSquare} className="is-warn" />
        <span className="agt-appr__chip">Your answer</span>
        <span className="agt-spacer" />
        <span className="agt-mono agt-right">{agentDisplayName(block.agent)}</span>
      </div>
      {questions.map((question) => (
        <div className="agt-ask__question" key={question.question}>
          <div className="agt-ask__asked">
            {question.header !== '' && <span className="agt-ask__tag">{question.header}</span>}
            <span className="agt-ask__text">{question.question}</span>
          </div>
          <div className="agt-ask__options" role={question.multiSelect ? 'group' : 'radiogroup'} aria-label={question.question}>
            {question.options.map((option) => {
              const picked = (chosen[question.question] ?? []).includes(option.label);
              return (
                <button
                  key={option.label}
                  type="button"
                  role={question.multiSelect ? 'checkbox' : 'radio'}
                  aria-checked={picked}
                  className={`agt-ask__option${picked ? ' is-picked' : ''}`}
                  onClick={() => pick(question, option.label)}
                >
                  <span className="agt-ask__label">{option.label}</span>
                  {option.description !== '' && <span className="agt-ask__detail">{option.description}</span>}
                </button>
              );
            })}
          </div>
        </div>
      ))}
      <div className="agt-decisions">
        <button
          type="button"
          className="agt-decision is-allow"
          disabled={questions.some((question) => answers[question.question] === undefined)}
          aria-label={`Send answer: ${questions[0].question}`}
          onClick={() => onDecide?.(block.approvalId, allow, answers)}
        >
          Send answer
        </button>
        <button
          type="button"
          className="agt-decision is-deny"
          aria-label={`Dismiss question: ${questions[0].question}`}
          onClick={() => onDecide?.(block.approvalId, deny)}
        >
          Dismiss
        </button>
      </div>
    </div>
  );
}

function ApprovalView({ block, onDecide }: {
  block: ApprovalBlock;
  onDecide?: ApprovalDecider;
}): React.JSX.Element {
  const decisions = approvalDecisions(block);
  const questions = approvalQuestions(block);
  if (block.outcome !== null) {
    const allowed = block.outcome === 'ALLOWED';
    const outcome = allowed && questions.length > 0 ? 'Answered'
      : block.outcome === 'ALLOWED' && block.forTask ? 'Approved for this task'
        : OUTCOME_LABELS[block.outcome] ?? block.outcome;
    return (
      <div className="agt-appr-done" role="group" aria-label={`Approval: ${block.title}`}>
        <Glyph icon={allowed ? ShieldCheck : ShieldX} className={`${allowed ? 'is-ok' : 'is-failed'} is-outcome`} />
        <span>{outcome} —</span>
        {/* The tool input is only readable as a question once it is one. */}
        <span className={questions.length > 0 ? undefined : 'agt-mono'}>
          {questions.length > 0 ? questions.map((question) => question.question).join(' ') : block.detail}
        </span>
      </div>
    );
  }
  const allow = decisions.find((choice) => choice.decision === 'ALLOW');
  const deny = decisions.find((choice) => choice.decision === 'DENY');
  if (questions.length > 0 && allow !== undefined && deny !== undefined) {
    return <QuestionView block={block} questions={questions} allow={allow.decision} deny={deny.decision} onDecide={onDecide} />;
  }
  return (
    <div className="agt-appr" role="group" aria-label={`Approval: ${block.title}`}>
      <div className="agt-appr__head">
        <Glyph icon={ShieldCheck} className="is-warn" />
        <span className="agt-appr__chip">Approval needed</span>
        <span className="agt-appr__title">{block.title}</span>
        <span className="agt-spacer" />
        <span className="agt-mono agt-right">{agentDisplayName(block.agent)}</span>
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

export function BlockView({ block, repository, activityChoice, agentLabels, onToggleActivity, onDecide, onOpenAgent }: {
  block: TurnBlock;
  /** `owner/repo` a bare `#1234` in the text refers to. */
  repository?: string | null;
  activityChoice: Record<string, boolean>;
  agentLabels: Record<string, string>;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide?: ApprovalDecider;
  onOpenAgent?: (threadId: string) => void;
}): React.JSX.Element | null {
  switch (block.kind) {
    case 'text':
      if (block.role === 'reasoning') {
        return <div className="agt-reasoning">{renderMarkdown(block.text, repository)}{!block.complete && !block.historical && <span className="agt-cursor" aria-hidden="true" />}</div>;
      }
      if (block.role === 'planText') {
        return (
          <div className="agt-plan">
            <div className="agt-plan__head"><Glyph icon={ListChecks} /><span className="agt-label">Plan</span></div>
            <div className="agt-text">{renderMarkdown(block.text, repository)}{!block.complete && !block.historical && <span className="agt-cursor" aria-hidden="true" />}</div>
          </div>
        );
      }
      return <div className="agt-text">{renderMarkdown(block.text, repository)}{!block.complete && !block.historical && <span className="agt-cursor" aria-hidden="true" />}</div>;
    case 'plan':
      if (block.steps.length === 0) return null;
      return (
        <div className="agt-plan">
          <div className="agt-plan__head">
            <Glyph icon={ListChecks} />
            <span className="agt-label">Plan</span>
            <span className="agt-chip">{planChip(block.steps)}</span>
          </div>
          <PlanSteps steps={block.steps} repository={repository} />
        </div>
      );
    case 'activity': {
      const expanded = activityChoice[block.id] === true;
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
              {agentDisplayName(agentLabels[threadId] ?? 'Agent')}
            </button>
          ))}
          <span className="agt-agents__phase">{block.phase === 'started' ? 'started working' : 'finished'}</span>
        </div>
      );
    case 'error':
      return <div className="agt-error" role="alert"><Glyph icon={CircleAlert} className="is-failed" /><span>{block.message}{block.retryable ? ' — retrying' : ''}</span></div>;
    case 'note':
      return <div className="agt-note"><Glyph icon={TriangleAlert} className="is-warn" /><span>{linkIssueNumbers(block.text, repository)}</span></div>;
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
  const [referenceOpen, setReferenceOpen] = useState(false);
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
    parts.push(`Tools ${counters.toolCalls} · Explored ${counters.exploreActions}`);
  }
  if (turn.endedAt !== null) parts.push(clock(turn.endedAt));
  const guidelines = durable?.guidelines ?? [];
  const memories = durable?.memories ?? [];
  const reference = [memories.length > 0 ? `Memory ${memories.length}` : '', guidelines.length > 0 ? `Guidelines ${guidelines.length}` : '']
    .filter(part => part !== '').join(' · ');
  return (
    <>
      <div className="agt-usage">
        <Glyph icon={turn.outcome === 'interrupted' ? CircleX : CircleCheck} className={`${turn.outcome === 'interrupted' ? 'is-failed' : 'is-ok'} is-outcome`} />
        <span>{parts.join(' · ')}</span>
        {reference !== '' && <button type="button" className="agt-usage__reference" aria-expanded={referenceOpen}
          onClick={() => setReferenceOpen(value => !value)}>{reference}</button>}
      </div>
      {referenceOpen && <div className="agt-reference">
        {memories.length > 0 && <>
          <div className="agt-reference__label">Memory delivered at turn start</div>
          {memories.map(memory => <div className="agt-reference__memory" key={memory.id}><span className="agt-reference__id">{memory.id}</span>
            <span>{memory.line}</span></div>)}
        </>}
        {guidelines.length > 0 && <div className="agt-reference__guidelines">Guidelines: {guidelines.join(', ')}</div>}
      </div>}
    </>
  );
}

export function TurnView({ turn, repository, first, open, now, models, durableTurns, activityChoice, previews, agentLabels, onToggle, onToggleActivity, onDecide, onOpenAgent, generatedRequest = false, generatedBranch = null, turnRequest, localReview, discussions, reviewRequests, issueSource, jiraSource, onOpenReview, onOpenDiscussion, onSelectPassage, discussionBusy = false, focusedDiscussion, onHoverDiscussion, latest = false, children }: {
  localReview?: LocalReviewThreads;
  discussions?: TaskDiscussionDto[];
  onOpenDiscussion?: (discussionId: string) => void;
  focusedDiscussion?: string | null;
  onHoverDiscussion?: (discussionId: string | null) => void;
  onSelectPassage?: (passage: SelectedPassage, question: string) => Promise<unknown>;
  /** Another discussion on the task is being answered. */
  discussionBusy?: boolean;
  turnRequest?: React.ReactNode;
  reviewRequests?: readonly ReviewRequest[];
  issueSource?: AgentIssueSourceDto | null;
  jiraSource?: JiraTaskSource | null;
  onOpenReview?: (target: ReviewRequestTarget) => void;
  latest?: boolean;
  children?: React.ReactNode;
  generatedRequest?: boolean;
  generatedBranch?: string | null;
  turn: TranscriptTurn;
  repository?: string | null;
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
  onDecide?: ApprovalDecider;
  onOpenAgent?: (threadId: string) => void;
}): React.JSX.Element {
  const waiting = turn.blocks.some((block) => block.kind === 'approval' && block.actionable && block.outcome === null);
  const running = turn.startedAt !== null && turn.endedAt === null;
  const live = running && !waiting;
  const hasLog = turn.startedAt !== null || turn.blocks.length > 0;
  const durable = turn.turnId === null ? undefined : durableTurns[turn.turnId];
  const opening = turn.users[0];
  const developmentRequest = opening?.delivery === 'TURN' ? developmentTurnRequest(durable) : null;
  const request = opening?.delivery === 'TURN' ? draftPullRequestRequest(durable, opening, generatedBranch) : null;
  // Legacy source responses lack the ID; ordinal 1 is known only when history includes its beginning.
  const jiraRequest = jiraSource != null && turn.turnId != null && (jiraSource.initialTurnId != null
    ? turn.turnId === jiraSource.initialTurnId : first && turn.ordinal === 1);
  const issueRequest = issueSource != null && turn.turnId != null && (issueSource.initialTurnId != null
    ? turn.turnId === issueSource.initialTurnId : first && turn.ordinal === 1);
  return (
    <section className={`agt-turn${latest ? ' is-latest' : ''}`} data-history-anchor={turn.turnId ?? `turn-${turn.ordinal}`} aria-label={turn.ordinal > 0 ? `Turn ${turn.ordinal}` : 'Turn'}>
      {turn.users.map((user, index) => {
        const localThread = localReview?.threads.find(thread => thread.messages.some(message =>
          message.author === 'USER' && (message.messageEventId === user.id || (index === 0 && user.delivery !== 'STEER' && turn.turnId !== null && message.turnId === turn.turnId))));
        const localMessage = localThread?.messages.find(message => message.author === 'USER'
          && (message.messageEventId === user.id || (index === 0 && user.delivery !== 'STEER' && turn.turnId !== null && message.turnId === turn.turnId)));
        const reviewRequest = reviewRequests?.find(message => message.messageEventId === user.id
          || (index === 0 && user.delivery !== 'STEER' && turn.turnId !== null && message.turnId === turn.turnId));
        const reviewTarget = reviewRequest?.target;
        const fixRequest = findingFixRequest(user.text, durable?.taskId);
        const fixTarget = fixRequest?.target;
        return (
        <React.Fragment key={user.id}>
          {first && index === 0 && <div className="agt-stamp">{stamp(user.at, now)}</div>}
          {localThread !== undefined && localMessage !== undefined && localReview !== undefined ? (
            <div className="agt-user agt-local-review" data-history-anchor={user.id}>
              <ReviewRequestCard action={localThread.messages.find(message => message.author === 'USER')?.id === localMessage.id ? 'Ask about review' : 'Reply to review'}
                topic={localThread.learningAnchor?.title ?? `${localThread.path}:${localThread.side === 'LEFT' ? 'L' : 'R'}${localThread.line}`}
                body={localMessage.body} onOpen={onOpenReview === undefined ? undefined : () => onOpenReview({ kind: 'local', threadId: localThread.id })}>
                <LocalReviewThreadCard {...localReview} thread={localThread} />
              </ReviewRequestCard>
            </div>
          ) : <div className={`agt-user${fixRequest !== null ? ' is-review-fix' : ''}`} data-history-anchor={user.id}>
            {index === 0 && user.delivery === 'TURN' && turnRequest ? turnRequest : index === 0 && user.delivery === 'TURN' && jiraRequest && jiraSource != null
              ? <JiraRequestCard source={jiraSource} attachments={user.attachments.length > 0 && <div className="agt-user__files">
                {user.attachments.map(attachment => <AttachmentTile key={attachment.id} attachment={attachment} preview={previews[attachment.id]} />)}
              </div>} />
              : index === 0 && user.delivery === 'TURN' && issueRequest && issueSource != null
              ? <IssueRequestCard source={issueSource} attachments={user.attachments.length > 0 && <div className="agt-user__files">
                {user.attachments.map(attachment => <AttachmentTile key={attachment.id} attachment={attachment} preview={previews[attachment.id]} />)}
              </div>} /> : reviewRequest !== undefined ? <ReviewRequestCard {...reviewRequest}
              onOpen={onOpenReview !== undefined && reviewTarget !== undefined ? () => onOpenReview(reviewTarget) : undefined} /> : fixRequest !== null ? (
              <ReviewRequestCard {...fixRequest}
                onOpen={onOpenReview !== undefined && fixTarget !== undefined ? () => onOpenReview(fixTarget) : undefined}>
                {user.attachments.length > 0 && <div className="agt-user__files">
                  {user.attachments.map(attachment => <AttachmentTile key={attachment.id} attachment={attachment} preview={previews[attachment.id]} />)}
                </div>}
                <div className="agt-review-request__body">{renderMarkdown(user.text, repository, true)}</div>
              </ReviewRequestCard>
            ) : index === 0 && request !== null ? (
              <div className="agt-user__request">
                <GitPullRequest aria-hidden="true" />
                <span className="agt-user__request-action">{request.action}</span>
                {request.head !== null && <>{' '}<span className="agt-user__request-head" title={request.head}>{request.head}</span></>}
                {request.base !== null && <>{' '}<span className="agt-user__request-base">→ {request.base}</span></>}
              </div>
            ) : index === 0 && developmentRequest !== null ? (
              <ReviewRequestCard {...developmentRequest} detail="Generated instructions sent to the agent">
                {user.attachments.length > 0 && <div className="agt-user__files">
                  {user.attachments.map(attachment => <AttachmentTile key={attachment.id} attachment={attachment} preview={previews[attachment.id]} />)}
                </div>}
                <div className="agt-review-request__body">{renderMarkdown(user.text, repository, true)}</div>
              </ReviewRequestCard>
            ) : (
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
                <div>{linkIssueNumbers(user.text, repository)}</div>
                {user.delivery !== 'TURN' && (
                  <div className="agt-muted agt-small agt-user__delivery">
                    <Glyph icon={user.delivery === 'STEER' ? CornerDownRight : ListPlus} />
                    <span>{user.delivery === 'STEER' ? 'Steered into the running turn' : 'Queued'}</span>
                  </div>
                )}
              </div>
            )}
          </div>}
        </React.Fragment>
      ); })}
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
          repository={repository}
          activityChoice={activityChoice}
          agentLabels={agentLabels}
          onToggleActivity={onToggleActivity}
          onDecide={onDecide}
          onOpenAgent={onOpenAgent}
        /></div>
      ))}
      {turn.answer !== null && (
        <div className="agt-answer" data-history-anchor={turn.answer.anchorId ?? turn.answer.id}>
          <DiscussableAnswer
            turnId={turn.turnId}
            text={turn.answer.text}
            repository={repository}
            streaming={!turn.answer.complete}
            discussions={(discussions ?? []).filter(discussion => discussion.turnId === turn.turnId)}
            focused={focusedDiscussion}
            busy={discussionBusy}
            onSelect={onSelectPassage}
            onOpen={onOpenDiscussion}
            onHover={onHoverDiscussion}
          />
          {!turn.answer.complete && !turn.answer.historical && <span className="agt-cursor" aria-hidden="true" />}
        </div>
      )}
      {turn.endedAt !== null && <TurnFooter turn={turn} durable={turn.turnId === null ? undefined : durableTurns[turn.turnId]} models={models} />}
      {children}
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
  turnId?: string | null;
  steerBlockedReason?: string;
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
export function ConversationQueue({ queue, running, idle, resumeBlocked, resumeBlockedReason, steerEnabled = false, onSteer, onRemove, onResume }: {
  queue: ConversationQueuedInput[];
  running: boolean;
  idle: boolean;
  resumeBlocked: boolean;
  resumeBlockedReason?: string;
  steerEnabled?: boolean;
  onSteer?: (queuedInputId: string) => void;
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
            {running && onSteer !== undefined && input.turnId == null && ['QUEUED', 'PAUSED', 'UNKNOWN'].includes(state) && (
              <button type="button" className="agt-queue__link" disabled={!steerEnabled || input.steerBlockedReason !== undefined}
                title={input.steerBlockedReason ?? (steerEnabled ? 'Sends this queued message into the running turn now' : 'Steering is unavailable for the current turn')}
                onClick={() => onSteer(input.id)}>Steer</button>
            )}
            {state === 'PAUSED' && input.id === head.id && idle && onResume !== undefined && (
              <button
                type="button"
                className="agt-queue__link"
                disabled={resumeBlocked}
                title={resumeBlocked ? resumeBlockedReason ?? 'Check provider readiness before resuming the queue' : undefined}
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

/** Follows the bottom; a glide still under way is retargeted rather than cut short by a jump. */
function pinBottom(container: HTMLElement, glide: React.RefObject<boolean>): void {
  if (glide.current && container.scrollHeight - container.scrollTop - container.clientHeight > 1) {
    container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
    return;
  }
  glide.current = false;
  container.scrollTop = container.scrollHeight;
}

/** Shared user-intent scrolling; streamed output and folding must not move someone reading history. */
export function useConversationHistory(events: AgentEventEnvelope[], endedAt: string | null | undefined, history: ConversationHistory) {
  const transcriptRef = useRef<HTMLDivElement>(null);
  const follow = useRef(true);
  const [following, setFollowing] = useState(true);
  const scrollIntent = useRef(0);
  const previousScrollTop = useRef(0);
  // A message sent from the live edge glides from the bottom to the top instead of appearing there.
  const glide = useRef(false);
  const [sends, setSends] = useState(0);
  useOutputWheel(transcriptRef, scrollIntent);
  const pendingHistory = useRef<{ events: AgentEventEnvelope[]; anchors: HistoryScrollAnchor[]; direction: 'older' | 'newer' } | null>(null);
  const followingChange = useRef<(next: boolean) => void>(() => {});
  const { before, after, loading, onLoad, onFollow } = history;
  const changeFollowing = (next: boolean): void => {
    follow.current = next;
    if (!next) glide.current = false;
    setFollowing(next);
    onFollow(next);
  };
  /** Sending resumes following; the pin runs after the sent message renders, even if no event changed. */
  const followSend = (): void => {
    glide.current = follow.current && !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    scrollIntent.current = 0;
    changeFollowing(true);
    setSends(count => count + 1);
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
    else if (follow.current) pinBottom(container, glide);
    else if (pending === null && after === null && container.scrollHeight <= container.clientHeight) followingChange.current(true);
  }, [events, after, endedAt, sends]);
  useEffect(() => {
    // An immediate failure can batch loading=true/false into one render.
    if (!loading) pendingHistory.current = null;
  });
  useLayoutEffect(() => {
    const container = transcriptRef.current;
    if (following && container !== null) pinBottom(container, glide);
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
      const { scrollTop, scrollHeight, clientHeight } = event.currentTarget;
      if (scrollHeight - scrollTop - clientHeight <= 1) glide.current = false;
      const intent = scrollIntent.current === 2 ? Math.sign(scrollTop - previousScrollTop.current) : scrollIntent.current;
      previousScrollTop.current = scrollTop;
      if (scrollIntent.current !== 2) scrollIntent.current = 0;
      scrollHistory(event.currentTarget, intent);
      if (pendingHistory.current !== null) pendingHistory.current.anchors = captureHistoryAnchors(event.currentTarget);
    },
  };
  return { following, changeFollowing, followSend, loadHistory, transcriptProps, before, after, loading };
}

/** A sent message as the newest turn, until its durable event replaces it. */
function pendingTurn(message: PendingMessage, now: number): TranscriptTurn {
  return {
    turnId: null, ordinal: 0, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
    users: [{ id: 'pending-message', text: message.text, attachments: message.attachments, delivery: 'TURN', at: new Date(now).toISOString() }],
  };
}

export function AgentConversation({
  turns, repository, durableTurns, models, now, scroll, connection, running, activeTurn, activeTurnId = activeTurn?.id, waiting, gap = false, pendingMessage = null,
  activityChoice, turnChoice, onToggleTurn, onToggleActivity, onDecide,
  previews = {}, agentLabels = {}, pendingApprovals = [], generatedTurnIds, generatedBranch,
  onOpenAgent, children, transcriptFooter, turnFooter, turnRequest, localReview, discussions, reviewRequests, issueSource, jiraSource, onOpenReview, onOpenDiscussion, onSelectPassage, discussionBusy, focusedDiscussion, onHoverDiscussion, label = 'Task transcript',
  workspaceId,
}: {
  turns: TranscriptTurn[];
  /** `owner/repo` a bare `#1234` in the transcript refers to. */
  repository?: string | null;
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
  /** Shown at once as local progress; it is not a transcript event. */
  pendingMessage?: PendingMessage | null;
  activityChoice: Record<string, boolean>;
  turnChoice: Record<string, boolean>;
  onToggleTurn: (id: string, open: boolean) => void;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide: ApprovalDecider;
  previews?: Record<string, AttachmentPreview>;
  agentLabels?: Record<string, string>;
  pendingApprovals?: AgentPendingApprovalDto[];
  generatedTurnIds?: ReadonlySet<string>;
  generatedBranch?: string | null;
  onOpenAgent?: (threadId: string) => void;
  children: React.ReactNode;
  transcriptFooter?: React.ReactNode;
  turnFooter?: (turnId: string) => React.ReactNode;
  turnRequest?: (turnId: string) => React.ReactNode;
  localReview?: LocalReviewThreads;
  discussions?: TaskDiscussionDto[];
  onOpenDiscussion?: (discussionId: string) => void;
  focusedDiscussion?: string | null;
  onHoverDiscussion?: (discussionId: string | null) => void;
  onSelectPassage?: (passage: SelectedPassage, question: string) => Promise<unknown>;
  /** Another discussion on the task is being answered. */
  discussionBusy?: boolean;
  reviewRequests?: readonly ReviewRequest[];
  issueSource?: AgentIssueSourceDto | null;
  jiraSource?: JiraTaskSource | null;
  onOpenReview?: (target: ReviewRequestTarget) => void;
  label?: string;
  /** The workspace whose memory a Remembered row's Undo changes; without it the rows show no Undo. */
  workspaceId?: string | null;
}): React.JSX.Element {
  const pending = scroll.after === null ? pendingMessage : null;
  const liveTurn = turns.find(turn => turn.turnId === activeTurnId);
  const liveLabel = connection !== 'open' || gap ? 'Reconnecting…'
    : pending !== null ? 'Sending…'
    : waiting ? 'Waiting for approval'
      : activeTurn?.cancellationRequestedAt != null ? 'Stopping…'
        : activeTurn?.state === 'ACCEPTED' ? 'Starting…'
          : liveTurn?.endedAt != null ? 'Finishing…' : currentActivityLabel(liveTurn);
  // Reading earlier in the live page must not hide what the agent is doing; only an older page can.
  const liveActivity = (running || pending !== null) && scroll.after === null
    && <LiveActivity label={liveLabel} animated={!waiting && connection === 'open' && !gap} />;
  const tailRef = useRef<HTMLDivElement>(null);
  const transcriptRef = scroll.transcriptProps.ref;
  useLayoutEffect(() => {
    const tail = tailRef.current;
    const transcript = transcriptRef.current;
    if (tail === null || transcript === null) return undefined;
    // Trailing notices share the latest turn's reserved viewport instead of pushing its request off the top.
    const observer = new ResizeObserver(() => transcript.style.setProperty('--agt-tail', `${tail.offsetHeight}px`));
    observer.observe(tail);
    return () => observer.disconnect();
  }, [transcriptRef]);
  const undo = useMemo(() => workspaceId == null ? null : async (memoryId: string): Promise<void> => {
    await window.bridge.undoRememberedMemory(workspaceId, memoryId);
    window.dispatchEvent(new CustomEvent(MEMORY_CHANGED, { detail: workspaceId }));
  }, [workspaceId]);
  return (
    <MemoryUndo.Provider value={undo}>
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
        {turns.length === 0 && pending === null && connection !== 'connecting'
          ? <div className="agt-empty">No activity yet.</div>
          : turns.map((turn, index) => {
            const id = turn.turnId ?? `turn-${turn.ordinal}`;
            const open = turnChoice[id] ?? (turn.endedAt === null || turn.answer === null);
            const latest = scroll.after === null && pending === null && index === turns.length - 1;
            // A request reserves a viewport only until its reply starts, so sending lifts it to the top.
            // From the first block on the turn is its own height, so output stays just above the composer.
            const reserve = latest && turn.blocks.length === 0 && turn.answer === null;
            return <TurnView key={id} turn={turn} repository={repository} first={index === 0} open={open} now={now}
              models={models} durableTurns={durableTurns} activityChoice={activityChoice}
              previews={previews} agentLabels={agentLabels} onToggle={() => onToggleTurn(id, !open)}
              onToggleActivity={onToggleActivity} onDecide={onDecide} onOpenAgent={onOpenAgent}
              localReview={localReview} discussions={discussions} onOpenDiscussion={onOpenDiscussion} onSelectPassage={onSelectPassage}
              discussionBusy={discussionBusy} focusedDiscussion={focusedDiscussion} onHoverDiscussion={onHoverDiscussion}
              turnRequest={turn.turnId !== null ? turnRequest?.(turn.turnId) : undefined}
              reviewRequests={reviewRequests} issueSource={issueSource} jiraSource={jiraSource} onOpenReview={onOpenReview} latest={reserve}
              generatedRequest={turn.turnId !== null && generatedTurnIds?.has(turn.turnId)} generatedBranch={generatedBranch}>
              {turn.turnId !== null && turnFooter?.(turn.turnId)}
              {latest && liveActivity}
            </TurnView>;
          })}
        {pending !== null && <TurnView key="pending-message" turn={pendingTurn(pending, now)} repository={repository} first={turns.length === 0} open now={now}
          models={models} durableTurns={durableTurns} activityChoice={activityChoice} previews={previews} agentLabels={agentLabels}
          onToggle={() => {}} onToggleActivity={onToggleActivity} latest>
          {liveActivity}
        </TurnView>}
        {turns.length === 0 && pending === null && liveActivity}
        {/* Kept outside the latest turn so a new turn cannot remount a card holding a reply draft. */}
        <div className="agt-transcript-tail" ref={tailRef}>
          {localReview?.threads.filter(thread => thread.messages.some(message => message.author === 'USER'
            && message.turnId === null && message.deliveryState !== 'REMOVED' && message.deliveryState !== 'ABANDONED')).map(thread => (
            <div className="agt-local-review" key={thread.id} data-history-anchor={`local-review-${thread.id}`}>
              <LocalReviewThreadCard {...localReview} thread={thread} />
            </div>
          ))}
          {transcriptFooter}
        </div>
      </div>
      <div className="agt-bottom">{children}</div>
    </div>
    </MemoryUndo.Provider>
  );
}

/** The whole task composer, including its controls, is shared by every conversation. */
export function ConversationComposer({
  value, onChange, onSend, onStop, onSteer, onQueue, running, sending, disabled,
  sendEnabled, steerEnabled, queueEnabled, models, model, effort, serviceTier, provider = '',
  onSelectModel, onAttach, attachEnabled = false,
  attachTitle = 'Image attachments are unavailable', attachments = [], previews = {}, onRemoveAttachment, footer, automation,
  composerRef, onPaste, connection, closing = false, ciMention = false, routedToCi = false, ciMessageHint,
  placeholder = 'Message the agent',
  steerTitle = 'Sends into the running turn now', queueTitle = 'Runs after this turn finishes',
  stopLabel = 'Stop this turn', stopTitle = 'Interrupts the running turn and waits for its work to stop',
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
  placeholder?: string;
  connection: AgentStreamConnectionState;
  closing?: boolean;
  ciMention?: boolean;
  routedToCi?: boolean;
  ciMessageHint?: string;
  steerTitle?: string;
  queueTitle?: string;
  stopLabel?: string;
  stopTitle?: string;
}): React.JSX.Element {
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const composing = useRef(false);
  const nextCaret = useRef<number | null>(null);
  const dismissedMention = useRef<{ value: string; start: number; end: number } | null>(null);
  const mentionId = useId();
  const [mention, setMention] = useState<{ value: string; start: number; end: number } | null>(null);
  const activeMention = ciMention && !disabled && mention?.value === value ? mention : null;
  const setComposerRef = useCallback((input: HTMLTextAreaElement | null) => {
    inputRef.current = input;
    if (typeof composerRef === 'function') return composerRef(input);
    if (composerRef !== undefined && composerRef !== null) composerRef.current = input;
  }, [composerRef]);
  const updateMention = (input: HTMLTextAreaElement): void => {
    const caret = input.selectionStart;
    const dismissed = dismissedMention.current;
    if (dismissed?.value === input.value && dismissed.start === caret && dismissed.end === input.selectionEnd) return;
    dismissedMention.current = null;
    const match = /(?:^|\s)@([^\s@]*)$/.exec(input.value.slice(0, caret));
    const suffix = input.value.slice(caret).match(/^\S*/)?.[0] ?? '';
    setMention(ciMention && !composing.current && caret === input.selectionEnd && match !== null && 'ci'.startsWith((match[1] + suffix).toLowerCase())
      ? { value: input.value, start: caret - match[1].length - 1, end: caret + suffix.length }
      : null);
  };
  const selectCi = (): void => {
    if (activeMention === null) return;
    const prefix = value.slice(0, activeMention.start) + '@ci ';
    nextCaret.current = prefix.length;
    onChange(prefix + value.slice(activeMention.end));
    setMention(null);
  };
  useLayoutEffect(() => {
    if (nextCaret.current === null || inputRef.current === null) return;
    inputRef.current.focus();
    inputRef.current.setSelectionRange(nextCaret.current, nextCaret.current);
    nextCaret.current = null;
  });
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
        {activeMention !== null && <div className="agt-mentions" id={mentionId} role="listbox" aria-label="Mention an agent">
          <button type="button" id={`${mentionId}-ci`} role="option" aria-selected="true" tabIndex={-1}
            onMouseDown={event => event.preventDefault()} onClick={selectCi}>
            <strong>@ci</strong><span>CI Autofix</span>
          </button>
        </div>}
        {ciMention && <BackportRequestHint text={value} />}
        <textarea ref={setComposerRef} className="agt-compose__input" rows={1} value={value}
          aria-label="Message the agent" placeholder={connection === 'connecting' ? 'Connecting…' : closing ? 'Closing…' : placeholder} disabled={disabled}
          aria-autocomplete={ciMention ? 'list' : undefined} aria-controls={activeMention !== null ? mentionId : undefined}
          aria-activedescendant={activeMention !== null ? `${mentionId}-ci` : undefined}
          aria-describedby={ciMessageHint ? `${mentionId}-hint` : undefined}
          onChange={event => { onChange(event.target.value); updateMention(event.currentTarget); }} onPaste={onPaste}
          onFocus={event => updateMention(event.currentTarget)} onSelect={event => updateMention(event.currentTarget)}
          onBlur={() => setMention(null)}
          onCompositionStart={() => { composing.current = true; setMention(null); }}
          onCompositionEnd={event => { composing.current = false; updateMention(event.currentTarget); }}
          onKeyDown={event => {
            if (event.nativeEvent.isComposing || composing.current || event.keyCode === 229) return;
            if (activeMention !== null) {
              if (event.key === 'Escape') {
                event.preventDefault();
                dismissedMention.current = { value, start: event.currentTarget.selectionStart, end: event.currentTarget.selectionEnd };
                setMention(null);
                return;
              }
              if ((event.key === 'Enter' || event.key === 'Tab') && !event.shiftKey) {
                event.preventDefault(); selectCi(); return;
              }
              if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); return; }
            }
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              if (sendEnabled) onSend();
            }
          }} />
        {ciMessageHint && <div className="agt-compose__hint" id={`${mentionId}-hint`} role="status">{ciMessageHint}</div>}
        <div className="agt-compose__row">
          <button type="button" className="agt-attach" aria-label="Attach an image" title={attachTitle}
            disabled={!attachEnabled || onAttach === undefined || closing || sending} onClick={onAttach}>+</button>
          {automation}
          <ModelPicker
            ariaLabel="Model and effort for the next message"
            disabled={models.length === 0 || sending || onSelectModel === undefined}
            models={models} provider={provider} selectedModel={model ?? ''}
            selectedEffort={effort ?? undefined} selectedServiceTier={serviceTier}
            onSelect={onSelectModel ?? (() => {})} />
          {running && !routedToCi && value.trim() !== '' && <>
            <button type="button" className="agt-steer" disabled={!steerEnabled} title={steerTitle} onClick={onSteer}>Steer</button>
            <button type="button" className="agt-compose__chip" disabled={!queueEnabled} title={queueTitle} onClick={onQueue}>Run next</button>
          </>}
          {(!running || routedToCi) && <button type="button" className="agt-send" aria-label={routedToCi ? 'Send to CI agent' : 'Send message'}
            title={routedToCi ? 'Send to CI agent' : 'Send'} onClick={onSend} disabled={!sendEnabled}>↑</button>}
          {running &&
            // A development turn reads as running during its baseline capture, before it can be stopped cleanly.
            <button type="button" className="agt-send is-stop" aria-label={stopLabel}
                title={stopTitle} onClick={onStop} disabled={sending}>
                <span className="agt-stop-square" aria-hidden="true" />
              </button>}
        </div>
      </div>
      {footer}
    </div>
  );
}
