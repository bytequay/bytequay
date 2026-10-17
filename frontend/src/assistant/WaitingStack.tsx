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
import { CalendarCheck, ChevronLeft, ChevronRight, CircleCheck, List, X } from 'lucide-react';
import { PendingApprovalCard, type ApprovalDecider } from '../agents/AgentConversation';
import { runningTaskRoute } from '../agents/RunningTasks';
import type { RunningTask } from '../agents/runningTaskModel';
import { IconButton } from '../cards/Card';
import TodayList, { openTarget } from '../home/TodayList';
import { clockTime, doneRatio, showsToday } from '../home/today';
import { useTodayAgenda } from '../home/useTodayAgenda';
import { pollVisible } from '../ide/pollVisible';
import { APPROVAL_NOTICE, NOTICE_ACTIONS, noticeLabel, type AppNotice } from '../notices/contracts';
import { remoteErrorMessage } from '../pulls/PullOverview';
import { relativeTime } from '../relativeTime';
import type { AgentPendingApprovalDto } from '../types';
import { GROUPS, stackOf, waitingCards, type Group, type WaitingCard } from './model';

const LABELS: Record<Group, string> = { all: 'All', decision: 'Decision', running: 'Running', finished: 'Finished', today: 'Today' };
const RUNNING_POLL_MS = 5000;

const heading = (notice: AppNotice): string => notice.pullRequestTitle ?? notice.title;
const cardTitle = (card: WaitingCard): string => 'notice' in card ? heading(card.notice) : card.task.title;
const cardMeta = (card: WaitingCard): string => 'notice' in card ? noticeLabel(card.notice) : relativeTime(card.at, { suffix: false });

/**
 * What waits on the user, under the composer: a pill for each group with something in it, then the group's top card with two
 * peeking behind it, or today's agenda. Tab moves between the pills and ← → page the cards.
 */
export default function WaitingStack({ notices, talk, request, onRequestDone, onAnswered, onLeave, onOpenNotice, onClearNotice, visible, input }: {
  notices: AppNotice[];
  /** The day's conversation, between the pills and the cards. */
  talk: ReactNode;
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
  const drawn = group === 'today' ? [] : listed ? shown : stackOf(shown, at);

  useLayoutEffect(() => {
    visible.current = drawn.flatMap(card => 'notice' in card ? [card.notice.id] : []);
  });

  useLayoutEffect(() => {
    if (request === null) return;
    onRequestDone();
    setChosen('all');
    setTopKey(request.id);
    setListed(false);
    setOpened(request.id);
  }, [request, onRequestDone]);

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
  const page = (step: number) => {
    if (shown.length > 1) setTopKey(shown[(at + step + shown.length) % shown.length].key);
  };
  const onKeyDown = (event: ReactKeyboardEvent) => {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const target = event.target instanceof HTMLElement ? event.target : null;
    const pill = target?.dataset.group as Group | undefined;
    if (pill !== undefined && event.key === 'Tab') {
      event.preventDefault();
      const next = groups[(groups.indexOf(pill) + (event.shiftKey ? groups.length - 1 : 1)) % groups.length];
      choose(next);
      root.current?.querySelector<HTMLElement>(`[data-group="${next}"]`)?.focus();
    }
    else if (pill !== undefined && event.key === 'ArrowDown') {
      event.preventDefault();
      root.current?.querySelector<HTMLElement>('[data-card], .assistant-line, .today button')?.focus();
    }
    else if ((event.key === 'ArrowLeft' || event.key === 'ArrowRight') && !(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) {
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
          <button key={each} type="button" data-group={each} className={`assistant-pill${each === group ? ' is-selected' : ''}`} aria-pressed={each === group}
            title={`${LABELS[each]} (Tab to move)`} onClick={() => choose(each)}>
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
      {group === 'today'
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
                        ? <ApprovalCard notice={card.notice} pager={pager} onAnswered={() => onAnswered(card.notice.id)}
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

function CardHead({ kind, warn = false, time, pager }: { kind: string; warn?: boolean; time: string; pager: ReactNode }) {
  return (
    <div className="assistant-card__head">
      <span className={`assistant-kind${warn ? ' is-warn' : ''}`}>{kind}</span>
      <span className="assistant-spacer" />
      <span className="assistant-card__time">{time}</span>
      {pager}
    </div>
  );
}

function CardTitle({ title, where }: { title: string; where: string }) {
  return <div className="assistant-card__title" title={`${title} · ${where}`}>{title}<span className="assistant-card__where"> · {where}</span></div>;
}

function NoticeCard({ notice, pager, onOpen, onClear }: { notice: AppNotice; pager: ReactNode; onOpen: () => void; onClear: () => void }) {
  const { changes } = notice;
  return <>
    <CardHead kind={noticeLabel(notice)} time={clockTime(notice.occurredAt)} pager={pager} />
    <CardTitle title={heading(notice)} where={notice.repository} />
    {notice.pullRequest !== null && <div className="assistant-card__ref" title={notice.pullRequest}>{notice.pullRequest}</div>}
    <div className="assistant-card__muted">
      {notice.kind === 'TURN_FINISHED' && changes !== null ? `${changes.files} files · +${changes.additions} −${changes.deletions}` : notice.message}
    </div>
    <div className="assistant-card__actions">
      <button type="button" className="assistant-button" onClick={onOpen}>{NOTICE_ACTIONS[notice.kind]}</button>
      <span className="assistant-spacer" />
      <IconButton label={`Clear “${heading(notice)}”`} title="Clear" onClick={onClear}><X size={13} aria-hidden="true" /></IconButton>
    </div>
  </>;
}

/** An approval or a question with the task's own buttons; its data is the task's, read fresh, never the notice's text. */
function ApprovalCard({ notice, pager, onAnswered, onOpen, onClear }: {
  notice: AppNotice;
  pager: ReactNode;
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
    void resolved.then(() => window.bridge.recordAssistantAnswer(id).catch((): void => undefined)).then(onAnswered)
      .catch((cause: unknown) => setError(remoteErrorMessage(cause)));
  };
  return <>
    <CardHead kind={noticeLabel(notice)} warn={notice.kind === 'APPROVAL'} time={clockTime(notice.occurredAt)} pager={pager} />
    <CardTitle title={heading(notice)} where={notice.repository} />
    {approval === undefined ? <div className="assistant-card__muted">Loading…</div> : <PendingApprovalCard approval={approval} onDecide={decide} />}
    {error !== null && <p className="assistant-error" role="alert">{error}</p>}
  </>;
}

function TaskCard({ task, pager, onOpen }: { task: RunningTask; pager: ReactNode; onOpen: () => void }) {
  return <>
    <CardHead kind="Running" time={relativeTime(task.startedAt, { suffix: false })} pager={pager} />
    <CardTitle title={task.title} where={task.repository} />
    <div className="assistant-card__actions">
      <button type="button" className="assistant-button" onClick={onOpen}>Open task</button>
    </div>
  </>;
}
