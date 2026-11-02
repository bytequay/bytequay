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
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type MouseEvent as ReactMouseEvent, type ReactNode } from 'react';
import {
  AlarmClock, ArrowUpRight, Ban, CalendarCheck, CheckCheck, CircleCheck, CircleDot, CircleX, Clock, Flag, History, ListEnd, ListPlus, ListTodo, MessageCircleQuestion,
  MessageSquareReply, Play, RotateCw, Send, ShieldCheck, SquarePen, SquarePlus, TriangleAlert, X, type LucideIcon,
} from 'lucide-react';
import { relativeTime } from '@bytequay/ui/relativeTime';
import Composer from '../Composer';
import { renderMarkdown } from '../agents/markdown';
import { runningTaskRoute } from '../agents/RunningTasks';
import { IconButton, SourceIcon } from '../cards/Card';
import { MyWorkBoard } from '../home/MyWork';
import TodayList, { openTarget } from '../home/TodayList';
import { reloadHandled, type AttentionSource } from '../home/attention';
import { clockTime } from '../home/today';
import { useMyWork } from '../home/useMyWork';
import type { WorkLane } from '../home/work';
import { requestPullComment } from '../pulls/PullOverview';
import { APPROVAL_NOTICE, type AppNotice } from '../notices/contracts';
import { remoteErrorMessage } from '../remoteError';
import { settingsRoute, type SettingsSection } from '../settings/routes';
import type { AgendaTargetDto, AssistantItemDto, AssistantReceiptDto, AssistantRowDto, AssistantSummaryDto } from '../types';
import AssistantList from './AssistantList';
import { ApprovalCard, NoticeCard, ResultCard, RunningCard, RunningFold, StopButton } from './Cards';
import DraftCard from './DraftCard';
import {
  SUGGESTION, arrivedOpen, createdDrafts, decisionOrder, linkRefs, pageRoute, refFromHref, refLabel, takesSuggestion, type AssistantRequest, type AssistantView, type Pill,
  type WaitingCard,
} from './model';
import AssistantPills, { useWaiting } from './Pills';
import { approvePull, closePull, mergePull, type PullAction } from './pullActions';
import { useAssistant } from './useAssistant';
import '../css/assistant.css';

/** How long the Assistant stays closed before it opens on a new conversation. */
const BREAK_MS = 15 * 60_000;

function setHash(hash: string) {
  window.location.hash = hash;
}

/**
 * The Assistant over every page, opened with ⌘J, from Home, or over other apps: the composer and its pills on top, then one view under
 * them, the conversation until a pill opens another. Each topic gets its own conversation: New starts one, after a break the Assistant
 * opens on a new one, and the history or the line to continue goes back to an earlier one. It floats, and nothing paints over a native
 * view, so the app leaves a web page before it opens and the editor hides under any dialog.
 */
