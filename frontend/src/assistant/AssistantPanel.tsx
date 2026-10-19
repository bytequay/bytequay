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
import { Hand, Mic, TriangleAlert, X } from 'lucide-react';
import Composer from '../Composer';
import { renderMarkdown } from '../agents/markdown';
import { openTarget } from '../home/TodayList';
import { clockTime } from '../home/today';
import type { AppNotice } from '../notices/contracts';
import { remoteErrorMessage } from '../remoteError';
import { settingsRoute, type SettingsSection } from '../settings/routes';
import type { AgendaTargetDto, AssistantItemDto, AssistantReceiptDto, AssistantRowDto } from '../types';
import AssistantList from './AssistantList';
import { arrivedOpen, entries, linkRefs, pageRoute, refFromHref, refLabel } from './model';
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
  // Reply links carry refs, so they open where the item's work lives instead of as a web page.
  const openLink = (event: ReactMouseEvent) => {
    const anchor = event.target instanceof Element ? event.target.closest('a') : null;
    const ref = anchor === null ? null : refFromHref(anchor.getAttribute('href') ?? '');
    if (ref === null) return;
    event.preventDefault();
    if (ref in targets) show(targets[ref]);
  };
  // The user's own click on a task's card; the list shows it completed once the day is read again.
  const completeTask = (target: AgendaTargetDto): Promise<void> => {
    if (target.id === null || target.workspaceId === null) return Promise.resolve();
    setFailure(null);
    return window.bridge.completeAgentTask(target.id, target.workspaceId).then(refresh).catch((cause: unknown) => setFailure(remoteErrorMessage(cause)));
  };
  const noticeAction = (action: Promise<void>, message: string, then?: () => void) => {
    setFailure(null);
    void action.then(then).catch(() => setFailure(message));
  };

  const talk = talkShown && items.length > 0 && (
    <div ref={scroller} className="assistant-sheet assistant-talk" onClickCapture={openLink} onAuxClickCapture={openLink}>
      {entries(items).map(entry => Array.isArray(entry)
        ? <div key={entry[0].id} className="assistant-receipts">{entry.map(item => <Receipt key={item.id} item={item} onOpen={openReceipt} />)}</div>
        : <Entry key={entry.id} item={entry} rows={view?.rows ?? {}} retryable={chatEnabled && !running && entry.id === lastExchange} onRetry={retry} onSettings={openSettings}
          onOpen={show} onComplete={completeTask} />)}
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
              <span className="assistant-chip__name">On screen:</span>
              <span className="assistant-chip__ref">{refLabel(screen)}</span>
              <button type="button" aria-label="Leave what's on screen out of this message" title="Leave it out"
                onClick={() => setHiddenScreen(screen)}><X size={11} aria-hidden="true" /></button>
            </span>
          )}
          <span className="assistant-spacer" />
          {view?.model && <span className="assistant-model" title={`Answers with ${view.model}`}>{view.model}</span>}
          <span className="assistant-mic" role="img" aria-label="Voice comes later" title="Voice comes later. macOS Dictation already types here.">
            <Mic size={15} aria-hidden="true" />
          </span>
        </Composer> : <div className="assistant-sheet">
          <p>Task notices and your conversation are still available.</p>
          <button type="button" className="assistant-link" onClick={() => openSettings('workflow')}>Set up Assistant in Settings</button>
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

function Entry({ item, rows, retryable, onRetry, onSettings, onOpen, onComplete }: {
  item: AssistantItemDto;
  rows: Record<string, AssistantRowDto>;
  retryable: boolean;
  onRetry: () => void;
  onSettings: (section: SettingsSection) => void;
  onOpen: (target: AgendaTargetDto) => void;
  onComplete: (target: AgendaTargetDto) => Promise<void>;
}) {
  if (item.kind === 'USER') return <>
    <div className="assistant-time">{clockTime(item.at)}</div>
    <div className="agt-user"><div className="agt-user__bubble">{item.text}</div></div>
  </>;
  if (item.kind === 'REPLY') return <div className="assistant-reply">{renderMarkdown(linkRefs(item.text, item.targets))}</div>;
  if (item.kind === 'LIST') return <AssistantList item={item} rows={rows} onOpen={onOpen} onComplete={onComplete} />;
  if (item.kind === 'RECEIPT') {
    // What was refused or failed: one muted line, pointing down at the card's buttons when they are the way to do it.
    const failed = item.receipt?.outcome === 'FAILED';
    return (
      <p className={`assistant-refusal${failed ? ' is-failed' : ''}`}>
        {failed ? <TriangleAlert size={13} aria-hidden="true" /> : <Hand size={13} aria-hidden="true" />}
        <span>
          {item.text}
          {item.receipt?.words != null && <> <span className="assistant-refusal__words">“{item.receipt.words}”</span></>}
          {item.receipt?.fix === 'BUTTONS' && <span aria-hidden="true"> ↓</span>}
        </span>
        {item.receipt?.fix === 'SETTINGS' && <button type="button" className="assistant-link" onClick={() => onSettings('workflow')}>Open Settings</button>}
      </p>
    );
  }
  return (
    <div className="assistant-failure" role="status">
      <TriangleAlert size={14} aria-hidden="true" />
      <span>{item.text}</span>
      {item.notReady && <button type="button" className="assistant-link" onClick={() => onSettings('models')}>Open Settings</button>}
      {retryable && <button type="button" className="assistant-link" onClick={onRetry}>Retry</button>}
    </div>
  );
}

/** What an action did: Open goes where it acted, and Stop cancels the first turn of a task it started. */
function Receipt({ item, onOpen }: { item: AssistantItemDto; onOpen: (receipt: AssistantReceiptDto) => void }) {
  const [stop, setStop] = useState<'idle' | 'stopping' | 'stopped'>('idle');
  const [error, setError] = useState<string | null>(null);
  const receipt = item.receipt;
  if (receipt === null) return null;
  const { target, page, turnId } = receipt;
  const cancel = (turn: string) => {
    setStop('stopping');
    setError(null);
    window.bridge.cancelAgentTurn(turn).then(() => setStop('stopped')).catch((cause: unknown) => { setStop('idle'); setError(remoteErrorMessage(cause)); });
  };
  return (
    <div className="assistant-receipt">
      <div className="assistant-receipt__text">
        <div className="assistant-receipt__line" title={item.text}>{item.text}</div>
        {/* Exactly what was taken as the user's: what a task was sent, or what the assistant acted on. */}
        {receipt.words !== null && <div className="assistant-receipt__words" title={receipt.words}>“{receipt.words}”</div>}
        {(error ?? receipt.detail) !== null && <div className="assistant-receipt__detail" title={error ?? receipt.detail ?? undefined}>{error ?? receipt.detail}</div>}
      </div>
      {turnId !== null && (stop === 'stopped'
        ? <span className="assistant-receipt__detail">Stopped</span>
        : <button type="button" className="assistant-receipt__action" disabled={stop === 'stopping'} onClick={() => cancel(turnId)}>Stop</button>)}
      {(target !== null || page !== null) && <button type="button" className="assistant-receipt__action" onClick={() => onOpen(receipt)}>Open</button>}
    </div>
  );
}
