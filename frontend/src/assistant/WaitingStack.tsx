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
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode, type RefObject } from 'react';
import { CalendarCheck, Check, ChevronLeft, ChevronRight, CircleCheck, List, X } from 'lucide-react';
import { latestStep, PendingApprovalCard, type ApprovalDecider, type TaskStep } from '../agents/AgentConversation';
import { runningTaskRoute } from '../agents/RunningTasks';
import { RUNNING_TASK_TYPES, type RunningTask } from '../agents/runningTaskModel';
import { TaskTypeIcon } from '../agents/TaskListCards';
import type { TaskType } from '../agents/taskList';
import { formatDuration, projectTranscript } from '../agents/transcript';
import { IconButton } from '../cards/Card';
import TodayList, { openTarget } from '../home/TodayList';
import { clockTime, doneRatio, showsToday } from '../home/today';
import { useTodayAgenda } from '../home/useTodayAgenda';
import { pollVisible } from '../ide/pollVisible';
import { APPROVAL_NOTICE, NOTICE_ACTIONS, noticeLabel, type AppNotice } from '../notices/contracts';
import { remoteErrorMessage } from '../remoteError';
import { useNow } from '../useNow';
import RepoAvatar from '../RepoAvatar';
import { relativeTime } from '@bytequay/ui/relativeTime';
import type { AgentPendingApprovalDto } from '../types';
import { GROUPS, replyExcerpt, stackOf, waitingCards, type Group, type WaitingCard } from './model';

const LABELS: Record<Group, string> = { all: 'All', decision: 'Decision', running: 'Running', finished: 'Finished', today: 'Today' };
const RUNNING_POLL_MS = 5000;
const STEP_POLL_MS = 2000;

const heading = (notice: AppNotice): string => notice.pullRequestTitle ?? notice.title;
const cardTitle = (card: WaitingCard): string => 'notice' in card ? heading(card.notice) : card.task.title;
const cardMeta = (card: WaitingCard): string => 'notice' in card ? noticeLabel(card.notice) : relativeTime(card.at, { suffix: false });

/**
 * What waits on the user, under the composer: a pill for each group with something in it, then the group's top card with two
 * peeking behind it, or today's agenda. Tab moves between the pills and ← → page the cards.
 */