export default function AssistantPanel({ open, covered = false, chatEnabled = true, notices, request, onRequestDone, onScreen, onClose, onPage = setHash, onOpenDetail, onOpened }: {
  open: boolean;
  /** A detail opened from here keeps this view mounted until the user returns. */
  covered?: boolean;
  chatEnabled?: boolean;
  notices: AppNotice[];
  /** Where to open, such as a pill from Home or an alert's card; a new object each time. */
  request: AssistantRequest | null;
  /** The request was taken; its holder lets it go, so a remount never acts on it again. */
  onRequestDone?: () => void;
  /** The ref of what the user is looking at, sent with a message unless they remove it. */
  onScreen: string | null;
  onClose: () => void;
  /** Opens a page's route with nothing left over it. */
  onPage?: (hash: string) => void;
  /** The main window can cover this panel with a detail; the floating window hands off and closes. */
  onOpenDetail?: (target: AgendaTargetDto) => void;
  /** After opening a page or detail, where the user goes on. */
  onOpened?: () => void;
}) {
  const [conversation, setConversation] = useState<string>(() => crypto.randomUUID());
  const [history, setHistory] = useState(false);
  const [view, setView] = useState<AssistantView>('conversation');
  const [lane, setLane] = useState<WorkLane | null>(null);
  const [card, setCard] = useState<string | null>(null);
  const [outgoing, setOutgoing] = useState<string | null>(null);
  const { view: chat, conversations, error, sending, busy, send, stop, retry, refresh } = useAssistant(open, conversation);
  const [draft, setDraft] = useState('');
  const [hiddenScreen, setHiddenScreen] = useState<string | null>(null);
  const [answered, setAnswered] = useState<ReadonlySet<string>>(() => new Set());
  const [failure, setFailure] = useState<string | null>(null);
  const closedAt = useRef(Date.now());
  const replying = useRef(false);
  const input = useRef<HTMLTextAreaElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const visible = useRef<string[]>([]);
  const returnFocus = useRef<Element | null>(null);
  const detailReturnFocus = useRef<Element | null>(null);
  const openedAt = useRef(0);
  const followed = useRef(new Set<string>());
  const items = chat?.items ?? [];
  const running = chat?.running ?? false;
  // A card answered here leaves at once; the notice itself goes on the host's next read.
  const waiting = notices.filter(notice => !answered.has(notice.id));
  const screen = onScreen !== null && onScreen !== hiddenScreen ? onScreen : null;
  const talkOpen = items.length > 0;
  // Messages sent while a reply was written wait in line on the backend, each answered in turn.
  const waitingIds = new Set(chat?.waiting ?? []);

  useEffect(() => { replying.current = running; }, [running]);

  useLayoutEffect(() => {
    if (open) {
      openedAt.current = Date.now();
      returnFocus.current = document.activeElement;
      // A reply still being written keeps its conversation, which the user comes back to read.
      if (Date.now() - closedAt.current >= BREAK_MS && !replying.current) setConversation(crypto.randomUUID());
      input.current?.focus();
      return;
    }
    setView('conversation');
    setLane(null);
    setHistory(false);
    setFailure(null);
    detailReturnFocus.current = null;
    closedAt.current = Date.now();
    const previous = returnFocus.current;
    returnFocus.current = null;
    const active = document.activeElement;
    if (previous instanceof HTMLElement && previous.isConnected && (active === null || active === document.body)) previous.focus();
  }, [open]);

  useLayoutEffect(() => {
    if (covered) return;
    openedAt.current = Date.now();
    const previous = detailReturnFocus.current;
    detailReturnFocus.current = null;
    if (previous instanceof HTMLElement && previous.isConnected) previous.focus({ preventScroll: true });
  }, [covered]);

  // Runs after the open above, so a message from Home goes to the conversation the Assistant opens on.
  useLayoutEffect(() => {
    if (request === null) return;
    if (request.fresh === true) setConversation(crypto.randomUUID());
    setHistory(request.history === true);
    setView(request.view ?? 'conversation');
    setLane(request.lane ?? null);
    setCard(request.card ?? null);
    if (request.message !== undefined && request.message.trim() !== '') setOutgoing(request.message.trim());
    onRequestDone?.();
  }, [request, onRequestDone]);

  useEffect(() => setAnswered(current => {
    const live = current.size === 0 ? current : new Set([...current].filter(id => notices.some(notice => notice.id === id)));
    return live.size === current.size ? current : live;
  }), [notices]);

  useLayoutEffect(() => {
    const node = scroller.current;
    if (node !== null && view === 'conversation') node.scrollTop = node.scrollHeight;
  }, [open, view, talkOpen, items.length, running]);

  // The Assistant closes first, so nothing opens under it, and focus stays with what opened instead of going back.
  const leave = useCallback((go: () => void) => {
    returnFocus.current = null;
    onClose();
    go();
    onOpened?.();
  }, [onClose, onOpened]);

  const show = useCallback((target: AgendaTargetDto) => {
    if (onOpenDetail === undefined || target.kind === 'TASK' || target.kind === 'REVIEW') { leave(() => openTarget(target)); return; }
    detailReturnFocus.current = document.activeElement;
    onOpenDetail(target);
    onOpened?.();
  }, [leave, onOpenDetail, onOpened]);

  // Where a receipt opens, a page before an item.
  const openReceipt = useCallback((receipt: AssistantReceiptDto) => {
    const hash = receipt.page === null ? null : pageRoute(receipt.page);
    if (hash === null && receipt.target === null) return;
    if (hash !== null) leave(() => onPage(hash));
    else if (receipt.target !== null) show(receipt.target);
  }, [leave, onPage, show]);

  // An open the assistant made while the user watched goes there once; read later, its receipt only offers Open.
  useEffect(() => {
    if (!open || covered) return;
    const arrived = arrivedOpen(chat?.items ?? [], openedAt.current, followed.current);
    if (arrived === null || arrived.receipt === null) return;
    followed.current.add(arrived.id);
    openReceipt(arrived.receipt);
  }, [open, covered, chat, openReceipt]);

  // The lists read marks once at start, so a mark the assistant made reaches them when its receipt does.
  const marked = items.filter(item => item.receipt?.outcome === 'DONE' && (item.receipt.operation === 'ACKNOWLEDGE' || item.receipt.operation === 'SNOOZE')).length;
  useEffect(() => { if (marked > 0) void reloadHandled().catch(() => {}); }, [marked]);
  // Today's agenda reads on open and focus, so one the assistant started reaches Today when its receipt does.
  const making = items.filter(item => item.receipt?.outcome === 'DONE' && item.receipt.operation === 'MAKE_AGENDA').length;
  useEffect(() => { if (making > 0) window.dispatchEvent(new Event('bytequay:agenda-changed')); }, [making]);

  // Sent at once, even while a reply is written, with the cards and page on screen now; the backend answers it in turn.
  const submitText = (text: string) => {
    if (!chatEnabled || text === '') return;
    void send(text, visible.current, screen).then(sent => {
      if (!sent) {
        setDraft(current => current.trim() === '' ? text : current);
        return;
      }
      setHistory(false);
      setView('conversation');
      setDraft(current => current.trim() === text ? '' : current);
      setHiddenScreen(null);
    });
  };
  const submitRef = useRef(submitText);
  useLayoutEffect(() => { submitRef.current = submitText; });

  useEffect(() => {
    if (outgoing === null) return;
    setOutgoing(null);
    submitRef.current(outgoing);
  }, [outgoing]);

  if (!open) return null;

  const choose = (id: string) => {
    setConversation(id);
    setHistory(false);
    setView('conversation');
    input.current?.focus();
  };
  const startNew = () => choose(crypto.randomUUID());
  const openSettings = (section: SettingsSection) => leave(() => onPage(settingsRoute(section)));
  // A receipt a card's button left after a failed reply, or a task's report, does not end that exchange, so Retry stays on the failure.
  const lastExchange = items.findLast(item => item.kind !== 'RECEIPT' && item.kind !== 'REPORT')?.id;
  // A refusal in the latest exchange points down at a card's buttons, so the decisions show under the conversation.
  const pointsDown = items.slice(items.findLastIndex(item => item.kind === 'USER')).some(item => item.receipt?.fix === 'BUTTONS');
  const targets: Record<string, AgendaTargetDto> = {};
  for (const item of items) Object.assign(targets, item.targets);
  const drafts = createdDrafts(items);
  // Reply links carry refs, so they open where the item's work lives instead of as a web page.
  const openLink = (event: ReactMouseEvent) => {
    const anchor = event.target instanceof Element ? event.target.closest('a') : null;
    const ref = anchor === null ? null : refFromHref(anchor.getAttribute('href') ?? '');
    if (ref === null) return;
    event.preventDefault();
    if (ref in targets) show(targets[ref]);
  };
  // The user's own click on a task's card; the list shows it completed once the day is read again, and the card says why it could not.
  const completeTask = (target: AgendaTargetDto): Promise<void> => {
    if (target.id === null || target.workspaceId === null) return Promise.resolve();
    return window.bridge.completeAgentTask(target.id, target.workspaceId).then(refresh);
  };
  // The user's own click on a pull request's card: Comment opens it at its comment box, and the others act as its page's controls do.
  const actOnPull = (target: AgendaTargetDto, action: PullAction, head: string | null): Promise<void> => {
    if (target.repo === null || target.number === null) return Promise.resolve();
    if (action === 'comment') {
      requestPullComment(target.repo, target.number);
      show(target);
      return Promise.resolve();
    }
    // A review draft the user wrote goes out from the pull request's own review, never from a one-click button.
    if (action === 'approve') return approvePull(target.repo, target.number, head, target.workspaceId ?? undefined).then(sent => sent ? refresh() : show(target));
    const acted = action === 'merge' ? mergePull(target.repo, target.number, head, target.workspaceId ?? undefined) : closePull(target.repo, target.number, target.workspaceId ?? undefined);
    return acted.then(refresh);
  };
  const noticeAction = (action: Promise<void>, message: string, then?: () => void) => {
    setFailure(null);
    void action.then(then).catch(() => setFailure(message));
  };
  const answeredNotice = (id: string) => {
    setAnswered(current => new Set(current).add(id));
    refresh();
  };
  const openNotice = (id: string) => noticeAction(window.bridge.openAppNotice(id), 'Unable to open this task.', onClose);
  const clearNotice = (id: string) => noticeAction(window.bridge.removeAppNotice(id), 'Unable to clear this notice.');
  // Only a task's latest report takes a reply; the earlier ones are its history.
  const latestReports = new Map<string, string>();
  for (const item of items) {
    const task = item.report?.target.id;
    if (task != null) latestReports.set(task, item.id);
  }
  // The user's words go straight to the report's task, with no model between; the receipt comes with the next read.
  const replyOnReport = (itemId: string) => (text: string, key: string): Promise<boolean> =>
    window.bridge.replyFromAssistant(conversation, itemId, text, key).then(() => {
      setFailure(null);
      refresh();
      return true;
    }, (cause: unknown) => {
      setFailure(remoteErrorMessage(cause));
      return false;
    });
  const reportEntry = (item: AssistantItemDto) => {
    const notice = waiting.find(each => each.id === item.report?.noticeId);
    // Not while the task waits on an approval, which its card's buttons answer.
    const replyable = latestReports.get(item.report?.target.id ?? '') === item.id && notice?.id.startsWith(APPROVAL_NOTICE) !== true;
    return <Report key={item.id} item={item} notice={notice} conversation={conversation} onAnswered={answeredNotice} onOpenNotice={openNotice}
      onClearNotice={clearNotice} onShow={show} onReply={replyable ? replyOnReport(item.id) : undefined} />;
  };
  const pick = (pill: Pill) => {
    setHistory(false);
    setLane(null);
    // The open pill again goes back to the conversation.
    setView(pill === view ? 'conversation' : pill);
  };

  // The newest conversation that is not on screen, to continue from a new one with one click.
  const latest = conversations.find(each => each.id !== conversation);
  const talk = history ? <Conversations conversations={conversations} current={conversation} onChoose={choose} />
    // Not while a chosen conversation is still being read, so the line does not flash before it.
    : !talkOpen ? chat !== null && latest !== undefined && <Conversations conversations={[latest]} current={conversation} onChoose={choose} />
    : (
      <div className="assistant-talk" onClickCapture={openLink} onAuxClickCapture={openLink}>
        {items.map(item => item.kind === 'RECEIPT'
          ? <Receipt key={item.id} item={item} onOpen={openReceipt} onSettings={openSettings} />
          : item.kind === 'REPORT'
            ? reportEntry(item)
            : <Entry key={item.id} item={item} waiting={waitingIds.has(item.id)} rows={chat?.rows ?? {}} drafts={drafts} model={chat?.model ?? null}
              retryable={chatEnabled && !running && item.id === lastExchange} onRetry={retry} onSettings={openSettings} onOpen={show} onComplete={completeTask}
              onPull={actOnPull} onRefresh={refresh} />)}
        {running && <p className="assistant-thinking" role="status">Thinking<span className="assistant-caret" aria-hidden="true" /></p>}
      </div>
    );

  const composer = chatEnabled ? (
    <Composer inputRef={input} aria-label="Message the Assistant" placeholder={SUGGESTION} value={draft} maxLength={16 * 1024}
      onChange={event => setDraft(event.target.value)} busy={sending} onSubmit={() => submitText(draft.trim())}
      onKeyDown={event => {
        if (takesSuggestion(event, draft)) {
          event.preventDefault();
          setDraft(SUGGESTION);
          return;
        }
        // With words in it, Tab moves on to the pills.
        if (event.key !== 'Tab' || event.shiftKey || event.altKey || event.ctrlKey || event.metaKey) return;
        const pills = event.currentTarget.closest('.assistant-float')?.querySelector<HTMLElement>('.assistant-pill.is-open, .assistant-pill:not(.is-empty)');
        if (!pills) return;
        event.preventDefault();
        pills.focus();
      }}
      header={waitingIds.size > 0 && <div className="assistant-waiting-line"><Clock size={12} aria-hidden="true" />{waitingIds.size} waiting · answers after the current reply</div>}
      notice={(error ?? failure) !== null && <p className="assistant-error" role="alert">{error ?? failure}</p>}
      submitEnabled={draft.trim() !== '' && !sending} showSubmit={!running || draft.trim() !== ''}
      submitLabel={busy ? 'Send after the current reply' : 'Send to the Assistant'} submitTitle={busy ? 'Answers after the current reply (Enter)' : 'Send (Enter)'}
      trailingActions={running && draft.trim() === '' && (
        <button type="button" className="agt-send is-stop" aria-label="Stop the reply" title="Stop the reply" onClick={stop}>
          <span className="agt-stop-square" aria-hidden="true" />
        </button>
      )}>
      {screen !== null && (
        <span className="assistant-chip" title="The item on the current page. Remove to ask without it.">
          <ScreenIcon screen={screen} />
          <span className="assistant-chip__name">On screen</span>
          <span className="assistant-chip__ref">{refLabel(screen)}</span>
          <button type="button" aria-label="Leave what's on screen out of this message" title="Leave it out"
            onClick={() => setHiddenScreen(screen)}><X size={11} aria-hidden="true" /></button>
        </span>
      )}
      <span className="assistant-spacer" />
      {chat?.model && <span className="assistant-model" title={`Answers with ${chat.model}`}>{chat.model}</span>}
      {talkOpen && <IconButton label="New conversation" title="New conversation (⌘N)" onClick={startNew}><SquarePen size={15} aria-hidden="true" /></IconButton>}
      {latest !== undefined && (
        <IconButton label="Conversations" title="History" tone="active" pressed={history} onClick={() => setHistory(shown => !shown)}><History size={15} aria-hidden="true" /></IconButton>
      )}
    </Composer>
  ) : (
    <div className="assistant-off">
      <p>Task notices and your conversation are still available.</p>
      <button type="button" className="assistant-link" onClick={() => openSettings('features')}>Set up Assistant in Settings</button>
      {running && <button type="button" className="assistant-link" onClick={stop}>Stop the reply</button>}
    </div>
  );

  return (
    <div className="assistant-panel" hidden={covered} inert={covered}>
      <button type="button" className="assistant-backdrop" tabIndex={-1} aria-label="Close the Assistant" onClick={onClose} />
      <section className={`assistant-float${busy || sending ? ' is-replying' : ''}${view === 'mywork' && !history ? ' is-wide' : ''}`} role="dialog" aria-label="Assistant"
        onKeyDown={event => {
          if (event.defaultPrevented) return;
          if (event.key === 'Escape') {
            event.preventDefault();
            if (history) setHistory(false);
            else onClose();
          }
          else if (chatEnabled && event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 'n') {
            event.preventDefault();
            startNew();
          }
        }}>
        <AssistantViews notices={waiting} view={history ? 'conversation' : view} lane={lane} card={card} talk={talk} pointsDown={pointsDown}
          composer={composer} scroller={scroller} visible={visible} conversation={conversation} onPick={pick} onView={setView} onLane={setLane}
          onCardShown={() => setCard(null)} onLeave={leave} onPage={onPage} onShow={show} onAnswered={answeredNotice} onOpenNotice={openNotice} onClearNotice={clearNotice} />
      </section>
    </div>
  );
}

