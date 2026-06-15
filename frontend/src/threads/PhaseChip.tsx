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
import { phaseGroupDotColor, phaseGroupOf, phaseGroupPillStyle, phaseLabel } from './taskPhase';

/**
 * The task's current 12-phase value as a chip, colored by its phase
 * group. Used in the task-detail top bar + right rail. NEEDS_ATTENTION
 * carries an extra warning tint so the parked state stands out.
 */
export function PhaseChip({ phase }: { phase: TaskPhaseDto }) {
  const group = phaseGroupOf(phase);
  const parked = phase === 'NEEDS_ATTENTION';
  return (
    <span
      style={{ ...chipStyle, ...phaseGroupPillStyle(group), ...(parked ? parkedStyle : null) }}
      title={`Phase: ${phaseLabel(phase)}`}
    >
      <span aria-hidden style={{ ...dotStyle, background: phaseGroupDotColor(group) }} />
      {phaseLabel(phase)}
    </span>
  );
}

const chipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '2px 10px',
  borderRadius: 999,
  fontSize: 11,
  fontWeight: 700,
};

const parkedStyle: React.CSSProperties = {
  background: 'rgba(220,38,38,0.12)',
  color: '#b91c1c',
};

const dotStyle: React.CSSProperties = {
  width: 7, height: 7, borderRadius: 999,
};
