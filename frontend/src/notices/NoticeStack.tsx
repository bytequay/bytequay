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
import { X } from 'lucide-react';
import { ago } from '@bytequay/ui/relativeTime';
import { APPROVAL_NOTICE, NOTICE_ACTIONS as ACTIONS, needsAction, noticeLabel as label, type AppNotice, type NoticeBridge, type NoticeSnapshot } from './contracts';

const describe = (notice: AppNotice): string => `${label(notice)}: ${notice.pullRequestTitle ?? notice.title}`;
const count = (value: number, noun: string): string => `${value} ${noun}${value === 1 ? '' : 's'}`;

function NoticeCard({ notice, active, onDismiss, onOpen }: { notice: AppNotice; active: boolean; onDismiss: () => void; onOpen: () => void }) {
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const remaining = useRef(8000);
  const actionable = needsAction(notice);
  const dismiss = useRef(onDismiss);
  dismiss.current = onDismiss;
  useEffect(() => {
    if (actionable || !active || hovered || focused) return;
    const start = Date.now();
    const timer = window.setTimeout(() => dismiss.current(), remaining.current);
    return () => { window.clearTimeout(timer); remaining.current = Math.max(0, remaining.current - (Date.now() - start)); };
  }, [actionable, active, hovered, focused]);
  const heading = notice.pullRequestTitle ?? notice.title;
  const changes = actionable ? null : notice.changes;
  return <article className={`app-notice is-${notice.kind.toLowerCase()}`} aria-label={describe(notice)}
    onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
    onFocusCapture={() => setFocused(true)} onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false); }}>
    <div className="app-notice__head"><span className="app-notice__dot" aria-hidden="true" /><span>{label(notice)}</span>
      <time className="app-notice__time" dateTime={notice.occurredAt}>{ago(notice.occurredAt)}</time>
      <button type="button" className="app-notice__close" aria-label={`Close ${label(notice).toLowerCase()} notification`} onClick={onDismiss}><X size={14} /></button>
    </div>
    <div className="app-notice__subject"><h2 title={heading}>{heading}</h2>
      {notice.pullRequest && <div className="app-notice__ref" title={notice.pullRequest}>{notice.pullRequest}</div>}</div>
    {changes ? <div className="app-notice__changes">{changes.update && <span>Update</span>}<span>{count(changes.commits, 'commit')}</span>
      <span>{count(changes.files, 'file')}</span><span className="is-added">+{changes.additions}</span><span className="is-deleted">−{changes.deletions}</span></div>
      : (actionable || notice.kind === 'NEXT_WORK') && <p className="app-notice__message" title={notice.message}>{notice.message}</p>}
    <div className="app-notice__actions"><button type="button" onClick={onOpen}>{notice.id.startsWith(APPROVAL_NOTICE) ? 'Answer' : ACTIONS[notice.kind]}</button>
      <span title={notice.repository}>{notice.repository}</span></div>
  </article>;
}

/** The bottom-right alert: the newest notice not seen yet. Every notice waits in the Assistant panel, the one inbox. */
export default function NoticeStack({ bridge = window.noticeBridge }: { bridge?: NoticeBridge }) {
  const [state, setState] = useState<NoticeSnapshot>({ notices: [], popupIds: [], viewedIds: [], active: false, maxHeight: 600 });
  const [error, setError] = useState<{ id: string; message: string } | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let active = true;
    let received = false;
    let prior = new Set<string>();
    const update = (next: NoticeSnapshot) => {
      if (!active) return;
      const added = next.notices.filter(item => next.popupIds.includes(item.id) && !prior.has(item.id));
      if (next.active && added.length) setAnnouncement(added.map(describe).join('. '));
      prior = new Set(next.popupIds);
      setState(next);
    };
    const stop = bridge.subscribe(next => { received = true; update(next); });
    void bridge.read().then(next => { if (!received) update(next); }).catch(() => {});
    return () => { active = false; stop(); };
  }, [bridge]);
  const alert = state.notices.filter(notice => state.popupIds.includes(notice.id))
    .reduce<AppNotice | null>((newest, notice) => newest === null || Date.parse(notice.occurredAt) > Date.parse(newest.occurredAt) ? notice : newest, null);
  const shown = alert !== null;
  useLayoutEffect(() => {
    const node = root.current;
    if (!node) { bridge.resize(0); return; }
    const measure = () => bridge.resize(Math.ceil(node.getBoundingClientRect().height));
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    measure();
    return () => observer.disconnect();
  }, [bridge, shown]);
  if (alert === null) return null;
  const act = (action: Promise<void>, message: string) => {
    setError(null);
    void action.catch(() => setError({ id: alert.id, message }));
  };
  return <div ref={root} className="app-notices" style={{ '--notice-max-height': `${state.maxHeight}px` } as CSSProperties}>
    <span className="app-notices__sr" role="status" aria-live="polite" aria-atomic="true">{announcement}</span>
    <section className="app-notices__cards" aria-label="App notices">
      {/* × only closes the alert; the notice stays in the Assistant panel. */}
      <NoticeCard key={alert.id} notice={alert} active={state.active} onDismiss={() => act(bridge.dismiss(alert.id), 'Unable to dismiss this notice.')}
        onOpen={() => act(bridge.open(alert.id), 'Unable to open this notice. Try again.')} />
    </section>
    {error?.id === alert.id && <div className="app-notices__error" role="alert">{error.message}</div>}
  </div>;
}
