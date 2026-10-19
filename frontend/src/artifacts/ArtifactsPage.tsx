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
import { ArrowLeft, ArrowUp, ChevronRight, Clock, FileText, Files, LoaderCircle, Lock, TriangleAlert } from 'lucide-react';
import { useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import Composer from '../Composer';
import { renderMarkdown } from '../agents/markdown';
import { ago } from '@bytequay/ui/relativeTime';
import { remoteErrorMessage } from '../remoteError';
import { useResizablePane } from '../useResizablePane';
import { LayerCovered } from '../Zoom';
import type { ArtifactItemDto, ArtifactPageDto, ArtifactRowDto, ArtifactViewDto } from './contracts';
import '../css/artifacts.css';

const LIST_POLL_MS = 2_000;
const VIEW_POLL_MS = 1_000;
// The backend's limit on one message.
const MAXIMUM_MESSAGE = 4_000;
const BUSY = 'Wait for';

/** Artifacts: the conversations of a workspace, then one conversation with its pages beside it. */
export default function ArtifactsPage({ workspaceId }: { workspaceId: string }) {
  const [open, setOpen] = useState<{ id: string; view: ArtifactViewDto | null } | null>(null);
  useEffect(() => { setOpen(null); }, [workspaceId]);
  return open === null
    ? <ConversationList workspaceId={workspaceId} onOpen={(id, view) => setOpen({ id, view })} />
    : <Conversation key={open.id} workspaceId={workspaceId} artifactId={open.id} initial={open.view} onBack={() => setOpen(null)} />;
}

function ConversationList({ workspaceId, onOpen }: { workspaceId: string; onOpen: (id: string, view: ArtifactViewDto | null) => void }) {
  const [rows, setRows] = useState<ArtifactRowDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [startError, setStartError] = useState<string | null>(null);
  const live = useLive();
  const refresh = useCallback(() => window.bridge.listArtifacts(workspaceId).then(items => {
    if (!live.current) return;
    setRows(items);
    setError(null);
  }, failure => { if (live.current) setError(remoteErrorMessage(failure)); }), [live, workspaceId]);
  useEffect(() => { void refresh(); }, [refresh]);
  usePoll(refresh, rows?.some(row => row.artifact.working) ?? false, LIST_POLL_MS);

  const start = (text: string) => {
    setStartError(null);
    return window.bridge.startArtifact(workspaceId, text).then(view => onOpen(view.artifact.id, view), failure => {
      if (live.current) setStartError(remoteErrorMessage(failure));
      throw failure;
    });
  };
  return <section className="artifacts-page" aria-label="Artifacts">
    <header className="artifacts-bar"><span className="artifacts-bar__title">Artifacts</span></header>
    <div className="artifacts-scroll">
      <div className="artifacts-list">
        <MessageBox className="is-start" label="Ask about this repository" placeholder="Ask about this repository…" rows={2} send={start}
          extra={<span className="artifacts-lock" title="The agent reads this repository and cannot change it"><Lock size={13} aria-hidden="true" /></span>} />
        {startError !== null && <Notice text={startError} />}
        {error !== null ? <p className="artifacts-hint" role="alert">{error}</p>
          : rows?.length === 0 ? <p className="artifacts-hint">Ask about this repository; the agent can turn the conversation into a page.</p>
            : rows !== null && <div className="artifacts-rows">
              {rows.map(({ artifact, pages }) => <button key={artifact.id} type="button" className="artifacts-row" onClick={() => onOpen(artifact.id, null)}>
                <FileText size={15} className="artifacts-row__icon" aria-hidden="true" />
                <span className="artifacts-row__title">{artifact.title}</span>
                {artifact.working && <span className="artifacts-row__working" title="Answering"><LoaderCircle size={13} className="artifacts-spin" aria-label="Answering" /></span>}
                {pages > 0 && <span className="artifacts-row__pages" title={pages === 1 ? '1 page' : `${pages} pages`}><Files size={13} aria-hidden="true" />{pages}</span>}
                <span className="artifacts-row__age">{ago(artifact.updatedAt)}</span>
              </button>)}
            </div>}
      </div>
    </div>
  </section>;
}

function Conversation({ workspaceId, artifactId, initial, onBack }: {
  workspaceId: string; artifactId: string; initial: ArtifactViewDto | null; onBack: () => void;
}) {
  const [view, setView] = useState<ArtifactViewDto | null>(initial);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [sendError, setSendError] = useState<string | null>(null);
  const [shown, setShown] = useState<string | null>(null);
  const live = useLive();
  // Each answer bumps this, so a poll that left before a send cannot bring back the idle state the send ended.
  const latest = useRef(0);
  const took = useCallback((next: ArtifactViewDto) => {
    latest.current++;
    if (live.current) setView(next);
  }, [live]);
  const refresh = useCallback(() => {
    const asked = ++latest.current;
    return window.bridge.viewArtifact(workspaceId, artifactId).then(next => {
      if (!live.current || asked !== latest.current) return;
      setView(next);
      setLoadError(null);
    }, failure => { if (live.current) setLoadError(remoteErrorMessage(failure)); });
  }, [artifactId, live, workspaceId]);
  useEffect(() => { void refresh(); }, [refresh]);
  const working = view?.artifact.working ?? false;
  usePoll(refresh, working, VIEW_POLL_MS);

  const items = view?.items ?? [];
  const pages = view?.pages ?? [];
  // A new save shows at once; until the next one the panel stays on what the user picked.
  const newest = pages.length === 0 ? null : pages[pages.length - 1].id;
  useEffect(() => { setShown(newest); }, [newest]);
  const page = pages.find(entry => entry.id === shown) ?? pages[pages.length - 1] ?? null;
  const offer = [...items].reverse().find(item => item.kind === 'OFFER')?.offer ?? null;
  const making = working && pages.length === 0 && offer?.state === 'ACCEPTED';
  const since = [...items].reverse().find(item => item.kind === 'USER')?.at ?? view?.artifact.updatedAt ?? new Date().toISOString();

  const act = (work: Promise<ArtifactViewDto>): Promise<void> => {
    setSendError(null);
    return work.then(took, failure => {
      if (live.current) setSendError(remoteErrorMessage(failure));
      throw failure;
    });
  };
  const send = (text: string) => act(window.bridge.sendArtifact(workspaceId, artifactId, text));
  const answer = (itemId: string, accept: boolean) => { act(window.bridge.answerArtifactOffer(workspaceId, artifactId, itemId, accept)).catch(() => {}); };
  const stop = () => { window.bridge.stopArtifact(workspaceId, artifactId).catch(failure => { if (live.current) setSendError(remoteErrorMessage(failure)); }); };

  const container = useRef<HTMLElement>(null);
  const { width, startResize } = useResizablePane(window.innerWidth < 1100 ? 340 : 420, 300, () => (container.current?.clientWidth ?? 1000) - 360);
  const split = pages.length > 0 || making;

  return <section ref={container} className={`artifacts-page artifacts-conversation${split ? ' has-page' : ''}`} aria-label={view?.artifact.title ?? 'Conversation'}>
    <div className="artifacts-chat" style={split ? { width: width ?? 420 } : undefined}>
      <header className="artifacts-bar">
        <button type="button" className="artifacts-icon-button" aria-label="Back to Artifacts" title="Back to Artifacts" onClick={onBack}>
          <ArrowLeft size={15} aria-hidden="true" />
        </button>
        <span className="artifacts-bar__title">{view?.artifact.title}</span>
      </header>
      <div className="artifacts-chat__scroll">
        <div className="artifacts-chat__column">
          {items.map((item, index) => <Item key={item.id} item={item} items={items} index={index} pages={pages} shown={page?.id ?? null} working={working}
            onShow={setShown} onAnswer={answer} onRetry={text => { send(text).catch(() => {}); }} />)}
          {working && <div className="artifacts-line" role="status">
            <LoaderCircle size={13} className="artifacts-spin artifacts-line__icon is-accent" aria-hidden="true" />Reading…<Elapsed since={since} />
          </div>}
          {sendError !== null && <Notice text={sendError} />}
          {loadError !== null && <Notice text={loadError} />}
        </div>
      </div>
      <div className="artifacts-chat__composer">
        <MessageBox label="Reply" placeholder="Reply, or ask for a change…" working={working} onStop={stop} send={send} />
      </div>
    </div>
    {split && <>
      <div className="artifacts-divider" role="separator" aria-orientation="vertical" aria-label="Resize the conversation" title="Drag to resize"
        onMouseDown={event => startResize(event)} />
      <PagePanel workspaceId={workspaceId} artifactId={artifactId} pages={pages} page={page} making={making} title={offer?.title ?? 'New page'} since={since}
        onShow={setShown} />
    </>}
  </section>;
}

/** One line of the conversation. */
function Item({ item, items, index, pages, shown, working, onShow, onAnswer, onRetry }: {
  item: ArtifactItemDto; items: ArtifactItemDto[]; index: number; pages: ArtifactPageDto[]; shown: string | null; working: boolean;
  onShow: (pageId: string) => void; onAnswer: (itemId: string, accept: boolean) => void; onRetry: (text: string) => void;
}) {
  switch (item.kind) {
    case 'USER':
      return <div className="agt-user"><div className="agt-user__bubble">{item.text}</div></div>;
    case 'REPLY':
      return <div className="artifacts-reply">{renderMarkdown(item.text)}</div>;
    case 'ACTIVITY': {
      // "DeleteManager.java · 4 steps": the file in front, the count after.
      const [what, steps] = item.text.split(' · ');
      const growing = working && index === items.length - 1;
      return <div className="artifacts-line" title={item.text}>
        {growing ? <LoaderCircle size={13} className="artifacts-spin artifacts-line__icon" aria-hidden="true" /> : <ChevronRight size={13} className="artifacts-line__icon" aria-hidden="true" />}
        <span className="artifacts-line__what">{what}</span>
        {steps !== undefined && <><span className="artifacts-dim" aria-hidden="true">·</span><span>{steps}</span></>}
      </div>;
    }
    case 'OFFER': {
      const offer = item.offer;
      if (offer === null) return null;
      if (offer.state !== 'SHOWN') {
        const made = offer.state === 'ACCEPTED';
        return <div className="artifacts-line" title={`Offered “${offer.title}”; ${made ? 'you made it' : 'you said not yet'}`}>
          <FileText size={13} className="artifacts-line__icon" aria-hidden="true" />Page offered<span className="artifacts-dim" aria-hidden="true">·</span>{made ? 'made' : 'not yet'}
        </div>;
      }
      return <div className="artifacts-offer">
        <div className="artifacts-offer__head">
          <FileText size={15} className="artifacts-offer__icon" aria-hidden="true" />
          <div className="artifacts-offer__text">
            <div className="artifacts-offer__title">{offer.title}</div>
            <div className="artifacts-offer__covers">{offer.covers}</div>
          </div>
        </div>
        <div className="artifacts-offer__actions">
          <button type="button" className="artifacts-button is-accent" disabled={working} onClick={() => onAnswer(item.id, true)}>Make the page</button>
          <button type="button" className="artifacts-button" disabled={working} onClick={() => onAnswer(item.id, false)}>Not yet</button>
        </div>
      </div>;
    }
    case 'PAGE': {
      const page = pages.find(entry => entry.id === item.pageId);
      if (page === undefined) return <div className="artifacts-line">{item.text}</div>;
      return <button type="button" className={`artifacts-pageline${page.id === shown ? ' is-shown' : ''}`} title={`Show v${page.version} in the panel`} onClick={() => onShow(page.id)}>
        <FileText size={15} className="artifacts-pageline__icon" aria-hidden="true" />{page.title}<span className="artifacts-dim">· v{page.version}</span>
      </button>;
    }
    case 'FAILURE': {
      // Try again sends the message this failure answered once more.
      const asked = items.slice(0, index).reverse().find(entry => entry.kind === 'USER')?.text ?? null;
      const stopped = item.text.startsWith('Stopped');
      return <div className="artifacts-line is-failure" title={stopped ? 'This turn was stopped. What it said or saved stays.' : item.text}>
        <TriangleAlert size={14} className={`artifacts-line__icon${stopped ? '' : ' is-warn'}`} aria-hidden="true" />
        <span className="artifacts-line__what">{item.text}</span>
        {asked !== null && <button type="button" className="artifacts-button is-small" disabled={working} onClick={() => onRetry(asked)}>Try again</button>}
      </div>;
    }
    default:
      return null;
  }
}

/** A message the backend refused, said where it was sent; a busy workspace gets a clock, anything else a warning. */
function Notice({ text }: { text: string }) {
  const busy = text.startsWith(BUSY);
  return <div className="artifacts-line" role="alert" title={busy ? 'Only one conversation per repository answers at a time. Send again when it finishes.' : undefined}>
    {busy ? <Clock size={13} className="artifacts-line__icon" aria-hidden="true" /> : <TriangleAlert size={14} className="artifacts-line__icon is-warn" aria-hidden="true" />}
    <span className="artifacts-line__what">{text}</span>
  </div>;
}

function PagePanel({ workspaceId, artifactId, pages, page, making, title, since, onShow }: {
  workspaceId: string; artifactId: string; pages: ArtifactPageDto[]; page: ArtifactPageDto | null; making: boolean; title: string; since: string;
  onShow: (pageId: string) => void;
}) {
  const keys = [...new Set(pages.map(entry => entry.key))];
  const newestOf = (key: string) => pages.filter(entry => entry.key === key).at(-1);
  const versions = page === null ? [] : pages.filter(entry => entry.key === page.key);
  return <section className="artifacts-panel" aria-label="Page">
    <header className="artifacts-bar artifacts-panel__bar">
      {keys.length > 1
        ? <div className="artifacts-tabs" aria-label="Pages">
          {keys.map(key => {
            const entry = newestOf(key);
            return entry && <button key={key} type="button" className={`artifacts-tabs__tab${key === page?.key ? ' is-selected' : ''}`} aria-pressed={key === page?.key}
              onClick={() => onShow(entry.id)}><FileText size={14} aria-hidden="true" />{entry.title}</button>;
          })}
        </div>
        : <span className="artifacts-panel__title"><FileText size={15} aria-hidden="true" /><span>{page?.title ?? title}</span></span>}
      {page !== null && <span className="artifacts-panel__commit" title={`Written from commit ${page.commit}`}>{page.commit.slice(0, 7)}</span>}
      <span className="artifacts-spacer" />
      <div className="artifacts-versions" aria-label="Versions">
        {versions.map(entry => <button key={entry.id} type="button" className={entry.id === page?.id ? 'is-selected' : ''} aria-pressed={entry.id === page?.id}
          onClick={() => onShow(entry.id)}>v{entry.version}</button>)}
        {making && <span className="artifacts-versions__writing" title="Writing the page">
          <LoaderCircle size={11} className="artifacts-spin" aria-hidden="true" />v1 writing…
        </span>}
      </div>
    </header>
    {page !== null
      ? <ArtifactFrame workspaceId={workspaceId} artifactId={artifactId} pageId={page.id} />
      : <div className="artifacts-panel__placeholder" role="status">
        <LoaderCircle size={14} className="artifacts-spin is-accent" aria-hidden="true" />Writing the page…<Elapsed since={since} />
      </div>}
  </section>;
}

/** A message box: Enter sends, Shift+Enter adds a line, and the text stays when sending fails. */
function MessageBox({ label, placeholder, rows = 1, working = false, className = '', extra, onStop, send }: {
  label: string; placeholder: string; rows?: number; working?: boolean; className?: string; extra?: ReactNode; onStop?: () => void;
  send: (text: string) => Promise<unknown>;
}) {
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const submit = () => {
    const text = draft.trim();
    if (text === '' || sending || working) return;
    setSending(true);
    send(text).then(() => setDraft(current => current.trim() === text ? '' : current), () => {}).finally(() => setSending(false));
  };
  return <Composer className={`artifacts-compose ${className}`} aria-label={label} placeholder={placeholder} rows={rows} value={draft} maxLength={MAXIMUM_MESSAGE}
    onChange={event => setDraft(event.target.value)} busy={sending} onSubmit={submit}
    submitEnabled={draft.trim() !== '' && !sending && !working} showSubmit={!working} submitLabel="Send" submitTitle="Send (Enter)"
    submitContent={<ArrowUp size={16} aria-hidden="true" />}
    trailingActions={working && <button type="button" className="agt-send is-stop" aria-label="Stop" title="Stop" onClick={onStop}>
      <span className="agt-stop-square" aria-hidden="true" />
    </button>}>
    {extra}
  </Composer>;
}

function Elapsed({ since }: { since: string }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, []);
  const seconds = Math.max(0, Math.floor((now - Date.parse(since)) / 1_000));
  return <span className="artifacts-elapsed">{Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}</span>;
}

