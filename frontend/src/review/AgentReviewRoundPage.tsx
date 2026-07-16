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
import {
  useEffect, useMemo, useRef, useState,
  type KeyboardEvent, type ReactElement, type ReactNode,
} from 'react';
import ResizeHandle from '../ResizeHandle';
import { isPendingLocalComment } from '../diff/DiffInlineComments';
import { formatRelativeTime } from '../pr/utils';
import { MarkdownProse } from '../threads/MarkdownProse';
import {
  BackChevronIcon, ChatBubbleIcon, CheckIcon, ChevronRightIcon, CloseIcon,
  CommitIcon, EyeIcon, PlusIcon, SendUpIcon, WarnTriangleIcon,
} from '../ui/TaskBrainDesignIcons';
import { findingSummary } from './AgentEvidence';
import type {
  AgentReviewData, InvestigationStepRow, PanelReviewRunRow, ReviewAssignmentRow,
  ReviewedCommitRow, ReviewRoundRow, RoundMessageRow,
} from './agentReviewTypes';
import { findingComment, formatCents, roundPlanObjectives } from './agentReviewTypes';

type AsyncAction = boolean | Promise<boolean>;
type SendMessage = (roundId: string, target: string, text: string) => AsyncAction;

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/gi;
const MIN_CAP_CENTS = 50;
const MAX_CAP_CENTS = 500;
const CAP_STEP_CENTS = 25;
const ROUND_LEFT_WIDTH_KEY = 'bq.agentReviewRoundLeftWidth.v2';
const ROUND_PR_WIDTH_KEY = 'bq.agentReviewRoundPrWidth.v2';
const ROUND_LEFT_DEFAULT = 244;
const ROUND_LEFT_MIN = 196;
const ROUND_LEFT_MAX = 360;
const ROUND_PR_DEFAULT = 440;
const ROUND_PR_MIN = 320;
const ROUND_PR_MAX = 1200;
const ROUND_CENTER_MIN = 520;

function storedWidth(key: string, fallback: number, min: number, max: number): number {
  try {
    const value = typeof localStorage === 'undefined' ? Number.NaN : Number(localStorage.getItem(key));
    return Number.isFinite(value) && value >= min && value <= max ? value : fallback;
  }
  catch {
    return fallback;
  }
}

function persistWidth(key: string, value: number): void {
  try {
    localStorage.setItem(key, String(Math.round(value)));
  }
  catch {
    // Storage can be unavailable in a private renderer; resizing still works in memory.
  }
}

function conciseAssignmentSummary(data: AgentReviewData, assignmentId: string, summary: string): string {
  const compact = summary.replaceAll(/\s+/g, ' ').trim();
  const internal = compact.includes('<｜｜DSML｜｜')
    || compact.includes('<tool_call>')
    || /(?:re-?recording|record)_assignment|objective ids?/i.test(compact)
    || (compact.match(UUID_PATTERN)?.length ?? 0) > 1;
  if (!internal && compact.length > 0) {
    const firstSentence = compact.match(/^.*?[.!?](?:\s|$)/)?.[0]?.trim() ?? compact;
    return firstSentence.length <= 240 ? firstSentence : `${firstSentence.slice(0, 237).trimEnd()}…`;
  }
  const hypotheses = data.hypotheses.filter(row => row.assignment_id === assignmentId);
  const objectiveCount = new Set(hypotheses.map(row => row.objective_id).filter(Boolean)).size;
  return hypotheses.length > 0
    ? `Investigating ${hypotheses.length} ${hypotheses.length === 1 ? 'hypothesis' : 'hypotheses'} across ${objectiveCount || 1} review ${objectiveCount === 1 ? 'objective' : 'objectives'}.`
    : 'Reviewing the changed code against the assigned objectives.';
}

function stepTarget(argumentsJson: Record<string, unknown> | null | undefined): string {
  if (argumentsJson == null) return 'review context';
  const values = Object.values(argumentsJson).flatMap(value => {
    if (typeof value === 'string' || typeof value === 'number') return [String(value)];
    if (Array.isArray(value)) return value.filter(item => typeof item === 'string' || typeof item === 'number').map(String);
    return [];
  });
  return values.join(' · ') || 'review context';
}

function displayedStepStatus(status: string, parentRunning: boolean): string {
  return status === 'running' && !parentRunning ? 'skipped' : status;
}

function stepVerb(actionType: string): string {
  const action = actionType.toLowerCase();
  if (action.includes('read')) return 'Read';
  if (action.includes('search') || action.includes('grep')) return 'Search';
  if (action.includes('trace')) return 'Trace';
  if (action.includes('test') || action.includes('check')) return 'Check';
  const display = actionType.split(':').at(-1) ?? actionType;
  return display.replaceAll(/[_-]+/g, ' ').replace(/^./, first => first.toUpperCase());
}

function workSummary(steps: InvestigationStepRow[]): string {
  const labels = [...new Set(steps.map(step => {
    const verb = stepVerb(step.action_type).toLowerCase();
    return verb === 'read' ? 'read files' : verb === 'search' ? 'search code' : verb;
  }))];
  return labels.slice(0, 2).join(' + ') || 'review work';
}

function relativeTime(value: string | number | null | undefined): string {
  if (value == null) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : formatRelativeTime(date.toISOString());
}

