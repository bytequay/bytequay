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
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { ArrowLeft, ArrowRight, Bell, Check, ChevronLeft, ChevronRight, CircleAlert, MessageCircleQuestion, Pause, ShieldCheck, X } from 'lucide-react';
import { NOTICE_LABELS, NOTICE_WIDTH, needsAction, type AppNotice, type NoticeBridge, type NoticeSnapshot } from './contracts';

const ICONS = { TURN_FINISHED: Check, QUESTION: MessageCircleQuestion, APPROVAL: ShieldCheck, FAILURE: CircleAlert, RECOVERY: CircleAlert, QUEUE_PAUSED: Pause };
const ACTIONS = { TURN_FINISHED: 'View response', QUESTION: 'View question', APPROVAL: 'Review request', FAILURE: 'View task', RECOVERY: 'Inspect task', QUEUE_PAUSED: 'View queue' };

function NoticeCard({ notice, active, popup, preview, onDismiss, onClose, onOpen }: {
  notice: AppNotice; active: boolean; popup: boolean; preview: boolean; onDismiss: () => void; onClose: () => void; onOpen: () => void;
}) {
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const remaining = useRef(8000);
  const actionable = needsAction(notice);
  const dismiss = useRef(onDismiss);
  dismiss.current = onDismiss;
  useEffect(() => {
    if (actionable || !popup || !active || hovered || focused) return;
    const start = Date.now();
    const timer = window.setTimeout(() => dismiss.current(), remaining.current);
    return () => { window.clearTimeout(timer); remaining.current = Math.max(0, remaining.current - (Date.now() - start)); };
  }, [actionable, popup, active, hovered, focused]);
  const Icon = ICONS[notice.kind];
  const provider = notice.laneId.startsWith('claude') ? 'Claude' : notice.laneId.startsWith('codex') ? 'Codex' : notice.laneId;
  const source = `${provider} · ${notice.repository}`;
  return <article className={`app-notice is-${notice.kind.toLowerCase()}${preview ? ' is-preview' : ''}`} inert={preview} aria-hidden={preview || undefined}
    aria-label={`${NOTICE_LABELS[notice.kind]}: ${notice.title}`}
    onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
    onFocusCapture={() => setFocused(true)} onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false); }}>
    <div className="app-notice__head"><span className="app-notice__icon"><Icon size={16} aria-hidden="true" /></span>
      <span>{NOTICE_LABELS[notice.kind]}</span><span className="app-notice__spacer" />
      <button type="button" className="app-notice__close" aria-label={`Close ${NOTICE_LABELS[notice.kind].toLowerCase()} notification`} onClick={onClose}><X size={14} /></button>
    </div>
    <h2 title={notice.title}>{notice.title}</h2><p title={notice.message}>{notice.message}</p>
    <div className="app-notice__meta" title={source}>{source}</div>
    <div className="app-notice__actions"><button type="button" onClick={onOpen}>{ACTIONS[notice.kind]}</button>
      {needsAction(notice) && <span>Needs your attention</span>}</div>
  </article>;
}

