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

import React, { useEffect, useState } from 'react';
import { relativeTime } from '../relativeTime';
import { displayModelName } from '../settings/model';
import { TurnView } from './AgentTaskScreen';
import { taskStatePill } from './AgentTasksView';
import type { AgentEventEnvelope } from './events';
import type { AgentBridgePort } from './model';
import { projectTranscript, settleFinalTurn } from './transcript';
import type { AgentTaskDto, ModelOptionDto } from '../types';

/** How many of a task's first events a window shows; enough for its opening exchange. */
export const WINDOW_EVENT_LIMIT = 24;

/**
 * One task as the design's "task window": its own screen, scaled to fit, with a caption below.
 * Nothing inside the thumbnail is interactive — the whole card opens the task.
 */
export function TaskCard({ task, events, models, width, active = false, now, onOpen, onClose }: {
  task: AgentTaskDto;
  events: AgentEventEnvelope[];
  models: ModelOptionDto[];
  width: number;
  active?: boolean;
  now: number;
  onOpen: () => void;
  /** Present only where a card may close its task; the overview offers it, Home does not. */
  onClose?: () => void;
}): React.JSX.Element {
  const pill = taskStatePill(task);
  const projectedTurns = projectTranscript(events);
  const turns = projectedTurns.map((turn, index) => (
    task.state !== 'OPEN' && index === projectedTurns.length - 1
      ? settleFinalTurn(turn, task.terminalAt, task.state === 'COMPLETED')
      : turn
  ));
  const model = displayModelName(models.find((option) => option.id === task.defaults.model)?.name ?? task.defaults.model ?? '');
  const meta = `${model}${model === '' ? '' : ' · '}${relativeTime(task.updatedAt, { suffix: false, now })}`;
  const pillNode = (
    <span className={`agt-state ${pill.className}`}>
      {pill.spinning && <span className="agt-spin" aria-hidden="true" />}
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
                  open
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
      <div className="agt-card__caption">
        <span className="agt-row__mark" aria-hidden="true">❋</span>
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
 * The windows for a set of tasks. Each shows its task's first events, read once and bounded, so
 * a Home or overview full of tasks costs one small read per task rather than a subscription.
 */
export function TaskWindows({ tasks, models, width, bridge, activeTaskId = null, onOpen, onClose }: {
  tasks: AgentTaskDto[];
  models: ModelOptionDto[];
  width: number;
  bridge: () => AgentBridgePort;
  activeTaskId?: string | null;
  onOpen: (taskId: string) => void;
  onClose?: (task: AgentTaskDto) => void;
}): React.JSX.Element {
  const [events, setEvents] = useState<Record<string, AgentEventEnvelope[]>>({});
  const wanted = tasks.map((task) => task.id).filter((id) => events[id] === undefined).join(',');
  useEffect(() => {
    if (wanted === '') return;
    let active = true;
    for (const taskId of wanted.split(',')) {
      void bridge().readAgentEvents(taskId, 0, WINDOW_EVENT_LIMIT).then(
        (found) => { if (active) setEvents((current) => ({ ...current, [taskId]: found })); },
        () => { if (active) setEvents((current) => ({ ...current, [taskId]: [] })); },
      );
    }
    return () => { active = false; };
  }, [bridge, wanted]);
  // ponytail: one clock reading per render; a shared ticker if windows stay open long enough to drift
  const now = Date.now();
  return (
    <>
      {tasks.map((task) => (
        <TaskCard
          key={task.id}
          task={task}
          events={events[task.id] ?? []}
          models={models}
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
