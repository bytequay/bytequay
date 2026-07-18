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
import { useEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import type { DashboardPR } from '../types/dashboardPr';
import { PULL_TABS, rowsForTab } from './model';
import type { PullTab } from './model';
import PullRowItem from './PullRowItem';
import PullDetailPane from './PullDetailPane';
import { PaneToggleIcon } from './atoms';
import '../css/pulls.css';

/**
 * Screen 1 of the PR redesign — the standalone "Pull requests" surface
 * (docs/mockups/design/pr-redesign/Pull Requests.dc.html): filter tabs +
 * PR list on the left, drag-resizable detail pane on the right. The list
 * is live dashboard data; the pane renders <PullDetailPane> (header +
 * Overview tab) off the same row's unified PR bundle.
 */

const DETAIL_MIN = 460;
const DETAIL_MAX = 1150;
const DETAIL_DEFAULT = 940;

export default function PullsScreen({ onOpenWorkspacePr }: {
  /** Routes a PR into its repo's workspace surface; {@code agent} opens it
   *  with the agent-review column already showing. Omitted → the pane's
   *  workspace-bound buttons stay inert. */
  onOpenWorkspacePr?: (repo: string, prNumber: number, opts: { agent: boolean }) => void;
}) {
  const [prs, setPrs] = useState<DashboardPR[]>([]);
  const [tab, setTab] = useState<PullTab>('all');
  const [sel, setSel] = useState<string | null>(null);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detW, setDetW] = useState(DETAIL_DEFAULT);
  // repo fullName (lowercased) → workspace id, for the pane's workspace-
  // bound agent buttons — same resolution App's legacy-repo redirect uses.
  const [wsByRepo, setWsByRepo] = useState<Map<string, string>>(new Map());
  const alive = useRef(true);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.listWorkspaces()
      .then(cards => {
        if (cancelled) return;
        setWsByRepo(new Map(cards.flatMap(card => card.repository == null
          ? []
          : [[card.repository.fullName.toLowerCase(), card.id] as const])));
      })
      .catch(() => { /* transient; the buttons stay inert */ });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    alive.current = true;
    const load = async () => {
      try {
        const data = await window.bridge.fetchDashboardPrs();
        if (alive.current) setPrs(data);
      } catch {
        // Backend not up yet — the sync below retries the fetch.
      }
    };
    void load();
    // Kick a GitHub sync in the background, then refresh the list once.
    void (async () => {
      try {
        await window.bridge.syncDashboardPrs();
        await load();
      } catch {
        // Sync is best-effort; the initial fetch already rendered.
      }
    })();
    return () => { alive.current = false; };
  }, []);

  const rows = useMemo(() => rowsForTab(prs, tab), [prs, tab]);
  const selRow = sel !== null ? rows.find(r => r.id === sel) ?? null : null;
  const paneShown = selRow !== null && paneOpen;
  const wide = !paneShown;
  const selWorkspaceId = selRow === null ? undefined : wsByRepo.get(selRow.repo.toLowerCase());

  // Same start path as the dashboard's handleAgentReview: optimistic
  // reviewState flip, plain start (the button only shows when no review
  // exists yet), revert on failure.
  const assignAgent = (row: { id: string; repo: string }) => {
    const previous = prs.find(pr => pr.id === row.id)?.reviewState;
    setPrs(current => current.map(pr => pr.id === row.id ? { ...pr, reviewState: 'running' } : pr));
    void window.bridge.startAgentReview(row.id, { workspaceId: wsByRepo.get(row.repo.toLowerCase()) })
      .catch(() => {
        setPrs(current => current.map(pr => pr.id === row.id ? { ...pr, reviewState: previous ?? 'none' } : pr));
      });
  };

  const detDragStart = (e: ReactMouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = detW;
    const mv = (ev: globalThis.MouseEvent) =>
      setDetW(Math.max(DETAIL_MIN, Math.min(DETAIL_MAX, startW + (startX - ev.clientX))));
    const up = () => {
      window.removeEventListener('mousemove', mv);
      window.removeEventListener('mouseup', up);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', mv);
    window.addEventListener('mouseup', up);
  };

  return (
    <div style={{ display: 'flex', minWidth: 0, minHeight: 0, height: '100%', background: '#fff' }}>
      {/* ═══ Work list ═══ */}
      <div style={{ flex: 1, minWidth: 220, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 2, padding: '10px 12px 8px', borderBottom: '1px solid #e7e9ec', flexShrink: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 2, flex: 1, minWidth: 0, overflow: 'hidden' }}>
            {PULL_TABS.map(t => (
              <button
                key={t.key}
                onClick={() => setTab(t.key)}
                style={{
                  padding: '4px 12px',
                  border: 0,
                  borderRadius: 7,
                  background: tab === t.key ? '#e7e9ec' : 'transparent',
                  fontSize: 13,
                  fontWeight: tab === t.key ? 600 : 500,
                  color: tab === t.key ? '#17191c' : '#59636e',
                  cursor: 'pointer',
                  whiteSpace: 'nowrap',
                }}
              >
                {t.label}
              </button>
            ))}
          </div>
          <span
            className="pl-hov-tgl"
            onClick={() => setPaneOpen(o => !o)}
            title="Toggle PR panel"
            style={{
              width: 28,
              height: 28,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              cursor: 'pointer',
              borderRadius: 7,
              color: paneShown ? '#17191c' : '#8b949e',
              background: paneShown ? '#e7e9ec' : 'transparent',
              flexShrink: 0,
            }}
          >
            <PaneToggleIcon />
          </span>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '6px 8px 24px', display: 'flex', flexDirection: 'column', gap: 1 }}>
          {rows.map(row => (
            <PullRowItem
              key={row.id}
              row={row}
              wide={wide}
              selected={sel === row.id}
              onPick={() => { setSel(row.id); setPaneOpen(true); }}
            />
          ))}
          {rows.length === 0 && (
            <div style={{ padding: '24px 12px', fontSize: 13, color: '#8b949e', textAlign: 'center' }}>Nothing here yet.</div>
          )}
        </div>
      </div>

      {/* ═══ PR detail pane ═══ */}
      {paneShown && selRow !== null && (
        <div style={{ width: detW, flexShrink: 1, minWidth: 0, borderLeft: '1px solid #e7e9ec', display: 'flex', minHeight: 0, background: '#fff', position: 'relative' }}>
          <div
            className="pl-hov-drag"
            onMouseDown={detDragStart}
            title="Drag to resize"
            style={{ position: 'absolute', left: -3, top: 0, bottom: 0, width: 6, cursor: 'col-resize', zIndex: 5 }}
          />
          <PullDetailPane
            key={selRow.id}
            row={selRow}
            onWorkWithAgent={onOpenWorkspacePr !== undefined && selWorkspaceId !== undefined
              ? () => onOpenWorkspacePr(selRow.repo, selRow.num, { agent: true })
              : undefined}
            onOpenInWorkspace={onOpenWorkspacePr !== undefined && selWorkspaceId !== undefined
              ? () => onOpenWorkspacePr(selRow.repo, selRow.num, { agent: false })
              : undefined}
            onAssignAgent={() => assignAgent(selRow)}
            noWorkspace={onOpenWorkspacePr !== undefined && selWorkspaceId === undefined}
          />
        </div>
      )}
    </div>
  );
}
