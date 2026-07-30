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
import type { AgentReviewData } from '../review/agentReviewTypes';
import { workspaceApi, type WorkspaceRepositoryDto } from '../workspace/workspaceApi';
import { getCached, setCached } from '../dataCache';
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
import AgentColumn from './AgentColumn';
import { pullRowFromLocal } from './localRow';
import PullBoard from './PullBoard';
import PullDetailPane from './PullDetailPane';
import { PullDetailHost } from './PullDetailZoom';
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

function reviewState(review: AgentReviewData | null): 'none' | 'running' | 'done' | 'stale' {
  if (review === null) return 'none';
  if (review.rounds.some(round => round.status === 'QUEUED' || round.status === 'RUNNING')) {
    return 'running';
  }
  return review.review.status === 'STALE' ? 'stale' : 'done';
}

/** Last-known rows/repo per workspace, so re-entering this screen repaints the
 *  list it had instead of flashing empty while the fetch runs. Same module
 *  cache the standalone Pulls screen seeds from. */
function prsCacheKey(workspaceId: string) {
  return `workspacePulls:${workspaceId}`;
}

function repoCacheKey(workspaceId: string) {
  return `workspaceRepo:${workspaceId}`;
}

async function loadPullRequests(workspaceId: string, selectedNumber?: number): Promise<PullRequestDto[]> {
  const rows = await workspaceApi.pullRequests(workspaceId);
  if (selectedNumber === undefined || rows.some(pr => pr.number === selectedNumber)) return rows;
  try {
    return [...rows, await workspaceApi.pullRequest(workspaceId, selectedNumber)];
  }
  catch {
    return rows;
  }
}

function RefreshIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 12a9 9 0 1 0 3-6.7" />
      <path d="M3 3v5h5" />
    </svg>
  );
}

