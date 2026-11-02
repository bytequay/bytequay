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
import { useEffect, useState, type ReactNode } from 'react';
import {
  CircleCheck, CircleX, Clock, ExternalLink, Eye, GitPullRequest, Hand, Hourglass, ListTodo, LoaderCircle, MessageCircleQuestion, Terminal, TimerOff, TriangleAlert,
  X, type LucideIcon,
} from 'lucide-react';
import { latestStep, PendingApprovalCard, type ApprovalDecider, type TaskStep } from '../agents/AgentConversation';
import type { RunningTask } from '../agents/runningTaskModel';
import { formatDuration } from '../agents/transcript';
import { IconButton } from '../cards/Card';
import { ConfirmDialog } from '../ConfirmDialog';
import { pollVisible } from '../ide/pollVisible';
import { APPROVAL_NOTICE, NOTICE_ACTIONS, approvalTimeLeft, noticeLabel, type AppNotice } from '../notices/contracts';
import { errorMessage } from '../errorMessage';
import { laneLabel } from '../settings/model';
import type { AgentEventEnvelope } from '../agents/events';
import type { AgendaTargetDto, AgentPendingApprovalDto } from '../types';
import { useNow } from '../useNow';

const STEP_POLL_MS = 2000;

const heading = (notice: AppNotice): string => notice.pullRequestTitle ?? notice.title;
const where = (notice: AppNotice): string => `${notice.repository} · ${laneLabel(notice.laneId)}`;

/**
 * One card of the Assistant: an icon, the title over where it lives, then its state, words or buttons on the right; a decision's
 * command, question and buttons go in a body under it. Buttons that only matter under the pointer replace the right side there.
 */
function CardRow({ icon, tone, tip, title, meta, below, right, hover, body, urgent = false, focused = false, id }: {
  icon: LucideIcon;
  tone?: 'accent' | 'warn' | 'danger';
  tip?: string;
  title: string;
  meta: string;
  below?: ReactNode;
  right?: ReactNode;
  hover?: ReactNode;
  body?: ReactNode;
  urgent?: boolean;
  /** Opened from its alert: outlined for a moment so the eye finds it. */
  focused?: boolean;
  id?: string;
}) {
  const Icon = icon;
  return (
    <div className={`assistant-card${urgent ? ' is-urgent' : ''}${focused ? ' is-opened' : ''}${hover === undefined ? '' : ' has-hover'}`} data-card={id}
      tabIndex={id === undefined ? undefined : -1}>
      <span className={`assistant-card__icon${tone === undefined ? '' : ` is-${tone}`}`} title={tip}><Icon size={16} aria-hidden="true" /></span>
      <div className="assistant-card__main">
        <div className="assistant-card__title" title={title}>{title}</div>
        <div className="assistant-card__meta" title={meta}>{meta}</div>
        {below}
      </div>
      <div className="assistant-card__side">
        {right !== undefined && <span className="assistant-card__right">{right}</span>}
        {hover !== undefined && <span className="assistant-card__hover">{hover}</span>}
      </div>
      {body !== undefined && <div className="assistant-card__body">{body}</div>}
    </div>
  );
}

function StatePill({ text, expiresAt, urgent }: { text: string; expiresAt?: string; urgent?: boolean }) {
  const now = useNow(expiresAt !== undefined);
  const left = expiresAt === undefined ? null : approvalTimeLeft(expiresAt, now);
  return <span className={`assistant-state${urgent === true || left?.urgent === true ? ' is-warn' : ''}`}>{text}{left !== null && ` · ${left.text} left`}</span>;
}

/** Stop, asked first, since it ends the turn's work. */
export function StopButton({ turnId, label = 'Stop…' }: { turnId: string; label?: string }) {
  const [asking, setAsking] = useState(false);
  const [state, setState] = useState<'idle' | 'stopping' | 'stopped'>('idle');
  const [error, setError] = useState<string | null>(null);
  if (state === 'stopped') return <span className="assistant-dim">Stopped</span>;
  const stop = () => {
    setState('stopping');
    setError(null);
    window.bridge.cancelAgentTurn(turnId).then(() => setState('stopped'))
      .catch((cause: unknown) => { setState('idle'); setError(errorMessage(cause)); }).finally(() => setAsking(false));
  };
  return <>
    <button type="button" className="assistant-button" disabled={state === 'stopping'} title={error ?? undefined} onClick={() => setAsking(true)}>{label}</button>
    {asking && (
      <ConfirmDialog title="Stop this turn?" confirmLabel="Stop turn" destructive busy={state === 'stopping'} onConfirm={stop} onCancel={() => setAsking(false)}>
        The agent stops where it is. Its changes so far stay in the task.
      </ConfirmDialog>
    )}
  </>;
}

