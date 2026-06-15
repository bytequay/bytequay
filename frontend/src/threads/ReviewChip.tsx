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
type Props = {
  /** Review-pass phase (e.g. 'DEBATE', 'PUBLISHED'). */
  phase: string;
  round: number;
  roundCap: number;
  /** AGREED finding count, shown in the terminal (PUBLISHED) label. */
  agreedCount?: number;
  onOpen: () => void;
};

/**
 * The linked-review affordance on someone-else's PR row / detail rail:
 * {@code ⚖  Multi-agent review   [PHASE]   ↗}. The PHASE pill reads
 * "DEBATE r 2 / 3" while running, "PUBLISHED · 5 AGREED" when terminal.
 */
export function ReviewChip({ phase, round, roundCap, agreedCount, onOpen }: Props) {
  return (
    <button type="button" style={chipStyle} onClick={onOpen} title="Open the review panel">
      <span aria-hidden>⚖</span>
      <span style={titleStyle}>Multi-agent review</span>
      <span style={pillStyle}>{reviewPhaseLabel(phase, round, roundCap, agreedCount)}</span>
      <span aria-hidden style={arrowStyle}>↗</span>
    </button>
  );
}

/** "DEBATE r 2 / 3" while running; "PUBLISHED · 5 AGREED" at terminal. */
export function reviewPhaseLabel(
  phase: string, round: number, roundCap: number, agreedCount?: number,
): string {
  if (phase === 'PUBLISHED') {
    return agreedCount === undefined
      ? 'PUBLISHED'
      : `PUBLISHED · ${agreedCount} AGREED`;
  }
  return round > 0 ? `${phase} r ${round} / ${roundCap}` : phase;
}

const chipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
  maxWidth: 320,
  padding: '5px 10px',
  border: '1px solid rgba(217,119,6,0.30)',
  borderRadius: 999,
  background: 'rgba(245,158,11,0.08)',
  cursor: 'pointer',
  font: 'inherit',
  fontSize: 12,
  color: '#7c2d12',
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
  background: 'rgba(245,158,11,0.20)',
  color: '#7c2d12',
};

const arrowStyle: React.CSSProperties = { flexShrink: 0, opacity: 0.7 };
