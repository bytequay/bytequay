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
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react';
import {
  AlarmClock, ArrowUpRight, Ban, CheckCheck, CircleDot, Flag, ListEnd, ListPlus, ListTodo, MessageSquareReply, Mic, Play, RotateCw, Send, ShieldCheck,
  SquarePlus, TriangleAlert, X, type LucideIcon,
} from 'lucide-react';
import Composer from '../Composer';
import { renderMarkdown } from '../agents/markdown';
import { SourceIcon } from '../cards/Card';
import { openTarget } from '../home/TodayList';
import { reloadHandled, type AttentionSource } from '../home/attention';
import { requestPullComment } from '../pulls/PullOverview';
import { clockTime } from '../home/today';
import type { AppNotice } from '../notices/contracts';
import { remoteErrorMessage } from '../remoteError';
import { settingsRoute, type SettingsSection } from '../settings/routes';
import type { AgendaTargetDto, AssistantItemDto, AssistantReceiptDto, AssistantRowDto } from '../types';
import AssistantList from './AssistantList';
import DraftCard from './DraftCard';
import { arrivedOpen, createdDrafts, linkRefs, pageRoute, refFromHref, refLabel } from './model';
import { approvePull, closePull, mergePull, type PullAction } from './pullActions';
import { useAssistant } from './useAssistant';
import WaitingStack from './WaitingStack';
import '../css/assistant.css';

/**
 * The Assistant over every page, opened with ⌘J: the composer, the day's conversation once the user talks, then what waits on them.
 * It floats, and nothing paints over a native view, so the app leaves a web page before it opens and the editor hides under any dialog.
 */
