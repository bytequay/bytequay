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

import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { zoomReference } from '../githubReference';
import type { GoalItemDto } from './contracts';
import { GoalPanel, GoalRing, type GoalPanelProps } from './GoalPanel';
import { placePill, summarize, type PillPosition } from './model';
import type { Goals } from './useGoals';
import '../css/goals.css';

/** A workspace this wide keeps the goal docked beside its Home; narrower ones open it as a drawer. */
const DOCK_MIN_WIDTH = 1100;
const DRAG_THRESHOLD = 4;
const PILL_MARGIN = 8;
const DOCK_COLLAPSED_KEY = 'bq.goal.dockCollapsed';

function pillKey(page: string): string {
  return `bq.goal.pill.${page}`;
}

function loadPosition(page: string): PillPosition | null {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(pillKey(page)) ?? 'null');
    if (typeof parsed !== 'object' || parsed === null) return null;
    const { right, bottom } = parsed as Record<string, unknown>;
    return typeof right === 'number' && typeof bottom === 'number' ? { right, bottom } : null;
  }
  catch {
    return null;
  }
}

function saveLocal(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  }
  catch { /* A full or disabled store only means the default comes back next time. */ }
}

function dockCollapsed(): boolean {
  try {
    return window.localStorage.getItem(DOCK_COLLAPSED_KEY) === '1';
  }
  catch {
    return false;
  }
}

function editable(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && (target.isContentEditable || target.matches('input, textarea, select'));
}

/** Opens a goal item where the app shows it: a pull request, issue or ticket over the page, a task as its screen. */
export function openGoalItem(item: GoalItemDto, workspaceId: string, onOpenTask: (taskId: string) => void): void {
  if (item.kind === 'TASK') {
    onOpenTask(item.key);
    return;
  }
  if (item.kind === 'JIRA') {
    const [connectionId, cloudId, issueId] = item.key.split(':');
    zoomReference({ kind: 'jira', connectionId, cloudId, issueId });
    return;
  }
  const hash = item.key.lastIndexOf('#');
  const repo = item.key.slice(0, hash);
  const number = Number(item.key.slice(hash + 1));
  zoomReference(item.kind === 'PULL_REQUEST' ? { kind: 'pull', repo, number, workspaceId } : { kind: 'issue', repo, number, workspaceId });
}

type Drag = { x: number; y: number; from: PillPosition; moved: boolean };

/**
 * The workspace's pinned goal around one of its pages: a pill the user can drag anywhere, which
 * opens the goal docked beside a wide Home or as a drawer over any other page, and a board over
 * everything for managing it. ⌥G opens and closes it.
 */