/**
 * The pills and the view they open, mounted only while the Assistant is open, since it reads running tasks, today's agenda and the
 * viewer's work: the composer and pills on top, one scroll under them.
 */
function AssistantViews({ notices, view, lane, card, talk, pointsDown, composer, scroller, visible, conversation, onPick, onView, onLane, onCardShown, onLeave, onPage, onShow,
  onAnswered, onOpenNotice, onClearNotice }: {
  notices: AppNotice[];
  view: AssistantView;
  lane: WorkLane | null;
  /** The card an alert's Answer asked for, until it has been shown. */
  card: string | null;
  talk: ReactNode;
  pointsDown: boolean;
  composer: ReactNode;
  scroller: React.RefObject<HTMLDivElement | null>;
  /** The notices drawn on screen, the only ones the user's next message may act on. */
  visible: React.RefObject<string[]>;
  conversation: string;
  onPick: (pill: Pill) => void;
  onView: (view: AssistantView) => void;
  onLane: (lane: WorkLane | null) => void;
  onCardShown: () => void;
  onLeave: (go: () => void) => void;
  onPage: (hash: string) => void;
  onShow: (target: AgendaTargetDto) => void;
  onAnswered: (id: string) => void;
  onOpenNotice: (id: string) => void;
  onClearNotice: (id: string) => void;
}) {
  const waiting = useWaiting(notices);
  const { work } = useMyWork();
  const [opened, setOpened] = useState<string | null>(null);
  const { cards } = waiting;
  const decisions = decisionOrder(cards);
  const running = cards.filter(each => each.group === 'running');
  const finished = cards.filter(each => each.group === 'finished');
  const showsDecisions = view === 'decision' || view === 'all' || view === 'conversation' && pointsDown;
  // Not before the first read of running tasks, or a view opened from Home would close before its cards arrive.
  const emptied = waiting.ready && (view === 'decision' && decisions.length === 0 || view === 'running' && running.length === 0 || view === 'finished' && finished.length === 0
    || view === 'all' && cards.length === 0);

  // A view that empties, such as Decision after its last answer, hands back to the conversation.
  useEffect(() => { if (emptied) onView('conversation'); }, [emptied, onView]);

  useLayoutEffect(() => {
    visible.current = showsDecisions ? decisions.flatMap(each => 'notice' in each ? [each.notice.id] : []) : [];
  });

  useEffect(() => {
    if (card === null) return;
    onCardShown();
    setOpened(card);
  }, [card, onCardShown]);

  useEffect(() => {
    if (opened === null) return;
    // The card itself, not one of its buttons, so a stray Enter cannot answer it.
    const node = scroller.current?.querySelector<HTMLElement>(`[data-card="${CSS.escape(opened)}"]`);
    node?.focus();
    node?.scrollIntoView({ block: 'nearest' });
    // The outline holds for 2 s, then fades for 0.4 s (assistant.css).
    const timer = window.setTimeout(() => setOpened(null), 2400);
    return () => window.clearTimeout(timer);
  }, [opened, scroller]);

  const decisionCard = (each: WaitingCard) => {
    if (!('notice' in each)) return null;
    const { notice } = each;
    return notice.id.startsWith(APPROVAL_NOTICE)
      ? <ApprovalCard key={each.key} notice={notice} focused={opened === notice.id} conversation={conversation} onAnswered={() => onAnswered(notice.id)}
        onOpen={() => onOpenNotice(notice.id)} onClear={() => onClearNotice(notice.id)} />
      : <NoticeCard key={each.key} notice={notice} focused={opened === notice.id} onOpen={() => onOpenNotice(notice.id)} onClear={() => onClearNotice(notice.id)} />;
  };
  const resultCard = (each: WaitingCard) => 'notice' in each
    && <ResultCard key={each.key} notice={each.notice} onShow={onShow} onOpen={() => onOpenNotice(each.notice.id)}
      onClear={() => onClearNotice(each.notice.id)} />;
  const runningCard = (each: WaitingCard) => 'task' in each && <RunningCard key={each.key} task={each.task} onOpen={() => onLeave(() => onPage(runningTaskRoute(each.task)))} />;
  const section = (label: string) => <div className="assistant-section">{label}</div>;

  let body: ReactNode;
  switch (view) {
    case 'conversation':
      body = talk || pointsDown ? <>{talk}{pointsDown && decisions.map(decisionCard)}</> : null;
      break;
    case 'decision':
      body = decisions.map(decisionCard);
      break;
    case 'running':
      body = running.map(runningCard);
      break;
    case 'finished':
      body = finished.map(resultCard);
      break;
    case 'all':
      body = <>
        {decisions.length > 0 && <>{section('Decision')}{decisions.map(decisionCard)}</>}
        {running.length > 0 && <>{section('Running')}<RunningFold count={running.length} onOpen={() => onView('running')} /></>}
        {finished.length > 0 && <>{section('Finished')}{finished.map(resultCard)}</>}
      </>;
      break;
    case 'today':
      body = <div className="assistant-today"><TodayList today={waiting.today} scope="global" onOpen={onShow} /></div>;
      break;
    case 'mywork':
      body = <MyWorkBoard items={work} lane={lane} onLane={onLane} onOpen={onShow} onDecision={() => onView('decision')}
        onAllWork={() => onLeave(() => onPage('#/work'))} />;
      break;
  }

  return <>
    <div className="assistant-head">
      {composer}
      <AssistantPills waiting={waiting} needsYou={work.some(item => item.lane === 'needs')} open={view === 'conversation' ? null : view} onPick={onPick} />
    </div>
    {body !== null && <div ref={scroller} className="assistant-body">{body}</div>}
  </>;
}

