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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { getCached, setCached } from '../dataCache';
import AddRepoModal from '../repos/AddRepoModal';
import type { AgentReviewData } from '../review/agentReviewTypes';
import type { DashboardPR } from '../types/dashboardPr';
import { workspaceApi, type WorkspaceCreationDto } from '../workspace/workspaceApi';
import { PULL_TABS, rowsForTab, toRow } from './model';
import type { PullRow, PullTab } from './model';
import PullRowItem from './PullRowItem';
import PullDetailPane from './PullDetailPane';
import { PullDetailHost } from './PullDetailZoom';
import { pullRowFromLocal } from './localRow';
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
const PRS_CACHE_KEY = 'prs:list';
const PENDING_FULL_REVIEW_KEY = 'bytequay.pending-full-review';
const REVIEW_POLL_MS = 1_000;
const PULL_PAGE_SIZE = 20;

type QuickReviewUi = {
  state: 'idle' | 'running' | 'done' | 'failed';
  result: AgentReviewData | null;
  error: string | null;
};

type WatchUi = {
  state: 'idle' | 'preparing' | 'failed';
  error: string | null;
};

type PendingFullReview = {
  operationId: string;
  prId: string;
  repo: string;
  prNumber: number;
};

const IDLE_QUICK_REVIEW: QuickReviewUi = { state: 'idle', result: null, error: null };
const IDLE_WATCH: WatchUi = { state: 'idle', error: null };

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function readPendingFullReview(): PendingFullReview | null {
  try {
    const raw = window.localStorage.getItem(PENDING_FULL_REVIEW_KEY);
    if (raw === null) return null;
    const value = JSON.parse(raw) as Partial<PendingFullReview>;
    return typeof value.operationId === 'string'
      && typeof value.prId === 'string'
      && typeof value.repo === 'string'
      && typeof value.prNumber === 'number'
      ? value as PendingFullReview
      : null;
  }
  catch {
    return null;
  }
}

function persistPendingFullReview(value: PendingFullReview | null): void {
  try {
    if (value === null) window.localStorage.removeItem(PENDING_FULL_REVIEW_KEY);
    else window.localStorage.setItem(PENDING_FULL_REVIEW_KEY, JSON.stringify(value));
  }
  catch {
    // Persistence is a convenience; private storage must not block review.
  }
}

