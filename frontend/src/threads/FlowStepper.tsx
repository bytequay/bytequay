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
import type { TaskPhaseDto } from '../types';
import { FLOW_STEPPER_NODES, stepperNodeOf } from './taskPhase';

/**
 * The 8-node linear happy-path stepper for the task lifecycle. The
 * current phase maps to a node via {@link stepperNodeOf}; nodes before
 * it read "done", the current one is active, the rest pending. Loop
 * phases never rewind the stepper (see {@code stepperNodeOf}).
 */
export function FlowStepper({ currentPhase }: { currentPhase: TaskPhaseDto }) {
  const current = stepperNodeOf(currentPhase);
  return (
    <ol style={listStyle} aria-label="task flow">
      {FLOW_STEPPER_NODES.map((label, i) => {
        // The terminal "Done" node is only ever current when the task has
        // COMPLETED, so render it as done (green), not active (amber) — a
        // finished task shouldn't show its last node mid-progress.
        const isLast = i === FLOW_STEPPER_NODES.length - 1;
        const state = i < current
          ? 'done'
          : i === current
            ? (isLast ? 'done' : 'active')
            : 'pending';
        return (
          <li key={label} style={nodeStyle} data-state={state}>
            <span aria-hidden style={dotStyle(state)} />
            <span style={labelStyle(state)}>{label}</span>
            {i < FLOW_STEPPER_NODES.length - 1 && <span aria-hidden style={connectorStyle(i < current)} />}
          </li>
        );
      })}
    </ol>
  );
}

const listStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 0,
  margin: 0,
  padding: '6px 0',
  listStyle: 'none',
};

const nodeStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
};

function dotStyle(state: string): React.CSSProperties {
  const bg = state === 'done' ? '#10b981' : state === 'active' ? '#d97706' : 'rgba(0,0,0,0.18)';
  return {
    width: 9, height: 9, borderRadius: 999, background: bg, flexShrink: 0,
    boxShadow: state === 'active' ? '0 0 0 3px rgba(217,119,6,0.18)' : 'none',
  };
}

function labelStyle(state: string): React.CSSProperties {
  return {
    fontSize: 11,
    fontWeight: state === 'active' ? 700 : 500,
    color: state === 'pending' ? 'var(--text-4)' : 'var(--text-2)',
    whiteSpace: 'nowrap',
  };
}

function connectorStyle(filled: boolean): React.CSSProperties {
  return {
    width: 20, height: 2, margin: '0 6px',
    background: filled ? '#10b981' : 'rgba(0,0,0,0.12)',
  };
}
