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

import React from 'react';
import type { AgentMetricsDto } from '../types';
import { formatDuration, turnElapsedMs, type TranscriptTurn } from './transcript';

function tokens(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M tok`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(count >= 10_000 ? 0 : 1)}k tok`;
  return `${count} tok`;
}

/** The line under the composer: what the task has done so far and what it has used, from its totals. */
export function TaskStats({ metrics, turns, now }: { metrics: AgentMetricsDto | null; turns: TranscriptTurn[]; now: number }): React.JSX.Element | null {
  if (metrics === null || turns.length === 0) return null;
  const activities = turns.flatMap((turn) => turn.blocks).reduce((total, block) => total + (block.kind === 'activity' ? block.members.length : 0), 0);
  const worked = turns.reduce((total, turn) => total + (turnElapsedMs(turn, now) ?? 0), 0);
  const { inputTokens, cachedInputTokens, outputTokens } = metrics;
  const groups = [
    `${turns.length} ${turns.length === 1 ? 'turn' : 'turns'} · ${activities} ${activities === 1 ? 'activity' : 'activities'}`,
    `Worked ${formatDuration(worked)}`,
  ];
  if (inputTokens > 0) groups.push(`Cache hit ${Math.round((cachedInputTokens / inputTokens) * 100)}%`);
  groups.push(`Input ${tokens(inputTokens)} · Cached ${tokens(cachedInputTokens)} · Output ${tokens(outputTokens)}`);
  const counters = metrics.counters;
  if (counters) {
    groups.push(`Tools ${counters.toolCalls} · Explored ${counters.exploreActions}`);
  }
  return (
    <div className="agt-compose__stats" aria-label="Task statistics">
      {groups.map((group, index) => (
        <React.Fragment key={group}>
          {index > 0 && <span className="agt-compose__sep" aria-hidden="true">|</span>}
          <span>{group}</span>
        </React.Fragment>
      ))}
    </div>
  );
}
