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

import { useEffect, useState } from 'react';
import type { LiveActivity } from '../../threads/liveActivity';

/**
 * A live "agent is working" row — a pulsing dot + label shown at the foot
 * of the conversation while the agent is generating, so the surface
 * never looks idle between a prompt and the response. Pass `since` (an
 * epoch-ms start time) to append a ticking elapsed counter — reassurance
 * that a long, quiet turn (e.g. extended thinking) is still alive, not
 * dead.
 */
export function Working({ label = 'Working…', tail, detail, since, onStop, activities = [] }: {
  label?: string;
  /** A file path shown after the label, head-truncated so the filename tail
   *  survives instead of the interchangeable worktree prefix. Omit for
   *  command/MCP args, which read head-first and stay inside `label`. */
  tail?: string;
  /** Full text shown on hover — e.g. the complete shell command when the
   *  label is truncated to one line. */
  detail?: string;
  since?: number;
  /** When set, a Stop button appears that interrupts the running turn. */
  onStop?: () => void;
  /** Recent tool calls from the live stream. These are deliberately
   * ephemeral: they clear at the end of the active turn. */
  activities?: LiveActivity[];
}) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    if (since === undefined) return undefined;
    const tick = () => setElapsed(Math.max(0, Math.round((Date.now() - since) / 1000)));
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [since]);

  return (
    <div className="working" role="status" aria-live="polite">
      <div className="working__summary">
        <span className="working__dot" aria-hidden />
        <span className="working__label" title={detail ?? label}>{label}</span>
        {tail !== undefined && <span className="working__tail" title={tail}>{tail}</span>}
        {since !== undefined && <span className="working__elapsed">{formatElapsed(elapsed)}</span>}
        {onStop !== undefined && (
          <button type="button" className="working__stop" onClick={onStop} title="Stop the agent">
            Stop
          </button>
        )}
      </div>
      {activities.length > 0 && (
        <div className="working__log" aria-label="Live agent activity">
          {activities.map(activity => (
            <div key={activity.callId} className="working__log-row" title={activity.detail ?? activity.label}>
              <span aria-hidden>{activity.failed ? '×' : activity.done ? '✓' : '›'}</span>
              <span>{activity.label}{activity.detail === null ? '' : ` · ${activity.detail}`}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function formatElapsed(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