/** Conversations as one-line rows, titled by their first message: the history, or the newest one to continue. */
function Conversations({ conversations, current, onChoose }: { conversations: AssistantSummaryDto[]; current: string; onChoose: (id: string) => void }) {
  return (
    <div className="assistant-list" role="group" aria-label="Conversations">
      {conversations.map(each => (
        <button key={each.id} type="button" className={`assistant-line${each.id === current ? ' is-current' : ''}`} title={each.title}
          aria-current={each.id === current} onClick={() => onChoose(each.id)}>
          <span className="assistant-line__icon" aria-hidden="true">{each.running ? <span className="assistant-spinner" /> : <History size={13} />}</span>
          <span className="assistant-line__text">{each.title}</span>
          <span className="assistant-line__meta">{relativeTime(each.updatedAt, { suffix: false })}</span>
        </button>
      ))}
    </div>
  );
}

const SCREEN_SOURCES: Record<string, AttentionSource> = { pr: 'pr', issue: 'issue', jira: 'jira', task: 'task', review: 'task' };

/** What kind of item is on screen, by its source's icon; a work item has no source and takes a list's work item icon. */
function ScreenIcon({ screen }: { screen: string }) {
  const source = SCREEN_SOURCES[screen.slice(0, screen.indexOf(':'))];
  return source === undefined ? <ListTodo size={12} aria-hidden="true" /> : <SourceIcon source={source} size={12} />;
}

