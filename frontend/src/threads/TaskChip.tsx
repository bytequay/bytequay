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
import type { TaskPhaseGroupDto } from '../types';
import { phaseGroupDotColor, phaseGroupLabel, phaseGroupPillStyle } from './taskPhase';

type Props = {
  /** Task display title (truncated by the chip). */
  title: string;
  /** Coarse phase group — drives the dot color + GROUP LABEL pill. */
  group: TaskPhaseGroupDto;
  /** Open the task detail. */
  onOpen: () => void;
};

/**
 * The linked-dev-task affordance on a PR row / detail rail:
 * {@code ●  Task title   [GROUP LABEL]   ↗}. The dot color + pill encode
 * the {@link TaskPhaseGroupDto}.
 */
export function TaskChip({ title, group, onOpen }: Props) {
  return (
    <button type="button" style={chipStyle} onClick={onOpen} title={`Open task — ${title}`}>
      <span aria-hidden style={{ ...dotStyle, background: phaseGroupDotColor(group) }} />
      <span style={titleStyle}>{title}</span>
      <span style={{ ...pillStyle, ...phaseGroupPillStyle(group) }}>{phaseGroupLabel(group)}</span>
      <span aria-hidden style={arrowStyle}>↗</span>
    </button>
  );
}

const chipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
  maxWidth: 320,
  padding: '5px 10px',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 999,
  background: 'var(--surface, #fff)',
  cursor: 'pointer',
  font: 'inherit',
  fontSize: 12,
  color: 'var(--text-1)',
};

const dotStyle: React.CSSProperties = {
  width: 8, height: 8, borderRadius: 999, flexShrink: 0,
};

const titleStyle: React.CSSProperties = {
  fontWeight: 600,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  minWidth: 0,
};

const pillStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '1px 8px',
  borderRadius: 999,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.02em',
  textTransform: 'uppercase',
};

const arrowStyle: React.CSSProperties = { flexShrink: 0, color: 'var(--text-3)' };
