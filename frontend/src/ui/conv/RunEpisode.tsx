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
import type { ReactNode } from 'react';
import type { AgentRunDto } from '../../types/brainView';
import { SpineNode, type SpineColor } from './spine/Spine';

const KIND_LABEL: Record<AgentRunDto['kind'], string> = {
  plan: 'Plan run',
  dev: 'Development run',
  review: 'Review run',
  ci_fix: 'CI fix run',
  review_round: 'Addressing run',
  branch_guard: 'Branch guard run',
  panel_review: 'Panel review run',
};

/**
 * One folded row for an {@link AgentRunDto} in a stage's feed (plan-rail-runs.md
 * Phase 5) — the Development feed's `ci_fix` runs and a live round's nested
 * re-run. Opens the run's own log (`RunLogPage`) on click.
 */
type RunEpisodeRow = Pick<AgentRunDto, 'kind' | 'source' | 'status' | 'iterations' | 'headline'>;

export function RunEpisode({ run, onOpen, onToggle, collapsed, name, state: stateOverride, meta: metaOverride, mark = '⚙', color, right }: {
  run: RunEpisodeRow;
  onOpen?: () => void;
  onToggle?: () => void;
  collapsed?: boolean;
  name?: ReactNode;
  state?: ReactNode;
  meta?: ReactNode;
  mark?: ReactNode;
  color?: SpineColor;
  right?: ReactNode;
}) {
  const working = run.status === 'running';
  const state = run.status === 'succeeded' ? 'done'
    : run.status === 'failed' ? 'failed'
      : run.status === 'cancelled' ? 'cancelled'
        : run.status === 'awaiting_gate' ? 'awaiting you'
          : run.status === 'paused' ? 'paused'
            : run.status === 'queued' ? 'queued'
              : 'running';
  const meta = metaOverride ?? run.headline ?? (run.iterations > 0 ? `iter ${run.iterations}` : undefined);
  return (
    <SpineNode
      mark={mark}
      color={color ?? (run.status === 'failed' || run.status === 'paused'
        ? 'orange' : run.status === 'queued' ? 'gray' : 'amber')}
      name={name ?? `${KIND_LABEL[run.kind]}${run.source !== null ? ` · ${run.source}` : ''}`}
      state={stateOverride ?? state}
      meta={meta}
      onOpen={onOpen}
      onToggle={onToggle}
      collapsed={collapsed}
      right={right}
      flash={working}
    />
  );
}
