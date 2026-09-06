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

import React, { useEffect, useReducer, useRef, useState } from 'react';
import { EyeOff, LoaderCircle, Pencil } from 'lucide-react';
import { relativeTime } from '../relativeTime';
import { displayModelName, laneLabel } from '../settings/model';
import { TurnView } from './AgentTaskScreen';
import { taskStatePill } from './AgentTasksView';
import type { AgentEventEnvelope } from './events';
import { agentTaskReducer, initialAgentTaskState, pollAgentTask, type AgentBridgePort } from './model';
import { projectTranscript, settleFinalTurn } from './transcript';
import type { AgentTaskDto, ModelProviderDto } from '../types';

/** How often a window re-reads its task and the events it has not shown yet. */
const WINDOW_POLL_MS = 2000;
/** The narrowest Home window; the grid packs as many as fit and stretches them to the row. */
export const HOME_WINDOW_MIN = 250;
const HOME_WINDOW_GAP = 16;

/** One column of a `repeat(auto-fill, minmax(min, 1fr))` grid, so a window can scale its screen to it. */
export function gridColumnWidth(containerWidth: number, min = HOME_WINDOW_MIN, gap = HOME_WINDOW_GAP): number {
  if (containerWidth <= 0) return min;
  const columns = Math.max(1, Math.floor((containerWidth + gap) / (min + gap)));
  return Math.floor((containerWidth - gap * (columns - 1)) / columns);
}

/** Whether a put-away window must come back: the task is stuck, or waiting on the user. */
export function needsAttention(task: AgentTaskDto, events: AgentEventEnvelope[]): boolean {
  if (task.state === 'OPEN' && task.mutationClaim?.kind === 'RECOVERY') return true;
  return projectTranscript(events).some((turn) => turn.blocks.some(
    (block) => block.kind === 'approval' && block.actionable && block.outcome === null,
  ));
}

/**
 * One task as the design's "task window": its own screen, scaled to fit, with a footer that names
 * it. Nothing inside the thumbnail is interactive — the thumbnail opens the task, and the footer
 * name is where a rename happens.
 */