/** A decision, answered in place: an approval or a question with the task's own buttons, read fresh, never from the notice's text. */
export function ApprovalCard({ notice, focused, conversation, onAnswered, onOpen, onClear }: {
  notice: AppNotice;
  focused: boolean;
  conversation: string;
  onAnswered: () => void;
  onOpen: () => void;
  onClear: () => void;
}) {
  const [approval, setApproval] = useState<AgentPendingApprovalDto | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const { taskId, turnId, reviewId } = notice;
  const approvalId = notice.id.slice(APPROVAL_NOTICE.length);
  const now = useNow(notice.expiresAt !== undefined);

  useEffect(() => {
    let live = true;
    if (turnId === null) {
      setApproval(null);
      return;
    }
    const read = reviewId === null ? window.bridge.readAgentPendingApprovals(taskId, turnId) : window.bridge.readAgentReviewApprovals(reviewId, taskId, turnId);
    void read.then(pending => { if (live) setApproval(pending.find(item => item.approvalId === approvalId) ?? null); })
      .catch(() => { if (live) setApproval(null); });
    return () => { live = false; };
  }, [approvalId, reviewId, taskId, turnId]);

  // Answered elsewhere, or no longer readable: the task itself shows what happened.
  if (approval === null) return <NoticeCard notice={notice} focused={focused} onOpen={onOpen} onClear={onClear} />;
  const decide: ApprovalDecider = (id, decision, answers) => {
    setError(null);
    const resolved = reviewId === null
      ? window.bridge.resolveAgentApproval(id, decision, undefined, answers)
      : window.bridge.resolveAgentReviewApproval(reviewId, id, decision, answers);
    // The answer stands whether or not its receipt is kept, so a lost receipt never brings the card back.
    void resolved.then(() => window.bridge.recordAssistantAnswer(conversation, id).catch((): void => undefined)).then(onAnswered)
      .catch((cause: unknown) => setError(errorMessage(cause)));
  };
  const question = notice.kind === 'QUESTION';
  const outside = approval !== undefined && !approval.sandboxed;
  const expiresAt = notice.expiresAt ?? approval?.expiresAt;
  return (
    <CardRow id={notice.id} focused={focused} icon={question ? MessageCircleQuestion : outside ? Hand : Terminal}
      tip={outside ? approval?.title : undefined} title={heading(notice)} meta={where(notice)}
      urgent={expiresAt !== undefined && approvalTimeLeft(expiresAt, now)?.urgent === true}
      right={<StatePill text={question ? 'Answer question' : outside ? 'Allow outside sandbox' : 'Allow command'} expiresAt={expiresAt} />}
      body={<>
        {approval === undefined ? <div className="assistant-dim">Loading…</div> : <PendingApprovalCard approval={approval} onDecide={decide} />}
        {error !== null && <p className="assistant-error" role="alert">{error}</p>}
      </>} />
  );
}

const NOTICE_ICONS: Partial<Record<AppNotice['kind'], LucideIcon>> = { QUESTION: MessageCircleQuestion, RECOVERY: Hourglass, STUCK: TimerOff, QUEUE_PAUSED: Clock };

/**
 * A decision the task's own page answers, such as a question asked at the end of a turn or a blocked task: why, then where to answer. A
 * turn that looks stuck can also be stopped here.
 */
export function NoticeCard({ notice, focused, onOpen, onClear }: { notice: AppNotice; focused: boolean; onOpen: () => void; onClear: () => void }) {
  const now = useNow(notice.expiresAt !== undefined);
  const warn = notice.kind === 'RECOVERY' || notice.kind === 'STUCK';
  return (
    <CardRow id={notice.id} focused={focused} icon={NOTICE_ICONS[notice.kind] ?? TriangleAlert} tone={warn ? 'warn' : undefined}
      title={heading(notice)} meta={where(notice)} urgent={notice.expiresAt !== undefined && approvalTimeLeft(notice.expiresAt, now)?.urgent === true}
      right={<StatePill text={noticeLabel(notice)} expiresAt={notice.expiresAt} urgent={warn} />}
      body={<>
        {notice.message !== '' && <div className="assistant-card__why">{notice.message}</div>}
        <div className="assistant-card__buttons">
          <button type="button" className="assistant-button" onClick={onOpen}>{NOTICE_ACTIONS[notice.kind]}</button>
          {notice.kind === 'STUCK' && notice.turnId !== null && <StopButton turnId={notice.turnId} label="Stop turn…" />}
          <span className="assistant-spacer" />
          <IconButton label={`Clear “${heading(notice)}”`} title="Clear" onClick={onClear}><X size={13} aria-hidden="true" /></IconButton>
        </div>
      </>} />
  );
}

const pullOf = (notice: AppNotice): { repo: string; number: number } | null => {
  const at = notice.pullRequest?.lastIndexOf('#') ?? -1;
  return notice.pullRequest == null || at < 1 ? null : { repo: notice.pullRequest.slice(0, at), number: Number(notice.pullRequest.slice(at + 1)) };
};