export function GoalLayer({ page, workspaceId, repository, goals, needsYou, onOpenTask, children }: {
  /** Which workspace page this is; the pill remembers a place per page. */
  page: string;
  workspaceId: string;
  repository: string;
  goals: Goals;
  needsYou: ReadonlySet<string>;
  onOpenTask: (taskId: string) => void;
  children: React.ReactNode;
}): React.JSX.Element {
  const layerRef = useRef<HTMLDivElement>(null);
  const pageRef = useRef<HTMLDivElement>(null);
  const pillRef = useRef<HTMLButtonElement>(null);
  const [width, setWidth] = useState(0);
  const [collapsed, setCollapsed] = useState(dockCollapsed);
  const [drawer, setDrawer] = useState(false);
  const [board, setBoard] = useState(false);
  const [saved, setSaved] = useState<PillPosition | null>(() => loadPosition(page));
  const [position, setPosition] = useState<PillPosition>({ right: 24, bottom: 24 });
  const [drag, setDrag] = useState<Drag | null>(null);
  const dragged = useRef(false);
  // The last spot a drag reached, which a release can report before the move that set it renders.
  const dropped = useRef<PillPosition | null>(null);
  // Measured on the whole layer: the dock itself narrows the page, which must not undock it.
  const dockable = page === 'home' && width >= DOCK_MIN_WIDTH;
  const mode = dockable && !collapsed ? 'dock' : drawer ? 'drawer' : 'pill';
  const dragging = drag !== null;

  useEffect(() => {
    setSaved(loadPosition(page));
    setDrawer(false);
    setBoard(false);
  }, [page]);

  useLayoutEffect(() => {
    const layer = layerRef.current;
    if (layer === null) return;
    const measure = (): void => setWidth(layer.clientWidth);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(layer);
    return () => observer.disconnect();
  }, []);

  // Placed again whenever the page or its composer changes size, so a growing draft never slides under the pill.
  useLayoutEffect(() => {
    const element = pageRef.current;
    if (element === null || mode !== 'pill' || dragging) return;
    const composer = element.querySelector('.agt-dock__inner');
    const place = (): void => {
      const pill = pillRef.current;
      if (pill === null) return;
      const bounds = element.getBoundingClientRect();
      const edges = composer?.getBoundingClientRect() ?? null;
      setPosition(placePill(saved, { width: pill.offsetWidth, height: pill.offsetHeight }, { width: bounds.width, height: bounds.height },
        edges === null ? null : { left: edges.left - bounds.left, top: edges.top - bounds.top, right: edges.right - bounds.left, bottom: edges.bottom - bounds.top }));
    };
    place();
    const observer = new ResizeObserver(place);
    observer.observe(element);
    if (composer !== null) observer.observe(composer);
    return () => observer.disconnect();
  }, [dragging, mode, page, saved, goals.pinned?.id]);

  const open = useCallback(() => {
    if (dockable) {
      setCollapsed(false);
      saveLocal(DOCK_COLLAPSED_KEY, null);
    }
    else setDrawer(true);
  }, [dockable]);
  const close = useCallback(() => {
    if (mode === 'dock') {
      setCollapsed(true);
      saveLocal(DOCK_COLLAPSED_KEY, '1');
    }
    setDrawer(false);
  }, [mode]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (!event.altKey || event.metaKey || event.ctrlKey || event.code !== 'KeyG' || editable(event.target)) return;
      event.preventDefault();
      if (mode === 'pill') open();
      else close();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [close, mode, open]);

  const goal = goals.pinned;
  const panel = (panelMode: GoalPanelProps['mode'], onClose: () => void) => (
    <GoalPanel
      mode={panelMode}
      repository={repository}
      goals={goals.goals}
      goal={goal}
      needsYou={needsYou}
      onClose={onClose}
      onExpand={() => setBoard(true)}
      onSwitch={(goalId) => { void goals.pin(goalId).catch(() => {}); }}
      onCreate={(title) => goals.create(title).then((created) => goals.pin(created.id))}
      onDelete={(goalId) => goals.remove(goalId)}
      onAdd={(item) => (goal === null ? Promise.resolve() : goals.add(goal.id, item.kind, item.key))}
      onRemove={(item) => { if (goal !== null) void goals.removeItem(goal.id, item.kind, item.key).catch(() => {}); }}
      onOpenItem={(item) => openGoalItem(item, workspaceId, onOpenTask)}
      search={goals.search}
    />
  );
  const summary = goal === null ? null : summarize(goal, needsYou);

  return (
    <div className="goal-layer" ref={layerRef}>
      <div className={`goal-layer__page${mode === 'drawer' ? ' has-drawer' : ''}`} ref={pageRef}>
        {children}
        {mode === 'pill' && (
          <button
            ref={pillRef}
            type="button"
            className={`goal-pill${drag?.moved ? ' is-dragging' : ''}`}
            style={{ right: position.right, bottom: position.bottom }}
            title="Drag to pin anywhere · click to open · ⌥G"
            aria-label={goal === null ? 'Open goals' : `Open goal: ${goal.title}`}
            onPointerDown={(event) => {
              event.currentTarget.setPointerCapture(event.pointerId);
              setDrag({ x: event.clientX, y: event.clientY, from: position, moved: false });
            }}
            onPointerMove={(event) => {
              const element = pageRef.current;
              if (drag === null || element === null) return;
              const dx = event.clientX - drag.x;
              const dy = event.clientY - drag.y;
              if (!drag.moved && Math.abs(dx) + Math.abs(dy) < DRAG_THRESHOLD) return;
              const pill = event.currentTarget;
              const clamp = (value: number, max: number) => Math.round(Math.max(PILL_MARGIN, Math.min(max, value)));
              if (!drag.moved) setDrag({ ...drag, moved: true });
              dropped.current = {
                right: clamp(drag.from.right - dx, element.clientWidth - pill.offsetWidth - PILL_MARGIN),
                bottom: clamp(drag.from.bottom - dy, element.clientHeight - pill.offsetHeight - PILL_MARGIN),
              };
              setPosition(dropped.current);
            }}
            onPointerUp={() => {
              const spot = dropped.current;
              dropped.current = null;
              if (drag?.moved && spot !== null) {
                // The click that follows a drop is not an open.
                dragged.current = true;
                saveLocal(pillKey(page), JSON.stringify(spot));
                setSaved(spot);
              }
              setDrag(null);
            }}
            onPointerCancel={() => setDrag(null)}
            onClick={() => {
              if (dragged.current) {
                dragged.current = false;
                return;
              }
              open();
            }}
          >
            <svg className="goal-pill__grip" width="8" height="14" viewBox="0 0 8 14" aria-hidden="true">
              {[3, 7, 11].flatMap((y) => [2, 6].map((x) => <circle key={`${x}.${y}`} cx={x} cy={y} r="1.1" />))}
            </svg>
            <GoalRing goal={goal} />
            <span className="goal-pill__title">{goal === null ? 'Goal' : goal.title}</span>
            {summary !== null && <span className="goal-mono">{summary.finished}/{summary.total}</span>}
            {summary !== null && summary.needs > 0 && <span className="goal-pill__needs" title="Needs you">{summary.needs}</span>}
          </button>
        )}
        {mode === 'drawer' && <div className="goal-drawer">{panel('drawer', close)}</div>}
      </div>
      {mode === 'dock' && <aside className="goal-dock">{panel('dock', close)}</aside>}
      {board && goal !== null && (
        <div className="goal-board-scrim" onClick={() => setBoard(false)}>
          <div className="goal-board" onClick={(event) => event.stopPropagation()}>{panel('board', () => setBoard(false))}</div>
        </div>
      )}
    </div>
  );
}