function durationLabel(run: PanelReviewRunRow | undefined): string | null {
  if (run?.startedAt == null) return null;
  const start = Date.parse(run.startedAt);
  const finish = run.finishedAt == null ? Date.now() : Date.parse(run.finishedAt);
  if (!Number.isFinite(start) || !Number.isFinite(finish) || finish < start) return null;
  const seconds = Math.max(1, Math.round((finish - start) / 1000));
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function roundStatus(round: ReviewRoundRow): { label: string; tone: string } {
  if (round.status === 'QUEUED') return { label: 'queued', tone: 'queued' };
  if (round.status === 'RUNNING') return round.message_gate_open === false
    ? { label: 'finalizing', tone: 'running' }
    : { label: 'running', tone: 'running' };
  if (round.status === 'CANCELLED') return { label: 'stopped', tone: 'cancelled' };
  if (round.status === 'ERRORED') return { label: 'errored', tone: 'errored' };
  if (round.status === 'COMPLETED_WITH_QUESTIONS') return { label: 'questions remain', tone: 'questions' };
  return { label: 'complete', tone: 'complete' };
}

function roundIsLive(round: ReviewRoundRow): boolean {
  return round.status === 'QUEUED' || round.status === 'RUNNING';
}

function roundAcceptsMessages(round: ReviewRoundRow): boolean {
  return round.status === 'RUNNING' && round.message_gate_open !== false;
}

function supportsIndependentVerifier(round: ReviewRoundRow): boolean {
  return round.budget_json.wall_clock_minutes !== 5;
}

function commitsForRound(data: AgentReviewData, round: ReviewRoundRow): ReviewedCommitRow[] {
  const recorded = (data.reviewed_commits ?? []).filter(row => row.round_id === round.id)
    .sort((a, b) => a.position - b.position);
  if (recorded.length > 0) return recorded;
  const sha = round.end_commit ?? round.start_commit;
  return sha.length === 0 ? [] : [{ round_id: round.id, sha, message: 'Reviewed head', position: 0 }];
}

function assignmentStage(assignment: ReviewAssignmentRow, delta: boolean): string {
  if (assignment.reviewer_def_id === 'independent-verifier') return 'Verification';
  return delta ? 'Fix verification' : 'Investigation';
}

type RoundGlyphName = 'planner' | 'reviewer' | 'verifier' | 'user' | 'round' | 'finding';

function RoundGlyph({ name, size = 14 }: { name: RoundGlyphName; size?: number }) {
  const paths: Record<typeof name, ReactNode> = {
    planner: <><path d="M5 3v18" /><path d="M5 4h11l-2.6 3.4L16 11H5" /></>,
    reviewer: <><path d="m9 8-4 4 4 4" /><path d="m15 8 4 4-4 4" /></>,
    verifier: <><path d="M12 3 5 6v5c0 4 3 6.6 7 8 4-1.4 7-4 7-8V6z" /><path d="m9 11 2 2 4-4" /></>,
    user: <><circle cx="12" cy="8" r="3.6" /><path d="M5.5 20a6.5 6.5 0 0 1 13 0" /></>,
    round: <path d="M12 3 21 12 12 21 3 12z" />,
    finding: <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="2.2" fill="currentColor" stroke="none" /></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

type EventGlyphName = 'reply' | 'commit' | 'evidence' | 'warning';

function EventGlyph({ name }: { name: EventGlyphName }) {
  if (name === 'reply') return <ChatBubbleIcon size={13} />;
  if (name === 'commit') return <CommitIcon size={13} />;
  if (name === 'evidence') return <EyeIcon size={13} />;
  return <WarnTriangleIcon size={14} />;
}

function InvestigationGroup({ assignment, steps, delta, live }: {
  assignment: ReviewAssignmentRow;
  steps: InvestigationStepRow[];
  delta: boolean;
  live: boolean;
}) {
  if (steps.length === 0) return null;
  const summary = workSummary(steps);
  const label = summary === 'review work'
    ? assignment.reviewer_def_id === 'independent-verifier' ? 'verify evidence' : delta ? 're-check changes' : summary
    : summary;
  return (
    <details className="agent-round-work">
      <summary><ChevronRightIcon size={11} /><span>{label}</span><small>· {steps.length} steps</small></summary>
      <div className="agent-round-work__steps">
      {steps.map(step => {
        const status = displayedStepStatus(step.status, live);
        return (
          <div className="agent-round-step" key={step.id} title={step.reason}>
            <span className={`is-${status}`} aria-hidden>{status === 'completed' ? <CheckIcon size={11} /> : status === 'running' ? <i /> : <CloseIcon size={10} />}</span>
            <b>{stepVerb(step.action_type)}</b>
            <code>{stepTarget(step.arguments_json)}</code>
            <small>{formatCents(step.cost_cents)}</small>
          </div>
        );
      })}
      </div>
    </details>
  );
}

function InlineTalk({ roundId, target, onSend, disabled }: {
  roundId: string;
  target: string;
  onSend: SendMessage;
  disabled: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [failed, setFailed] = useState(false);
  const targetRole = target === 'planner' ? 'planner' : target.includes('verifier') ? 'verifier' : 'reviewer';
  const submit = async () => {
    const body = text.trim();
    if (body.length === 0 || disabled || sending) return;
    setSending(true);
    setFailed(false);
    const ok = await onSend(roundId, target, body);
    if (ok) { setText(''); setOpen(false); }
    else setFailed(true);
    setSending(false);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey) return;
    event.preventDefault();
    void submit();
  };
  return (<>
      <button type="button" className="agent-round-stage-talk__trigger" onClick={() => setOpen(value => !value)} aria-expanded={open}>
        <span>{open ? 'Hide' : `Talk to ${target}`}</span><ChevronRightIcon size={11} />
      </button>
      {open && (
        <div className="agent-round-stage-talk__box">
          <div>
            <i><RoundGlyph name={targetRole} /></i>
            <b>Direct message · {target}</b>
            <span>private · this round</span>
            <button type="button" onClick={() => setOpen(false)} aria-label={`Close message to ${target}`}><CloseIcon /></button>
          </div>
          <textarea
            aria-label={`Message ${target} directly`}
            value={text}
            onChange={event => setText(event.target.value)}
            onKeyDown={onKeyDown}
            placeholder={`Message ${target} directly…`}
            disabled={sending || disabled}
          />
          <button type="button" className="send" onClick={() => { void submit(); }} disabled={text.trim().length === 0 || sending || disabled} aria-label={`Send message to ${target}`}>
            {sending ? '…' : <SendUpIcon size={14} />}
          </button>
          {failed && <small role="alert">Message was not sent. Try again.</small>}
        </div>
      )}
    </>);
}

function ReviewStage({ label, agent, role, metrics, live, children, assignment, steps, delta, roundId = '', onTalk, talkDisabled = false }: {
  label: string;
  agent: string;
  role: 'planner' | 'reviewer' | 'verifier';
  metrics: string[];
  live?: boolean;
  children: ReactNode;
  assignment?: ReviewAssignmentRow;
  steps?: InvestigationStepRow[];
  delta: boolean;
  roundId?: string;
  onTalk?: SendMessage;
  talkDisabled?: boolean;
}) {
  return (
    <section className={`agent-round-stage${live ? ' is-live' : ''}`}>
      <div className="agent-round-stage__rail"><span><RoundGlyph name={role} /></span><i /></div>
      <div className="agent-round-stage__content">
        <div className="agent-round-stage__head">
          <b>{label}</b><span>{agent}</span>{live && <i aria-label="Running" />}
          <div className="agent-round-stage__metrics">{metrics.map(metric => <small key={metric}>{metric}</small>)}</div>
          {onTalk !== undefined && <InlineTalk roundId={roundId} target={agent} onSend={onTalk} disabled={talkDisabled} />}
        </div>
        <div className="agent-round-stage__prose">{children}</div>
        {assignment !== undefined && <InvestigationGroup assignment={assignment} steps={steps ?? []} delta={delta} live={live ?? false} />}
      </div>
    </section>
  );
}

function RoundMessage({ row, roundNumber, delta }: {
  row: RoundMessageRow;
  roundNumber: number;
  delta: boolean;
}) {
  const user = row.sender.toLowerCase() === 'user' || row.sender.toLowerCase() === 'you';
  const seed = user && row.target === 'panel' && row.assignment_id == null
    && row.status === 'completed';
  const failed = row.status === 'failed' || row.status === 'cancelled';
  const delivery = row.status === 'pending' || row.status === 'running' || row.status === 'processing'
    ? `sending to ${row.target}…`
    : row.status === 'cancelled' ? 'cancelled before delivery'
      : row.status === 'failed' ? `could not be processed by ${row.target}`
        : `sent to ${row.target}`;
  return (
    <div className="agent-round-message-pair">
      <div className="agent-round-seed">
        <div className="agent-round-seed__rail"><span><RoundGlyph name="user" size={16} /></span><i /></div>
        <div className="agent-round-seed__content">
          <div><b>{user ? 'You' : row.sender}</b><span>{row.target === 'panel' ? 'steered this round' : `to ${row.target}`}</span><time>{relativeTime(row.created_at)}</time></div>
          <div className="agent-round-seed__bubble"><MarkdownProse text={row.body} /></div>
          <small className={failed ? 'is-failed' : undefined}>
            {seed ? `seeded Round ${roundNumber} · ${delta ? 'delta' : 'full'} scope` : delivery}
          </small>
        </div>
      </div>
      {row.response != null && row.response.trim().length > 0 && (
        <ReviewStage label={failed ? row.status === 'cancelled' ? 'Cancelled' : 'Guidance failed' : 'Response'} agent={row.target} role={row.target === 'planner' ? 'planner' : row.target.includes('verifier') ? 'verifier' : 'reviewer'} metrics={[]} delta={false}>
          <MarkdownProse text={row.response} />
        </ReviewStage>
      )}
    </div>
  );
}

function eventCopy(data: AgentReviewData, roundId: string) {
  const findingIds = new Set(data.findings.filter(finding => finding.round_id === roundId).map(finding => finding.id));
  return data.pr_timeline_events.flatMap(event => {
    const payload = event.payload;
    const kind = payload?.reviewEvent;
    const findingId = typeof payload?.findingId === 'string' ? payload.findingId : null;
    if (typeof kind !== 'string'
      || (payload?.roundId !== roundId && (findingId === null || !findingIds.has(findingId)))) return [];
    const body = typeof payload?.body === 'string' ? payload.body : null;
    const sha = typeof payload?.sha === 'string' ? payload.sha : null;
    const suggestion = typeof payload?.suggestion === 'string' ? payload.suggestion : null;
    const message = typeof payload?.message === 'string' ? payload.message : null;
    const rows: Record<string, { glyph: EventGlyphName; text: string }> = {
      'author-reply': { glyph: 'reply', text: `${event.actor} (author) replied on ${findingId ?? 'the finding'}${body === null ? '' : ` — “${body}”`}` },
      addresses: { glyph: 'commit', text: `${event.actor} pushed ${sha ?? 'a commit'} — addresses ${findingId ?? 'the finding'}` },
      answered: { glyph: 'reply', text: `You answered ${findingId ?? 'the review question'} — recorded as review evidence` },
      'plan-amendment-suggested': { glyph: 'evidence', text: `Planner suggested a plan amendment${suggestion === null ? '' : ` — ${suggestion}`}` },
      'round-error': { glyph: 'warning', text: `Round failed${message === null ? '' : ` — ${message}`}` },
    };
    const row = rows[kind];
    if (row === undefined) return [];
    const first = { ...row, id: event.id, time: relativeTime(event.createdAt) };
    return kind === 'author-reply' ? [
      first,
      {
        glyph: 'evidence' as const, id: `${event.id}-criterion`, time: first.time,
        text: `Reviewer recorded the author reply as evidence on ${findingId ?? 'the finding'} — the acceptance criterion now includes the author’s clarification.`,
      },
    ] : [first];
  });
}

function FindingRows({ data, round, latest, onOpenFinding, onOpenReviewList, onReopenFinding }: {
  data: AgentReviewData;
  round: ReviewRoundRow;
  latest: boolean;
  onOpenFinding: (findingId: string, filePath: string | null, lineNumber: number | null) => void;
  onOpenReviewList?: (findingId: string) => void;
  onReopenFinding?: (findingId: string) => void;
}) {
  const roundFindings = data.findings.filter(row => row.round_id === round.id);
  const latestOutcomes = new Map(data.outcomes.map(outcome => [outcome.finding_id, outcome]));
  const fixedOutcomes = [...latestOutcomes.values()].flatMap(outcome => {
    if (outcome.author_response !== 'fixed') return [];
    const finding = data.findings.find(row => row.id === outcome.finding_id);
    return finding === undefined ? [] : [{ finding, outcome }];
  });
  const fixed = latest ? fixedOutcomes : [];
  const fixedIds = new Set(fixedOutcomes.map(({ finding }) => finding.id));
  const pending = roundFindings.filter(finding => finding.lifecycle_status !== 'dropped' && !fixedIds.has(finding.id));
  if (fixed.length === 0 && pending.length === 0) return null;
  return (
    <div className="agent-round-findings">
      {fixed.map(({ finding, outcome }) => {
        const comment = findingComment(data, finding.id);
        const pendingDraft = comment !== undefined && isPendingLocalComment(comment);
        const confirmed = outcome.epistemic_resolution === 'confirmed';
        const refuted = outcome.epistemic_resolution === 'refuted';
        const resolutionLabel = confirmed ? `${finding.id} fixed — fix verified`
          : refuted ? `${finding.id} — reported fix was refuted`
            : `${finding.id} — fix reported, verification unresolved`;
        return (
          <div className={`agent-round-finding-event agent-round-resolution${confirmed ? ' is-confirmed' : refuted ? ' is-refuted' : ' is-unresolved'}`} key={`fixed-${finding.id}`}>
            <div className="agent-round-finding-event__rail"><span>{confirmed ? <CheckIcon size={13} /> : refuted ? <CloseIcon size={12} /> : <WarnTriangleIcon size={13} />}</span><i /></div>
            <div className="agent-round-finding-event__content">
              <div className="agent-round-resolution__head"><span>Resolution</span><b>{resolutionLabel}{pendingDraft ? ' · resolve + reply drafted' : ''}</b>{pendingDraft && <small>resolve · pending</small>}</div>
              <p>{confirmed ? <>Finding status <code>open → fixed</code></> : refuted ? 'The author reported a fix, but the recorded evidence refuted it.' : 'The author reported a fix, but verification has not established it yet.'}{comment?.filePath == null ? '' : ` · review thread at ${comment.filePath}:${comment.lineNumber ?? 1}`}. Nothing posts to GitHub until you submit.</p>
              <div className="agent-round-resolution__actions">
                <button type="button" onClick={() => onOpenReviewList?.(finding.id)}>View in review list <ChevronRightIcon /></button>
                <button type="button" onClick={() => onOpenFinding(finding.id, comment?.filePath ?? null, comment?.lineNumber ?? null)}>View on diff <ChevronRightIcon /></button>
                <button type="button" className="danger" onClick={() => onReopenFinding?.(finding.id)}>Reopen finding</button>
              </div>
              {pendingDraft && <strong>1 draft rides your next Submit review <ChevronRightIcon /></strong>}
            </div>
          </div>
        );
      })}
      {pending.map(finding => {
        const comment = findingComment(data, finding.id);
        const location = comment?.filePath == null ? 'PR-level comment' : `${comment.filePath}:${comment.lineNumber ?? 1}`;
        const number = data.findings.indexOf(finding) + 1;
        const pendingDraft = comment !== undefined && isPendingLocalComment(comment)
          && finding.lifecycle_status !== 'excluded' && finding.lifecycle_status !== 'dismissed';
        const state = finding.lifecycle_status === 'excluded' ? 'Excluded'
          : finding.lifecycle_status === 'dismissed' || comment?.dismissedAt != null ? 'Dismissed'
            : comment?.publishedAt != null || finding.lifecycle_status === 'published' ? 'Published'
              : comment?.resolvedAt != null ? 'Resolved' : 'Pending';
        return (
          <div className={`agent-round-finding-event is-${state.toLowerCase()}`} key={finding.id}>
            <div className="agent-round-finding-event__rail"><span>{state === 'Dismissed' || state === 'Excluded' ? <CloseIcon size={12} /> : <RoundGlyph name="finding" size={13} />}</span><i /></div>
            <details className="agent-round-finding-event__content">
              <summary>
                <span>Finding {number}</span>
                <b title={findingSummary(finding.claim)}>{findingSummary(finding.claim)}</b>
                <small>{state}{pendingDraft ? ' · draft' : ''} · {location}</small>
                <ChevronRightIcon className="agent-round-finding-event__chevron" size={12} />
              </summary>
              <div className="agent-round-finding-event__body">
                <MarkdownProse text={comment?.body ?? finding.claim} />
                <button type="button" aria-label={comment?.filePath == null ? `View ${finding.id} in PR conversation` : `View ${finding.id} on diff`} onClick={() => onOpenFinding(finding.id, comment?.filePath ?? null, comment?.lineNumber ?? null)}>
                  {comment?.filePath == null ? 'View in PR conversation' : 'View on diff'} <ChevronRightIcon />
                </button>
              </div>
            </details>
          </div>
        );
      })}
    </div>
  );
}

function RoundSection({ data, round, index, collapsed, latest, busy, onToggle, onOpenFinding, onOpenReviewList, onReopenFinding, onSendMessage }: {
  data: AgentReviewData;
  round: ReviewRoundRow;
  index: number;
  collapsed: boolean;
  latest: boolean;
  busy: boolean;
  onToggle: () => void;
  onOpenFinding: (findingId: string, filePath: string | null, lineNumber: number | null) => void;
  onOpenReviewList?: (findingId: string) => void;
  onReopenFinding?: (findingId: string) => void;
  onSendMessage?: SendMessage;
}) {
  const messages = (data.round_messages ?? []).filter(row => row.round_id === round.id)
    .sort((a, b) => new Date(a.created_at).getTime() - new Date(b.created_at).getTime());
  const guidanceAssignments = new Set(messages.flatMap(message => message.assignment_id == null ? [] : [message.assignment_id]));
  const assignments = data.assignments.filter(row => row.round_id === round.id && !guidanceAssignments.has(row.id));
  const investigations = assignments.filter(row => row.reviewer_def_id !== 'independent-verifier');
  const verifiers = assignments.filter(row => row.reviewer_def_id === 'independent-verifier');
  const objectives = roundPlanObjectives(data, round.id);
  const run = data.runs.find(row => row.id === round.agent_run_id);
  const commits = commitsForRound(data, round);
  const findings = data.findings.filter(row => row.round_id === round.id);
  const accepted = findings.filter(row => row.verification_status !== 'rejected' && row.lifecycle_status !== 'dropped');
  const rejected = findings.length - accepted.length;
  const questions = accepted.filter(row => row.verification_status === 'unknown'
    || row.lifecycle_status === 'NEEDS_USER_JUDGEMENT'
    || row.lifecycle_status === 'NEEDS_AUTHOR_INPUT').length;
  const status = roundStatus(round);
  const delta = round.scope !== 'full';
  const duration = durationLabel(run);
  const plannerMetrics = [
    `${objectives.length} ${objectives.length === 1 ? 'objective' : 'objectives'}`,
    round.scope,
    'deterministic',
  ];
  const acceptsMessages = roundAcceptsMessages(round);
  const fallbackTargets = ['panel', 'planner', ...assignments.map(row => row.reviewer_def_id)];
  if (supportsIndependentVerifier(round)) fallbackTargets.push('independent-verifier');
  const allowedTargets = new Set(data.round_message_targets?.[round.id] ?? fallbackTargets);
  const talk = (target: string) => acceptsMessages && allowedTargets.has(target) ? onSendMessage : undefined;
  return (
    <section className={`agent-round-section${collapsed ? ' is-collapsed' : ''}`} data-round-id={round.id}>
      <button type="button" className={`agent-round-section__header${latest ? ' is-current' : ''}`} onClick={latest ? undefined : onToggle} aria-expanded={!collapsed}>
        {!latest && <span className="agent-round-section__chevron"><ChevronRightIcon size={11} /></span>}
        <b>Round {index + 1}</b><span>{round.scope} scope</span>
        {collapsed && <small>· {accepted.length} findings{rejected > 0 ? ` · ${rejected} rejected` : ''} · {formatCents(round.cost_cents)}</small>}
        <i />
        <em className={`agent-round-section__status is-${status.tone}`}>{round.status === 'RUNNING' && <span />}{status.label}</em>
      </button>
      {!collapsed && (
        <div className="agent-round-section__body">
          {messages.map(message => (
            <RoundMessage key={message.id} row={message} roundNumber={index + 1} delta={delta} />
          ))}
          <ReviewStage label="Planning" agent="planner" role="planner" metrics={plannerMetrics} live={round.status === 'RUNNING' && investigations.length === 0} delta={delta} roundId={round.id} onTalk={talk('planner')} talkDisabled={busy}>
            <p>{delta ? 'Delta' : 'Full'} scope — <b>{objectives.length} {objectives.length === 1 ? 'objective' : 'objectives'}</b> assigned{investigations.length > 0 ? ` to ${investigations.map(row => row.reviewer_def_id).join(', ')}` : ''}. {delta ? 'Only affected findings and their dependency spans are being re-verified.' : 'The panel is reviewing the changed code against the deterministic plan.'}</p>
          </ReviewStage>
          {investigations.map(assignment => {
            const steps = data.steps.filter(step => step.assignment_id === assignment.id);
            const cost = steps.reduce((sum, step) => sum + step.cost_cents, 0);
            const metrics = [formatCents(cost), `${steps.length} steps`, assignment.runner];
            if (duration !== null && investigations.length === 1) metrics.splice(2, 0, duration);
            return (
              <ReviewStage key={assignment.id} label={assignmentStage(assignment, delta)} agent={assignment.reviewer_def_id} role="reviewer" metrics={metrics} live={roundIsLive(round) && assignment.status === 'running'} assignment={assignment} steps={steps} delta={delta} roundId={round.id} onTalk={talk(assignment.reviewer_def_id)} talkDisabled={busy}>
                <MarkdownProse text={conciseAssignmentSummary(data, assignment.id, assignment.understanding_summary)} />
              </ReviewStage>
            );
          })}
          {verifiers.map(assignment => {
            const steps = data.steps.filter(step => step.assignment_id === assignment.id);
            return (
              <ReviewStage key={assignment.id} label="Verification" agent={assignment.reviewer_def_id} role="verifier" metrics={[formatCents(steps.reduce((sum, step) => sum + step.cost_cents, 0)), `${steps.length} steps`, assignment.runner]} live={roundIsLive(round) && assignment.status === 'running'} assignment={assignment} steps={steps} delta={delta} roundId={round.id} onTalk={talk(assignment.reviewer_def_id)} talkDisabled={busy}>
                <MarkdownProse text={conciseAssignmentSummary(data, assignment.id, assignment.understanding_summary)} />
              </ReviewStage>
            );
          })}
          {supportsIndependentVerifier(round) && verifiers.length === 0 && findings.some(finding => finding.verification_status !== 'unknown') && (
            <ReviewStage label="Verification" agent="independent-verifier" role="verifier" metrics={[`${data.verifications.filter(row => findings.some(finding => finding.id === row.finding_id)).length} checked`]} delta={delta} roundId={round.id} onTalk={talk('independent-verifier')} talkDisabled={busy}>
              <p>{rejected > 0 ? `${rejected} candidate ${rejected === 1 ? 'finding was' : 'findings were'} dropped as unsupported.` : 'The surviving findings were checked against the recorded evidence.'}</p>
            </ReviewStage>
          )}
          {eventCopy(data, round.id).map(event => <div className={`agent-round-event is-${event.glyph}`} key={event.id}><span><EventGlyph name={event.glyph} /></span><MarkdownProse text={event.text} /><time>{event.time}</time></div>)}
          <FindingRows data={data} round={round} latest={latest} onOpenFinding={onOpenFinding} onOpenReviewList={onOpenReviewList} onReopenFinding={onReopenFinding} />
          {!roundIsLive(round) && (
            <div className="agent-round-outcome">
              <div className="agent-round-outcome__rail"><span><RoundGlyph name="round" /></span></div>
              <div className="agent-round-outcome__card">
                <div><b>Round {index + 1} {status.label}</b><i />
                  <small>{accepted.length} {accepted.length === 1 ? 'finding' : 'findings'}</small>
                  {rejected > 0 && <small className="rejected">{rejected} rejected</small>}
                  {questions > 0 && <small>{questions} needs judgement</small>}
                  <small>{formatCents(round.cost_cents)}</small>
                </div>
                {commits.length > 0 && <div><b>Reviewed</b><code>{commits.length === 1 ? commits[0].sha.slice(0, 7) : `${commits[0].sha.slice(0, 7)} … ${commits.at(-1)!.sha.slice(0, 7)}`}</code><span>{commits.length} {commits.length === 1 ? 'commit' : 'commits'}</span></div>}
              </div>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

function commitRange(commits: ReviewedCommitRow[]): string {
  if (commits.length === 0) return 'No commits recorded';
  if (commits.length === 1) return commits[0].sha.slice(0, 7);
  return `${commits[0].sha.slice(0, 7)} … ${commits.at(-1)!.sha.slice(0, 7)}`;
}

export function AgentReviewRoundPage({
  data, roundId, prView, prTitle = 'Pull request', onBack, onSelectRound,
  onOpenFinding, onOpenReviewList, onReopenFinding, onStopRound,
  onStartRound, onSendMessage, onUpdateBudget, busy = false, error = null,
}: {
  data: AgentReviewData;
  roundId: string;
  /** The shared PRView owned by the PR page; it stays mounted while folded. */
  prView: ReactElement;
  prTitle?: string;
  onBack: () => void;
  onSelectRound?: (roundId: string) => void;
  onOpenFinding: (findingId: string, filePath: string | null, lineNumber: number | null) => void;
  onOpenReviewList?: (findingId: string) => void;
  onReopenFinding?: (findingId: string) => void;
  onStopRound?: (roundId: string) => void;
  onStartRound?: (seed: string, costCapCents?: number) => AsyncAction;
  onSendMessage?: SendMessage;
  onUpdateBudget?: (roundId: string, costCapCents: number) => AsyncAction;
  busy?: boolean;
  error?: string | null;
}) {
  const latestId = data.rounds.reduceRight<ReviewRoundRow | undefined>(
    (selected, round) => selected ?? (roundIsLive(round) ? round : undefined),
    undefined,
  )?.id ?? data.rounds.at(-1)?.id ?? roundId;
  const [activeRoundId, setActiveRoundId] = useState(() => data.rounds.some(round => round.id === roundId) ? roundId : latestId);
  const [collapsed, setCollapsed] = useState(() => new Set(data.rounds.slice(0, -1).map(round => round.id)));
  const [prOpen, setPrOpen] = useState(true);
  const [recipient, setRecipient] = useState('panel');
  const [recipientOpen, setRecipientOpen] = useState(false);
  const [composerText, setComposerText] = useState('');
  const [composerSending, setComposerSending] = useState(false);
  const [reviewer, setReviewer] = useState('You');
  const [leftWidth, setLeftWidth] = useState(() => storedWidth(
    ROUND_LEFT_WIDTH_KEY, ROUND_LEFT_DEFAULT, ROUND_LEFT_MIN, ROUND_LEFT_MAX,
  ));
  const [prWidth, setPrWidth] = useState(() => storedWidth(
    ROUND_PR_WIDTH_KEY, ROUND_PR_DEFAULT, ROUND_PR_MIN, ROUND_PR_MAX,
  ));
  const pageRef = useRef<HTMLDivElement | null>(null);
  const feedRef = useRef<HTMLDivElement | null>(null);
  const composerRef = useRef<HTMLTextAreaElement | null>(null);
  const previousLatest = useRef(latestId);

  useEffect(() => {
    if (!data.rounds.some(round => round.id === roundId)) return;
    setActiveRoundId(roundId);
    setCollapsed(current => {
      const next = new Set(current);
      next.delete(roundId);
      return next;
    });
  }, [data.rounds, roundId]);

  useEffect(() => {
    if (previousLatest.current === latestId) return;
    previousLatest.current = latestId;
    setActiveRoundId(latestId);
    setCollapsed(current => {
      const next = new Set(current);
      data.rounds.slice(0, -1).forEach(round => next.add(round.id));
      next.delete(latestId);
      return next;
    });
    onSelectRound?.(latestId);
  }, [data.rounds, latestId, onSelectRound]);

  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      const feed = feedRef.current;
      const section = [...(feed?.querySelectorAll<HTMLElement>('[data-round-id]') ?? [])]
        .find(element => element.dataset.roundId === activeRoundId);
      if (feed !== null && section !== undefined) feed.scrollTop = Math.max(0, section.offsetTop - 4);
    });
    return () => cancelAnimationFrame(frame);
  }, [activeRoundId]);

  useEffect(() => {
    const bridge = typeof window === 'undefined' ? undefined : window.bridge;
    if (bridge?.getUserProfile === undefined) return;
    let cancelled = false;
    void bridge.getUserProfile().then(profile => { if (!cancelled) setReviewer(profile.login); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  // AgentReview aggregates are created together with their first round.
  const activeRound = (data.rounds.find(round => round.id === activeRoundId) ?? data.rounds.at(-1))!;
  const activeIndex = data.rounds.indexOf(activeRound);
  const guidanceAssignments = useMemo(
    () => new Set((data.round_messages ?? []).flatMap(message =>
      message.assignment_id == null ? [] : [message.assignment_id])),
    [data.round_messages],
  );
  const activeAssignments = useMemo(
    () => data.assignments.filter(assignment =>
      assignment.round_id === activeRound.id && !guidanceAssignments.has(assignment.id)),
    [activeRound.id, data.assignments, guidanceAssignments],
  );
  const activeObjectives = roundPlanObjectives(data, activeRound.id);
  const activeSteps = data.steps.filter(step => activeAssignments.some(assignment => assignment.id === step.assignment_id));
  const activeCommits = commitsForRound(data, activeRound);
  const activeStatus = roundStatus(activeRound);
  const localSource = activeRound.capabilities_json.source_mode === 'local-source';
  const doneObjectives = activeObjectives.filter(objective => objective.resolution_status === 'finding' || objective.resolution_status === 'investigated-clean').length;
  const activeFindingIds = new Set(data.findings.filter(finding => finding.round_id === activeRound.id).map(finding => finding.id));
  const verifierApplicable = supportsIndependentVerifier(activeRound)
    || activeAssignments.some(assignment => assignment.reviewer_def_id === 'independent-verifier')
    || data.verifications.some(verification => activeFindingIds.has(verification.finding_id));
  const recipientOptions = useMemo(() => {
    if (!roundAcceptsMessages(activeRound)) return ['panel'];
    const authoritative = data.round_message_targets?.[activeRound.id];
    if (authoritative !== undefined) return authoritative;
    const names = activeAssignments.map(assignment => assignment.reviewer_def_id);
    if (verifierApplicable && !names.includes('independent-verifier')) names.push('independent-verifier');
    return ['panel', 'planner', ...new Set(names)];
  }, [activeAssignments, activeRound, data.round_message_targets, verifierApplicable]);
  const canSteer = roundAcceptsMessages(activeRound) && recipient !== 'new-round'
    && recipientOptions.includes(recipient) && onSendMessage !== undefined;
  const nextRoundNumber = data.rounds.length + 1;

  useEffect(() => {
    if (recipient === 'new-round' || recipientOptions.includes(recipient)) return;
    setRecipient('panel');
  }, [recipient, recipientOptions]);

  const selectRound = (id: string) => {
    setActiveRoundId(id);
    setCollapsed(current => {
      const next = new Set(current);
      next.delete(id);
      return next;
    });
    onSelectRound?.(id);
  };

  const resizeLeft = (clientX: number) => {
    const rect = pageRef.current?.getBoundingClientRect();
    if (rect === undefined) return;
    const currentPrWidth = prOpen ? prWidth : 46;
    const currentPrMin = prOpen ? ROUND_PR_MIN : 46;
    const centerMin = Math.max(0, Math.min(ROUND_CENTER_MIN, rect.width - ROUND_LEFT_MIN - currentPrMin));
    const max = Math.max(ROUND_LEFT_MIN, Math.min(ROUND_LEFT_MAX, rect.width - currentPrWidth - centerMin));
    const next = Math.max(ROUND_LEFT_MIN, Math.min(max, clientX - rect.left));
    setLeftWidth(next);
    persistWidth(ROUND_LEFT_WIDTH_KEY, next);
  };

  const resizePr = (clientX: number) => {
    const rect = pageRef.current?.getBoundingClientRect();
    if (rect === undefined) return;
    const centerMin = Math.max(0, Math.min(ROUND_CENTER_MIN, rect.width - ROUND_LEFT_MIN - ROUND_PR_MIN));
    const max = Math.max(ROUND_PR_MIN, Math.min(ROUND_PR_MAX, rect.width - leftWidth - centerMin));
    const next = Math.max(ROUND_PR_MIN, Math.min(max, rect.right - clientX));
    setPrWidth(next);
    persistWidth(ROUND_PR_WIDTH_KEY, next);
  };

  const startNewRound = () => {
    setRecipient('new-round');
    setRecipientOpen(false);
    requestAnimationFrame(() => composerRef.current?.focus());
  };

  const submitComposer = async () => {
    const text = composerText.trim();
    if (text.length === 0 || composerSending || busy) return;
    setComposerSending(true);
    const ok = recipient === 'new-round'
      ? await (onStartRound?.(text) ?? false)
      : canSteer ? await onSendMessage!(activeRound.id, recipient, text) : false;
    if (ok) {
      setComposerText('');
      if (recipient === 'new-round') setRecipient('panel');
    }
    setComposerSending(false);
  };

  const onComposerKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey) return;
    event.preventDefault();
    void submitComposer();
  };

  const adjustBudget = (change: number) => {
    if (onUpdateBudget === undefined || activeRound.status !== 'RUNNING' || busy) return;
    const spentFloor = Math.ceil(activeRound.cost_cents / CAP_STEP_CENTS) * CAP_STEP_CENTS;
    const requested = activeRound.budget_json.cost_cap_cents + change;
    const cap = Math.min(MAX_CAP_CENTS, Math.max(
      MIN_CAP_CENTS, change > 0 ? Math.max(requested, spentFloor) : requested,
    ));
    if (change < 0 && cap < activeRound.cost_cents) return;
    if (cap !== activeRound.budget_json.cost_cap_cents) void onUpdateBudget(activeRound.id, cap);
  };

  const revealFinding = (findingId: string, filePath: string | null, lineNumber: number | null) => {
    setPrOpen(true);
    onOpenFinding(findingId, filePath, lineNumber);
  };
  const revealReviewList = onOpenReviewList === undefined ? undefined : (findingId: string) => {
    setPrOpen(true);
    onOpenReviewList(findingId);
  };

  return (
    <div
      ref={pageRef}
      className={`agent-round-page${prOpen ? '' : ' agent-round-page--pr-closed'}`}
      style={{ gridTemplateColumns: `${leftWidth}px minmax(0, 1fr) ${prOpen ? `${prWidth}px` : '46px'}` }}
    >
      <aside className="agent-round-rail">
        <div className="agent-round-rail__back"><button type="button" onClick={onBack}><BackChevronIcon size={13} /><span>Back to PR conversation</span></button></div>
        <div className="agent-round-rail__scroll">
          <div className="agent-round-rail__divider" />
          <div className="agent-round-rail__heading"><span>Rounds</span></div>
          <div className="agent-round-list">
            {data.rounds.map((round, index) => {
              const status = roundStatus(round);
              return (
                <button type="button" className={round.id === activeRound.id ? 'active' : ''} key={round.id} onClick={() => selectRound(round.id)} aria-current={round.id === activeRound.id ? 'page' : undefined}>
                  <span className={`agent-round-list__dot is-${status.tone}`} />
                  <span><b>Round {index + 1}</b><small>{round.scope} · {status.label}</small></span>
                  <code>{formatCents(round.cost_cents)}</code>
                </button>
              );
            })}
          </div>
          <div className="agent-round-trigger"><button type="button" onClick={startNewRound} disabled={onStartRound === undefined || busy}><PlusIcon size={13} /> Trigger next round</button></div>

          <div className="agent-round-rail__heading"><span>Plan</span><small>{doneObjectives} / {activeObjectives.length}</small></div>
          <div className="agent-round-plan">
            {activeObjectives.map(objective => {
              const done = objective.resolution_status === 'finding' || objective.resolution_status === 'investigated-clean';
              const running = !done && activeRound.status === 'RUNNING';
              return <div key={objective.id}><span className={done ? 'done' : running ? 'running' : ''}>{done ? <CheckIcon size={10} /> : running ? <i /> : null}</span><p>{objective.statement}</p></div>;
            })}
          </div>

          <div className="agent-round-rail__heading"><span>Scope</span></div>
          <div className="agent-round-scope-facts">
            <div><span>Coverage</span><b>{localSource ? 'Local source' : 'Remote-only'}</b></div>
            <div><span>Reviewers</span><b>{activeAssignments.length === 1 ? activeAssignments[0].reviewer_def_id : `${activeAssignments.length} · ${activeSteps.length} steps`}</b></div>
          </div>

          <div className="agent-round-rail__heading"><span>Commits reviewed</span><small>{activeCommits.length}</small></div>
          <div className="agent-round-commits">
            <code>{commitRange(activeCommits)}</code>
            {activeCommits.map(commit => <div key={`${commit.round_id}-${commit.sha}`}><code>{commit.sha.slice(0, 7)}</code><span>{commit.message}</span></div>)}
          </div>
        </div>
        <div className="agent-round-budget">
          <div><span>ROUND {activeIndex + 1} BUDGET</span>
            {activeRound.status === 'RUNNING' && onUpdateBudget !== undefined && <span className="agent-round-budget__buttons"><button type="button" onClick={() => adjustBudget(-CAP_STEP_CENTS)} disabled={busy || activeRound.budget_json.cost_cap_cents <= MIN_CAP_CENTS || activeRound.budget_json.cost_cap_cents - CAP_STEP_CENTS < activeRound.cost_cents} aria-label="Decrease round budget"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><path d="M5 12h14" /></svg></button><button type="button" onClick={() => adjustBudget(CAP_STEP_CENTS)} disabled={busy || activeRound.budget_json.cost_cap_cents >= MAX_CAP_CENTS} aria-label="Increase round budget"><PlusIcon size={11} /></button></span>}
          </div>
          <b>{formatCents(activeRound.cost_cents)} / {formatCents(activeRound.budget_json.cost_cap_cents)}</b>
          <div className="agent-round-budget__meter"><span style={{ width: `${activeRound.budget_json.cost_cap_cents === 0 ? 0 : Math.min(100, activeRound.cost_cents / activeRound.budget_json.cost_cap_cents * 100)}%` }} /></div>
        </div>
        <div className="agent-round-reviewer"><span><RoundGlyph name="user" size={15} /></span><b>{reviewer} · reviewer</b></div>
      </aside>

      <main className="agent-round-conversation">
        <div className="agent-round-conversation__head">
          <span className="agent-round-conversation__label"><RoundGlyph name="round" size={11} /> REVIEW ROUND</span>
          <b>Round {activeIndex + 1} · {activeRound.scope}</b><i />
          <span className={`agent-round-capability agent-round-capability--${localSource ? 'local' : 'remote'}`}>{localSource ? 'LOCAL SOURCE' : 'REMOTE ONLY'}</span>
          <span className={`agent-round-conversation__status ${activeStatus.tone}`}><span />{activeStatus.label.toUpperCase()} · {formatCents(activeRound.cost_cents)}</span>
          {roundIsLive(activeRound) && onStopRound !== undefined && <button type="button" className="agent-round-conversation__stop" onClick={() => onStopRound(activeRound.id)} disabled={busy} aria-label={activeRound.status === 'QUEUED' ? 'Cancel queued round' : 'Stop round'}>{activeRound.status === 'QUEUED' ? 'Cancel' : 'Stop'}</button>}
        </div>
        <div className="agent-round-feed" ref={feedRef}>
          <div className="agent-round-feed__inner">
            {data.rounds.map((round, index) => (
              <RoundSection
                key={round.id}
                data={data}
                round={round}
                index={index}
                collapsed={collapsed.has(round.id)}
                latest={round.id === latestId}
                busy={busy}
                onToggle={() => setCollapsed(current => {
                  const next = new Set(current);
                  if (next.has(round.id)) next.delete(round.id); else next.add(round.id);
                  return next;
                })}
                onOpenFinding={revealFinding}
                onOpenReviewList={revealReviewList}
                onReopenFinding={onReopenFinding}
                onSendMessage={onSendMessage}
              />
            ))}
          </div>
        </div>
        <div className="agent-round-composer">
          <div className="agent-round-composer__inner">
            <div className="agent-round-recipient">
              <span>To</span>
              <button type="button" className={recipient === 'panel' || recipient === 'new-round' ? 'is-panel' : 'is-agent'} aria-haspopup="menu" aria-expanded={recipientOpen} onClick={() => setRecipientOpen(value => !value)}>
                <i>{recipient === 'new-round' ? <PlusIcon size={13} /> : <RoundGlyph name={recipient === 'planner' ? 'planner' : recipient.includes('verifier') ? 'verifier' : recipient === 'panel' ? 'round' : 'reviewer'} size={13} />}</i>
                {recipient === 'new-round' ? 'New round' : recipient === 'panel' ? 'Review panel' : recipient}<span><ChevronRightIcon size={12} /></span>
              </button>
              <small>{recipient === 'new-round' ? `queues round ${nextRoundNumber}` : recipient === 'panel' ? `steers round ${activeIndex + 1}` : 'direct'}</small>
              {recipientOpen && (
                <div className="agent-round-recipient__menu" role="menu" aria-label="Review message recipient">
                  <b>STEER ROUND {activeIndex + 1}</b>
                  {recipientOptions.map(option => (
                    <button type="button" className={option === 'panel' ? 'is-panel' : 'is-agent'} role="menuitemradio" aria-checked={recipient === option} key={option} onClick={() => { setRecipient(option); setRecipientOpen(false); }}>
                      <i><RoundGlyph name={option === 'planner' ? 'planner' : option.includes('verifier') ? 'verifier' : option === 'panel' ? 'round' : 'reviewer'} size={13} /></i><span><b>{option === 'panel' ? 'Review panel' : option}</b><small>{option === 'panel' ? 'steers the active round' : 'direct message'}</small></span>{recipient === option && <em><CheckIcon size={12} /></em>}
                    </button>
                  ))}
                  <hr />
                  <button type="button" role="menuitemradio" aria-checked={recipient === 'new-round'} onClick={startNewRound} className="new-round"><i><PlusIcon size={14} /></i><span><b>Start a new round</b><small>queues round {nextRoundNumber} with a fresh scope</small></span></button>
                </div>
              )}
            </div>
            <div className={`agent-round-composer__box${recipient === 'new-round' ? ' is-new-round' : ''}`}>
              <textarea
                rows={1}
                ref={composerRef}
                value={composerText}
                onChange={event => setComposerText(event.target.value)}
                onKeyDown={onComposerKeyDown}
                placeholder={recipient === 'new-round' ? `Describe what round ${nextRoundNumber} should check…` : recipient === 'panel' ? `Steer round ${activeIndex + 1} — seed a hypothesis, or answer a question…` : `Message ${recipient} directly…`}
                aria-label={recipient === 'new-round' ? `Describe round ${nextRoundNumber}` : `Message ${recipient}`}
                disabled={busy || composerSending || (recipient !== 'new-round' && !canSteer)}
              />
              <span className="agent-round-composer__add" aria-hidden><PlusIcon size={16} /></span>
              <button type="button" onClick={() => { void submitComposer(); }} disabled={composerText.trim().length === 0 || busy || composerSending || (recipient !== 'new-round' && !canSteer)} aria-label={recipient === 'new-round' ? `Start round ${nextRoundNumber}` : `Send to ${recipient}`}>
                {composerSending ? '…' : recipient === 'new-round' ? <><RoundGlyph name="round" size={13} /> Start round {nextRoundNumber} <ChevronRightIcon size={11} /></> : <SendUpIcon size={15} />}
              </button>
            </div>
            {error !== null && <div className="agent-round-composer__error" role="alert">{error}</div>}
          </div>
        </div>
      </main>

      <aside className={`agent-round-pr-panel${prOpen ? ' is-open' : ' is-folded'}`} aria-label="Pull request context">
        <div className="agent-round-pr-panel__content" aria-hidden={!prOpen} inert={!prOpen}>{prView}</div>
        {prOpen ? (
          <button type="button" className="agent-round-pr-fold" onClick={() => setPrOpen(false)} aria-label="Hide PR panel" title="Fold PR panel">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M15 4v16" /></svg>
          </button>
        ) : (
          <div className="agent-round-pr-rail"><button type="button" onClick={() => setPrOpen(true)} aria-label="Show PR panel" title="Show PR panel"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M15 4v16" /></svg></button><span>{prTitle}</span></div>
        )}
      </aside>
      <ResizeHandle className="agent-round-resize agent-round-resize--left" ariaLabel="Resize review rounds sidebar" onResize={resizeLeft} style={{ left: leftWidth - 2 }} />
      {prOpen && <ResizeHandle className="agent-round-resize agent-round-resize--right" ariaLabel="Resize pull request context" onResize={resizePr} style={{ right: prWidth - 2 }} />}
    </div>
  );
}