function Entry({ item, waiting, rows, drafts, model, retryable, onRetry, onSettings, onOpen, onComplete, onPull, onRefresh }: {
  item: AssistantItemDto;
  /** A message still in line behind the reply being written. */
  waiting: boolean;
  rows: Record<string, AssistantRowDto>;
  /** The work items of the drafts the user already created. */
  drafts: ReadonlySet<string>;
  /** The model the assistant answers with, as the composer names it. */
  model: string | null;
  retryable: boolean;
  onRetry: () => void;
  onSettings: (section: SettingsSection) => void;
  onOpen: (target: AgendaTargetDto) => void;
  onComplete: (target: AgendaTargetDto) => Promise<void>;
  onPull: (target: AgendaTargetDto, action: PullAction, head: string | null) => Promise<void>;
  onRefresh: () => void;
}) {
  if (item.kind === 'USER') {
    return <>
      <div className="assistant-time">{clockTime(item.at)}</div>
      <div className={`agt-user${waiting ? ' is-waiting' : ''}`}><div className="agt-user__bubble" title={`Sent at ${clockTime(item.at)}`}>{item.text}</div></div>
      {waiting && <div className="assistant-waiting-note"><Clock size={12} aria-hidden="true" />Answers after the current reply</div>}
    </>;
  }
  if (item.kind === 'REPLY') return <div className="assistant-reply">{renderMarkdown(linkRefs(item.text, item.targets))}</div>;
  if (item.kind === 'LIST') return <AssistantList item={item} rows={rows} onOpen={onOpen} onComplete={onComplete} onPull={onPull} />;
  if (item.kind === 'DRAFT') return <DraftCard item={item} created={item.draft !== null && drafts.has(item.draft.workItemId)} onCreated={onRefresh} onOpen={onOpen} />;
  return (
    <div className="assistant-failure" role="status"
      title={item.notReady ? `${model ?? 'The Assistant'} answers through your Claude account. Sign in from Settings → Models.` : undefined}>
      <TriangleAlert size={14} aria-hidden="true" />
      <span>{item.text}</span>
      <span className="assistant-spacer" />
      {item.notReady && <button type="button" className="assistant-button" onClick={() => onSettings('models')}>Open Settings</button>}
      {retryable && <button type="button" className="assistant-button" onClick={onRetry}><RotateCw size={12} aria-hidden="true" />Retry</button>}
    </div>
  );
}