export default function WaitingStack({ notices, talk, folded, onFold, conversation, request, onRequestDone, onAnswered, onLeave, onOpenNotice, onClearNotice, visible, input }: {
  notices: AppNotice[];
  /** The conversation on screen, the history, or the line to continue, between the pills and the cards. */
  talk: ReactNode;
  /** Only the pills show, so the conversation has the room. */
  folded: boolean;
  onFold: (folded: boolean) => void;
  /** The conversation a card's answer leaves its receipt in. */
  conversation: string;
  /** The card an alert's Answer asked for, until it has been brought to the top. */
  request: { id: string } | null;
  onRequestDone: () => void;
  onAnswered: (id: string) => void;
  /** Closes the Assistant before something opens under it. */
  onLeave: () => void;
  onOpenNotice: (id: string) => void;
  onClearNotice: (id: string) => void;
  /** The notices drawn on screen, the only ones the user's next message may act on. */
  visible: RefObject<string[]>;
  input: RefObject<HTMLTextAreaElement | null>;
}) {
  const today = useTodayAgenda();
  const tasks = useRunningTasks();
  const [chosen, setChosen] = useState<Group>('all');
  const [topKey, setTopKey] = useState<string | null>(null);
  const [listed, setListed] = useState(false);
  const [opened, setOpened] = useState<string | null>(null);
  const root = useRef<HTMLDivElement>(null);

  const cards = waitingCards(notices, tasks);
  const agenda = today.agenda !== null && today.agenda.state !== 'NONE' && showsToday(today.agenda, 'global') ? today.agenda : null;
  const ratio = agenda === null ? null : doneRatio(agenda.items);
  const count = (group: Group): number => group === 'all' ? cards.length : group === 'today' ? Number(agenda !== null)
    : cards.filter(card => card.group === group).length;
  const groups = GROUPS.filter(group => count(group) > 0);
  // A group that empties, such as Decision after its last answer, hands over to the first one left.
  const group: Group | undefined = groups.includes(chosen) ? chosen : groups[0];
  const shown = group === 'all' ? cards : cards.filter(card => card.group === group);
  const at = Math.max(0, shown.findIndex(card => card.key === topKey));
  const drawn = folded || group === 'today' ? [] : listed ? shown : stackOf(shown, at);

  useLayoutEffect(() => {
    visible.current = drawn.flatMap(card => 'notice' in card ? [card.notice.id] : []);
  });

  useLayoutEffect(() => {
    if (request === null) return;
    onRequestDone();
    onFold(false);
    setChosen('all');
    setTopKey(request.id);
    setListed(false);
    setOpened(request.id);
  }, [request, onRequestDone, onFold]);

  useEffect(() => {
    if (opened === null) return;
    // The card itself, not one of its buttons, so a stray Enter cannot answer it.
    root.current?.querySelector<HTMLElement>(`[data-card="${CSS.escape(opened)}"]`)?.focus();
    // The outline holds for 2 s, then fades for 0.4 s (assistant.css).
    const timer = window.setTimeout(() => setOpened(null), 2400);
    return () => window.clearTimeout(timer);
  }, [opened]);

  if (group === undefined) return talk;

  const choose = (next: Group) => {
    setChosen(next);
    setTopKey(null);
    setListed(false);
  };
  // Beside the conversation, the open pill folds its group again.
  const pick = (each: Group) => {
    if (talk && !folded && each === group) return onFold(true);
    if (each !== group) choose(each);
    onFold(false);
  };
  const page = (step: number) => {
    if (shown.length > 1) setTopKey(shown[(at + step + shown.length) % shown.length].key);
  };
  const onKeyDown = (event: ReactKeyboardEvent) => {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const target = event.target instanceof HTMLElement ? event.target : null;
    const pill = target?.dataset.group as Group | undefined;
    // Folded, Tab moves on as anywhere else, and nothing pages.
    if (!folded && pill !== undefined && event.key === 'Tab') {
      event.preventDefault();
      const next = groups[(groups.indexOf(pill) + (event.shiftKey ? groups.length - 1 : 1)) % groups.length];
      choose(next);
      root.current?.querySelector<HTMLElement>(`[data-group="${next}"]`)?.focus();
    }
    else if (pill !== undefined && event.key === 'ArrowDown') {
      event.preventDefault();
      root.current?.querySelector<HTMLElement>('[data-card], .assistant-line, .today button')?.focus();
    }
    else if (!folded && (event.key === 'ArrowLeft' || event.key === 'ArrowRight') && !(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) {
      event.preventDefault();
      page(event.key === 'ArrowLeft' ? -1 : 1);
    }
    // Typing on a pill goes back to the composer, which takes the key.
    else if (pill !== undefined && event.key.length === 1 && event.key !== ' ') input.current?.focus();
  };

  const pager = shown.length > 1 && (
    <span className="assistant-pager">
      <IconButton label="Previous card" title="Previous card (←)" onClick={() => page(-1)}><ChevronLeft size={13} aria-hidden="true" /></IconButton>
      <span>{at + 1}/{shown.length}</span>
      <IconButton label="Next card" title="Next card (→)" onClick={() => page(1)}><ChevronRight size={13} aria-hidden="true" /></IconButton>
    </span>
  );

  return (
    <div ref={root} className="assistant-waiting" onKeyDown={onKeyDown}>
      <div className="assistant-pills" role="group" aria-label="Waiting on you">
        {groups.map(each => (
          <button key={each} type="button" data-group={each} className={`assistant-pill${!folded && each === group ? ' is-selected' : ''}`}
            aria-pressed={!folded && each === group} title={`${LABELS[each]} (Tab to move)`} onClick={() => pick(each)}>
            {each === 'decision' && <span className="assistant-dot" aria-hidden="true" />}
            {each === 'running' && <span className="assistant-spinner" aria-hidden="true" />}
            {each === 'finished' && <CircleCheck size={13} aria-hidden="true" />}
            {each === 'today' && <CalendarCheck size={13} aria-hidden="true" />}
            <span>{LABELS[each]}</span>
            {each !== 'today' ? <span className="assistant-pill__count">{count(each)}</span>
              : ratio !== null && ratio.total > 0 && <span className="assistant-pill__count">{ratio.done}/{ratio.total}</span>}
          </button>
        ))}
      </div>
      {talk}
      {folded ? null : group === 'today'
        ? <div className="assistant-sheet assistant-today"><TodayList today={today} scope="global" onOpen={target => { onLeave(); openTarget(target); }} /></div>
        : listed
          ? (
            <div className="assistant-sheet assistant-list">
              {shown.map((card, index) => (
                <button key={card.key} type="button" className={`assistant-line${index === at ? ' is-current' : ''}`} title={cardTitle(card)}
                  onClick={() => { setTopKey(card.key); setListed(false); }}>
                  <CardLine card={card} />
                </button>
              ))}
              <button type="button" className="assistant-list__back" onClick={() => setListed(false)}>Back to cards</button>
            </div>
          )
          : (
            <div className="assistant-stack">
              {drawn.map((card, index) => index === 0
                ? (
                  <div key={card.key} className={`assistant-card${opened === card.key ? ' is-opened' : ''}`} data-card={card.key} tabIndex={-1}>
                    {'task' in card
                      ? <TaskCard task={card.task} pager={pager} onOpen={() => { onLeave(); window.location.hash = runningTaskRoute(card.task); }} />
                      : card.notice.id.startsWith(APPROVAL_NOTICE)
                        ? <ApprovalCard notice={card.notice} pager={pager} conversation={conversation} onAnswered={() => onAnswered(card.notice.id)}
                          onOpen={() => onOpenNotice(card.notice.id)} onClear={() => onClearNotice(card.notice.id)} />
                        : <NoticeCard notice={card.notice} pager={pager} onOpen={() => onOpenNotice(card.notice.id)} onClear={() => onClearNotice(card.notice.id)} />}
                  </div>
                )
                : (
                  <button key={card.key} type="button" className={`assistant-peek is-${index}`} title={cardTitle(card)} onClick={() => setTopKey(card.key)}>
                    <CardLine card={card} />
                  </button>
                ))}
              {shown.length > 3 && (
                <button type="button" className="assistant-more" title="Show all as a list" onClick={() => setListed(true)}>
                  <span>{shown.length - 3} more</span><span className="assistant-more__dim">· View all</span><List size={12} aria-hidden="true" />
                </button>
              )}
            </div>
          )}
    </div>
  );
}

/** Running tasks in every repository while the Assistant is open, as the sidebar lists them. */
function useRunningTasks(): RunningTask[] {
  const [tasks, setTasks] = useState<RunningTask[]>([]);
  useEffect(() => pollVisible(async () => {
    try { setTasks(await window.bridge.listRunningTasks()); }
    catch { /* The last list stays through a failed read. */ }
  }, RUNNING_POLL_MS), []);
  return tasks;
}

/** One card on one line: a peek behind the top card, or a row of View all. */
function CardLine({ card }: { card: WaitingCard }) {
  return <>
    <span className="assistant-line__icon" aria-hidden="true">
      {card.group === 'decision' ? <span className="assistant-dot" /> : card.group === 'running' ? <span className="assistant-spinner" /> : <CircleCheck size={13} />}
    </span>
    <span className="assistant-line__text">{cardTitle(card)}</span>
    <span className="assistant-line__meta">{cardMeta(card)}</span>
  </>;
}

/** A card's top, the same for every kind: the task's kind icon, its title with the state chip after it, where it runs, then the time and pager. */
function CardTop({ type, title, chip, repository, number, branch, time, pager }: {
  type: TaskType;
  title: string;
  chip: ReactNode;
  repository: string;
  number: string | null;
  branch: string | null;
  time: string;
  pager: ReactNode;
}) {
  const [owner, repo] = repository.split('/');
  return (
    <div className="assistant-task">
      <span className="assistant-task__icon" aria-hidden="true"><TaskTypeIcon type={type} size={16} /></span>
      <div className="assistant-task__main">
        <div className="assistant-task__title">
          <span className="assistant-card__title" title={title}>{title}</span>
          {chip}
        </div>
        <div className="assistant-task__where">
          <span>{repository}</span>
          <RepoAvatar owner={owner} repo={repo ?? ''} size={14} radius={4} />
          {number !== null && <span className="assistant-task__number">{number}</span>}
          {branch !== null && <><span aria-hidden="true">·</span><span className="assistant-task__branch" title={branch}>{branch}</span></>}
        </div>
      </div>
      <span className="assistant-card__time">{time}</span>
      {pager}
    </div>
  );
}

function NoticeTop({ notice, pager }: { notice: AppNotice; pager: ReactNode }) {
  const done = notice.kind === 'TURN_FINISHED';
  const number = notice.pullRequest === null ? null : notice.pullRequest.slice(notice.pullRequest.lastIndexOf('#'));
  return <CardTop type={notice.reviewId === null ? 'dev' : 'review'} title={heading(notice)} repository={notice.repository} number={number}
    branch={notice.branch ?? null} time={clockTime(notice.occurredAt)} pager={pager}
    chip={<span className={`assistant-kind${done ? ' is-done' : notice.kind === 'APPROVAL' ? ' is-warn' : ''}`}>
      {done && <Check size={11} aria-hidden="true" />}{noticeLabel(notice)}</span>} />;
}

/** A finished task turn's reply, read once from the task's latest history; a review's turn says what it covered instead. */
function useReply(notice: AppNotice): string | null {
  const [reply, setReply] = useState<string | null>(null);
  const turnId = notice.kind === 'TURN_FINISHED' && notice.reviewId === null ? notice.turnId : null;
  useEffect(() => {
    if (turnId === null) return;
    let live = true;
    void window.bridge.readAgentHistory(notice.taskId).then(page => {
      const text = projectTranscript(page.events).find(turn => turn.turnId === turnId)?.answer?.text ?? '';
      if (live && text.trim() !== '') setReply(replyExcerpt(text));
    }).catch(() => { /* The notice's own line stays. */ });
    return () => { live = false; };
  }, [notice.taskId, turnId]);
  return reply;
}

function NoticeCard({ notice, pager, onOpen, onClear }: { notice: AppNotice; pager: ReactNode; onOpen: () => void; onClear: () => void }) {
  const { changes } = notice;
  const reply = useReply(notice);
  return <>
    <NoticeTop notice={notice} pager={pager} />
    <div className="assistant-card__muted assistant-task__body">
      {notice.kind === 'TURN_FINISHED' && changes !== null
        ? <>{changes.files} {changes.files === 1 ? 'file' : 'files'} <span className="assistant-lines" aria-label={`${changes.additions} added, ${changes.deletions} deleted`}>
          <span className="is-added">+{changes.additions}</span><span className="is-deleted">−{changes.deletions}</span></span></>
        : reply ?? notice.message}
    </div>
    <div className="assistant-card__actions assistant-task__actions">
      <button type="button" className="assistant-button" onClick={onOpen}>{NOTICE_ACTIONS[notice.kind]}</button>
      <span className="assistant-spacer" />
      <IconButton label={`Clear “${heading(notice)}”`} title="Clear" onClick={onClear}><X size={13} aria-hidden="true" /></IconButton>
    </div>
  </>;
}

/** An approval or a question with the task's own buttons; its data is the task's, read fresh, never the notice's text. */
function ApprovalCard({ notice, pager, conversation, onAnswered, onOpen, onClear }: {
  notice: AppNotice;
  pager: ReactNode;
  conversation: string;
  onAnswered: () => void;
  onOpen: () => void;
  onClear: () => void;
}) {
  const [approval, setApproval] = useState<AgentPendingApprovalDto | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const { taskId, turnId, reviewId } = notice;
  const approvalId = notice.id.slice(APPROVAL_NOTICE.length);

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
  if (approval === null) return <NoticeCard notice={notice} pager={pager} onOpen={onOpen} onClear={onClear} />;
  const decide: ApprovalDecider = (id, decision, answers) => {
    setError(null);
    const resolved = reviewId === null
      ? window.bridge.resolveAgentApproval(id, decision, undefined, answers)
      : window.bridge.resolveAgentReviewApproval(reviewId, id, decision, answers);
    // The answer stands whether or not its receipt is kept, so a lost receipt never brings the card back.
    void resolved.then(() => window.bridge.recordAssistantAnswer(conversation, id).catch((): void => undefined)).then(onAnswered)
      .catch((cause: unknown) => setError(remoteErrorMessage(cause)));
  };
  return <>
    <NoticeTop notice={notice} pager={pager} />
    <div className="assistant-task__body">
      {approval === undefined ? <div className="assistant-card__muted">Loading…</div> : <PendingApprovalCard approval={approval} onDecide={decide} />}
      {error !== null && <p className="assistant-error" role="alert">{error}</p>}
    </div>
  </>;
}

type Steps = { step: TaskStep; previous: TaskStep | null };

/** The task's newest step from its latest history page, and the one it replaced while that rolls away. */
function useLatestStep(taskId: string): Steps | null {
  const [steps, setSteps] = useState<Steps | null>(null);
  useEffect(() => pollVisible(async () => {
    try {
      const step = latestStep((await window.bridge.readAgentHistory(taskId)).events);
      if (step !== null) setSteps(old => old === null || old.step.id === step.id ? { step, previous: old?.previous ?? null } : { step, previous: old.step });
    }
    catch { /* The last step stays through a failed read. */ }
  }, STEP_POLL_MS), [taskId]);
  return steps;
}

function TaskCard({ task, pager, onOpen }: { task: RunningTask; pager: ReactNode; onOpen: () => void }) {
  const steps = useLatestStep(task.id);
  const now = useNow(steps?.step.endedAt === null);
  const took = steps === null ? 0 : (steps.step.endedAt ?? now) - steps.step.startedAt;
  return <>
    <CardTop type={RUNNING_TASK_TYPES[task.kind]} title={task.title} repository={task.repository}
      number={task.pullRequestNumber === null ? null : `#${task.pullRequestNumber}`} branch={task.branch}
      time={relativeTime(task.startedAt, { suffix: false })} pager={pager}
      chip={<span className="assistant-kind is-running"><span className="assistant-spinner" aria-hidden="true" />{task.kind === 'REVIEW' ? 'Reviewing' : 'Running'}</span>} />
    {steps !== null && (
      <div className="assistant-step" title={steps.step.text}>
        <span className="assistant-step__kind">{steps.step.kind}</span>
        <span className="assistant-step__roll">
          {steps.previous !== null && <span key={steps.previous.id} className="assistant-step__text is-out" aria-hidden="true">{steps.previous.text}</span>}
          <span key={steps.step.id} className={`assistant-step__text${steps.previous === null ? '' : ' is-in'}`}>{steps.step.text}</span>
        </span>
        {/* A running step counts up; a finished one keeps its time, unless it was too quick to matter. */}
        {(steps.step.endedAt === null || took >= 1000) && <span className="assistant-step__time">{formatDuration(took)}</span>}
      </div>
    )}
    <div className="assistant-card__actions assistant-task__actions">
      <button type="button" className="assistant-button" onClick={onOpen}>Open task</button>
    </div>
  </>;
}