export default function NoticeStack({ bridge = window.noticeBridge }: { bridge?: NoticeBridge }) {
  const [state, setState] = useState<NoticeSnapshot>({ notices: [], popupIds: [], viewedIds: [], active: false, maxHeight: 600 });
  const [expanded, setExpanded] = useState(false);
  const [folded, setFolded] = useState(false);
  const [closed, setClosed] = useState<Set<string>>(() => new Set());
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const root = useRef<HTMLDivElement>(null);
  const footer = useRef<HTMLDivElement>(null);
  const launcher = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    let active = true;
    let received = false;
    let prior = new Set<string>();
    const update = (next: NoticeSnapshot) => {
      if (!active) return;
      const added = next.notices.filter(item => next.popupIds.includes(item.id) && !prior.has(item.id));
      if (next.active && added.length) setAnnouncement(added.map(item => `${NOTICE_LABELS[item.kind]}: ${item.title}`).join('. '));
      prior = new Set(next.popupIds);
      setState(next);
      if (next.notices.length === 0) { setExpanded(false); setFolded(false); setError(null); }
    };
    const stop = bridge.subscribe(next => { received = true; update(next); });
    void bridge.read().then(next => { if (!received) update(next); }).catch(() => {});
    return () => { active = false; stop(); };
  }, [bridge]);
  const waiting = state.notices.filter(needsAction).length;
  const available = state.notices.filter(notice => !closed.has(notice.id) && (expanded || !state.viewedIds.includes(notice.id)));
  const pages = Math.max(1, available.length);
  const currentPage = Math.min(page, pages - 1);
  const popups = available.filter(notice => state.popupIds.includes(notice.id));
  const cards = folded ? [] : expanded ? available.slice(currentPage, currentPage + 2)
    : popups.length ? popups.slice(0, 2) : available.slice(0, 2);
  const compact = folded || cards.length === 0 && !expanded && error === null;
  useLayoutEffect(() => {
    const node = root.current;
    if (!node) { bridge.resize(0, NOTICE_WIDTH, false); return; }
    const measure = () => {
      const padding = getComputedStyle(node);
      // Transparent native view pixels still intercept input on controls underneath.
      const width = compact && footer.current
        ? footer.current.getBoundingClientRect().width + parseFloat(padding.paddingLeft) + parseFloat(padding.paddingRight) : NOTICE_WIDTH;
      bridge.resize(Math.ceil(node.getBoundingClientRect().height), Math.ceil(width), folded);
    };
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    if (footer.current) observer.observe(footer.current);
    measure();
    return () => observer.disconnect();
  }, [bridge, compact, folded, state.notices.length]);
  const closeCard = (notice: AppNotice) => setClosed(ids => new Set([...ids].filter(id => state.notices.some(item => item.id === id)).concat(notice.id)));
  const dismiss = (notice: AppNotice, closedByUser = false) => {
    setError(null);
    void bridge.dismiss(notice.id).then(() => {
      if (!closedByUser) return;
      closeCard(notice);
      if (available.length === 1) { setExpanded(false); launcher.current?.focus(); }
    }).catch(() => setError('Unable to dismiss this notice.'));
  };
  const open = (notice: AppNotice) => {
    setError(null);
    void bridge.open(notice.id).then(() => { closeCard(notice); setExpanded(false); }).catch(() => setError('Unable to open this notice. Try again.'));
  };
  if (state.notices.length === 0) return null;
  return <div ref={root} className={`app-notices${folded ? ' is-folded' : ''}`} style={{ '--notice-max-height': `${state.maxHeight}px`,
    '--notice-color': waiting > 0 ? 'var(--fg-warning)' : 'var(--accent)',
    '--notice-tint': `${Math.min(48, 12 + state.notices.length * 4)}%` } as CSSProperties}>
    <span className="app-notices__sr" role="status" aria-live="polite" aria-atomic="true">{announcement}</span>
    {!folded && expanded && <div className="app-notices__header"><span>App notices</span><button type="button" aria-label="Close notices" onClick={() => { setExpanded(false); launcher.current?.focus(); }}><X size={16} /></button></div>}
    <section className="app-notices__cards" aria-label="App notices">{cards.map((notice, index) => <NoticeCard key={notice.id} notice={notice} active={state.active} preview={index > 0}
      popup={!expanded && index === 0 && cards.length > 1 && state.popupIds.includes(notice.id)}
      onDismiss={() => dismiss(notice)} onClose={() => dismiss(notice, true)} onOpen={() => open(notice)} />)}</section>
    {!folded && error && <div className="app-notices__error" role="alert">{error}</div>}
    <div ref={footer} className="app-notices__footer">
      {!folded && expanded && pages > 1 && <div className="app-notices__pages"><button type="button" aria-label="Previous notices" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}><ChevronLeft size={16} /></button>
        <span>{currentPage + 1} / {pages}</span><button type="button" aria-label="Next notices" disabled={currentPage + 1 === pages} onClick={() => setPage(currentPage + 1)}><ChevronRight size={16} /></button></div>}
      <button ref={launcher} type="button" className={`app-notices__launcher has-notices${folded ? ' app-notices__edge' : ''}`} aria-expanded={!folded && expanded}
        aria-label={folded ? `Show ${state.notices.length} notifications` : undefined} title={folded ? 'Show notifications' : undefined}
        onClick={() => { if (folded || !expanded) setClosed(new Set()); setExpanded(folded || !expanded); setFolded(false); setPage(0); setError(null); }}>
        {folded ? <><ArrowLeft size={16} aria-hidden="true" /><span>{state.notices.length}</span></> : <>
          <Bell size={14} aria-hidden="true" />{waiting > 0 ? `${waiting} need${waiting === 1 ? 's' : ''} you` : 'Notices'}
          {state.notices.length > waiting && <span>· {state.notices.length - waiting} recent</span>}
        </>}
      </button>
      {!folded && <button type="button" className="app-notices__fold" aria-label="Fold notifications" title="Fold notifications to the edge"
        onClick={() => { setFolded(true); setError(null); launcher.current?.focus(); }}><ArrowRight size={16} aria-hidden="true" /></button>}
    </div>
  </div>;
}