/**
 * What a task the conversation started or sent words to raised: while its notice lasts, that notice's own card, answered or opened in
 * place; after, one line that opens the task. A task's latest report takes a reply.
 */
function Report({ item, notice, conversation, onAnswered, onOpenNotice, onClearNotice, onShow, onReply }: {
  item: AssistantItemDto;
  notice: AppNotice | undefined;
  conversation: string;
  onAnswered: (id: string) => void;
  onOpenNotice: (id: string) => void;
  onClearNotice: (id: string) => void;
  onShow: (target: AgendaTargetDto) => void;
  onReply?: (text: string, key: string) => Promise<boolean>;
}) {
  const report = item.report;
  if (report === null) return null;
  let entry: ReactNode;
  if (notice !== undefined) {
    const open = () => onOpenNotice(notice.id);
    const clear = () => onClearNotice(notice.id);
    entry = report.state !== 'DECISION' ? <ResultCard notice={notice} onShow={onShow} onOpen={open} onClear={clear} />
      : notice.id.startsWith(APPROVAL_NOTICE)
        ? <ApprovalCard notice={notice} focused={false} conversation={conversation} onAnswered={() => onAnswered(notice.id)} onOpen={open} onClear={clear} />
        : <NoticeCard notice={notice} focused={false} onOpen={open} onClear={clear} />;
  }
  else {
    const Icon = report.state === 'FAILED' ? CircleX : report.state === 'READY' ? CircleCheck : MessageCircleQuestion;
    entry = (
      <div className="assistant-receipt" title={item.text}>
        <Icon className="assistant-receipt__icon" size={14} aria-hidden="true" />
        <div className="assistant-receipt__text"><span className="assistant-receipt__lead">{item.text}</span></div>
        <button type="button" className="assistant-button" onClick={() => onShow(report.target)}>Open</button>
      </div>
    );
  }
  return <>{entry}{onReply !== undefined && <ReplyToTask onSend={onReply} />}</>;
}