export default function AssistantPanel({ open, chatEnabled = true, notices, openCard, onScreen, onClose }: {
  open: boolean;
  chatEnabled?: boolean;
  notices: AppNotice[];
  /** The waiting card an alert's Answer asked for; a new object each time. */
  openCard: { id: string } | null;
  /** The ref of what the user is looking at, sent with a message unless they remove it. */
  onScreen: string | null;
  onClose: () => void;
}) {
  const { view, error, sending, send, stop, retry, refresh } = useAssistant(open);
  const [draft, setDraft] = useState('');
  const [hiddenScreen, setHiddenScreen] = useState<string | null>(null);
  const [answered, setAnswered] = useState<ReadonlySet<string>>(() => new Set());
  const [failure, setFailure] = useState<string | null>(null);
  const [request, setRequest] = useState(openCard);
  // The conversation shows once the user talks, or when a reply came in while the Assistant was closed.
  const [talking, setTalking] = useState(false);
  const [closedAt, setClosedAt] = useState(Date.now);
  const input = useRef<HTMLTextAreaElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const visible = useRef<string[]>([]);
  const returnFocus = useRef<Element | null>(null);
  const openedAt = useRef(0);
  const followed = useRef(new Set<string>());
  const items = view?.items ?? [];
  const running = view?.running ?? false;
  // A card answered here leaves at once; the notice itself goes on the host's next read.
  const waiting = notices.filter(notice => !answered.has(notice.id));
  const screen = onScreen !== null && onScreen !== hiddenScreen ? onScreen : null;
  const talkShown = talking || running || items.some(item => Date.parse(item.at) > closedAt);

  useLayoutEffect(() => {
    if (open) {
      openedAt.current = Date.now();
      returnFocus.current = document.activeElement;
      input.current?.focus();
      return;
    }
    setTalking(false);
    setFailure(null);
    setClosedAt(Date.now());
    const previous = returnFocus.current;
    returnFocus.current = null;
    const active = document.activeElement;
    if (previous instanceof HTMLElement && previous.isConnected && (active === null || active === document.body)) previous.focus();
  }, [open]);

  useEffect(() => setRequest(openCard), [openCard]);
  const requestDone = useCallback(() => setRequest(null), []);

  useEffect(() => setAnswered(current => {
    const live = current.size === 0 ? current : new Set([...current].filter(id => notices.some(notice => notice.id === id)));
    return live.size === current.size ? current : live;
  }), [notices]);

  useLayoutEffect(() => {
    const node = scroller.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [open, talkShown, items.length, running]);

  // Where a receipt opens, a page before an item; the Assistant closes first, so nothing opens under it.
  const openReceipt = useCallback((receipt: AssistantReceiptDto) => {
    const hash = receipt.page === null ? null : pageRoute(receipt.page);
    if (hash === null && receipt.target === null) return;
    onClose();
    if (hash !== null) window.location.hash = hash;
    else if (receipt.target !== null) openTarget(receipt.target);
  }, [onClose]);

  // An open the assistant made while the user watched goes there once; read later, its receipt only offers Open.
  useEffect(() => {
    if (!open) return;
    const arrived = arrivedOpen(view?.items ?? [], openedAt.current, followed.current);
    if (arrived === null || arrived.receipt === null) return;
    followed.current.add(arrived.id);
    openReceipt(arrived.receipt);
  }, [open, view, openReceipt]);

  // The lists read marks once at start, so a mark the assistant made reaches them when its receipt does.
  const marked = items.filter(item => item.receipt?.outcome === 'DONE' && (item.receipt.operation === 'ACKNOWLEDGE' || item.receipt.operation === 'SNOOZE')).length;
  useEffect(() => { if (marked > 0) void reloadHandled().catch(() => {}); }, [marked]);

  if (!open) return null;

  const show = (target: AgendaTargetDto) => { onClose(); openTarget(target); };
  const openSettings = (section: SettingsSection) => { onClose(); window.location.hash = settingsRoute(section); };
  // A receipt a card's button left after a failed reply does not end that exchange, so Retry stays on the failure.
  const lastExchange = items.findLast(item => item.kind !== 'RECEIPT')?.id;
  const submit = () => {
    if (!chatEnabled) return;
    const text = draft.trim();
    void send(text, visible.current, screen).then(sent => {
      if (!sent) return;
      setTalking(true);
      setDraft(current => current.trim() === text ? '' : current);
      setHiddenScreen(null);
    });
  };
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
  const actOnPull = (target: AgendaTargetDto, action: PullAction): Promise<void> => {
    if (target.repo === null || target.number === null) return Promise.resolve();
    if (action === 'comment') {
      requestPullComment(target.repo, target.number);
      show(target);
      return Promise.resolve();
    }
    const act = action === 'approve' ? approvePull : action === 'merge' ? mergePull : closePull;
    return act(target.repo, target.number).then(refresh);
  };
  const noticeAction = (action: Promise<void>, message: string, then?: () => void) => {
    setFailure(null);
    void action.then(then).catch(() => setFailure(message));
  };

  const talk = talkShown && items.length > 0 && (
    <div ref={scroller} className="assistant-sheet assistant-talk" onClickCapture={openLink} onAuxClickCapture={openLink}>
      {items.map(item => item.kind === 'RECEIPT'
        ? <Receipt key={item.id} item={item} onOpen={openReceipt} onSettings={openSettings} />
        : <Entry key={item.id} item={item} rows={view?.rows ?? {}} drafts={drafts} model={view?.model ?? null} retryable={chatEnabled && !running && item.id === lastExchange}
          onRetry={retry} onSettings={openSettings} onOpen={show} onComplete={completeTask} onPull={actOnPull} onRefresh={refresh} />)}
      {running && <p className="assistant-thinking" role="status">Thinking…</p>}
    </div>
  );

  return (
    <div className="assistant-panel">
      <button type="button" className="assistant-backdrop" tabIndex={-1} aria-label="Close the Assistant" onClick={onClose} />
      <section className={`assistant-float${running || sending ? ' is-replying' : ''}`} role="dialog" aria-label="Assistant"
        onKeyDown={event => {
          if (event.key !== 'Escape' || event.defaultPrevented) return;
          event.preventDefault();
          onClose();
        }}>
        {chatEnabled ? <Composer inputRef={input} aria-label="Message the Assistant" placeholder="Ask, or tell it what to start" value={draft} maxLength={16 * 1024}
          onChange={event => setDraft(event.target.value)} busy={sending} onSubmit={submit}
          onKeyDown={event => {
            if (event.key !== 'Tab' || event.altKey || event.ctrlKey || event.metaKey) return;
            const pill = event.currentTarget.closest('.assistant-float')?.querySelector<HTMLElement>('.assistant-pill.is-selected');
            if (!pill) return;
            event.preventDefault();
            pill.focus();
          }}
          notice={(error ?? failure) !== null && <p className="assistant-error" role="alert">{error ?? failure}</p>}
          submitEnabled={draft.trim() !== '' && !running && !sending} showSubmit={!running} submitLabel="Send to the Assistant" submitTitle="Send (Enter)"
          trailingActions={running && (
            <button type="button" className="agt-send is-stop" aria-label="Stop the reply" title="Stop the reply" onClick={stop}>
              <span className="agt-stop-square" aria-hidden="true" />
            </button>
          )}>
          {screen !== null && (
            <span className="assistant-chip" title="On screen: tells the assistant what “this one” means">
              <ScreenIcon screen={screen} />
              <span className="assistant-chip__name">On screen:</span>
              <span className="assistant-chip__ref">{refLabel(screen)}</span>
              <button type="button" aria-label="Leave what's on screen out of this message" title="Leave it out"
                onClick={() => setHiddenScreen(screen)}><X size={12} aria-hidden="true" /></button>
            </span>
          )}
          <span className="assistant-spacer" />
          {view?.model && <span className="assistant-model" title={`Answers with ${view.model}`}>{view.model}</span>}
          <span className="assistant-mic" role="img" aria-label="Voice comes later" title="Voice comes later. macOS Dictation already types here.">
            <Mic size={15} aria-hidden="true" />
          </span>
        </Composer> : <div className="assistant-sheet assistant-off">
          <p>Task notices and your conversation are still available.</p>
          <button type="button" className="assistant-link" onClick={() => openSettings('features')}>Set up Assistant in Settings</button>
          {running && <button type="button" className="assistant-link" onClick={stop}>Stop the reply</button>}
        </div>}
        <WaitingStack notices={waiting} talk={talk} request={request} onRequestDone={requestDone} visible={visible} input={input} onLeave={onClose}
          onAnswered={id => { setAnswered(current => new Set(current).add(id)); refresh(); }}
          onOpenNotice={id => noticeAction(window.bridge.openAppNotice(id), 'Unable to open this task.', onClose)}
          onClearNotice={id => noticeAction(window.bridge.removeAppNotice(id), 'Unable to clear this notice.')} />
      </section>
    </div>
  );
}

const SCREEN_SOURCES: Record<string, AttentionSource> = { pr: 'pr', issue: 'issue', jira: 'jira', task: 'task', review: 'task' };

/** What kind of item is on screen, by its source's icon; a work item has no source and takes a list's work item icon. */
function ScreenIcon({ screen }: { screen: string }) {
  const source = SCREEN_SOURCES[screen.slice(0, screen.indexOf(':'))];
  return source === undefined ? <ListTodo size={12} aria-hidden="true" /> : <SourceIcon source={source} size={12} />;
}

function Entry({ item, rows, drafts, model, retryable, onRetry, onSettings, onOpen, onComplete, onPull, onRefresh }: {
  item: AssistantItemDto;
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
  onPull: (target: AgendaTargetDto, action: PullAction) => Promise<void>;
  onRefresh: () => void;
}) {
  if (item.kind === 'USER') return <div className="agt-user"><div className="agt-user__bubble" title={`Sent at ${clockTime(item.at)}`}>{item.text}</div></div>;
  if (item.kind === 'REPLY') return <div className="assistant-reply">{renderMarkdown(linkRefs(item.text, item.targets))}</div>;
  if (item.kind === 'LIST') return <AssistantList item={item} rows={rows} onOpen={onOpen} onComplete={onComplete} onPull={onPull} />;
  if (item.kind === 'DRAFT') return <DraftCard item={item} created={item.draft !== null && drafts.has(item.draft.workItemId)} onCreated={onRefresh} onOpen={onOpen} />;
  return (
    <div className="assistant-failure" role="status"
      title={item.notReady ? `${model ?? 'The Assistant'} answers through your Claude account. Sign in from Settings → Models.` : undefined}>
      <TriangleAlert size={14} aria-hidden="true" />
      <span>{item.text}</span>
      <span className="assistant-spacer" />
      {item.notReady && <button type="button" className="assistant-action" onClick={() => onSettings('models')}>Open Settings</button>}
      {retryable && <button type="button" className="assistant-action" onClick={onRetry}><RotateCw size={12} aria-hidden="true" />Retry</button>}
    </div>
  );
}

const ACTED: Record<AssistantReceiptDto['operation'], LucideIcon> = {
  START_TASK: Play, ANSWER_APPROVAL: ShieldCheck, ANSWER_QUESTION: MessageSquareReply, REPLY_TO_TASK: Send, OPEN: ArrowUpRight, MARK_EPIC: Flag,
  ADD_TO_EPIC: ListPlus, CREATE_WORK_ITEM: SquarePlus, CREATE_EPIC: CircleDot, ACKNOWLEDGE: CheckCheck, SNOOZE: AlarmClock,
};

/**
 * What an action did, or why it did not, in one line: the user's own words and a detail below it, then Open to where it acted, Settings
 * to the switch that is off, and Stop for the first turn of a task it started. A refusal points down at the card's buttons when they
 * are the way to do it.
 */
function Receipt({ item, onOpen, onSettings }: { item: AssistantItemDto; onOpen: (receipt: AssistantReceiptDto) => void; onSettings: (section: SettingsSection) => void }) {
  const [stop, setStop] = useState<'idle' | 'stopping' | 'stopped'>('idle');
  const [error, setError] = useState<string | null>(null);
  const receipt = item.receipt;
  if (receipt === null) return null;
  const { target, page, turnId, outcome, fix, words } = receipt;
  const detail = error ?? receipt.detail;
  // A reply to a task at work waits behind its turn, which its detail says.
  const Icon = outcome === 'FAILED' ? TriangleAlert : outcome === 'REFUSED' ? Ban
    : receipt.operation === 'REPLY_TO_TASK' && receipt.detail !== null ? ListEnd : ACTED[receipt.operation];
  const below = [words === null ? null : `“${words}”`, detail].filter(each => each !== null).join(' · ');
  const cancel = (turn: string) => {
    setStop('stopping');
    setError(null);
    window.bridge.cancelAgentTurn(turn).then(() => setStop('stopped')).catch((cause: unknown) => { setStop('idle'); setError(remoteErrorMessage(cause)); });
  };
  return (
    <div className={`assistant-receipt${outcome === 'DONE' ? '' : ' is-refused'}`}>
      <Icon className="assistant-receipt__icon" size={14} aria-hidden="true" />
      <div className="assistant-receipt__text">
        <div className="assistant-receipt__line" title={item.text}>{item.text}{fix === 'BUTTONS' && <span aria-hidden="true"> ↓</span>}</div>
        {/* Exactly what was taken as the user's: what a task was sent, or what the assistant acted on. */}
        {below !== '' && (
          <div className="assistant-receipt__below" title={below}>
            {words !== null && <span className="assistant-receipt__words">“{words}”</span>}
            {words !== null && detail !== null && ' · '}
            {detail !== null && <span className="assistant-receipt__detail">{detail}</span>}
          </div>
        )}
      </div>
      {turnId !== null && (stop === 'stopped'
        ? <span className="assistant-receipt__stopped">Stopped</span>
        : <button type="button" className="assistant-action" disabled={stop === 'stopping'} onClick={() => cancel(turnId)}>Stop</button>)}
      {fix === 'SETTINGS' && <button type="button" className="assistant-action" onClick={() => onSettings('features')}>Settings</button>}
      {outcome === 'DONE' && (target !== null || page !== null) && <button type="button" className="assistant-action" onClick={() => onOpen(receipt)}>Open</button>}
    </div>
  );
}
