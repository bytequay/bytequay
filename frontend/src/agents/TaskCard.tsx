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

import React, { useEffect, useReducer, useState } from 'react';
import { LoaderCircle } from 'lucide-react';
import { getCached, setCached } from '../dataCache';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { displayModelName, laneLabel } from '../settings/model';
import { TurnView } from './AgentTaskScreen';
import { ProviderIcon } from './ProviderIcon';
import { taskStatePill } from './taskStatus';
import type { AgentEventEnvelope } from './events';
import { agentTaskReducer, initialAgentTaskState, pollAgentTask, type AgentBridgePort, type AgentTaskState } from './model';
import { projectTranscript, settleFinalTurn } from './transcript';
import type { AgentTaskDto, ModelProviderDto } from '../types';

/** How often a window re-reads its task and the events it has not shown yet. */
const WINDOW_POLL_MS = 2000;

/**
 * One task as the overview's "task window": its own screen, scaled to fit, with a footer that names
 * it. Nothing inside the thumbnail is interactive; the thumbnail opens the task.
 */
export function TaskCard({ task, events, providers, width, active = false, now, onOpen, onClose, historyIncomplete = false }: {
  task: AgentTaskDto;
  events: AgentEventEnvelope[];
  historyIncomplete?: boolean;
  providers: ModelProviderDto[];
  width: number;
  active?: boolean;
  now: number;
  onOpen: () => void;
  /** Present only where a card may close its task. */
  onClose?: () => void;
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
              <span className="agt-mono agt-right"><ProviderIcon id={task.laneId} size={11} /> {meta}</span>
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
      <div className="agt-card__foot">
        <span className="agt-card__name">{task.title}</span>
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
 * A superseded poll is discarded, so a window never accumulates an unseen transcript.
 */
function TaskWindow({ task: listed, tick, bridge, ...card }: {
  task: AgentTaskDto;
  tick: number;
  bridge: () => AgentBridgePort;
  providers: ModelProviderDto[];
  width: number;
  active: boolean;
  now: number;
  onOpen: () => void;
  onClose?: () => void;
}): React.JSX.Element {
  // The overview remounts each time it opens; the window's last state paints the thumbnail until the poll replaces it.
  // ponytail: one retained state per task shown this session; evict closed tasks if renderer memory ever matters.
  const seed = `task-window:${listed.id}`;
  const [state, dispatch] = useReducer(agentTaskReducer, seed, (key) => getCached<AgentTaskState>(key) ?? initialAgentTaskState());
  useEffect(() => { setCached(seed, state); }, [seed, state]);
  useEffect(() => {
    let active = true;
    void pollAgentTask(bridge(), listed.id, listed.workspaceId, dispatch, () => active).catch(() => {});
    return () => { active = false; };
  }, [bridge, listed.id, listed.workspaceId, tick]);
  return <TaskCard task={state.task ?? listed} events={state.events} historyIncomplete={(state.historyPages[0]?.beforeCursor ?? null) !== null} {...card} />;
}

/** The windows for a set of tasks, each polling its own task on one shared tick. */
export function TaskWindows({ tasks, providers, width, bridge, activeTaskId = null, onOpen, onClose }: {
  tasks: AgentTaskDto[];
  providers: ModelProviderDto[];
  width: number;
  bridge: () => AgentBridgePort;
  activeTaskId?: string | null;
  onOpen: (taskId: string) => void;
  onClose?: (task: AgentTaskDto) => void;
}): React.JSX.Element {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setTick((count) => count + 1), WINDOW_POLL_MS);
    return () => clearInterval(timer);
  }, []);
  // One clock reading per tick, shared by every window.
  const now = Date.now();
  return (
    <>
      {tasks.map((task) => (
        <TaskWindow
          key={task.id}
          task={task}
          tick={tick}
          bridge={bridge}
          providers={providers}
          width={width}
          active={task.id === activeTaskId}
          now={now}
          onOpen={() => onOpen(task.id)}
          onClose={onClose === undefined ? undefined : () => onClose(task)}
        />
      ))}
    </>
  );
}