export function TaskCard({ task, events, providers, width, active = false, now, onOpen, onClose, onRename, onHide, historyIncomplete = false }: {
  task: AgentTaskDto;
  events: AgentEventEnvelope[];
  historyIncomplete?: boolean;
  providers: ModelProviderDto[];
  width: number;
  active?: boolean;
  now: number;
  onOpen: () => void;
  /** Present only where a card may close its task; the overview offers it, Home does not. */
  onClose?: () => void;
  /** Present where a card may be renamed in place; Home offers it, the overview does not. */
  onRename?: (title: string) => void;
  /** Present where a card may be put away from view; Home offers it. */
  onHide?: () => void;
}): React.JSX.Element {
  const pill = taskStatePill(task);
  const projectedTurns = projectTranscript(events, null, {}, !historyIncomplete);
  const turns = projectedTurns.map((turn, index) => (
    task.state !== 'OPEN' && index === projectedTurns.length - 1
      ? settleFinalTurn(turn, task.terminalAt, task.state === 'COMPLETED')
      : turn
  ));
  const provider = providers.find((candidate) => candidate.id === task.laneId);
  const models = provider?.models ?? [];
  const model = displayModelName(models.find((option) => option.id === task.defaults.model)?.name ?? task.defaults.model ?? '');
  const providerName = provider?.label ?? laneLabel(task.laneId);
  const meta = `${providerName}${model === '' ? '' : ` · ${model}`}`;
  const [draft, setDraft] = useState<string | null>(null);
  // Enter commits and then the input unmounts, which blurs it; the flag keeps that from committing twice.
  const committed = useRef(false);
  const startRename = (): void => {
    if (onRename === undefined) return;
    committed.current = false;
    setDraft(task.title);
  };
  const commit = (): void => {
    if (committed.current || draft === null) return;
    committed.current = true;
    const next = draft.trim();
    setDraft(null);
    if (next !== '' && next !== task.title) onRename?.(next);
  };
  const pillNode = (
    <span className={`agt-state ${pill.className}`}>
      {pill.spinning && <LoaderCircle className="agt-spin" aria-hidden="true" />}
      {pill.label}
    </span>
  );
  return (
    <div className={`agt-card${active ? ' is-active' : ''}`} style={{ '--card-w': width } as React.CSSProperties}>
      <button type="button" className="agt-card__open" aria-label={`Open task: ${task.title}`} onClick={onOpen}>
        <div className="agt-card__thumb">
          <div className="agt-card__inner" aria-hidden="true">
            <div className="agt-card__head">
              <span className="agt-card__back">‹ Tasks</span>
              <span className="agt-card__title">{task.title}</span>
              {pillNode}
              <span className="agt-mono agt-right">{meta}</span>
            </div>
            <div className="agt-card__body">
              {turns.length === 0 && <div className="agt-stamp">{relativeTime(task.createdAt, { now })}</div>}
              {turns.map((turn, index) => (
                <TurnView
                  key={turn.turnId ?? `turn-${turn.ordinal}`}
                  turn={turn}
                  first={index === 0}
                  open={turn.endedAt === null}
                  now={now}
                  models={models}
                  durableTurns={{}}
                  activityChoice={{}}
                  previews={{}}
                  agentLabels={{}}
                  onToggle={() => {}}
                  onToggleActivity={() => {}}
                />
              ))}
            </div>
          </div>
        </div>
      </button>
      {(onRename !== undefined || onHide !== undefined) && draft === null && (
        <div className="agt-card__tools">
          {onRename !== undefined && (
            <button type="button" className="agt-card__tool" title="Rename" aria-label={`Rename task: ${task.title}`} onClick={startRename}>
              <Pencil size={14} aria-hidden="true" />
            </button>
          )}
          {onHide !== undefined && (
            <button type="button" className="agt-card__tool" title="Hide from Home (task stays in Tasks)" aria-label={`Hide task from Home: ${task.title}`} onClick={onHide}>
              <EyeOff size={14} aria-hidden="true" />
            </button>
          )}
        </div>
      )}
      <div className="agt-card__foot">
        {draft === null ? (
          <span className="agt-card__name" title={onRename === undefined ? undefined : 'Double-click to rename'} onDoubleClick={startRename}>
            {task.title}
          </span>
        ) : (
          <input
            className="agt-card__rename"
            type="text"
            aria-label="Task name"
            value={draft}
            autoFocus
            onChange={(event) => setDraft(event.target.value)}
            onBlur={commit}
            onKeyDown={(event) => {
              if (event.key === 'Enter') commit();
              if (event.key === 'Escape') { committed.current = true; setDraft(null); }
            }}
          />
        )}
        {pillNode}
      </div>
      {onClose !== undefined && (
        <button
          type="button"
          className="agt-card__close"
          aria-label={`Close task: ${task.title}`}
          title={pill.spinning ? 'Complete unlocks when the task is idle' : 'Close this task'}
          disabled={pill.spinning}
          onClick={(event) => { event.stopPropagation(); onClose(); }}
        >
          ✕
        </button>
      )}
    </div>
  );
}

/**
 * Each tick replaces the card's single recent history page and reads current task facts.
 * A superseded poll is discarded, so hidden cards never accumulate an unseen transcript.
 *
 * A hidden window keeps polling and draws nothing, so the task can ask to be shown again the
 * moment it needs the user.
 */