function useLive() {
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => { live.current = false; };
  }, []);
  return live;
}

function usePoll(refresh: () => Promise<unknown>, active: boolean, ms: number): void {
  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => { void refresh(); }, ms);
    return () => window.clearInterval(timer);
  }, [active, ms, refresh]);
}

/** The page itself is a native view main lays over this box; it hides whenever app content covers the box. */
function ArtifactFrame({ workspaceId, artifactId, pageId }: { workspaceId: string; artifactId: string; pageId: string }) {
  const element = useRef<HTMLDivElement>(null);
  const sent = useRef<string | null>(null);
  const covered = useContext(LayerCovered);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => { setError(null); }, [pageId]);

  useEffect(() => {
    const frame = element.current;
    if (frame === null) return;
    let live = true;
    const layout = () => {
      const obscured = covered || [...document.querySelectorAll('[role="dialog"]')].some(dialog => !dialog.contains(frame) && dialog.getClientRects().length > 0);
      const box = frame.getBoundingClientRect();
      const visible = error === null && !obscured && box.width > 0 && box.height > 0;
      const target = visible ? { workspaceId, artifactId, pageId } : null;
      const bounds = visible ? { x: box.x, y: box.y, width: box.width, height: box.height } : null;
      // Every DOM change anywhere in the app lands here; only a real change reaches main.
      const key = JSON.stringify([target, bounds, attempt]);
      if (key === sent.current) return;
      sent.current = key;
      window.bridge.showArtifact(target, bounds).catch(failure => {
        if (!live) return;
        sent.current = null;
        setError(remoteErrorMessage(failure));
      });
    };
    const resize = new ResizeObserver(layout);
    resize.observe(frame);
    const dialogs = new MutationObserver(layout);
    dialogs.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'hidden', 'open'] });
    window.addEventListener('resize', layout);
    layout();
    return () => { live = false; resize.disconnect(); dialogs.disconnect(); window.removeEventListener('resize', layout); };
  }, [artifactId, attempt, covered, error, pageId, workspaceId]);

  useEffect(() => () => { void window.bridge.showArtifact(null, null).catch(() => {}); }, []);

  return <div className="artifacts-page__view" ref={element}>
    {error !== null && <div className="artifacts-panel__placeholder" role="alert">
      <span>This page could not be opened: {error}</span>
      <button type="button" className="artifacts-button" onClick={() => { setError(null); setAttempt(value => value + 1); }}>Try again</button>
    </div>}
  </div>;
}
