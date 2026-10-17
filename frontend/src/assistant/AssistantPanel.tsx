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
import { useEffect, useLayoutEffect, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react';
import { Mic, Settings, TriangleAlert, X } from 'lucide-react';
import Composer from '../Composer';
import { PendingApprovalCard, type ApprovalDecider } from '../agents/AgentConversation';
import { renderMarkdown } from '../agents/markdown';
import { IconButton } from '../cards/Card';
import TodayList, { openTarget } from '../home/TodayList';
import { clockTime, dayText } from '../home/today';
import { useTodayAgenda } from '../home/useTodayAgenda';
import { APPROVAL_NOTICE, NOTICE_ACTIONS, noticeLabel, type AppNotice } from '../notices/contracts';
import { remoteErrorMessage } from '../pulls/PullOverview';
import { settingsRoute } from '../settings/routes';
import type { AgendaTargetDto, AgentPendingApprovalDto, AssistantItemDto } from '../types';
import { linkRefs, refFromHref, refLabel } from './model';
import { useAssistant } from './useAssistant';
import '../css/assistant.css';

/**
 * The Assistant beside every page: today's agenda, the day's conversation, then the notices waiting on the user with their own buttons.
 * It docks rather than floats, because a page can be a native browser or editor view that nothing can paint over.
 */
export default function AssistantPanel({ open, notices, openCard, onScreen, onClose, onShowApp, onResizeStart }: {
  open: boolean;
  notices: AppNotice[];
  /** The waiting card an alert's Answer asked for; a new object each time. */
  openCard: { id: string } | null;
  /** The ref of what the user is looking at, sent with a message unless they remove it. */
  onScreen: string | null;
  onClose: () => void;
  /** Brings the app back from a web page before the panel opens anything in it. */
  onShowApp: () => void;
  onResizeStart: (event: ReactMouseEvent) => void;
}) {
  const { view, error, sending, send, stop, retry } = useAssistant(open);
  const [draft, setDraft] = useState('');
  const [hiddenScreen, setHiddenScreen] = useState<string | null>(null);
  const [answered, setAnswered] = useState<ReadonlySet<string>>(() => new Set());
  const [failure, setFailure] = useState<string | null>(null);
  const [opened, setOpened] = useState<string | null>(null);
  const panel = useRef<HTMLElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const returnFocus = useRef<Element | null>(null);
  const items = view?.items ?? [];
  const running = view?.running ?? false;
  // A card answered here leaves at once; the notice itself goes on the host's next read.
  const waiting = notices.filter(notice => !answered.has(notice.id));
  const screen = onScreen !== null && onScreen !== hiddenScreen ? onScreen : null;

  useLayoutEffect(() => {
    if (open) {
      returnFocus.current = document.activeElement;
      input.current?.focus();
      return;
    }
    const previous = returnFocus.current;
    returnFocus.current = null;
    const active = document.activeElement;
    if (previous instanceof HTMLElement && previous.isConnected && (active === null || active === document.body || panel.current?.contains(active))) previous.focus();
  }, [open]);

  useEffect(() => setAnswered(current => {
    const live = current.size === 0 ? current : new Set([...current].filter(id => notices.some(notice => notice.id === id)));
    return live.size === current.size ? current : live;
  }), [notices]);

  useLayoutEffect(() => {
    const node = scroller.current;
    if (open && node) node.scrollTop = node.scrollHeight;
  }, [open, items.length, running, waiting.length]);

  useLayoutEffect(() => {
    const card = openCard === null ? null : scroller.current?.querySelector<HTMLElement>(`[data-notice="${CSS.escape(openCard.id)}"]`);
    if (openCard === null || !card || !content.current) return;
    card.scrollIntoView({ block: 'nearest' });
    // The card itself, not one of its buttons, so a stray Enter cannot answer it.
    card.focus({ preventScroll: true });
    setOpened(openCard.id);
    // The agenda, the conversation and the card's own approval are still being read; what they add must not push it out of view.
    const observer = new ResizeObserver(() => card.scrollIntoView({ block: 'nearest' }));
    observer.observe(content.current);
    // The outline holds for 2 s, then fades for 0.4 s (assistant.css).
    const timer = window.setTimeout(() => { observer.disconnect(); setOpened(null); }, 2400);
    return () => { window.clearTimeout(timer); observer.disconnect(); setOpened(null); };
  }, [openCard]);

  const show = (target: AgendaTargetDto) => { onShowApp(); openTarget(target); };
  const openSettings = () => { onShowApp(); window.location.hash = settingsRoute('models'); };
  const noticeAction = (action: Promise<void>, message: string) => {
    setFailure(null);
    void action.catch(() => setFailure(message));
  };
  const submit = () => {
    const text = draft.trim();
    void send(text, waiting.map(notice => notice.id), screen).then(sent => {
      if (!sent) return;
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

  return (
    <aside ref={panel} className="assistant-panel" aria-label="Assistant" hidden={!open}
      onKeyDown={event => {
        if (event.key !== 'Escape' || event.defaultPrevented) return;
        event.preventDefault();
        onClose();
      }}>
      <div className="assistant-panel__resize" role="separator" aria-label="Resize the Assistant" aria-orientation="vertical" title="Drag to resize"
        onMouseDown={onResizeStart} />
      <header className="assistant-head">
        <span className="assistant-head__title" title={view?.model ? `Model: ${view.model}` : undefined}>Assistant</span>
        {view !== null && <span className="assistant-head__date">{dayText(view.day)}</span>}
        <span className="assistant-spacer" />
        <IconButton label="Settings" onClick={openSettings}><Settings size={15} aria-hidden="true" /></IconButton>
        <IconButton label="Close the Assistant" title="Close (⌘J)" onClick={onClose}><X size={15} aria-hidden="true" /></IconButton>
      </header>
      <div ref={scroller} className="assistant-scroll" onClickCapture={openLink} onAuxClickCapture={openLink}>
        <div ref={content}>
          {open && <PanelAgenda onOpen={show} />}
          {items.map((item, index) => <Entry key={item.id} item={item} retryable={!running && index === items.length - 1} onRetry={retry}
            onSettings={openSettings} />)}
          {running && <p className="assistant-thinking" role="status">Thinking…</p>}
          {waiting.length > 0 && <section className="assistant-waiting" aria-label="Waiting on you">
            {waiting.map(notice => (
              <div key={notice.id} className={`assistant-waiting__card${opened === notice.id ? ' is-opened' : ''}`} data-notice={notice.id} tabIndex={-1}>
                {notice.id.startsWith(APPROVAL_NOTICE)
                  ? <WaitingApproval notice={notice} onAnswered={() => setAnswered(current => new Set(current).add(notice.id))}
                    onOpen={() => noticeAction(window.bridge.openAppNotice(notice.id), 'Unable to open this task.')}
                    onClear={() => noticeAction(window.bridge.removeAppNotice(notice.id), 'Unable to clear this notice.')} />
                  : <NoticeRow notice={notice} onOpen={() => noticeAction(window.bridge.openAppNotice(notice.id), 'Unable to open this task.')}
                    onClear={() => noticeAction(window.bridge.removeAppNotice(notice.id), 'Unable to clear this notice.')} />}
              </div>
            ))}
          </section>}
        </div>
      </div>
      <div className="assistant-compose">
        {(error ?? failure) !== null && <p className="assistant-error" role="alert">{error ?? failure}</p>}
        <Composer inputRef={input} aria-label="Message the Assistant" placeholder="Ask about your day…" value={draft} maxLength={16 * 1024}
          onChange={event => setDraft(event.target.value)} busy={sending} onSubmit={submit}
          submitEnabled={draft.trim() !== '' && !running && !sending} showSubmit={!running} submitLabel="Send to the Assistant" submitTitle="Send (Enter)"
          trailingActions={running && (
            <button type="button" className="agt-send is-stop" aria-label="Stop the reply" title="Stop the reply" onClick={stop}>
              <span className="agt-stop-square" aria-hidden="true" />
            </button>
          )}>
          {screen !== null && (
            <span className="assistant-chip">
              <span className="assistant-chip__name">On screen:</span>
              <span className="assistant-chip__ref">{refLabel(screen)}</span>
              <button type="button" aria-label="Leave what's on screen out of this message" title="Leave it out of this message"
                onClick={() => setHiddenScreen(screen)}><X size={11} aria-hidden="true" /></button>
            </span>
          )}
          <span className="assistant-spacer" />
          <span className="assistant-mic" role="img" aria-label="Voice comes later" title="Voice comes later. macOS Dictation already types here.">
            <Mic size={15} aria-hidden="true" />
          </span>
        </Composer>
      </div>
    </aside>
  );
}

function PanelAgenda({ onOpen }: { onOpen: (target: AgendaTargetDto) => void }) {
  const today = useTodayAgenda();
  // Before the day's agenda is made there is nothing to show; Home keeps its Make now.
  return today.agenda?.state === 'NONE' ? null : <TodayList today={today} scope="global" onOpen={onOpen} />;
}

function Entry({ item, retryable, onRetry, onSettings }: { item: AssistantItemDto; retryable: boolean; onRetry: () => void; onSettings: () => void }) {
  if (item.kind === 'USER') return <>
    <div className="assistant-time">{clockTime(item.at)}</div>
    <div className="agt-user"><div className="agt-user__bubble">{item.text}</div></div>
  </>;
  if (item.kind === 'REPLY') return <div className="assistant-reply">{renderMarkdown(linkRefs(item.text, item.targets))}</div>;
  return (
    <div className="assistant-failure" role="status">
      <TriangleAlert size={14} aria-hidden="true" />
      <span>{item.text}</span>
      {item.notReady && <button type="button" className="assistant-link" onClick={onSettings}>Open Settings</button>}
      {retryable && <button type="button" className="assistant-link" onClick={onRetry}>Retry</button>}
    </div>
  );
}

/** An approval or a question with the task's own buttons; its data is the task's, read fresh, never the notice's text. */
function WaitingApproval({ notice, onAnswered, onOpen, onClear }: { notice: AppNotice; onAnswered: () => void; onOpen: () => void; onClear: () => void }) {
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
  if (approval === null) return <NoticeRow notice={notice} onOpen={onOpen} onClear={onClear} />;
  const decide: ApprovalDecider = (id, decision, answers) => {
    setError(null);
    const resolved = reviewId === null
      ? window.bridge.resolveAgentApproval(id, decision, undefined, answers)
      : window.bridge.resolveAgentReviewApproval(reviewId, id, decision, answers);
    void resolved.then(onAnswered).catch((cause: unknown) => setError(remoteErrorMessage(cause)));
  };
  const heading = notice.pullRequestTitle ?? notice.title;
  return (
    <div className="assistant-wait">
      <div className="assistant-wait__head">
        <span className="assistant-wait__title" title={heading}>{heading}</span>
        <span className="assistant-wait__meta" title={notice.repository}>· {notice.repository}</span>
        <span className="assistant-spacer" />
        <button type="button" className="assistant-link" onClick={onOpen}>Open</button>
      </div>
      {approval === undefined ? <div className="assistant-wait__loading">Loading…</div> : <PendingApprovalCard approval={approval} onDecide={decide} />}
      {error !== null && <p className="assistant-error" role="alert">{error}</p>}
    </div>
  );
}

function NoticeRow({ notice, onOpen, onClear }: { notice: AppNotice; onOpen: () => void; onClear: () => void }) {
  const heading = notice.pullRequestTitle ?? notice.title;
  return (
    <div className="assistant-row">
      <span className="assistant-row__text" title={`${noticeLabel(notice)}: ${heading}`}>
        <span className="assistant-row__title">{heading}</span>
        <span className="assistant-row__meta"> · {noticeLabel(notice)}</span>
      </span>
      <button type="button" className="assistant-row__open" onClick={onOpen}>{NOTICE_ACTIONS[notice.kind]}</button>
      <IconButton label={`Clear “${heading}”`} title="Clear" onClick={onClear}><X size={14} aria-hidden="true" /></IconButton>
    </div>
  );
}