/**
 * A result, not an event: what is ready and its evidence as short counts, then the next action; a failure says so, and why, in one line.
 */
export function ResultCard({ notice, onShow, onOpen, onClear }: {
  notice: AppNotice;
  /** Opens an item, after the Assistant closes. */
  onShow: (target: AgendaTargetDto) => void;
  onOpen: () => void;
  onClear: () => void;
}) {
  const failed = notice.kind === 'FAILURE';
  const review = notice.reviewId !== null;
  const pull = pullOf(notice);
  const { changes } = notice;
  // A development result's pull request is where it is reviewed; the rest are read in their task. Opened either way, it has been seen,
  // so it leaves Finished, as opening it in its task does.
  const next = failed ? 'Open' : review ? 'Read' : pull !== null ? 'Review' : notice.kind === 'NEXT_WORK' ? NOTICE_ACTIONS.NEXT_WORK : 'Open';
  const act = () => {
    if (pull === null || failed || review) {
      onOpen();
      return;
    }
    onShow({ kind: 'PULL', repo: pull.repo, number: pull.number, workspaceId: notice.workspaceId, id: null, connectionId: null, cloudId: null });
    onClear();
  };
  const evidence = [
    // A development result's message is where its pull request stands, such as "Draft PR #58 · checks running".
    pull !== null && <span key="pr"><GitPullRequest size={13} aria-hidden="true" />{review ? notice.pullRequest : notice.message}</span>,
    changes !== null && changes.files > 0 && (
      <span key="lines" className="assistant-lines" aria-label={`${changes.files} files, ${changes.additions} added, ${changes.deletions} deleted`}>
        <span className="is-added">+{changes.additions}</span><span className="is-deleted">−{changes.deletions}</span>
      </span>
    ),
  ].filter(Boolean);
  return (
    <CardRow icon={failed ? CircleX : review ? Eye : pull !== null ? GitPullRequest : notice.kind === 'NEXT_WORK' ? ListTodo : CircleCheck}
      tone={failed ? 'danger' : undefined} title={review ? `Review · ${heading(notice)}` : heading(notice)} meta={where(notice)}
      below={failed ? <div className="assistant-card__fail">Failed · {notice.message}</div>
        : evidence.length > 0 ? <div className="assistant-card__evidence">{evidence}</div>
          : notice.message !== '' && <div className="assistant-card__evidence" title={notice.message}><span className="assistant-card__line">{notice.message}</span></div>}
      right={<>
        <button type="button" className="assistant-button" onClick={act}>{next}</button>
        <IconButton label={`Open “${heading(notice)}”`} title="Open" onClick={onOpen}><ExternalLink size={14} aria-hidden="true" /></IconButton>
        <span className="assistant-reveal"><IconButton label={`Clear “${heading(notice)}”`} title="Clear" onClick={onClear}><X size={13} aria-hidden="true" /></IconButton></span>
      </>} />
  );
}

type Live = { step: TaskStep | null; turnId: string | null };

/** The task's newest step and its current turn, from its latest history page. */
function useLive(taskId: string): Live {
  const [live, setLive] = useState<Live>({ step: null, turnId: null });
  useEffect(() => pollVisible(async () => {
    try {
      const events: AgentEventEnvelope[] = (await window.bridge.readAgentHistory(taskId)).events;
      setLive({ step: latestStep(events), turnId: events.findLast(event => event.turnId !== null)?.turnId ?? null });
    }
    catch { /* The last step stays through a failed read. */ }
  }, STEP_POLL_MS), [taskId]);
  return live;
}

/** A task at work: its current step and how long that has run; under the pointer, Open and Stop take their place. */
export function RunningCard({ task, onOpen }: { task: RunningTask; onOpen: () => void }) {
  const { step, turnId } = useLive(task.id);
  const now = useNow(step !== null && step.endedAt === null);
  const took = step === null ? null : (step.endedAt ?? now) - step.startedAt;
  return (
    <CardRow icon={LoaderCircle} tone="accent" title={task.title}
      meta={[task.repository, task.pullRequestNumber === null ? null : `#${task.pullRequestNumber}`].filter(Boolean).join(' · ')}
      right={step === null ? 'Working' : took !== null && took >= 1000 ? `${step.text} · ${formatDuration(took)}` : step.text}
      hover={<>
        <button type="button" className="assistant-button" onClick={onOpen}>Open</button>
        {task.kind === 'TASK' && turnId !== null && <StopButton turnId={turnId} />}
      </>} />
  );
}

/** All is fine: the running tasks folded to one line that opens them. */
export function RunningFold({ count, onOpen }: { count: number; onOpen: () => void }) {
  return (
    <button type="button" className="assistant-fold" title="Open Running" onClick={onOpen}>
      <LoaderCircle size={16} className="assistant-fold__icon" aria-hidden="true" />
      <span>{count} running · all fine</span>
    </button>
  );
}