export default function PullsScreen({
  onOpenWorkspacePr,
  onRunQuickReview,
  onWatchRepoForFullReview,
  initialPr,
  initialReviewAction,
}: {
  /** Routes a PR into its repo's workspace surface; {@code agent} opens it
   *  with the agent-review column already showing. Omitted → the pane's
   *  workspace-bound buttons stay inert. */
  onOpenWorkspacePr?: (
    repo: string,
    prNumber: number,
    opts: { agent: boolean; prId: string },
  ) => void;
  /** Starts a one-shot diff review. This callback never opens an agent column. */
  onRunQuickReview?: (row: PullRow) => void;
  /** Watches an external repo and continues into its full-review flow. */
  onWatchRepoForFullReview?: (row: PullRow) => void;
  /** One-shot handoff from another PR surface into this screen's canonical
   *  quick/watch implementation. */
  initialReviewAction?: 'quick' | 'watch';
  /** Deep-link: resolve this PR on mount, select its row, and open the
   *  pane — even when the row isn't in the dashboard list. {@code repo}
   *  is the "owner/name" fullName. */
  initialPr?: { repo: string; number: number };
}) {
  const [prs, setPrs] = useState<DashboardPR[]>(
    () => getCached<DashboardPR[]>(PRS_CACHE_KEY) ?? [],
  );
  const [dashboardLoaded, setDashboardLoaded] = useState(false);
  const [tab, setTab] = useState<PullTab>('all');
  const [visibleCount, setVisibleCount] = useState(PULL_PAGE_SIZE);
  const [sel, setSel] = useState<string | null>(null);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detailZoomed, setDetailZoomed] = useState(false);
  const [detW, setDetW] = useState(DETAIL_DEFAULT);
  const [initialPrError, setInitialPrError] = useState(false);
  // Fallback pane row for a deep-linked PR outside the dashboard list.
  const [extraRow, setExtraRow] = useState<PullRow | null>(null);
  // repo fullName (lowercased) → workspace id, for the pane's workspace-
  // bound agent buttons — same resolution App's legacy-repo redirect uses.
  const [wsByRepo, setWsByRepo] = useState<Map<string, string> | null>(null);
  const [quickByPr, setQuickByPr] = useState<Record<string, QuickReviewUi>>({});
  const [watchByPr, setWatchByPr] = useState<Record<string, WatchUi>>(() => {
    const pending = readPendingFullReview();
    return pending === null
      ? {}
      : { [pending.prId]: { state: 'preparing', error: null } };
  });
  const [pendingFullReview, setPendingFullReview] = useState<PendingFullReview | null>(
    readPendingFullReview,
  );
  const [watchTarget, setWatchTarget] = useState<PullRow | null>(null);
  const [pendingAgentStarts, setPendingAgentStarts] = useState<Set<string>>(() => new Set());
  const alive = useRef(true);
  const listRef = useRef<HTMLDivElement>(null);
  const handledInitialReviewAction = useRef<string | null>(null);

  const markAgentStart = useCallback((prId: string, pending: boolean) => {
    setPendingAgentStarts(current => {
      const next = new Set(current);
      if (pending) next.add(prId);
      else next.delete(prId);
      return next;
    });
  }, []);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.listWorkspaces()
      .then(cards => {
        if (cancelled) return;
        const resolved = new Map(cards.flatMap(card => card.repository == null
          ? []
          : [[card.repository.fullName.toLowerCase(), card.id] as const]));
        setWsByRepo(current => current === null
          ? resolved
          : new Map([...resolved, ...current]));
      })
      .catch(() => { /* unresolved; review actions stay disabled */ });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    alive.current = true;
    const load = async () => {
      try {
        const data = await window.bridge.fetchDashboardPrs();
        if (alive.current) {
          setPrs(data);
          setCached(PRS_CACHE_KEY, data);
        }
      } catch {
        // Backend not up yet — the sync below retries the fetch.
      }
      finally {
        if (alive.current) setDashboardLoaded(true);
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

  // Deep-link: resolve the PR to its unified id, select it, open the pane.
  // Home already cached dashboard rows, so use that known id immediately.
  // The GitHub-backed resolver is only needed for PRs outside the dashboard.
  useEffect(() => {
    if (initialPr === undefined) return;
    const dashboardPr = prs.find(pr => pr.number === initialPr.number
      && pr.repo.toLowerCase() === initialPr.repo.toLowerCase());
    if (dashboardPr !== undefined) {
      setInitialPrError(false);
      setSel(dashboardPr.id);
      setPaneOpen(true);
      return;
    }
    if (!dashboardLoaded) return;
    const [owner, repo] = initialPr.repo.split('/');
    if (!owner || !repo) {
      setInitialPrError(true);
      return;
    }
    let cancelled = false;
    setInitialPrError(false);
    void window.bridge.getPrForRepoPull(owner, repo, initialPr.number)
      .then(pr => {
        if (cancelled) return;
        setExtraRow(pullRowFromLocal(pr, initialPr.repo, initialPr.number));
        setSel(pr.id);
        setPaneOpen(true);
      })
      .catch(() => { if (!cancelled) setInitialPrError(true); });
    return () => { cancelled = true; };
  }, [dashboardLoaded, initialPr, prs]);

  const rows = useMemo(() => rowsForTab(prs, tab), [prs, tab]);
  const selRow = sel === null ? null
    : rows.find(r => r.id === sel)
      // In the dashboard data but filtered out by the current tab
      // (e.g. a deep-linked merged PR while "All" hides done ones).
      ?? (() => { const pr = prs.find(p => p.id === sel); return pr === undefined ? null : toRow(pr); })()
      ?? (extraRow !== null && extraRow.id === sel ? extraRow : null);
  const openingInitialPr = initialPr !== undefined && selRow === null;
  const paneShown = paneOpen && (selRow !== null || openingInitialPr);
  const wide = !paneShown;
  const selWorkspaceId = selRow === null ? undefined : wsByRepo?.get(selRow.repo.toLowerCase());
  const workspaceResolved = wsByRepo !== null;
  const selectedPrId = selRow?.id;
  const selectedRepo = selRow?.repo;
  const selectedPrNumber = selRow?.num;
  const selectedPrTitle = selRow?.title;
  const selectedQuick = selRow === null ? IDLE_QUICK_REVIEW
    : quickByPr[selRow.id] ?? IDLE_QUICK_REVIEW;
  const selectedWatch = selRow === null ? IDLE_WATCH
    : watchByPr[selRow.id] ?? IDLE_WATCH;

  useEffect(() => setDetailZoomed(false), [sel]);

  const refreshQuickReview = useCallback(async (prId: string) => {
    try {
      const status = await window.bridge.getQuickReviewStatus(prId);
      if (!alive.current) return;
      if (status.state === 'RUNNING') {
        setQuickByPr(current => ({
          ...current,
          [prId]: { state: 'running', result: current[prId]?.result ?? null, error: null },
        }));
        return;
      }
      if (status.state === 'FAILED') {
        setQuickByPr(current => ({
          ...current,
          [prId]: { state: 'failed', result: current[prId]?.result ?? null, error: status.error ?? 'Quick review failed.' },
        }));
        return;
      }
      if (status.state === 'IDLE') {
        setQuickByPr(current => ({ ...current, [prId]: IDLE_QUICK_REVIEW }));
        return;
      }
      const result = await window.bridge.getLatestQuickReview(prId);
      if (!alive.current) return;
      setQuickByPr(current => ({
        ...current,
        [prId]: result === null
          ? IDLE_QUICK_REVIEW
          : { state: 'done', result, error: null },
      }));
    }
    catch (error) {
      if (!alive.current) return;
      setQuickByPr(current => ({
        ...current,
        [prId]: { state: 'failed', result: current[prId]?.result ?? null, error: errorText(error) },
      }));
    }
  }, []);

  // Recover the durable one-seat review whenever an unwatched PR is selected.
  // It stays inline here even though ReviewAssignmentTurn owns its execution.
  useEffect(() => {
    if (selectedPrId === undefined || !workspaceResolved || selWorkspaceId !== undefined) return;
    void refreshQuickReview(selectedPrId);
  }, [refreshQuickReview, selectedPrId, selWorkspaceId, workspaceResolved]);

  useEffect(() => {
    if (selectedPrId === undefined || selectedQuick.state !== 'running') return;
    const timer = window.setInterval(() => {
      void refreshQuickReview(selectedPrId);
    }, REVIEW_POLL_MS);
    return () => window.clearInterval(timer);
  }, [refreshQuickReview, selectedPrId, selectedQuick.state]);

  const runQuickReview = useCallback((row: PullRow) => {
    setQuickByPr(current => ({
      ...current,
      [row.id]: { state: 'running', result: current[row.id]?.result ?? null, error: null },
    }));
    onRunQuickReview?.(row);
    void window.bridge.startQuickReview(row.id)
      .then(() => refreshQuickReview(row.id))
      .catch(error => {
        if (!alive.current) return;
        setQuickByPr(current => ({
          ...current,
          [row.id]: { state: 'failed', result: current[row.id]?.result ?? null, error: errorText(error) },
        }));
      });
  }, [onRunQuickReview, refreshQuickReview]);

  // A watched clone can outlive this screen. The operation id and PR intent
  // are persisted together so remounting resumes setup and starts the same
  // full review exactly once when the local source becomes ready.
  useEffect(() => {
    if (pendingFullReview === null) return;
    let cancelled = false;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const operation = await workspaceApi.creation(pendingFullReview.operationId);
        if (cancelled) return;
        if (operation.state === 'failed') {
          const failed: WatchUi = {
            state: 'failed',
            error: operation.errorMessage ?? operation.stageMessage ?? 'Repository setup failed.',
          };
          setWatchByPr(current => ({ ...current, [pendingFullReview.prId]: failed }));
          persistPendingFullReview(null);
          setPendingFullReview(null);
          return;
        }
        if (operation.state === 'ready') {
          if (operation.workspaceId === null) throw new Error('Repository is ready but has no workspace.');
          const workspaceId = operation.workspaceId;
          setWsByRepo(current => {
            const next = new Map(current ?? []);
            next.set(pendingFullReview.repo.toLowerCase(), workspaceId);
            return next;
          });
          setPrs(current => current.map(pr => pr.id === pendingFullReview.prId
            ? { ...pr, reviewState: 'running' }
            : pr));
          setExtraRow(current => current?.id === pendingFullReview.prId
            ? { ...current, hasAgent: true, dto: { ...current.dto, reviewState: 'running' } }
            : current);
          markAgentStart(pendingFullReview.prId, true);
          try {
            await window.bridge.startAgentReview(pendingFullReview.prId, { workspaceId });
          }
          catch {
            // The repo is still watched. Return to an actionable Full review
            // button so the user can retry the agent start without cloning.
            if (!cancelled) {
              setPrs(current => current.map(pr => pr.id === pendingFullReview.prId
                ? { ...pr, reviewState: 'none' }
                : pr));
              setExtraRow(current => current?.id === pendingFullReview.prId
                ? { ...current, hasAgent: false, dto: { ...current.dto, reviewState: 'none' } }
                : current);
            }
          }
          if (!cancelled) markAgentStart(pendingFullReview.prId, false);
          if (!cancelled) {
            setWatchByPr(current => ({ ...current, [pendingFullReview.prId]: IDLE_WATCH }));
            persistPendingFullReview(null);
            setPendingFullReview(null);
          }
          return;
        }
      }
      catch {
        // Workspace setup is persisted by the backend; transient polling
        // failures are safe to retry while this screen remains mounted.
      }
      if (!cancelled) timer = window.setTimeout(() => { void poll(); }, REVIEW_POLL_MS);
    };
    void poll();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [markAgentStart, pendingFullReview]);

  const beginWatchingForFullReview = (row: PullRow) => {
    onWatchRepoForFullReview?.(row);
    setWatchTarget(row);
  };

  const rememberWorkspaceCreation = (row: PullRow, operation: WorkspaceCreationDto) => {
    const pending = {
      operationId: operation.id,
      prId: row.id,
      repo: row.repo,
      prNumber: row.num,
    } satisfies PendingFullReview;
    persistPendingFullReview(pending);
    setWatchByPr(current => ({
      ...current,
      [row.id]: { state: 'preparing', error: null },
    }));
    setPendingFullReview(pending);
    setWatchTarget(null);
  };

  // The list opens PR detail as local state (nav stays on the Pulls
  // surface), so App's nav-driven footprint capture never sees it. Record
  // the visit here — same PR surfaceId as the nav layer — so the opened PR
  // lands in the rail's Recent list. Fire-and-forget like the nav path.
  useEffect(() => {
    if (selectedRepo === undefined || selectedPrNumber === undefined || selectedPrTitle === undefined) return;
    void window.bridge.recordSurfaceVisit({
      surfaceType: 'PR',
      surfaceId: `${selectedRepo}#${selectedPrNumber}`,
      title: `${selectedPrTitle} #${selectedPrNumber}`,
      context: selectedRepo,
    })
      .then(() => window.dispatchEvent(new Event('footprint-recorded')))
      .catch(() => { /* fire-and-forget */ });
  }, [selectedPrNumber, selectedPrTitle, selectedRepo]);

  // Same start path as the dashboard's handleAgentReview: optimistic
  // reviewState flip, plain start (the button only shows when no review
  // exists yet), revert on failure.
  const assignAgent = (row: { id: string; repo: string }) => {
    const workspaceId = wsByRepo?.get(row.repo.toLowerCase());
    if (workspaceId === undefined) return;
    const previous = prs.find(pr => pr.id === row.id)?.reviewState;
    const previousExtra = extraRow?.id === row.id ? extraRow : null;
    markAgentStart(row.id, true);
    setPrs(current => current.map(pr => pr.id === row.id ? { ...pr, reviewState: 'running' } : pr));
    setExtraRow(current => current?.id === row.id
      ? { ...current, hasAgent: true, dto: { ...current.dto, reviewState: 'running' } }
      : current);
    void window.bridge.startAgentReview(row.id, { workspaceId })
      .then(() => markAgentStart(row.id, false))
      .catch(() => {
        markAgentStart(row.id, false);
        setPrs(current => current.map(pr => pr.id === row.id ? { ...pr, reviewState: previous ?? 'none' } : pr));
        setExtraRow(current => current?.id === row.id ? previousExtra : current);
      });
  };

  useEffect(() => {
    if (initialReviewAction === undefined || selRow === null || !workspaceResolved) return;
    const key = `${initialReviewAction}:${selRow.id}`;
    if (handledInitialReviewAction.current === key) return;
    handledInitialReviewAction.current = key;
    if (initialReviewAction === 'quick') {
      if (selWorkspaceId === undefined) runQuickReview(selRow);
      return;
    }
    if (selWorkspaceId === undefined) beginWatchingForFullReview(selRow);
    else assignAgent(selRow);
  }, [assignAgent, beginWatchingForFullReview, initialReviewAction, runQuickReview,
    selRow, selWorkspaceId, workspaceResolved]);

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
    <>
    <div style={{ display: 'flex', minWidth: 0, minHeight: 0, height: '100%', background: '#fff' }}>
      {/* ═══ Work list ═══ */}
      <div style={{ flex: 1, minWidth: 220, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 2, padding: '10px 12px 8px', borderBottom: '1px solid #e7e9ec', flexShrink: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 2, flex: 1, minWidth: 0, overflow: 'hidden' }}>
            {PULL_TABS.map(t => (
              <button
                key={t.key}
                onClick={() => {
                  setTab(t.key);
                  setVisibleCount(PULL_PAGE_SIZE);
                  if (listRef.current !== null) listRef.current.scrollTop = 0;
                }}
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
        <div
          ref={listRef}
          role="list"
          aria-label="Pull requests"
          onScroll={event => {
            const list = event.currentTarget;
            if (list.scrollTop + list.clientHeight >= list.scrollHeight - 80) {
              setVisibleCount(count => Math.min(count + PULL_PAGE_SIZE, rows.length));
            }
          }}
          style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '6px 8px 24px', display: 'flex', flexDirection: 'column', gap: 1 }}
        >
          {rows.slice(0, visibleCount).map(row => (
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
          {selRow !== null ? (
            <PullDetailPane
              key={selRow.id}
              row={selRow}
              zoomed={detailZoomed}
              onToggleZoom={() => setDetailZoomed(value => !value)}
              onWorkWithAgent={onOpenWorkspacePr !== undefined && selWorkspaceId !== undefined
                  && !pendingAgentStarts.has(selRow.id)
                ? () => onOpenWorkspacePr(selRow.repo, selRow.num, {
                    agent: true,
                    prId: selRow.id,
                  })
                : undefined}
              onOpenInWorkspace={onOpenWorkspacePr !== undefined && selWorkspaceId !== undefined
                ? () => onOpenWorkspacePr(selRow.repo, selRow.num, {
                    agent: false,
                    prId: selRow.id,
                  })
                : undefined}
              onAssignAgent={selWorkspaceId !== undefined ? () => assignAgent(selRow) : undefined}
              onRunQuickReview={workspaceResolved && selWorkspaceId === undefined
                ? () => runQuickReview(selRow)
                : undefined}
              onWatchRepoForFullReview={workspaceResolved && selWorkspaceId === undefined
                ? () => beginWatchingForFullReview(selRow)
                : undefined}
              quickReview={selectedQuick}
              fullReviewPreparation={selectedWatch}
              noWorkspace={workspaceResolved && selWorkspaceId === undefined}
            />
          ) : (
            <div
              role="status"
              style={{ flex: 1, display: 'grid', placeItems: 'center', color: initialPrError ? '#cf222e' : '#59636e', fontSize: 13 }}
            >
              {initialPrError
                ? `Couldn't open ${initialPr?.repo} #${initialPr?.number}.`
                : `Opening ${initialPr?.repo} #${initialPr?.number}…`}
            </div>
          )}
        </PullDetailHost>
      )}
    </div>
    {watchTarget !== null && (() => {
      const [owner, repo] = watchTarget.repo.split('/');
      return owner && repo ? (
        <AddRepoModal
          owner={owner}
          repo={repo}
          onClose={() => setWatchTarget(null)}
          onStarted={operation => rememberWorkspaceCreation(watchTarget, operation)}
        />
      ) : null;
    })()}
    </>
  );
}
