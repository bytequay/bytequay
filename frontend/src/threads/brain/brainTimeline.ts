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
import type { BrainFeedRow, StageDto } from '../../types/brainView';

/**
 * Pure transform of the flat brain feed into the timeline-spine model: an
 * ordered list of stage segments, each carrying its boundary stage + the
 * rounds that happened inside it. A round = one user turn (or an autonomous
 * `R#`) + the agent's whole response. The headline is the round's last row;
 * everything before it is folded work. No DOM, no React — unit-testable.
 */

/** A round: a user turn (or autonomous) + the agent rows answering it. */
export type BrainRound = {
  id: string;
  /** The user message that opened the round; null for an autonomous round. */
  userTurn: BrainFeedRow | null;
  /** Agent / system rows in the round, chronological. The last is the
   *  headline; the rest fold into the work disclosure. */
  rows: BrainFeedRow[];
};

/** A stage segment: the boundary stage + the rounds inside it. `stage` is
 *  null for any pre-stage / stage-less conversation. */
export type BrainSegment = {
  stage: StageDto | null;
  /** True once the stage's CLOSED boundary has been seen. */
  closed: boolean;
  rounds: BrainRound[];
};

const BOUNDARY_TYPES = new Set<BrainFeedRow['type']>(['STAGE_OPENED', 'STAGE_CLOSED']);

/** Append a non-boundary feed row to a segment's rounds, opening a new round
 *  on each user message and an autonomous round for leading agent rows. */
function pushRow(seg: BrainSegment, row: BrainFeedRow): void {
  const isUser = row.type === 'USER_MESSAGE' || row.type === 'TRUNK_MESSAGE';
  if (isUser) {
    seg.rounds.push({ id: row.id, userTurn: row, rows: [] });
    return;
  }
  // A PR creation is a durable stage milestone, not a continuation of the
  // preceding agent response. Give it its own round so it stays visible when
  // a completed Development stage is folded.
  if (row.type === 'PUSHED_PR_CREATED') {
    seg.rounds.push({ id: row.id, userTurn: null, rows: [row] });
    return;
  }
  const last = seg.rounds[seg.rounds.length - 1];
  if (last === undefined) {
    seg.rounds.push({ id: row.id, userTurn: null, rows: [row] });
  }
  else {
    last.rows.push(row);
  }
}

export function buildBrainTimeline(feed: BrainFeedRow[], stages: StageDto[]): BrainSegment[] {
  const byId = new Map(stages.map(s => [s.id, s]));
  const segments: BrainSegment[] = [];
  let current: BrainSegment | null = null;

  const ensureSegment = (): BrainSegment => {
    if (current === null) {
      current = { stage: null, closed: false, rounds: [] };
      segments.push(current);
    }
    return current;
  };

  for (const row of feed) {
    if (BOUNDARY_TYPES.has(row.type)) {
      if (row.type === 'STAGE_OPENED') {
        current = { stage: row.stageId !== null ? byId.get(row.stageId) ?? null : null, closed: false, rounds: [] };
        segments.push(current);
      }
      else {
        // STAGE_CLOSED — mark the matching open segment closed.
        const seg = [...segments].reverse().find(s => s.stage?.id === row.stageId);
        if (seg !== undefined) seg.closed = true;
      }
      continue;
    }
    pushRow(ensureSegment(), row);
  }
  return segments;
}

/** The round's headline row (its last), or null for a still-open round. */
export function headlineOf(round: BrainRound): BrainFeedRow | null {
  return round.rows.length > 0 ? round.rows[round.rows.length - 1] : null;
}

/** The round's folded work rows (everything before the headline). */
export function workOf(round: BrainRound): BrainFeedRow[] {
  return round.rows.length > 1 ? round.rows.slice(0, -1) : [];
}

/** True when the round is a clean Q&A: a user question + exactly one agent
 *  reply — rendered as a tucked `↳ replies` under the turn. */
export function isQnA(round: BrainRound): boolean {
  return round.userTurn !== null && round.rows.length === 1
    && round.rows[0].type === 'BRAIN_AGENT_RESPONSE';
}