function TaskWindow({ task: listed, tick, bridge, hidden = false, renamable = false, onAttention, onHide, ...card }: {
  task: AgentTaskDto;
  tick: number;
  bridge: () => AgentBridgePort;
  hidden?: boolean;
  renamable?: boolean;
  onAttention?: () => void;
  onHide?: () => void;
  providers: ModelProviderDto[];
  width: number;
  active: boolean;
  now: number;
  onOpen: () => void;
  onClose?: () => void;
}): React.JSX.Element | null {
  const [state, dispatch] = useReducer(agentTaskReducer, undefined, initialAgentTaskState);
  const [pendingApprovalCount, setPendingApprovalCount] = useState(0);
  useEffect(() => {
    let active = true;
    void pollAgentTask(bridge(), listed.id, listed.workspaceId, dispatch, () => active).catch(() => {});
    return () => { active = false; };
  }, [bridge, listed.id, listed.workspaceId, tick]);
  const task = state.task ?? listed;
  const latestTurnId = task.mutationClaim?.kind === 'TURN' ? task.mutationClaim.ownerId : state.latestTurnId;
  useEffect(() => {
    let active = true;
    if (latestTurnId === null || task.state !== 'OPEN') { setPendingApprovalCount(0); return; }
    void bridge().readAgentPendingApprovals(task.id, latestTurnId).then((pending) => {
      if (active) setPendingApprovalCount(pending.length);
    }).catch(() => {});
    return () => { active = false; };
  }, [bridge, task.id, task.state, latestTurnId, tick]);
  const attention = hidden && ((task.state === 'OPEN' && task.mutationClaim?.kind === 'RECOVERY') || pendingApprovalCount > 0);
  useEffect(() => {
    if (attention) onAttention?.();
  }, [attention, onAttention]);
  if (hidden) return null;
  const rename = renamable
    ? (title: string) => {
      void bridge().renameAgentTask(task.id, task.workspaceId, task.version, title)
        .then((renamed) => dispatch({ type: 'task/loaded', task: renamed }))
        .catch(() => {});
    }
    : undefined;
  return <TaskCard task={task} events={state.events} historyIncomplete={(state.historyPages[0]?.beforeCursor ?? null) !== null} onRename={rename} onHide={onHide} {...card} />;
}

/**
 * The windows for a set of tasks, each polling its own task on one shared tick.
 *
 * Fluid windows fill a responsive grid: the grid's column width is measured so each window can
 * scale its screen to exactly the column, and `width` is the narrowest column allowed.
 */
export function TaskWindows({
  tasks, providers, width, bridge, activeTaskId = null, onOpen, onClose, fluid = false, renamable = false, hiddenTaskIds, onHide, onAttention,
}: {
  tasks: AgentTaskDto[];
  providers: ModelProviderDto[];
  width: number;
  bridge: () => AgentBridgePort;
  activeTaskId?: string | null;
  onOpen: (taskId: string) => void;
  onClose?: (task: AgentTaskDto) => void;
  fluid?: boolean;
  renamable?: boolean;
  /** Windows put away by the user; they keep polling so a task can come back when it needs attention. */
  hiddenTaskIds?: ReadonlySet<string>;
  onHide?: (task: AgentTaskDto) => void;
  onAttention?: (task: AgentTaskDto) => void;
}): React.JSX.Element {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setTick((count) => count + 1), WINDOW_POLL_MS);
    return () => clearInterval(timer);
  }, []);
  const gridRef = useRef<HTMLDivElement>(null);
  const [columnWidth, setColumnWidth] = useState(width);
  useEffect(() => {
    const grid = gridRef.current;
    if (!fluid || grid === null || typeof ResizeObserver === 'undefined') return;
    const measure = (): void => setColumnWidth(gridColumnWidth(grid.clientWidth, width));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(grid);
    return () => observer.disconnect();
  }, [fluid, width]);
  // One clock reading per tick, shared by every window.
  const now = Date.now();
  const windows = tasks.map((task) => (
    <TaskWindow
      key={task.id}
      task={task}
      tick={tick}
      bridge={bridge}
      providers={providers}
      width={fluid ? columnWidth : width}
      active={task.id === activeTaskId}
      now={now}
      hidden={hiddenTaskIds?.has(task.id) ?? false}
      renamable={renamable}
      onOpen={() => onOpen(task.id)}
      onClose={onClose === undefined ? undefined : () => onClose(task)}
      onHide={onHide === undefined ? undefined : () => onHide(task)}
      onAttention={onAttention === undefined ? undefined : () => onAttention(task)}
    />
  ));
  return fluid ? <div className="agt-windows-grid" ref={gridRef}>{windows}</div> : <>{windows}</>;
}