/** A reply on a task's report, which goes to the task itself: Enter sends, Escape puts it away. */
function ReplyToTask({ onSend }: { onSend: (text: string, key: string) => Promise<boolean> }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  // One key for each reply, so one sent again after its answer was lost reaches the task once.
  const [key, setKey] = useState(() => crypto.randomUUID());
  const input = useRef<HTMLTextAreaElement>(null);
  useEffect(() => { if (open) input.current?.focus(); }, [open]);
  if (!open) {
    return <div className="assistant-reply-to"><button type="button" className="assistant-link" onClick={() => setOpen(true)}>Reply to the task</button></div>;
  }
  const send = () => {
    const words = text.trim();
    if (words === '' || sending) return;
    setSending(true);
    void onSend(words, key).then(sent => {
      setSending(false);
      if (!sent) return;
      setText('');
      setKey(crypto.randomUUID());
      setOpen(false);
    });
  };
  return (
    <div className="assistant-reply-to">
      <textarea ref={input} className="assistant-reply-to__text" aria-label="Reply to the task" placeholder="Reply to the task" rows={1} maxLength={16 * 1024}
        value={text} disabled={sending} onChange={event => setText(event.target.value)}
        onKeyDown={event => {
          if (event.key === 'Escape') {
            event.preventDefault();
            setOpen(false);
          }
          else if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault();
            send();
          }
        }} />
      <button type="button" className="assistant-button" disabled={text.trim() === '' || sending} onClick={send}>Send</button>
    </div>
  );
}