export default function WorkspacePullsScreen({ workspaceId, initialPrNumber, initialPrId, initialAgentView, onOpenPr, onBackToList }: {
  workspaceId: string;
  initialPrNumber?: number;
  /** Stable unified PR id; supports task PRs before a GitHub number exists. */
  initialPrId?: string;
  /** Open with the agent column already replacing the work list (deep link
   *  from the standalone Pulls screen's "Work with agent" button). */
  initialAgentView?: boolean;
  onOpenPr: (n: number) => void;
  onBackToList: () => void;
}) {
  const [prs, setPrs] = useState<PullRequestDto[]>(
    () => getCached<PullRequestDto[]>(prsCacheKey(workspaceId)) ?? [],
  );
  const [repo, setRepo] = useState<WorkspaceRepositoryDto | null>(
    () => getCached<WorkspaceRepositoryDto>(repoCacheKey(workspaceId)) ?? null,
  );
  const [view, setView] = useState<'board' | 'list'>('list');
  // Default filter: 'all' — deterministic before data loads. Revisit if a
  // review-first default proves better in daily use.
  const [filter, setFilter] = useState<WorkspaceFilter>('all');
  const [sel, setSel] = useState<number | null>(initialPrNumber ?? null);
  const [directPrId, setDirectPrId] = useState<string | null>(initialPrId ?? null);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detW, setDetW] = useState(DETAIL_DEFAULT);
  const [paneRow, setPaneRow] = useState<PullRow | null>(null);
  const [agentView, setAgentView] = useState(initialAgentView === true);
  const [detailZoomed, setDetailZoomed] = useState(false);
  const [pendingAgentStarts, setPendingAgentStarts] = useState<Set<string>>(() => new Set());

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.repository(workspaceId)
      .then(value => {
        setCached(repoCacheKey(workspaceId), value);
        if (!cancelled) setRepo(value);
      })
      .catch(() => { /* transient; pane resolution simply waits */ });
    return () => { cancelled = true; };
  }, [workspaceId]);

  useEffect(() => {
    let cancelled = false;
    void loadPullRequests(workspaceId, initialPrNumber)
      .then(rows => {
        setCached(prsCacheKey(workspaceId), rows);
        if (!cancelled) setPrs(rows);
      })
      .catch(() => { /* transient; Refresh retries */ });
    return () => { cancelled = true; };
  }, [workspaceId, initialPrNumber]);

  // Deep links mirror the App route in both directions. Clearing the route
  // must also close a previously selected PR/AgentColumn because this screen
  // stays mounted while the workspace sidebar changes sections.
  useEffect(() => {
    const targeted = initialPrNumber !== undefined || initialPrId !== undefined;
    setSel(initialPrNumber ?? null);
    setDirectPrId(initialPrId ?? null);
    setPaneOpen(targeted);
    setAgentView(targeted && initialAgentView === true);
    setDetailZoomed(false);
  }, [initialAgentView, initialPrId, initialPrNumber]);

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
  const paneShown = paneRow !== null && paneOpen;
  const wide = !paneShown;

  // Record a footprint for the opened PR (selection is local state plus a
  // workspace nav the footprint layer doesn't track) so it shows up in the
  // rail's Recent list. Same "owner/repo#num" surfaceId as the nav layer.
  useEffect(() => {
    if (selDto === null || repo === null) return;
    const fullName = `${repo.owner}/${repo.repo}`;
    void window.bridge.recordSurfaceVisit({
      surfaceType: 'PR',
      surfaceId: `${fullName}#${selDto.number}`,
      title: `${selDto.title} #${selDto.number}`,
      context: fullName,
    })
      .then(() => window.dispatchEvent(new Event('footprint-recorded')))
      .catch(() => { /* fire-and-forget */ });
  }, [selDto?.number, repo]);

  // Resolve the unified pr-table id for the selection, then build the pane's
  // PullRow off the workspace dto with that id swapped in (PullDetailPane's
  // usePR call needs it).
  useEffect(() => {
    setPaneRow(null);
    if (repo === null) return;
    let cancelled = false;
    if (directPrId !== null) {
      void Promise.all([
        window.bridge.getLocalPrBundle(directPrId),
        window.bridge.getAgentReview(directPrId).catch((): null => null),
      ]).then(([bundle, review]) => {
        if (cancelled) return;
        if (bundle === null) return;
        const fullName = `${repo.owner}/${repo.repo}`;
        const row = pullRowFromLocal(bundle.pr, fullName, bundle.pr.remotePrNumber ?? 0);
        setPaneRow({
          ...row,
          hasAgent: review !== null,
          dto: { ...row.dto, reviewState: reviewState(review) },
        });
      })
      .catch(() => { /* transient; next selection retries */ });
      return () => { cancelled = true; };
    }
    if (selDto === null) return;
    void window.bridge.getPrForRepoPull(repo.owner, repo.repo, selDto.number)
      .then(async pr => {
        const review = await window.bridge.getAgentReview(pr.id).catch((): null => null);
        if (cancelled) return;
        const row = pullRowFromDto(selDto);
        const state = review === null && row.hasAgent ? 'done' : reviewState(review);
        setPaneRow({
          ...row,
          id: pr.id,
          hasAgent: state !== 'none',
          dto: { ...toDashboardPr(selDto), id: pr.id, reviewState: state },
        });
      })
      .catch(() => { /* transient; next selection retries */ });
    return () => { cancelled = true; };
  }, [directPrId, selDto, repo]);

  const refresh = () => {
    void loadPullRequests(workspaceId, sel ?? undefined)
      .then(setPrs)
      .catch(() => { /* transient; user can retry */ });
  };

  const pick = (num: number) => {
    setDetailZoomed(false);
    setDirectPrId(null);
    setSel(num);
    setPaneOpen(true);
    onOpenPr(num);
  };

  const togglePane = () => {
    if (paneShown) {
      setDetailZoomed(false);
      setPaneOpen(false);
      onBackToList();
    }
    else {
      setPaneOpen(true);
      if (directPrId === null && sel !== null) onOpenPr(sel);
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

  // Full review is workspace-bound. Flip the selected row immediately so a
  // second click opens the canonical AgentColumn while the round is running.
  const assignAgent = () => {
    if (paneRow === null) return;
    const previous = paneRow;
    const prId = paneRow.id;
    setPendingAgentStarts(current => new Set(current).add(prId));
    setPaneRow({
      ...paneRow,
      hasAgent: true,
      dto: { ...paneRow.dto, reviewState: 'running' },
    });
    void window.bridge.startAgentReview(prId, { workspaceId })
      .then(() => {
        setPendingAgentStarts(current => {
          const next = new Set(current);
          next.delete(prId);
          return next;
        });
      })
      .catch(() => {
        setPendingAgentStarts(current => {
          const next = new Set(current);
          next.delete(prId);
          return next;
        });
        setPaneRow(current => current?.id === previous.id ? previous : current);
      });
  };

  const tabs: [WorkspaceFilter, string][] = [
    ['review', `To review · ${counts.review}`],
    ['mine', `Mine · ${counts.mine}`],
    ['all', `All · ${counts.open}`],
  ];

  const agentShown = agentView && paneRow !== null;

  return (
    <div style={{ display: 'flex', minWidth: 0, minHeight: 0, height: '100%', background: '#fff' }}>
      {/* ═══ Agent review column — swaps in for the work list ═══ */}
      {agentShown && paneRow !== null && (
        <AgentColumn prId={paneRow.id} workspaceId={workspaceId} onBack={() => setAgentView(false)} onTogglePanel={togglePane} />
      )}
      {/* ═══ Work list ═══ */}
      {!agentShown && (
      <div style={{ flex: 1, minWidth: 220, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px 8px', borderBottom: '1px solid #e7e9ec', flexShrink: 0, flexWrap: 'wrap' }}>
          {wide && (
            <>
              <span style={{ fontSize: 15, fontWeight: 400, color: '#17191c', whiteSpace: 'nowrap' }}>Pull requests</span>
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
      )}

      {/* ═══ PR detail pane ═══ */}
      {paneShown && (
        <PullDetailHost
          zoomed={detailZoomed}
          onClose={() => setDetailZoomed(false)}
          normalStyle={{ width: detW, flexShrink: 1, minWidth: 0, borderLeft: '1px solid #e7e9ec', display: 'flex', minHeight: 0, background: '#fff', position: 'relative' }}
        >
          {!detailZoomed && (
            <div
              className="pl-hov-drag"
              onMouseDown={detDragStart}
              title="Drag to resize"
              style={{ position: 'absolute', left: -3, top: 0, bottom: 0, width: 6, cursor: 'col-resize', zIndex: 5 }}
            />
          )}
          {paneRow !== null && (
            <PullDetailPane
              key={paneRow.id}
              row={paneRow}
              zoomed={detailZoomed}
              onToggleZoom={() => setDetailZoomed(value => !value)}
              onWorkWithAgent={pendingAgentStarts.has(paneRow.id)
                ? undefined
                : () => { setDetailZoomed(false); setAgentView(true); }}
              onAssignAgent={assignAgent}
            />
          )}
        </PullDetailHost>
      )}
    </div>
  );
}
