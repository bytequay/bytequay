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
import { useEffect, useMemo, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import type { PullRequestDto } from '../types';
import { workspaceApi, type WorkspaceRepositoryDto } from '../workspace/workspaceApi';
import { PaneToggleIcon } from './atoms';
import type { PullRow } from './model';
import {
  boardBuckets,
  filterCounts,
  pullRowFromDto,
  toDashboardPr,
  visibleRows,
  type WorkspaceFilter,
} from './workspaceModel';
import PullBoard from './PullBoard';
import PullDetailPane from './PullDetailPane';
import PullRowItem from './PullRowItem';
import '../css/pulls.css';

/**
 * The workspace variant of the redesigned PR surface (docs/mockups/design/
 * pr-redesign/Workspace PRs.dc.html): Board|List views over the workspace's
 * PR façade, with the same drag-resizable PullDetailPane opening beside the
 * board. The pane needs the unified pr-table id, which is resolved from the
 * workspace (owner, repo, number) via getPrForRepoPull — same mechanism as
 * pr/useExternalPr.
 */

const DETAIL_MIN = 460;
const DETAIL_MAX = 1150;
const DETAIL_DEFAULT = 940;

const VIEW_SEGS: ['board' | 'list', string][] = [['board', 'Board'], ['list', 'List']];

function RefreshIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 12a9 9 0 1 0 3-6.7" />
      <path d="M3 3v5h5" />
    </svg>
  );
}