const ACTED: Record<AssistantReceiptDto['operation'], LucideIcon> = {
  START_TASK: Play, ANSWER_APPROVAL: ShieldCheck, ANSWER_QUESTION: MessageSquareReply, REPLY_TO_TASK: Send, OPEN: ArrowUpRight, MARK_EPIC: Flag,
  ADD_TO_EPIC: ListPlus, CREATE_WORK_ITEM: SquarePlus, CREATE_EPIC: CircleDot, ACKNOWLEDGE: CheckCheck, SNOOZE: AlarmClock,
  MAKE_AGENDA: CalendarCheck,
};

/**
 * What an action did, or why it did not, on one line, with the user's own words in its tooltip; then Stop for the first turn of a task
 * it started, Settings to the switch that is off, and Open to where it acted. A refusal points down at the card's buttons when they
 * are the way to do it.
 */
function Receipt({ item, onOpen, onSettings }: { item: AssistantItemDto; onOpen: (receipt: AssistantReceiptDto) => void; onSettings: (section: SettingsSection) => void }) {
  const receipt = item.receipt;
  if (receipt === null) return null;
  const { target, page, turnId, outcome, fix, words, detail } = receipt;
  // A reply to a task at work waits behind its turn, which its detail says.
  const Icon = outcome === 'FAILED' ? TriangleAlert : outcome === 'REFUSED' ? Ban
    : receipt.operation === 'REPLY_TO_TASK' && detail !== null ? ListEnd : ACTED[receipt.operation];
  // Exactly what was taken as the user's: what a task was sent, or what the assistant acted on.
  const tip = [item.text, words === null ? null : `“${words}”`, detail].filter(each => each !== null).join(' · ');
  return (
    <div className={`assistant-receipt${outcome === 'DONE' ? '' : ' is-refused'}`} title={tip}>
      <Icon className="assistant-receipt__icon" size={14} aria-hidden="true" />
      <div className="assistant-receipt__text">
        <span className="assistant-receipt__lead">{item.text}</span>{detail !== null && ` · ${detail}`}{fix === 'BUTTONS' && <span aria-hidden="true"> ↓</span>}
      </div>
      {turnId !== null && <StopButton turnId={turnId} />}
      {fix === 'SETTINGS' && <button type="button" className="assistant-button" onClick={() => onSettings('features')}>Settings</button>}
      {outcome === 'DONE' && (target !== null || page !== null) && <button type="button" className="assistant-button" onClick={() => onOpen(receipt)}>Open</button>}
    </div>
  );
}
