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
import { FileText, Square } from 'lucide-react';
import { useCallback, useContext, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { ago } from '@bytequay/ui/relativeTime';
import { remoteErrorMessage } from '../remoteError';
import { LayerCovered } from '../Zoom';
import type { ArtifactDto } from './contracts';
import '../css/pulls.css';
import '../css/artifacts.css';

const POLL_MS = 2_000;

export default function ArtifactsPage({ workspaceId }: { workspaceId: string }) {
  const [artifacts, setArtifacts] = useState<ArtifactDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const live = useRef(true);
  useEffect(() => () => { live.current = false; }, []);

  const refresh = useCallback(() => window.bridge.listArtifacts(workspaceId).then(items => {
    if (!live.current) return;
    setArtifacts(items);
    setError(null);
  }, failure => { if (live.current) setError(remoteErrorMessage(failure)); }), [workspaceId]);

  useEffect(() => {
    setArtifacts(null);
    setSelected(null);
    void refresh();
  }, [refresh]);

  const writing = artifacts?.some(item => item.state === 'WRITING') ?? false;
  useEffect(() => {
    if (!writing) return;
    const timer = window.setInterval(() => { void refresh(); }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [refresh, writing]);

  // An action's answer shows at once; the next read brings the rest of the list along.
  const took = (artifact: ArtifactDto): void => {
    setArtifacts(items => [artifact, ...(items ?? []).filter(item => item.id !== artifact.id)]);
    void refresh();
  };

  const shown = artifacts?.find(item => item.id === selected) ?? artifacts?.[0] ?? null;
  return <div className="artifacts-page">
    <section className="artifacts-list" aria-label="Artifacts">
      <header className="pulls-list__toolbar"><h2 className="artifacts-list__title">Artifacts</h2></header>
      <Composer label="Ask for a page" placeholder="Teach me, explain or design something in this repository…"
        onSend={ask => window.bridge.askArtifact(workspaceId, ask).then(artifact => { took(artifact); setSelected(artifact.id); })} />
      <div className="pulls-list__rows">
        {error !== null ? <div className="artifacts-empty" role="alert">{error}</div>
          : artifacts === null ? <div className="artifacts-empty" role="status">Loading artifacts…</div>
            : artifacts.length === 0 ? <div className="artifacts-empty">Pages you ask for appear here.</div>
              : artifacts.map(item => <button key={item.id} type="button" className={`pull-row${item.id === shown?.id ? ' is-selected' : ''}`}
                aria-pressed={item.id === shown?.id} onClick={() => setSelected(item.id)}>
                <span className="pull-row__title"><FileText size={16} aria-hidden="true" /><span>{item.title}</span></span>
                <span className="pull-row__meta">
                  {item.state === 'WRITING' ? <span className="artifacts-writing"><Elapsed since={item.updatedAt} /></span>
                    : item.state === 'FAILED' ? <span className="artifacts-failed">Failed</span>
                      : <span className="pull-row__meta-time">{ago(item.writtenAt ?? item.updatedAt)}</span>}
                </span>
              </button>)}
      </div>
    </section>
    {shown !== null && <ArtifactDetail workspaceId={workspaceId} artifact={shown} onChanged={took} onOpen={setSelected} />}
  </div>;
}

function ArtifactDetail({ workspaceId, artifact, onChanged, onOpen }: {
  workspaceId: string; artifact: ArtifactDto; onChanged: (artifact: ArtifactDto) => void; onOpen: (artifactId: string) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  // Kept mounted across pages so the native view moves rather than flickers; only the error belongs to one page.
  useEffect(() => { setError(null); }, [artifact.id]);
  const act = (work: Promise<unknown>): void => {
    setError(null);
    work.catch(failure => setError(remoteErrorMessage(failure)));
  };
  const cards = artifact.next.filter(card => card.state === 'SHOWN');
  return <section className="artifacts-detail" aria-label={artifact.title}>
    {artifact.writtenAt !== null
      ? <ArtifactFrame workspaceId={workspaceId} artifactId={artifact.id} version={artifact.writtenAt} />
      : <div className="artifacts-detail__placeholder">
        {artifact.state === 'WRITING' ? <p role="status">Writing this page… <Elapsed since={artifact.updatedAt} /></p>
          : artifact.state === 'FAILED' ? null : <p>This page has no content.</p>}
      </div>}
    <div className="artifacts-detail__foot">
      {artifact.state === 'FAILED' && <div className="artifacts-failure" role="alert">
        <span>{artifact.failure ?? 'The page could not be written'}</span>
        <button type="button" className="artifacts-button" onClick={() => act(window.bridge.changeArtifact(workspaceId, artifact.id, artifact.ask).then(onChanged))}>
          Try again
        </button>
      </div>}
      {cards.length > 0 && artifact.state !== 'WRITING' && <div className="artifacts-next" aria-label="Next">
        {cards.map(card => <div key={card.id} className="artifacts-next__card" title={card.ask}>
          <span className="artifacts-next__title">{card.title}</span>
          <button type="button" className="artifacts-button" onClick={() => act(window.bridge.writeNextArtifact(workspaceId, artifact.id, card.id)
            .then(written => { onChanged(written); onOpen(written.id); }))}>Write it</button>
          <button type="button" className="artifacts-button" onClick={() => act(window.bridge.dismissNextArtifact(workspaceId, artifact.id, card.id).then(onChanged))}>
            Not now
          </button>
        </div>)}
      </div>}
      {error !== null && <div className="artifacts-failure" role="alert"><span>{error}</span></div>}
      {artifact.state === 'WRITING'
        ? <div className="artifacts-running" role="status">
          <span>{artifact.writtenAt === null ? 'Writing' : 'Rewriting'}… <Elapsed since={artifact.updatedAt} /></span>
          <button type="button" className="artifacts-button" onClick={() => act(window.bridge.stopArtifact(workspaceId, artifact.id))}>
            <Square size={12} aria-hidden="true" />Stop
          </button>
        </div>
        : artifact.writtenAt !== null && <Composer key={artifact.id} label="Ask to change this page" placeholder="Ask to change this page…"
          onSend={ask => window.bridge.changeArtifact(workspaceId, artifact.id, ask).then(onChanged)} />}
    </div>
  </section>;
}

/** A request box: Enter sends, Shift+Enter adds a line, and the text stays when sending fails. */
function Composer({ label, placeholder, onSend }: { label: string; placeholder: string; onSend: (text: string) => Promise<unknown> }) {
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const send = (event?: FormEvent): void => {
    event?.preventDefault();
    if (sending) return;
    if (text.trim() === '') {
      setError('Say what the page should be about.');
      return;
    }
    setSending(true);
    setError(null);
    onSend(text.trim()).then(() => setText(''), failure => setError(remoteErrorMessage(failure))).finally(() => setSending(false));
  };
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) send(event);
  };
  return <form className="artifacts-composer" onSubmit={send}>
    <textarea aria-label={label} placeholder={placeholder} rows={2} value={text} onKeyDown={keyDown}
      onChange={event => { setText(event.target.value); setError(null); }} />
    <div className="artifacts-composer__row">
      {error !== null && <span className="artifacts-composer__error" role="alert">{error}</span>}
      <button type="submit" className="artifacts-button" disabled={sending}>{sending ? 'Sending…' : 'Send'}</button>
    </div>
  </form>;
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

/** The page itself is a native view main lays over this box; it hides whenever app content covers the box. */
function ArtifactFrame({ workspaceId, artifactId, version }: { workspaceId: string; artifactId: string; version: string }) {
  const element = useRef<HTMLDivElement>(null);
  const sent = useRef<string | null>(null);
  const covered = useContext(LayerCovered);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => { setError(null); }, [artifactId]);

  useEffect(() => {
    const frame = element.current;
    if (frame === null) return;
    let live = true;
    const layout = () => {
      const obscured = covered || [...document.querySelectorAll('[role="dialog"]')].some(dialog => !dialog.contains(frame) && dialog.getClientRects().length > 0);
      const box = frame.getBoundingClientRect();
      const visible = error === null && !obscured && box.width > 0 && box.height > 0;
      const target = visible ? { workspaceId, artifactId, version } : null;
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
  }, [artifactId, attempt, covered, error, version, workspaceId]);

  useEffect(() => () => { void window.bridge.showArtifact(null, null).catch(() => {}); }, []);

  return <div className="artifacts-page__view" ref={element}>
    {error !== null && <div className="artifacts-empty" role="alert">
      <p>This page could not be opened: {error}</p>
      <button type="button" className="artifacts-button" onClick={() => { setError(null); setAttempt(value => value + 1); }}>Try again</button>
    </div>}
  </div>;
}
