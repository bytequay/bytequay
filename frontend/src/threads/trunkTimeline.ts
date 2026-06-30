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
import type { ThreadMessageDto, WorkUnitTaskDto } from '../types';
import { isShellTool, shellCommand } from './toolDisplay';

/** Best-effort plain text out of a message's JSON envelope. Thinking rows
 *  carry a `summary`; text rows carry `text`/`content`. */
export function extractText(contentJson: string): string {
  try {
    const v: unknown = JSON.parse(contentJson);
    if (typeof v === 'string') return v;
    if (v !== null && typeof v === 'object') {
      const o = v as Record<string, unknown>;
      if (typeof o.text === 'string') return o.text;
      if (typeof o.content === 'string') return o.content;
      if (typeof o.summary === 'string') return o.summary;
    }
  }
  catch { /* non-JSON envelope */ }
  return '';
}

/** Read a tool-call message into a tool name + a one-line summary. */
export function parseToolCall(contentJson: string): { name: string; summary: string } {
  try {
    const c = JSON.parse(contentJson) as { toolName?: unknown; input?: unknown };
    const name = typeof c.toolName === 'string' && c.toolName.length > 0 ? c.toolName : 'Tool';
    let summary = '';
    if (isShellTool(name)) {
      summary = shellCommand(c.input);
    }
    else if (c.input !== null && typeof c.input === 'object') {
      const o = c.input as Record<string, unknown>;
      for (const k of ['description', 'prompt', 'pattern', 'query', 'path', 'file_path', 'url', 'command']) {
        const v = o[k];
        if (typeof v === 'string' && v.length > 0) { summary = v; break; }
      }
    }
    summary = summary.replace(/\s+/g, ' ').trim();
    return { name, summary: summary.length > 160 ? `${summary.slice(0, 160)}…` : summary };
  }
  catch {
    return { name: 'Tool', summary: '' };
  }
}

/** A trunk round: a user turn (or autonomous) + the agent's rows. */
export type TrunkRound = {
  id: string;
  userTurn: ThreadMessageDto | null;
  rows: ThreadMessageDto[];
  ts: number;
};

/** A task-cut milestone — a task seeded from this thread. */
export type TrunkCut = { id: string; task: WorkUnitTaskDto; ts: number };

/** The trunk timeline: rounds + task-cut milestones merged in time order. */
export type TrunkItem =
  | { kind: 'round'; round: TrunkRound }
  | { kind: 'cut'; cut: TrunkCut };

const PLANNING_TYPES = new Set(['text', 'thinking', 'tool_call']);

/**
 * Pure transform of the trunk's raw planning messages + its cut tasks into
 * the timeline-spine model: rounds (user turn + agent rows) interleaved with
 * task-cut milestones at their creation time. The trunk has no stages — its
 * structure is the set of *outputs* it produces (task cuts), so the cuts are
 * the spine's big nodes. Architecture/risk are prose in the rounds until a
 * later milestone emits them structured (see DISCOVERY-FINDINGS deferral).
 */
export function buildTrunkTimeline(messages: ThreadMessageDto[], tasks: WorkUnitTaskDto[]): TrunkItem[] {
  const planning = messages.filter(m => m.taskId === null && PLANNING_TYPES.has(m.type));
  const rounds: TrunkRound[] = [];
  for (const m of planning) {
    const isUser = m.role === 'user' && m.type === 'text';
    if (isUser) {
      rounds.push({ id: m.id, userTurn: m, rows: [], ts: Date.parse(m.ts) });
      continue;
    }
    const last = rounds[rounds.length - 1];
    if (last === undefined) {
      rounds.push({ id: m.id, userTurn: null, rows: [m], ts: Date.parse(m.ts) });
    }
    else {
      last.rows.push(m);
    }
  }

  const items: TrunkItem[] = rounds.map(round => ({ kind: 'round' as const, round }));
  for (const task of tasks) {
    items.push({ kind: 'cut', cut: { id: task.id, task, ts: Date.parse(task.createdAt) } });
  }
  // Stable sort by timestamp keeps rounds and cuts in chronological order; a
  // cut lands right after the round that produced it.
  items.sort((a, b) => tsOf(a) - tsOf(b));
  return items;
}

function tsOf(item: TrunkItem): number {
  const t = item.kind === 'round' ? item.round.ts : item.cut.ts;
  return Number.isFinite(t) ? t : 0;
}

/** The round's headline row (its last assistant text), or null. */
export function trunkHeadline(round: TrunkRound): ThreadMessageDto | null {
  for (let i = round.rows.length - 1; i >= 0; i--) {
    if (round.rows[i].type === 'text') return round.rows[i];
  }
  return null;
}

/** The round's work rows (everything before the headline). */
export function trunkWork(round: TrunkRound): ThreadMessageDto[] {
  const headline = trunkHeadline(round);
  if (headline === null) return round.rows;
  const idx = round.rows.lastIndexOf(headline);
  return round.rows.slice(0, idx);
}
