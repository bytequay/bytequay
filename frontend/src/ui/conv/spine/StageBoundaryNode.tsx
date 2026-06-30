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
import type { StageDto, StageType } from '../../../types/brainView';
import { formatDuration } from '../../../threads/brain/format';
import { SpineNode } from './Spine';
import type { SpineColor } from './Spine';

const VISUAL: Record<StageType, { color: SpineColor; mark: string; name: string }> = {
  PLAN_STAGE: { color: 'purple', mark: '◆', name: 'Planning' },
  DEVELOPMENT_STAGE: { color: 'blue', mark: '▶', name: 'Development' },
  CI_FIXING_STAGE: { color: 'amber', mark: '✦', name: 'CI Fix' },
  REVIEW_MONITOR_STAGE: { color: 'teal', mark: '◇', name: 'Review Monitor' },
  CLEANUP_STAGE: { color: 'gray', mark: '◆', name: 'Cleanup' },
  REVIEW_STAGE: { color: 'purple', mark: '◆', name: 'Review' },
};

/** Wall-clock duration of a stage, when both ends are known. */
function durationLabel(stage: StageDto): string | null {
  if (stage.closedAt === null) return null;
  const sec = (Date.parse(stage.closedAt) - Date.parse(stage.openedAt)) / 1000;
  return Number.isFinite(sec) && sec > 0 ? formatDuration(sec) : null;
}

/**
 * Layer-4 domain node (brain feed): a stage boundary promoted out of the
 * message layer into a labelled, colour-coded spine node — status + duration
 * + outcome. This is the fix for "a boundary event reads like agent talking".
 * Composes {@link SpineNode}. Per-stage token cost is not on the feed row
 * (it lives on the stage-detail endpoint), so the node shows duration +
 * outcome only. When `onToggle` is given the closed stage folds its chatter.
 *
 * `amended` drops a small "plan amended" tick on the active stage when the
 * user changed direction mid-stage (rendered as a normal turn, per the M10
 * default — no schema change).
 */
export function StageBoundaryNode({ stage, closed, collapsed, onToggle, amended }: {
  stage: StageDto;
  closed: boolean;
  collapsed?: boolean;
  onToggle?: () => void;
  amended?: boolean;
}) {
  const v = VISUAL[stage.type];
  const dur = durationLabel(stage);
  const state = closed
    ? (dur !== null ? `done · ${dur}` : 'done')
    : stage.state === 'ACTIVE' || stage.state === 'OPEN' ? 'active'
      : stage.state === 'PAUSED' ? 'paused' : 'open';
  const outcome = stage.summary.trim().length > 0 ? truncate(stage.summary, 64) : undefined;
  return (
    <SpineNode
      mark={v.mark}
      color={v.color}
      name={v.name}
      state={state}
      meta={outcome}
      collapsed={collapsed}
      onToggle={onToggle}
      right={amended === true ? <span className="sp-node__tick">plan amended</span> : undefined}
    />
  );
}

function truncate(s: string, n: number): string {
  const t = s.replace(/\s+/g, ' ').trim();
  return t.length > n ? `${t.slice(0, n - 1)}…` : t;
}
