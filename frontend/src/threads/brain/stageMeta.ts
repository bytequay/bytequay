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
import type { StageDto, StageType } from '../../types/brainView';

/** Persona color class for a stage tag chip — maps each stage type to
 *  the CSS modifier that paints its chip (dev / cifix / revmon /
 *  review). Anything without a dedicated color falls back to neutral. */
export type Persona = 'dev' | 'cifix' | 'revmon' | 'review' | 'neutral';

export function personaForStageType(type: StageType | null): Persona {
  switch (type) {
    case 'DEVELOPMENT_STAGE': return 'dev';
    case 'CI_FIXING_STAGE': return 'cifix';
    case 'REVIEW_MONITOR_STAGE': return 'revmon';
    case 'REVIEW_STAGE': return 'review';
    default: return 'neutral';
  }
}

/** Friendly class-style name for a main stage type, used both as the
 *  rail label and as the default stage-tag label. */
export function stageDisplayName(type: StageType): string {
  switch (type) {
    case 'DEVELOPMENT_STAGE': return 'DevelopmentStage';
    case 'CI_FIXING_STAGE': return 'CiFixingStage';
    case 'REVIEW_MONITOR_STAGE': return 'ReviewMonitorStage';
    case 'CLEANUP_STAGE': return 'CleanupStage';
    case 'REVIEW_STAGE': return 'ReviewStage';
  }
}

/** Visual state for a rail stage chip, derived from the DTO's lifecycle
 *  state plus loop iteration. The wire format has no "not yet opened"
 *  state, so an OPEN stage that hasn't looped reads as `future`. */
export type RailState = 'done' | 'active' | 'idle' | 'future';

export function railStateFor(stage: StageDto): RailState {
  if (stage.state === 'CLOSED') return 'done';
  if (stage.state === 'ACTIVE') return 'active';
  return stage.loopIteration > 0 ? 'idle' : 'future';
}

/** Builds a stageId → display label map across main and sub stages.
 *  Sub-stages (ReviewStage instances) get a `#N` suffix by their order
 *  so the feed's tag chips read "ReviewStage #1", "ReviewStage #2". */
export function buildStageLabels(
  stages: StageDto[],
  subStages: StageDto[],
): Map<string, string> {
  const labels = new Map<string, string>();
  for (const s of stages) {
    labels.set(s.id, stageDisplayName(s.type));
  }
  subStages.forEach((s, i) => {
    labels.set(s.id, `${stageDisplayName(s.type)} #${i + 1}`);
  });
  return labels;
}