export default function WorkspacePullsScreen({ workspaceId, initialPrNumber, onOpenPr, onBackToList }: {
  workspaceId: string;
  initialPrNumber?: number;
  onOpenPr: (n: number) => void;
  onBackToList: () => void;
}) {
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [repo, setRepo] = useState<WorkspaceRepositoryDto | null>(null);
  const [view, setView] = useState<'board' | 'list'>('board');
  // Default filter: 'all' — deterministic before data loads. Revisit if a
  // review-first default proves better in daily use.
  const [filter, setFilter] = useState<WorkspaceFilter>('all');
  const [sel, setSel] = useState<number | null>(initialPrNumber ?? null);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detW, setDetW] = useState(DETAIL_DEFAULT);
  const [paneRow, setPaneRow] = useState<PullRow | null>(null);

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.repository(workspaceId)
      .then(value => { if (!cancelled) setRepo(value); })
      .catch(() => { /* transient; pane resolution simply waits */ });
    return () => { cancelled = true; };
  }, [workspaceId]);

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.pullRequests(workspaceId)
      .then(rows => { if (!cancelled) setPrs(rows); })
      .catch(() => { /* transient; Refresh retries */ });
    return () => { cancelled = true; };
  }, [workspaceId]);

  // Deep links: the App nav sets selectedNumber; mirror it into local state.
  useEffect(() => {
    if (initialPrNumber !== undefined) {
      setSel(initialPrNumber);
      setPaneOpen(true);
    }
  }, [initialPrNumber]);

  const counts = useMemo(() => filterCounts(prs), [prs]);
  const visible = useMemo(() => visibleRows(prs, filter), [prs, filter]);
  const listRows = useMemo(() => visible.map(pullRowFromDto), [visible]);
  const boardCols = useMemo(() => {
    const buckets = boardBuckets(visible);
    return {
      attention: buckets.attention.map(pullRowFromDto),
      progress: buckets.progress.map(pullRowFromDto),
      cleared: buckets.cleared.map(pullRowFromDto),
    };
  }, [visible]);

  const selDto = useMemo(
    () => (sel === null ? null : prs.find(pr => pr.number === sel) ?? null),
    [prs, sel],
  );
  const paneShown = selDto !== null && paneOpen;
  const wide = !paneShown;

  // Resolve the unified pr-table id for the selection, then build the pane's
  // PullRow off the workspace dto with that id swapped in (PullDetailPane's
  // usePR call needs it).
  useEffect(() => {
    setPaneRow(null);
    if (selDto === null || repo === null) return;
    let cancelled = false;
    void window.bridge.getPrForRepoPull(repo.owner, repo.repo, selDto.number)
      .then(pr => {
        if (cancelled) return;
        setPaneRow({ ...pullRowFromDto(selDto), id: pr.id, dto: { ...toDashboardPr(selDto), id: pr.id } });
      })
      .catch(() => { /* transient; next selection retries */ });
    return () => { cancelled = true; };
  }, [selDto, repo]);

  const refresh = () => {
    void workspaceApi.pullRequests(workspaceId)
      .then(setPrs)
      .catch(() => { /* transient; user can retry */ });
  };

  const pick = (num: number) => {
    setSel(num);
    setPaneOpen(true);
    onOpenPr(num);
  };

  const togglePane = () => {
    if (paneShown) {
      setPaneOpen(false);
      onBackToList();
    }
    else {
      setPaneOpen(true);
      if (sel !== null) onOpenPr(sel);
    }
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

  const tabs: [WorkspaceFilter, string][] = [
    ['review', `To review · ${counts.review}`],
    ['mine', `Mine · ${counts.mine}`],
    ['all', `All · ${counts.open}`],
  ];

  return (
    <div style={{ display: 'flex', minWidth: 0, minHeight: 0, height: '100%', background: '#fff' }}>
      {/* ═══ Work list ═══ */}
      <div style={{ flex: 1, minWidth: 220, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px 8px', borderBottom: '1px solid #e7e9ec', flexShrink: 0, flexWrap: 'wrap' }}>
          {wide && (
            <>
              <span style={{ fontSize: 15, fontWeight: 700, color: '#17191c', whiteSpace: 'nowrap' }}>Pull requests</span>
              <span style={{ fontSize: 13, color: '#8b949e', whiteSpace: 'nowrap' }}>{counts.open} open</span>
              <span style={{ display: 'inline-flex', background: '#eceef0', borderRadius: 8, padding: 2, gap: 1, marginLeft: 4 }}>
                {VIEW_SEGS.map(([key, label]) => (
                  <button
                    key={key}
                    onClick={() => setView(key)}
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 6,
                      padding: '3px 11px',
                      border: `1px solid ${view === key ? '#d0d7de' : 'transparent'}`,
                      background: view === key ? '#fff' : 'transparent',
                      borderRadius: 7,
                      fontSize: 12.5,
                      fontWeight: view === key ? 600 : 500,
                      color: view === key ? '#17191c' : '#59636e',
                      cursor: 'pointer',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {label}
                  </button>
                ))}
              </span>
              <span style={{ flex: 1 }} />
            </>
          )}
          <span style={{ display: 'inline-flex', gap: 2, overflow: 'hidden' }}>
            {tabs.map(([key, label]) => (
              <button
                key={key}
                onClick={() => setFilter(key)}
                style={{
                  padding: '4px 12px',
                  border: 0,
                  borderRadius: 7,
                  background: filter === key ? '#e7e9ec' : 'transparent',
                  fontSize: 12.5,
                  fontWeight: filter === key ? 600 : 500,
                  color: filter === key ? '#17191c' : '#59636e',
                  cursor: 'pointer',
                  whiteSpace: 'nowrap',
                }}
              >
                {label}
              </button>
            ))}
          </span>
          <button
            className="pl-hov-btn"
            onClick={refresh}
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '4px 12px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, fontSize: 12.5, fontWeight: 500, color: '#454c54', cursor: 'pointer', whiteSpace: 'nowrap', flexShrink: 0 }}
          >
            <RefreshIcon />Refresh
          </button>
          {!wide && <span style={{ flex: 1 }} />}
          <span
            className="pl-hov-tgl"
            onClick={togglePane}
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
        {view === 'list' ? (
          <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '6px 8px 24px', display: 'flex', flexDirection: 'column', gap: 1 }}>
            {listRows.map(row => (
              <PullRowItem
                key={row.id}
                row={row}
                wide={wide}
                selected={sel === row.num}
                onPick={() => pick(row.num)}
              />
            ))}
            {listRows.length === 0 && (
              <div style={{ padding: '24px 12px', fontSize: 13, color: '#8b949e', textAlign: 'center' }}>Nothing here yet.</div>
            )}
          </div>
        ) : (
          <PullBoard columns={boardCols} onPick={row => pick(row.num)} />
        )}
      </div>

      {/* ═══ PR detail pane ═══ */}
      {paneShown && (
        <div style={{ width: detW, flexShrink: 1, minWidth: 0, borderLeft: '1px solid #e7e9ec', display: 'flex', minHeight: 0, background: '#fff', position: 'relative' }}>
          <div
            className="pl-hov-drag"
            onMouseDown={detDragStart}
            title="Drag to resize"
            style={{ position: 'absolute', left: -3, top: 0, bottom: 0, width: 6, cursor: 'col-resize', zIndex: 5 }}
          />
          {paneRow !== null && <PullDetailPane key={paneRow.id} row={paneRow} />}
        </div>
      )}
    </div>
  );
}
