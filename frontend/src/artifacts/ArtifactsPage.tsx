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
import { FileText } from 'lucide-react';
import { useContext, useEffect, useRef, useState } from 'react';
import { ago } from '@bytequay/ui/relativeTime';
import { remoteErrorMessage } from '../remoteError';
import { LayerCovered } from '../Zoom';
import type { ArtifactSummaryDto } from './contracts';
import '../css/pulls.css';
import '../css/artifacts.css';

export default function ArtifactsPage({ workspaceId }: { workspaceId: string }) {
  const [artifacts, setArtifacts] = useState<ArtifactSummaryDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    setArtifacts(null);
    setError(null);
    window.bridge.listArtifacts(workspaceId).then(items => { if (live) setArtifacts(items); }, failure => { if (live) setError(remoteErrorMessage(failure)); });
    return () => { live = false; };
  }, [workspaceId]);
  const shown = artifacts?.find(item => item.id === selected) ?? artifacts?.[0] ?? null;
  return <div className="artifacts-page">
    <section className="artifacts-list" aria-label="Artifacts">
      <header className="pulls-list__toolbar"><h2 className="artifacts-list__title">Artifacts</h2></header>
      <div className="pulls-list__rows">
        {error !== null ? <div className="artifacts-empty" role="alert">{error}</div>
          : artifacts === null ? <div className="artifacts-empty" role="status">Loading artifacts…</div>
            : artifacts.length === 0 ? <div className="artifacts-empty">Pages you ask for appear here.</div>
              : artifacts.map(item => <button key={item.id} type="button" className={`pull-row${item.id === shown?.id ? ' is-selected' : ''}`}
                aria-pressed={item.id === shown?.id} onClick={() => setSelected(item.id)}>
                <span className="pull-row__title"><FileText size={16} aria-hidden="true" /><span>{item.title}</span></span>
                <span className="pull-row__meta"><span className="pull-row__meta-time">{ago(item.updatedAt)}</span></span>
              </button>)}
      </div>
    </section>
    {shown !== null && <ArtifactFrame workspaceId={workspaceId} artifactId={shown.id} />}
  </div>;
}

/** The page itself is a native view main lays over this box; it hides whenever app content covers the box. */
function ArtifactFrame({ workspaceId, artifactId }: { workspaceId: string; artifactId: string }) {
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
      const target = visible ? { workspaceId, artifactId } : null;
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
  }, [artifactId, attempt, covered, error, workspaceId]);

  useEffect(() => () => { void window.bridge.showArtifact(null, null).catch(() => {}); }, []);

  return <div className="artifacts-page__view" ref={element}>
    {error !== null && <div className="artifacts-empty" role="alert">
      <p>This page could not be opened: {error}</p>
      <button type="button" className="pulls-toolbar-button" onClick={() => { setError(null); setAttempt(value => value + 1); }}>Try again</button>
    </div>}
  </div>;
}
